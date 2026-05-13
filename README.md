# Design and Implementation of Human–AI Cooperation in a Video Game

**Author:** Margarita H. Radeva and Gianluca Scollo

---

## Overview

This repository contains a **Jason 3.3.0**–based **Symbolic AI** agent that integrates with the open‑source Overcooked-AI environment. The agent provides a transparent, logic‑driven teammate for real‑time human–AI collaboration in the Overcooked cooperative cooking benchmark.

This version also includes an integration with **ChatBDI** (from the `chatbdi` project) to support natural-language interaction patterns and a supporting interpreter component during chat phases.

In this integration, ChatBDI has been extended with an **explainability** feature based on two additional message types:
- **`askHow`**: generated when the user asks “how ...” questions about the agent’s behavior (e.g., *"How do you know when the pot is ready?"*).
- **`tellHow`**: sent back by the agent with a set of relevant internal plans/knowledge, which are then turned into a natural-language explanation for the user.

> **Component repository:** this repository is not the main entrypoint.  
> It is automatically fetched and started by **OvercookedAI** when the match starts or when the chat phase is triggered.

---

## Links & Related Repos

- **Overcooked-AI (single entrypoint / system runner)**  
  https://github.com/GianlucaScollo/OvercookedAI  
- **Original Symbolic AI Agent repository (reference)**  
  https://github.com/margaritaradeva/SymbolicAIAgent  
- **ChatBDI (upstream project integrated here)**  
  https://github.com/VEsNA-ToolKit/chatbdi  
- **Original Overcooked-AI repository (reference)**  
  https://github.com/HumanCompatibleAI/overcooked_ai  

---

## Usage

No standalone setup is required for normal usage.

To run the project end-to-end, follow the **OvercookedAI** README:
https://github.com/GianlucaScollo/OvercookedAI

> **Note:** all dependencies will be installed by the OvercookedAI Docker build.

### Runtime note (LLM / chat)
Chat and explainability features rely on **Ollama running on the host machine** (outside Docker). The Ollama model identifiers are configured in `kitchen.mas2j` (e.g., `gen_model`, `emb_model`).

> Advanced note (optional): you may build/debug this repository separately, but it is not required to use the system as designed.

---

## Repository Structure

```plaintext
SymbolicAIAgent/
├── interpreter/                          # ChatBDI interpreter
│   ├── gradle/wrapper/
│   └── src/agt/chatbdi/
│       ├── modelfiles/
│       ├── EmbeddingSpace.java
│       ├── Interpreter.java
│       ├── Ollama.java
│       └── Tools.java
├── jia/                                  # Java internal actions
│   ├── get_recipe_at_index.java          # Get a recipe at a certain index     
│   └── get_pot.java                      # Check if an ingredient matches any pot
├── Kitchen.java                          # Agent Environment
├── README.md                             # You are here!
├── build.gradle                          # Gradle build script
├── kitchen.mas2j                         # Agent configuration file
├── staychef.asl                          # Agent logic written in AgentSpeak
└── user.asl                              # Agent used for ChatBDI
```

---

## Acknowledgements

- **HumanCompatibleAI/overcooked_ai** for the original cooperative benchmark environment.
- **VEsNA-ToolKit/chatbdi** for the ChatBDI framework integrated into this version.

---
