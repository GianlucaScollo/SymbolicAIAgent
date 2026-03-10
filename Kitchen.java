package my.internal.actions;

// Jason and environment classes for syntax and percepts
import jason.environment.Environment;
import jason.asSyntax.Literal;
import jason.asSyntax.Structure;
import jason.asSyntax.ASSyntax;
import jason.asSyntax.ListTerm;
import jason.asSyntax.ListTermImpl;
import jason.asSyntax.Term;

// Socket.IO for real-time communication
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

// JSON format handling
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

// Java utilities
import java.util.*;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * The Kitchen environment acts as the interface between the Jason symbolic agent and
 * the Overcooked-AI envrinment. It handles real-time communication via Socket.IO,
 * updates beliefs based on game state, processes perceptions, gandles the A* algortithm
 * responsible for the agent's movement.
 */
public class Kitchen extends Environment {

    // Singleton instance to ensure only one environment is active at runtime
    private static Kitchen instance;

    public Kitchen() {
        instance = this;  // set the singleton on construction
    }

    public static Kitchen getInstance() {
        return instance;
    }

    // Socket.IO connection for communication with the Flask server
    private Socket socket;            
    // Flag for game status
    private volatile boolean active_game = false; 
    private Thread stayThread;
    
    // Grid for A* pathfinding with dimensions X,Y
    private int[][] grid;
    private int gridRows = 0;
    private int gridCols = 0;
    
    // Positions for the symbolic AI agent and the human player (initially 0 until received from the server)
    private int agentX = 0;  // X = column
    private int agentY = 0;  // Y = row
    
    private int humanX = 0;  // X = column
    private int humanY = 0;  // Y = row
    
    /** A STAR ALGORITHM *************************************
     * Disclaimer: This code has been extracted from Geek for Geeks (https://www.geeksforgeeks.org/a-search-algorithm/) and
     *             modified to suit the needs of the environment.
     * 
     * This class represents one cell in the frid for A* pathfinding.
     * Tracks:
     *   - parentY and parentX: coordinates of the previous cell in the best-known path
     *   - g: cost from the start cell to here
     *   - h: heuristic estimate from here to goal
     *   - f: g + h, total estimated cost through this cell
     */
    class Cell {
        int parentY, parentX;
        double f, g, h;
        Cell() {
            // No parent yet
            parentY = -1;
            parentX = -1;

            // Initialise costs to 'infinite' so any real path is better
            f = Double.POSITIVE_INFINITY;
            g = Double.POSITIVE_INFINITY;
            h = Double.POSITIVE_INFINITY;
        }
    }

    /**
     * A record used in the open-list priority queue.
     * Stores the coordinates and f-cost; ordering by f ensures
     * A* always expands the most promising node next.
     */
    class NodeRecord {
        int y, x; // cell coords
        double f; // total cost = g + f
        NodeRecord(int y, int x, double f) {
            this.y = y;
            this.x = x;
            this.f = f;
        }
    }
    // HELPERS FOR VALIDATING AND EVALUATING CELLS

    // Check if cell (y,x) is within grid bounds
    private boolean isValid(int y, int x, int ROW, int COL) {
        return (y >= 0 && y < ROW && x >= 0 && x < COL);
    }
    
    // Check if cell (y,x) is walkable (1 = free, 0 = blocked)
    // A walkable cell is empty, and all pots, counters, onions etc are unwalkable
    private boolean isUnBlocked(int[][] grid, int y, int x) {
        return grid[y][x] == 1;
    }
    
    // Check if the current cell is destination, if so return true
    private boolean isDestination(int y, int x, int[] dest) {
        return (y == dest[0] && x == dest[1]);
    }
    
    // Use Manhattan distance as heuristic function h(n). Admissibe bc it never overestimates on a 4-connected grid
    private double calculateHValue(int y, int x, int[] dest) {
        return Math.abs(y - dest[0]) + Math.abs(x - dest[1]);
    }
    
    // Path reconstruction. Trace back from the destination cell to the source by following parent pointers.
    // Returns a list of pairs from the source to destination 
    private List<int[]> tracePath(Cell[][] cellDetails, int[] dest) {
        List<int[]> path = new ArrayList<>();
        int y = dest[0];
        int x = dest[1];
        // Walk backwards until a cell a cell is its own parent (the sourec)
        while (!(cellDetails[y][x].parentY == y && cellDetails[y][x].parentX == x)) {
            path.add(new int[]{y, x});
            int py = cellDetails[y][x].parentY;
            int px = cellDetails[y][x].parentX;
            y = py;
            x = px;
        }
        // Add the source cell itself
        path.add(new int[]{y, x});
        // Reverse it si its in normal order (forward)
        Collections.reverse(path);
        return path;
    }
    
    // A* search ACTUAL implementation 
    /*
     * Compute the shortest path from the source to destination on the grid
     * grid - 2d array where 1 is walkable and 0 is not
     * src - startY and startX
     * dest - goalY and goaY
     * It returns a list full of coordinates from source to destination or:
     * - null if blocked or no path found
     * - empty if we are already at the destination
     */
    public List<int[]> aStarSearch(int[][] grid, int[] src, int[] dest) {
        int ROW = grid.length;
        int COL = grid[0].length;
        
        int sy = src[0], sx = src[1];
        int dy = dest[0], dx = dest[1];

        // Basic checks for sanity - if source and dest are valid
        if (!isValid(sy, sx, ROW, COL) || !isValid(dy, dx, ROW, COL)) {
            System.out.println("Source or destination is invalid");
            return null;
        }
        // return if we are already there
        if (isDestination(sy, sx, dest)) {
            System.out.println("Already at destination");
            return Collections.emptyList();
        }
        
        // Set up a closed list of already visited cells and details about it
        boolean[][] closedList = new boolean[ROW][COL];
        Cell[][] cellDetails = new Cell[ROW][COL];
        for (int r = 0; r < ROW; r++) {
            for (int c = 0; c < COL; c++) {
                cellDetails[r][c] = new Cell();
            }
        }
        
        // Initialise the start cell
        cellDetails[sy][sx].f = 0;
        cellDetails[sy][sx].g = 0;
        cellDetails[sy][sx].h = 0;
        cellDetails[sy][sx].parentY = sy;
        cellDetails[sy][sx].parentX = sx;
        
        // PriorityQueue for open list sorted by f cost
        PriorityQueue<NodeRecord> openList = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        openList.add(new NodeRecord(sy, sx, 0.0));
        
        // Main loop for A*
        while (!openList.isEmpty()) {
            NodeRecord current = openList.poll();
            int cy = current.y;
            int cx = current.x;
            closedList[cy][cx] = true;
            
            // Check each of the 4 neighbours
            int[][] directions = { {1,0}, {-1,0}, {0,1}, {0,-1} };
            for (int[] dir : directions) {
                int ny = cy + dir[0];
                int nx = cx + dir[1];
                
                // skip invalid or visited ones
                if (isValid(ny, nx, ROW, COL)) {
                    // If we reached the target (e.g pot), link and rebuild
                    if (isDestination(ny, nx, dest)) {
                        // Set parent then build path
                        cellDetails[ny][nx].parentY = cy;
                        cellDetails[ny][nx].parentX = cx;
                        return tracePath(cellDetails, dest);
                    }

                    //  If neighbour is walkable, compute costs
                    if (!closedList[ny][nx] && isUnBlocked(grid, ny, nx)) {
                        double gNew = cellDetails[cy][cx].g + 1.0;  // cost so far
                        double hNew = calculateHValue(ny, nx, dest); // heuristic
                        double fNew = gNew + hNew; // total f = g+h
                        
                        // If this path to neighbour is better add its details and add to the open list
                        if (cellDetails[ny][nx].f == Double.POSITIVE_INFINITY ||
                            cellDetails[ny][nx].f > fNew) {
                            cellDetails[ny][nx].f = fNew;
                            cellDetails[ny][nx].g = gNew;
                            cellDetails[ny][nx].h = hNew;
                            cellDetails[ny][nx].parentY = cy;
                            cellDetails[ny][nx].parentX = cx;
                            
                            openList.add(new NodeRecord(ny, nx, fNew));
                        }
                    }
                }
            }
        }
        // If we went through the entire list without finding the goal
        // printed for santity check in the mas console
        System.out.println("Failed to find a path to destination");
        return null;
    }
    
    // Convert the path into actions, up/down/left/right, so that we can use it with the agent
    public List<String> convertPathToActions(List<int[]> path) {
        List<String> actions = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) {
            int[] current = path.get(i - 1);
            int[] next    = path.get(i);
            int cy = current[0], cx = current[1];
            int ny = next[0],    nx = next[1];
            
            // Move in y
            if (ny == cy + 1 && nx == cx) {
                actions.add("down");
            } else if (ny == cy - 1 && nx == cx) {
                actions.add("up");
            }
            // Move in x
            else if (nx == cx + 1 && ny == cy) {
                actions.add("right");
            } else if (nx == cx - 1 && ny == cy) {
                actions.add("left");
            }
        }
        return actions;
    }
    
    /**
     * Construct a 2D integer grid representing walkable (represented by 11) and blocked (0s) cells
     * based on the agent's current perceptions of the terrain.
     *
     * Relies on the Jason we get of the form terrain(X,Y,Item), where:
     * returns a rows and columns array, with 1 for passable cells and 0 for obstacles
     */
    public int[][] buildGrid() {
        // Obtain the dimensions previously recorded when the layout was first received
        int rows = gridRows;
        int cols = gridCols;
        // Allocate a new grid of the appropriate size
        int[][] newGrid = new int[rows][cols];
        
        // Default everything to walkable (chaned later)
        for (int r = 0; r < rows; r++) {
            Arrays.fill(newGrid[r], 1);
        }
        
        // retrieve all percepts from the environment
        Collection<Literal> percepts = consultPercepts("staychef");
        if (percepts == null) {
            // if some reason the percepts dont exist (eg not connected to Flask yet)
            System.out.println("Warning: getPercepts is null — skipping buildGrid for now");
            return newGrid; 
        }

        
        // go through each percept to find what each tile is
        for (Literal lit : consultPercepts("staychef")) {
            if (lit.getFunctor().equals("terrain")) {
                // terrain(x,y,item)
                int x = Integer.parseInt(lit.getTerm(0).toString()); 
                int y = Integer.parseInt(lit.getTerm(1).toString());
                String type = lit.getTerm(2).toString(); // e.g. P for pot dispensers
                
                // If the type is not 'e' which means empty then the cell is an obstacle
                if (!type.equals("e")) {
                    if (y >= 0 && y < rows && x >= 0 && x < cols) {
                        newGrid[y][x] = 0; // mark as blocked
                    }
                }
            }
        }
        // return the constructed grid to be used by a*
        return newGrid;
    }
    
    /**
     * Compute a sequence of move actions that navigates the agent from its
     * current position to the target coordinates (goalX, goalY) using A*.
     *
     * Steps:
     * -Rebuild the grid from the terrain percepts
     * -Extract the agent's current coordinates from perceptions
     * -Invoke a* on the grid to compute a raw path of coordinates
     * -translate the path into overcooked-ai actions such as up, down etc.
     *
     * -goalX is the target column index and goalY  is the target row index
     * it returns a List of action strings, or null/empty if no movement is required/possible
     */
    public List<String> computePathToGoal(int goalX, int goalY) {
        // Rebuild the grid 
        grid = buildGrid();
        
        // retrieve percepts to find the agent position
        Collection<Literal> percepts = consultPercepts("staychef");
        if (percepts == null) {
            System.out.println("No percepts found");
            return null;
        }
        for (Literal lit : consultPercepts("staychef")) {
            if (lit.getFunctor().equals("agent_position")) {
                // agent_position(X,Y)
                agentX = Integer.parseInt(lit.getTerm(0).toString());
                agentY = Integer.parseInt(lit.getTerm(1).toString());
                break;
            }
        }
        
        // source and des for a*
        int[] src  = {agentY, agentX};
        int[] dest = {goalY,  goalX};
        
        // perform a* 
        List<int[]> path = aStarSearch(grid, src, dest);
        if (path == null) {
            System.out.println("No path found.");
            return null;
        }
        if (path.isEmpty()) {
            System.out.println("Already at the destination.");
            return Collections.emptyList();
        }

        // The final cell in the raw path is the goal itself
        // need to remove it so agent stops adjacent as it cant and shouldnt go on top
        if (path.size() > 1) {
            path.remove(path.size()-1);
        }
        // Convert path to overcooked-ai actions
        return convertPathToActions(path);
    }
    
    /*
     * Initialisation and Socket.IO setup to connect to overcooked-ai
     * Called when the agent is created
    */
    @Override
    public void init(String[] args) {
        try {
            // Connect to Overcooked server on localhost via socket.IO
            // (the server listens at the 5000 port inside the container)
            socket = IO.socket("http://localhost:5000");
            
            // SOCKET.EVENT_CONNECT is triggered when the socket successfully connects to
            // Overcooked-AI. Used to join a game
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    System.out.println("[Kitchen] Connected to Overcooked server");
                    try {
                        socket.emit("java_connected", new JSONObject().put("status", "ready"));
                    } catch (JSONException e) { 
                        e.printStackTrace(); 
                    }
                }
            });

            // WAITING is triggered when the game is created
            socket.on("waiting", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    // Avoid multiple joins if multiple waiting events arrive
                    if (active_game) return;
                    try {
                        // put some default parameters if creation of the game fails so we can create a new one
                        JSONObject data_to_join = new JSONObject()
                            .put("create_if_not_found", false)
                            .put("params", new JSONObject()
                                    .put("playerZero", "human")
                                    .put("playerOne", "human")
                                    .put("layout", "cramped_room")
                                    .put("gameTime", "120")
                                    .put("dataCollection", "off")
                                    .put("layouts", new JSONArray().put("scenario1_s"))
                            )
                            .put("game_name", "overcooked");
                        // emit join which will be caught by the Flask server
                        socket.emit("join", data_to_join);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            });

            // will receive it when Flask says the game has started
            socket.on("start_game", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    System.out.println("[Kitchen] Game is now active (user pressed start button)");
                    active_game = true;
                }
            });
            
            // received when time has run out or user clicks exit game
            socket.on("end_game", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    System.out.println("[Kitchen] Received end_game. Game over");
                    // Clear percepts - all of them
                    clearPercepts();
                    active_game = false;
                }
            });

            // similar to end_game, but for different reasons
            socket.on("end_lobby", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    System.out.println("[Kitchen] Received end_lobby. Game over");
                    clearPercepts();
                    active_game = false;
                }
            });

            // Trigerred when the socket connection terminates unexpectedly
            socket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    System.out.println("Disconnected from the Overcooked-AI server.");
                    clearPercepts();
                    active_game = false;
                }
            });

            // AGENT_SHUTDOWN is triggered when we want to shutdown the gradle process
            socket.on("agent_shutdown", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    System.out.println("[Kitchen] agent_shutdown — waiting for Interpreter cleanup...");
                    clearPercepts();
                    active_game = false;
                    socket.disconnect();
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    System.out.println("[Kitchen] JVM shutdown.");
                    System.exit(0);
                }
            });


            // receoved when the game starts and used to feed the agent with initial beliefs
            socket.on("java_layout", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    if (args.length > 0) {
                        // print this for debugging purposes
                         System.out.println("Received layout: " + args[0]);
                        try {
                            // parse the json object with the data
                            JSONObject layoutObject = new JSONObject(args[0].toString());
                            String layoutName = layoutObject.getString("layout_name");
                            JSONArray terrainArray = layoutObject.getJSONArray("terrain");
                            
                            // Determine grid dimensions
                            gridRows = terrainArray.length();
                            gridCols = terrainArray.getJSONArray(0).length();
                            
                            //Iterate over each row and column to add terrain percepts for the agent
                            for (int row = 0; row < terrainArray.length(); row++){
                                JSONArray terrainRow = terrainArray.getJSONArray(row);
                                for (int column = 0; column < terrainRow.length(); column++){
                                    String item = terrainRow.getString(column);
                                    if (item.equals(" ")) {
                                        item = "e";  // free/passable
                                    }
                                    // add where each dispenser is as wel as counters - used by the agent when checking stuff
                                    if (item.equals("O")) {
                                        item = "onion";
                                        addPercept("staychef", Literal.parseLiteral("onion(" + column + "," + row + ")"));
                                    }
                                    if (item.equals("P")) {
                                        addPercept("staychef", Literal.parseLiteral("pot(" + column + "," + row + ")"));
                                    }
                                    if (item.equals("D")) {
                                        addPercept("staychef", Literal.parseLiteral("plate(" + column + "," + row + ")"));
                                    }
                                    if (item.equals("S")) {
                                        addPercept("staychef", Literal.parseLiteral("serve(" + column + "," + row + ")"));
                                    }
                                    if (item.equals("T")) {
                                        addPercept("staychef", Literal.parseLiteral("tomato(" + column + "," + row + ")"));
                                    }
                                    if (item.equals("X")) {
                                        addPercept("staychef", Literal.parseLiteral("counter(" + column + "," + row + ")"));
                                    }
                                    
                                    // add the terrain too e.g. terrain(X,Y,onion) - not really used
                                    addPercept("staychef", Literal.parseLiteral(
                                            "terrain(" + column + "," + row + "," + item + ")"));
                                    
                                }
                            }
                            
                            // not really needed, could be removed
                            addPercept("staychef", Literal.parseLiteral("layout_name(" + layoutName + ")"));
                            
                            // Extract recipes (e.g. recipes[[tomato,onion],[onion,onion,onion]])
                            // and agent and human player positions
                            if (layoutObject.has("state")) {
                                JSONObject stateWrapper = layoutObject.getJSONObject("state");
                                if (stateWrapper.has("state")) {
                                    JSONObject state = stateWrapper.getJSONObject("state");
                                    if (state.has("players")) {
                                        JSONArray players = state.getJSONArray("players");
                                        if (players.length() > 1) {
                                            // player index 1 is the symbolic ai agent
                                            JSONObject agentPlayer = players.getJSONObject(1);
                                            JSONArray posArr = agentPlayer.getJSONArray("position");
                                            agentX = posArr.getInt(0);
                                            agentY = posArr.getInt(1);
                                            JSONObject humanPlayer = players.getJSONObject(0);
                                            JSONArray positArr = humanPlayer.getJSONArray("position");
                                            humanX = positArr.getInt(0);
                                            humanY = positArr.getInt(1);
                                        }
                                    }
                                    // combine all orders and bonus orders, but actually bonus orders are a subset of
                                    // all orders but that doesnt affect the game as if an order that was also a bonus order gets
                                    // completed it will be popped out of the list
                                    JSONArray allOrders = state.getJSONArray("all_orders");
                                    JSONArray bonusOrders = state.getJSONArray("bonus_orders");
                                    JSONArray combinedRecipes = new JSONArray();
                                    for (int i = 0; i < allOrders.length(); i++) {
                                        JSONObject order = allOrders.getJSONObject(i);
                                        combinedRecipes.put(order.getJSONArray("ingredients"));
                                    }
                                    for (int i = 0; i < bonusOrders.length(); i++) {
                                        JSONObject order = bonusOrders.getJSONObject(i);
                                        combinedRecipes.put(order.getJSONArray("ingredients"));
                                    }
                                    // convert the recipes to jason readable format
                                    ListTerm recipesList = new ListTermImpl();
                                    for (int i = 0; i < combinedRecipes.length(); i++) {
                                        JSONArray recipeArray = combinedRecipes.getJSONArray(i);
                                        ListTerm recipeTerm = new ListTermImpl();
                                        for (int j = 0; j < recipeArray.length(); j++) {
                                            String ingredient = recipeArray.getString(j);
                                            recipeTerm.add(ASSyntax.createAtom(ingredient));
                                        }
                                        recipesList.add(recipeTerm);
                                    }
                                    Literal recipesLiteral = ASSyntax.createLiteral("recipes", recipesList);
                                    addPercept("staychef", recipesLiteral);
                                } else {
                                    System.out.println("A problem ocurred on initialisation.");
                                    // adding empty recipes, we get updates overy 0.2 secs so we can come back from this fail easy
                                    addPercept("staychef", ASSyntax.createLiteral("recipes", new ListTermImpl()));
                                }
                            } else {
                                System.out.println("No state provided with layout.");
                                addPercept("staychef", ASSyntax.createLiteral("recipes", new ListTermImpl()));
                            }
                            
                            // Precumpute the reachability for both players
                            computeInitialReachability(agentX, agentY,1);
                            computeInitialReachability(humanX, humanY,0);

                            // Let the agent begin - this percept is what starts it
                            addPercept("staychef", Literal.parseLiteral("begin(now)"));
                            socket.emit("agent:plans_ready", new JSONObject().put("status", "ready"));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
            


            /*
             * this event is emmitted a few times per second (every 0.2s) and is used to
             * update the agent's beliefs about the environment/current game state
             * countains players positions, orders, pot contents, cooking/ready soup, if a player is holding something
             */
            socket.on("java_state_update", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    if (args.length > 0) {
                        try {
                            // WARNING: only uncomment if necessary bc it prints so often nothing else will be easy to see
                            //System.out.println("here updates " + args[0]);
                            JSONObject message = new JSONObject(args[0].toString());
                            JSONObject state = message.getJSONObject("state");
                            JSONArray players = state.getJSONArray("players");
                            
                            // Remove old perceptions
                            removePerceptsByUnif("staychef", Literal.parseLiteral("recipes(_)"));
                            removePerceptsByUnif("staychef", Literal.parseLiteral("human_position(_,_)"));
                            removePerceptsByUnif("staychef", Literal.parseLiteral("human_holds(_)"));
                            removePerceptsByUnif("staychef", Literal.parseLiteral("agent_position(_,_)"));
                            removePerceptsByUnif("staychef", Literal.parseLiteral("agent_holds(_)"));

                            // for orders do the same as in java_layout
                            if (state.has("all_orders") && state.has("bonus_orders")) {
                                JSONArray allOrders = state.getJSONArray("all_orders");
                                JSONArray bonusOrders = state.getJSONArray("bonus_orders");
                                JSONArray combinedRecipes = new JSONArray();
                                for (int i = 0; i < allOrders.length(); i++) {
                                    JSONObject order = allOrders.getJSONObject(i);
                                    combinedRecipes.put(order.getJSONArray("ingredients"));
                                }
                                for (int i = 0; i < bonusOrders.length(); i++) {
                                    JSONObject order = bonusOrders.getJSONObject(i);
                                    combinedRecipes.put(order.getJSONArray("ingredients"));
                                }
            
                                ListTerm recipesList = new ListTermImpl();
                                for (int i = 0; i < combinedRecipes.length(); i++) {
                                    JSONArray recipeArray = combinedRecipes.getJSONArray(i);
                                    ListTerm recipeTerm = new ListTermImpl();
                                    for (int j = 0; j < recipeArray.length(); j++) {
                                        String ingredient = recipeArray.getString(j);
                                        recipeTerm.add(ASSyntax.createAtom(ingredient));
                                    }
                                    recipesList.add(recipeTerm);
                                }
                                Literal recipesLiteral = ASSyntax.createLiteral("recipes", recipesList);
                                addPercept("staychef", recipesLiteral);
                                // System.out.println("Updated recipes: " + recipesLiteral);
            
                            } else {
                                // If no orders in state add empty recipes which will actually happen once all recipes have been completed
                                removePerceptsByUnif("staychef", Literal.parseLiteral("recipes(_)"));
                                addPercept("staychef", ASSyntax.createLiteral("recipes", new ListTermImpl()));
                                System.out.println("No all_orders or bonus_orders => recipes([]) added");
                            }
            
                            // Add new updated perceptions
                            for (int i = 0; i < players.length(); i++) {
                                // orientation - not used for now
                                JSONObject playersInfo = players.getJSONObject(i);
                                JSONArray orientations = playersInfo.getJSONArray("orientation");
                                int xOrient = orientations.getInt(0);
                                int yOrient = orientations.getInt(1);
                                // position - VERY important
                                JSONArray positions = playersInfo.getJSONArray("position");
                                int xPos = positions.getInt(0);
                                int yPos = positions.getInt(1);
                                // if players hold objects such as a plate for example
                                String heldObject = "none";
                                if (!playersInfo.isNull("held_object")) {
                                    JSONObject heldObj = playersInfo.getJSONObject("held_object");
                                    String objName = heldObj.getString("name");
                                    heldObject = objName;
                                }
                                // now actually add those perceptions to the agent's belief base
                                if (i == 0) {
                                    // Human
                                    addPercept("staychef", Literal.parseLiteral("human_position(" + xPos + "," + yPos + ")"));
                                    addPercept("staychef", Literal.parseLiteral("human_holds(" + heldObject + ")"));
                                } else if (i == 1) {
                                    // Our agent
                                    addPercept("staychef", Literal.parseLiteral("agent_position(" + xPos + "," + yPos + ")"));
                                    addPercept("staychef", Literal.parseLiteral("agent_holds(" + heldObject + ")"));
                                }
                            }

                            // Remove old pot perceptions and placed down ingredients too each time we get an update
                            // a placed_tomato for example is a tomato that is not in the tomato dispenser but on a counter tile
                            removePerceptsByUnif("staychef", Literal.parseLiteral("placed_stuff(_,_)"));
                            removePerceptsByUnif("staychef", Literal.parseLiteral("placed_soup(_,_)"));
                            removePerceptsByUnif("staychef", Literal.parseLiteral("placed_onion(_,_)"));
                            removePerceptsByUnif("staychef", Literal.parseLiteral("placed_tomato(_,_)"));
                            removePerceptsByUnif("staychef", Literal.parseLiteral("placed_dish(_,_)"));
                            removePerceptsByUnif("staychef", Literal.parseLiteral("pot_contents(_,_,_,_)"));
                            removePerceptsByUnif("staychef", Literal.parseLiteral("pot_cooking(_,_)"));
                            removePerceptsByUnif("staychef", Literal.parseLiteral("pot_ready(_,_)"));
                            removePerceptsByUnif("staychef", Literal.parseLiteral("not_cooking(_,_)"));

                            // if there was new objects that means theyre added on a counter so we add it to the belief base so the agent can take care of them
                            // e.g. we have an onion (O) placed on a tile that is typically a counter (X) - so the onion was placed there and it is not an onion dispenser
                            // in objects we would not even have the normal dispensers displayed, bc we only get that on java_layout
                            if (state.has("objects")) {
                                JSONArray objects = state.getJSONArray("objects");
                                for (int i = 0; i < objects.length(); i++) {
                                    JSONObject obj = objects.getJSONObject(i);
                                    String objName = obj.getString("name");
                                    JSONArray pos = obj.getJSONArray("position");
                                    int px = pos.getInt(0);
                                    int py = pos.getInt(1);
            
                                    // If it's just a loose onion
                                    if (objName.equals("onion")) {
                                        addPercept("staychef", Literal.parseLiteral("placed_stuff(" + px + "," + py + ")"));
                                        addPercept("staychef", Literal.parseLiteral("placed_onion(" + px + "," + py + ")"));
                                        
                                    }
                                    // If it's a loose tomato
                                    else if (objName.equals("tomato")) {
                                        addPercept("staychef", Literal.parseLiteral("placed_tomato(" + px + "," + py + ")"));
                                        
                                        addPercept("staychef", Literal.parseLiteral("placed_stuff(" + px + "," + py + ")"));
                                    }
                                    // If it's a loose plate
                                    else if (objName.equals("dish")) {
                                        addPercept("staychef", Literal.parseLiteral("placed_dish(" + px + "," + py + ")"));
                                        
                                        addPercept("staychef", Literal.parseLiteral("placed_stuff(" + px + "," + py + ")"));
                                    }
                                    // Mark that there is a placed soup. NB: a placed_soup will also be a cooked one in a pot
                                    // so when checking for a placed soup on coummter need to check for sth like placed_soup(X,Y) and not(pot(X,Y))
                                    else if (objName.equals("soup")) {
                                        addPercept("staychef", Literal.parseLiteral("placed_soup(" + px + "," + py + ")"));
                                        addPercept("staychef", Literal.parseLiteral("placed_stuff(" + px + "," + py + ")"));
                                        
                                        // Now will count how many ingredients we have in a pot
                                        int onionCount = 0; // default them to 0, bc a pot can be empty too
                                        int tomatoCount = 0;
                                        if (obj.has("_ingredients")) {
                                            JSONArray ingrArray = obj.getJSONArray("_ingredients");
                                            for (int ing_i = 0; ing_i < ingrArray.length(); ing_i++) {
                                                JSONObject ing = ingrArray.getJSONObject(ing_i);
                                                String ingName = ing.getString("name");
                                                if (ingName.equals("onion"))  onionCount++;
                                                if (ingName.equals("tomato")) tomatoCount++;
                                            }
                                        }
                                        // now add percepts with the onion/tomatoes count for a specific pot
                                        addPercept("staychef", Literal.parseLiteral(
                                            "pot_contents(" + px + "," + py + ",onion," + onionCount + ")"
                                        ));
                                        addPercept("staychef", Literal.parseLiteral(
                                            "pot_contents(" + px + "," + py + ",tomato," + tomatoCount + ")"
                                        ));
                                        
                                        // also add if a pot is cooking or ready to help the agent when making decisions
                                        // pot_cooking(X,Y) would mean that a pot at (X,Y) has a soup thats currently cooking
                                        boolean isCooking = obj.getBoolean("is_cooking");
                                        boolean isReady   = obj.getBoolean("is_ready");
                                        if (isCooking) {
                                            addPercept("staychef", Literal.parseLiteral("pot_cooking(" + px + "," + py + ")"));
                                        }
                                        if (isReady) {
                                            addPercept("staychef", Literal.parseLiteral("pot_ready(" + px + "," + py + ")"));
                                        } else {
                                            addPercept("staychef", Literal.parseLiteral("not_ready(" + px + "," + py + ")"));
                                        }
                        
                                    }
                                    
                                    
                                }
                            }
            
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
            
            // Finally, connect the socket
            socket.connect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Computes initial reachability percepts for the human player and the agent.
     * This method runs A* from the given start position to every object of interest in the kitchen,
     * which is pots, dishes, ingredients and serving tiles.
     * Then it adds beliefs indicating whether each object can or cannot be reached.
     * Very useful for the agent and its knowledge about the environment
     *
     * agentStartX is the starting X-coordinate
     * agentStartY is the starting Y-coordinate 
     * humanOrAgent: 0 for human, 1 for agent so we know if we are computing for human/agent and add the appropriate one
     */
    private void computeInitialReachability(int agentStartX, int agentStartY, int humanOrAgent) {
        System.out.println("Compute Reachability from ("+agentStartX+","+agentStartY+")");
        
        // Build the grid once
        grid = buildGrid();
        
        // Get all percepts from the existing beliefs
        Collection<Literal> allPercepts = consultPercepts("staychef");
        
        // Prepare lists to collect the coordinates of each object type
        List<int[]> onionPositions  = new ArrayList<>();
        List<int[]> tomatoPositions = new ArrayList<>();
        List<int[]> potPositions    = new ArrayList<>();
        List<int[]> platePositions  = new ArrayList<>();
        List<int[]> servePositions  = new ArrayList<>();
        List<int[]> counterpositions  = new ArrayList<>();
        
        // Iterate over all percepts to extract object coordinates
        for (Literal lit : allPercepts) {
            String functor = lit.getFunctor();
            if (functor.equals("onion")) {
                int x = Integer.parseInt(lit.getTerm(0).toString());
                int y = Integer.parseInt(lit.getTerm(1).toString());
                onionPositions.add(new int[]{x,y});
            } else if (functor.equals("tomato")) {
                int x = Integer.parseInt(lit.getTerm(0).toString());
                int y = Integer.parseInt(lit.getTerm(1).toString());
                tomatoPositions.add(new int[]{x,y});
            } else if (functor.equals("pot")) {
                int x = Integer.parseInt(lit.getTerm(0).toString());
                int y = Integer.parseInt(lit.getTerm(1).toString());
                potPositions.add(new int[]{x,y});
            } else if (functor.equals("plate")) {
                int x = Integer.parseInt(lit.getTerm(0).toString());
                int y = Integer.parseInt(lit.getTerm(1).toString());
                platePositions.add(new int[]{x,y});
            } else if (functor.equals("serve")) {
                int x = Integer.parseInt(lit.getTerm(0).toString());
                int y = Integer.parseInt(lit.getTerm(1).toString());
                servePositions.add(new int[]{x,y});
            } else if (functor.equals("counter")) {
                int x = Integer.parseInt(lit.getTerm(0).toString());
                int y = Integer.parseInt(lit.getTerm(1).toString());
                counterpositions.add(new int[]{x,y});}
  
        }
        
        // 3) For each object, run A* from agentStart to that object
        int[] src = new int[]{/* Y= */agentStartY, /* X= */agentStartX};
        
        //  For each object type, check reachability and assert appropriate beliefs using the helper function
        checkReachabilityAndAddBelief("onion", onionPositions, src,humanOrAgent);
        checkReachabilityAndAddBelief("tomato", tomatoPositions, src,humanOrAgent);
        checkReachabilityAndAddBelief("pot",   potPositions,    src,humanOrAgent);
        checkReachabilityAndAddBelief("plate", platePositions,  src,humanOrAgent);
        checkReachabilityAndAddBelief("serve", servePositions,  src,humanOrAgent);
        checkReachabilityAndAddBelief("counter", counterpositions,  src,humanOrAgent);
    }
    
    /*
     * Helper function that actually runs A* to compute reachability from human/agent to an object
     * 
     * objectType - onion, tomato, pot,etc
     * positions - target coordinates (the ones of the object)
     * src - positions of the agent/human
     * humanOrAgent - human is 0. agent is 1
     */

    private void checkReachabilityAndAddBelief(String objectType, List<int[]> positions, int[] src, int humanOrAgent) {
        // Iterate over every target coordinate
        for (int[] pos : positions) {
            int ox = pos[0];
            int oy = pos[1];
            // Destination coords
            int[] dest = {oy, ox};
            // Perform A* search on the grid
            List<int[]> path = aStarSearch(grid, src, dest);
            // construct the beliefs based on the answers we got from a* and based on if a human or agent
            if (humanOrAgent == 0) {
                if (path != null) {
                    // can reach
                    addPercept("staychef", 
                        Literal.parseLiteral("can_reach_human("+objectType+","+ox+","+oy+")"));
                } else {
                    // cannot reach
                    addPercept("staychef", 
                        Literal.parseLiteral("cannot_reach_human("+objectType+","+ox+","+oy+")"));
                }
            } else if (humanOrAgent == 1) {

                if (path != null) {
                // can reach
                addPercept("staychef", 
                    Literal.parseLiteral("can_reach_agent("+objectType+","+ox+","+oy+")"));
            } else {
                // cannot reach
                addPercept("staychef", 
                    Literal.parseLiteral("cannot_reach_agent("+objectType+","+ox+","+oy+")"));
            }
            }
            
        }
    }
    
    /**
     * Executes a given action structure sent by the Jason agent.
     * This method interprets the functor, dispatches to appropriate handlers,
     * and communicates actions back to the Overcooked-AI server or manipulates beliefs.
     *
     * act - the structure representing the action and its arguments
     * returns true to indicate successful handling, regardless of outcome
     */
    @Override
    public boolean executeAction(String agName, Structure act) {
        String functor = act.getFunctor(); // e.g., "compute_path", "match_pot_with_recipe", "action", etc.

        // this one triggers the use of A*. Used when an agent wants to go from point A to point B
        if (functor.equalsIgnoreCase("compute_path")) {
            try {
                // Parse the target coordinates from the plan arguments
                // AgentSpeak: !compute_path(X, Y)
                int goalX = Integer.parseInt(act.getTerm(0).toString());
                int goalY = Integer.parseInt(act.getTerm(1).toString());
                // Compute a sequence of moves (up/down/left/right) from current position to (goalX, goalY) using A*
                List<String> actions = computePathToGoal(goalX, goalY);
                // Send each computed move to the Overcooked server with a short delay so its not too quick on the screen and doesnt crash
                if (actions != null) {
                    for (String step : actions) {
                        JSONObject actionData = new JSONObject().put("action", step.toUpperCase());
                        socket.emit("action", actionData);
                        System.out.println("Action " + step);
                        try {
                            Thread.sleep(300); // delay of 0.3 secs
                        } catch (InterruptedException ie) {
                            ie.printStackTrace();
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (functor.equalsIgnoreCase("action") && act.getArity() == 1) {
            // simple single action. Mainly used for interaction action which is the space bar
            // eg action("space"), action("up")
            try {
                // Extract the direction string, uppercase it, and emit to server
                String direction = act.getTerm(0).toString().replace("\"", "").toUpperCase();
                JSONObject actionData = new JSONObject().put("action", direction);
                socket.emit("action", actionData);
                System.out.println("Action " + direction);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        } else if (functor.equalsIgnoreCase("thought") && act.getArity() == 1) {
            // Emit an internal ''thought'' for optional display 
            try {
                // Extract the thought text 
                String thoughtMsg = act.getTerm(0).toString().replaceAll("\"", "");
                // Create a JSON object with the thought message
                JSONObject thoughtData = new JSONObject().put("thought", thoughtMsg);
                // Emit this thought to the client - index.js will decide whether to display it
                socket.emit("thought", thoughtData);
                // for debugging, comment out if not wanted
                System.out.println("Thought sent: " + thoughtMsg);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        } else if (functor.equalsIgnoreCase("remove_beliefs") && act.getArity() == 1) {
            // Remove beliefs for the agent
            // Example usage: remove_beliefs("can_reach_human(counter,_,_)")
            try {
                // The single argument is a string that should parse to a literal template
                String templateStr = act.getTerm(0).toString();
                // e.g. "can_reach_human(counter,_,_)"
                System.out.println("Removing belief: " + templateStr);
    
                // Parse it into a Jason literal
                Literal templateLit = Literal.parseLiteral(templateStr);
    
                // Now remove from the environment's belief base
                removePerceptsByUnif("staychef", templateLit);
    
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (functor.equalsIgnoreCase("add_beliefs") && act.getArity() == 1) {
            // Add beliefs for the agent 
            // Example usage: add_beliefs("something(1,2)")
            try {
                String literalStr = act.getTerm(0).toString(); 
                System.out.println("Adding belief: " + literalStr);
                // Convert it into a Jason Literal
                Literal newBelief = Literal.parseLiteral(literalStr);
                // Now add it to the belief base
                addPercept("staychef", newBelief);
        
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        
        else {
            // Fallback for other functors that might appear - will just print out in the mas console
            // very useful for debuggings
            try {
                String actionLiteral = act.toString().replaceAll("\"", "").toUpperCase();
                JSONObject actionData = new JSONObject().put("action", actionLiteral);
                socket.emit("action", actionData);
                System.out.println("Action " + actionLiteral);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return true;
    }
}
