package chatbdi;

import java.util.List;
import java.util.ArrayList;
// // import java.util.Map;
import java.util.logging.Level;
// // import java.util.logging.Logger;
// // import java.util.HashMap;
// // import java.util.Set;
// // import java.util.HashSet;
import java.util.Collection;
import java.util.Queue;
import java.util.UUID;

import jason.asSyntax.*;
// // import jason.asSemantics.*;
import jason.architecture.AgArch;
import static jason.asSyntax.ASSyntax.*;
import jason.asSemantics.Agent;
import jason.asSemantics.Message;
import jason.infra.local.RunLocalMAS;
// // import jason.runtime.RuntimeServices;
import jason.runtime.Settings;
import jason.bb.BeliefBase;
import jason.pl.PlanLibrary;

import jason.asSyntax.parser.ParseException;
import java.net.ConnectException;
import java.io.IOException;
import java.rmi.RemoteException;

// ── Socket.IO ──────────────────────────────────────────────────────────────
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;
// ────────────────────────────────────────────────────────────────────────────

/**
 * Interpreter is an Agent Architecture that enables the user to interact with
 * the agents in the MAS via web Socket.IO chat.
 *
 * Web chat flow:
 *   Browser → Flask chat:send → broadcast chat:message
 *        → Interpreter receives chat:message
 *        → handleUserMsg() → nl2kqml() → sendMsg() to Jason agent
 *        → Jason agent replies → checkMail() → kqml2nl()
 *        → socket.emit("chat:send") back to Flask → broadcast to browser
 *
 * @author Andrea Gatti  (Socket.IO integration: GianlucaScollo)
 */
public class Interpreter extends AgArch {

    /** Supported Illocutionary forces for the classifier */
    private final String[] SUPPORTED_ILF = { "tell", "askOne", "askAll" };

    /** Ollama manages the connection with the daemon */
    private Ollama ollama;
    /** EmbeddingSpace manages the embedding space */
    private EmbeddingSpace embSpace;

    // ── Socket.IO state ────────────────────────────────────────────────────
    /** Socket.IO connection to the Flask server */
    private Socket socket;
    /**
     * True when we are in a chat phase (pre-game or post-game).
     * False during an active game — messages are ignored.
     */
    private volatile boolean in_chat_phase = false;
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Initialises Ollama client, EmbeddingSpace, ChatUI and the Socket.IO connection.
     */
    @Override
    public void init() throws Exception {
        super.init();
        logFine( "init: supported ilfs: " + SUPPORTED_ILF );
        try {
            Settings stts = getTS().getSettings();
            ollama = new Ollama( SUPPORTED_ILF, getAgName(), stts );
            logInfo( "Initializing Ollama models" );
            embSpace = new EmbeddingSpace( ollama );
            initEmbeddingSpace();
            logInfo( "Initializing the Embedding Space" );
            // ── Socket.IO setup ────────────────────────────────────────────────
            initSocket();
            // ────────────────────────────────────────────────────────────────────
        } catch ( ConnectException ce ) {
            logSevere( ce.getMessage() );
            logFine( ce.getStackTrace().toString() );
        } catch ( RemoteException re ) {
            logSevere( "REMOTE EXCEPTION! " + re.getMessage() );
            logFine( re.getStackTrace().toString() );
        } catch (Exception e) {
            logSevere( e.getMessage() );
            logFine( e.getStackTrace().toString() );
        }
        
    }


    // =========================================================================
    //  Socket.IO — connection and event handling
    // =========================================================================

    /**
     * Creates the Socket.IO connection to the Flask server and registers all
     * the event listeners needed for the web chat.
     */
    private void initSocket() {
        try {
            socket = IO.socket("http://localhost");

            // ── CONNECT ───────────────────────────────────────────────────
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    logInfo("[SOCKET] Connected to Flask server");
                    try {
                        // Join the shared lobby room
                        JSONObject chatJoin = new JSONObject().put("room", "lobby");
                        socket.emit("chat:join", chatJoin);
                        in_chat_phase = true;
                        logInfo("[CHAT] Entered lobby — pre-game chat active.");
                    } catch (JSONException e) {
                        logSevere("[SOCKET] Error joining lobby: " + e.getMessage());
                    }
                }
            });

            // ── CHAT:MESSAGE — incoming message from the web user ─────────
            socket.on("chat:message", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    // Only handle messages during chat phases, not during an active game
                    if (!in_chat_phase) return;
                    if (args.length == 0) return;

                    try {
                        JSONObject payload = new JSONObject(args[0].toString());
                        String sender  = payload.optString("sender", "unknown");
                        String msg    = payload.optString("message", "").trim();

                        if (msg.isEmpty()) return;
                        // Ignore our own replies to avoid infinite loops
                        if (sender.equals("staychef")) return;

                        logInfo("[CHAT] Received from web — sender: " + sender + ", msg: " + msg);

                        new Thread(() -> {
                            try {
                                
                                // Determine receivers: broadcast to all agents in the MAS
                                List<String> receivers = new ArrayList<>(getRuntimeServices().getAgentsName());
                                // The Interpreter agent itself is not a target
                                receivers.remove(getAgName());

                                UUID id = UUID.randomUUID();

                                // Translate NL → KQML and send to Jason agent(s)

                                String plainContent = msg.replaceAll("<[^>]*>", "");
                                int result = handleUserMsg(id, receivers, plainContent);

                                if (result == -1 && socket != null && socket.connected()) {
                                    try {
                                        JSONObject errPayload = new JSONObject()
                                            .put("message", "Error: translation failed.")
                                            .put("sender", "system");
                                        socket.emit("chat:send", errPayload);
                                    } catch (JSONException e1) { 
                                        logSevere( e1.getMessage() );
                                        logFine( e1.getStackTrace().toString() );
                                    }
                                } else if (result == 0 && socket != null && socket.connected()) {
                                    try {
                                        JSONObject warnPayload = new JSONObject()
                                            .put("message", "Error: translation partially failed.")
                                            .put("sender", "system");
                                        socket.emit("chat:send", warnPayload);
                                    } catch (JSONException e2) { 
                                        logSevere( e2.getMessage() );
                                        logFine( e2.getStackTrace().toString() );
                                    }
                                }
                            } catch (Exception e) {
                                logSevere("[CHAT] Error processing web message: " + e.getMessage());
                            }
                        }).start();

                    } catch (JSONException e) {
                        logSevere("[SOCKET] JSON error in chat:message: " + e.getMessage());
                    }
                }
            });

            // ── GAME PHASE TRANSITIONS ────────────────────────────────────

            // Game started → disable chat responses
            socket.on("start_game", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    in_chat_phase = false;
                    logInfo("[GAME] Game started — web chat disabled.");
                }
            });

            // Game ended → re-enable chat
            socket.on("end_game", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    in_chat_phase = true;
                    logInfo("[CHAT] Game ended — web chat re-enabled.");
                }
            });

            // Lobby ended (user left before game started) → keep chat active
            socket.on("end_lobby", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    in_chat_phase = true;
                    logInfo("[CHAT] Lobby ended — web chat active.");
                }
            });

            // ── DISCONNECT ────────────────────────────────────────────────
            socket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    in_chat_phase = false;
                    logInfo("[SOCKET] Disconnected from Flask server.");
                }
            });

            socket.connect();
            logInfo("[SOCKET] socket.connect() called.");

        } catch (Exception e) {
            logSevere("[SOCKET] Exception in initSocket(): " + e.getMessage());
        }
    }


    /**
     * Interpreter overwrites the checkMail method: 
     * every message received by the agent triggers a translation to Natural Language and displays it on the chat.
     */
    @Override
    public void checkMail() {
        super.checkMail();

        Queue<Message> mbox = getTS().getC().getMailBox();

        if ( mbox.isEmpty() )
            return;
        
        while( !mbox.isEmpty() ) {
            Message m = mbox.poll();

            new Thread(() -> {
                String sender = m.getSender();
                String nlMsg = kqml2nl(m);
                if (socket != null && socket.connected() && in_chat_phase) {
                    try {
                        JSONObject replyPayload = new JSONObject()
                            .put("message", nlMsg)
                            .put("sender",  "staychef");
                        socket.emit("chat:send", replyPayload);
                        logInfo("[CHAT] Sent reply to web: " + nlMsg);
                    } catch (JSONException e) {
                        logSevere("[SOCKET] Error sending reply: " + e.getMessage());
                    }
                }
            }).start();

        }
    }

    /**
     * Translates and send a user message to the agents
     * @param receivers the list of receiver agents
     * @param msg the message written on the chat
     * @throws Exception if broadcast or sendMsg raise it
     */
    protected int handleUserMsg( UUID id, List<String> receivers, String msg ) throws Exception, ParseException {
        Collection<String> agNames = getRuntimeServices().getAgentsName();
        logInfo("Starting");

        updateEmbeddingSpace();

        boolean partial = false;
        if ( !receivers.isEmpty() ) {
            logInfo("There are receivers" );
            for ( int i=0; i<receivers.size(); i++ ) {
                if ( !agNames.contains( receivers.get(i) ) ) {
                    logInfo("The agent " + receivers.get(i) + " does not exist" );
                    partial = true;
                    //chatUI.showAgentNotFoundNotice( id, receivers.get(i) );
                    receivers.remove( receivers.get(i) );
                }
            }
            if ( receivers.isEmpty() ) {
                logInfo("Receivers is now empty!");
                return -1;
            }
        }
        // Translates the message into a KQML Message
        logInfo("Translating the message");
        Message m = nl2kqml( receivers, msg );

        if ( m == null ) {
            logInfo( "The generated message is null");
            return -1;
        }
        // show the generated KQML translation under the user's message
        // try {
        //    if ( chatUI != null )
        //        chatUI.setKQML( id, m.getIlForce(), m.getPropCont().toString() );
        //} catch ( Exception e ) {
        //    logSevere( "Cannot show KQML translation: " + e.getMessage() );
        //}
        // Broadcast if no receivers are set
        if ( receivers.isEmpty() ) {
            logInfo("Broadcasting the message");
            broadcast( m );
            return 1;
        }
        // Send it to all receivers
        for ( String receiver : receivers ) {
            if ( agNames.contains( receiver ) ) {
                m.setReceiver( receiver );
                sendMsg( m );
            }
        }
        if ( partial )
            return 0;
        return 1;
    }

    /**
     * Translates a user message into a KQML Message object
     * @param receivers the list of receiver agents
     * @param msg the message written on the chat
     * @return the KQML Message
     * @throws ParseException if the resulting translation is not syntactically correct
     * @throws Exception if it fails sending or broadcasting the message
     */
    protected Message nl2kqml( List<String> receivers, String msg ) throws Exception, ParseException {
        // If the message is empty return
        if ( msg.trim().isEmpty() )
            return null;
        // Classify the message
        Literal ilf = ollama.classify( msg );
        // Generate the final term
        Literal term = generateTerm( receivers, ilf, msg );
        // If the computed ilf is an askHow add the triggering +! part to the term
        if ( ilf.equalsAsStructure( createLiteral( "askHow" ) ) )
            term = new Trigger( Trigger.TEOperator.add, Trigger.TEType.achieve, term );
        logInfo( "Generated: \n ilf: " + ilf + "\n term: " + term );

        return new Message( ilf.toString(), this.getAgName(), null, term );
    }

    /**
     * This method translates KQML into Natural Language
     * @param m the KQML Message
     * @return the translation
     */
    protected String kqml2nl( Message m ) {
        try {
            return ollama.generate( m );
        } catch ( IOException ioe ) {
            logSevere( ioe.getMessage() );
        }
        return "Error showing the message";
    }

    /**
     * Generates the final term to send 
     * @param receivers who will receive the message: we will use their BB and PL for translation
     * @param ilf the Illocutionary Force classified
     * @param msg the message sent by the user
     * @return the term generated from the message
     * @throws ParseException if the generated term is not syntactically correct
     */
    private Literal generateTerm( List<String> receivers, Literal ilf, String msg ) throws ParseException {
        msg = msg.replaceAll( "\\s*@\\S+", "" );
        String subSpace = "terms";
        if ( ilf.equals( "achieve" ) )
            subSpace = "plans";
        Literal nearest = embSpace.findNearest( receivers, subSpace, msg );
        try {
            System.out.println( "[LOG] " + nearest );
            List<Literal> examples = embSpace.getExamples( ilf, nearest );
            return ollama.generate( msg, nearest, ilf, examples );
        } catch( IOException ioe ) {
            throw new IllegalArgumentException( "Prompt loading caused a IO Exception: check the file path. Full error: " + ioe.getMessage() );
        }
    }

    /**
     * Inititalizes the embedding space
     * @throws RemoteException if the agent fails accessing BB or PL of another agent
     */
    private void initEmbeddingSpace() throws RemoteException {
        logInfo( "Initializing content of the Embedding Space" );
        Collection<String> agNames = getRuntimeServices().getAgentsName();
        for ( String agName : agNames ) {
            logInfo( "Considering " + agName );
            Agent ag = RunLocalMAS.getRunner().getAg( agName ).getTS().getAg();
            BeliefBase bb = ag.getBB().clone();
            PlanLibrary pl = ag.getPL().clone();
            embSpace.update( agName, bb, pl );
        }
        embSpace.print();
    }

    private void updateEmbeddingSpace() throws RemoteException {
        logInfo( "Updating content of the Embedding Space" );
        Collection<String> agNames = getRuntimeServices().getAgentsName();
        for ( String agName : agNames ) {
            logInfo( "Considering " + agName );
            Agent ag = RunLocalMAS.getRunner().getAg( agName ).getTS().getAg();
            BeliefBase bb = ag.getBB().clone();
            PlanLibrary pl = ag.getPL().clone();
            embSpace.update( agName, bb, pl );
        }
    }


    /** Prints ERROR on the agent log
     * @param msg what to print
     */
    protected void logSevere( String msg ) {
        getTS().getLogger().log( Level.SEVERE, msg );
    }

    protected void logWarning( String msg ) {
        getTS().getLogger().log( Level.WARNING, msg );
    }

    /** Prints INFO on the agent log 
     * @param msg what to print
    */
    protected void logInfo( String msg ) {
        getTS().getLogger().log( Level.INFO, msg );
    }

    protected void logConfig( String msg ) {
        getTS().getLogger().log( Level.CONFIG, msg );
    }

    protected void logFine( String msg ) {
        getTS().getLogger().log( Level.FINE, msg );
    }

    protected void logFiner( String msg ) {
        getTS().getLogger().log( Level.FINER, msg );
    }

    protected void logFinest( String msg ) {
        getTS().getLogger().log( Level.FINEST, msg );
    }

}