package src.pas.pokemon.agents;

import edu.bu.pas.pokemon.core.Battle.BattleView;
import edu.bu.pas.pokemon.core.Move.MoveView;
import edu.bu.pas.pokemon.core.Team.TeamView;
import edu.bu.pas.pokemon.core.Pokemon.PokemonView;
import edu.bu.pas.pokemon.utils.Pair;
import edu.bu.pas.pokemon.core.enums.Stat;
import edu.bu.pas.pokemon.core.enums.NonVolatileStatus;
import edu.bu.pas.pokemon.core.enums.Flag;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class GameNode {
    // Enum to represent the three types of nodes in our stochastic game tree
    public enum NodeType {
        MAX,       // Our turn to maximize utility
        MIN,       // Opponent's turn to minimize utility
        CHANCE     // Random events (move order, status effects, move outcomes, etc.)
    }

    // Fields
    private BattleView battleView;     // Current game state
    private NodeType type;             // Type of this node (MAX, MIN, or CHANCE)
    private int depth;                 // Depth in the tree
    private MoveView lastMove;         // Move that led to this state (null for root)
    private double probability;        // Probability of reaching this node (1.0 for non-chance nodes)
    private double utilityValue;       // Evaluated utility of this node
    private int myTeamIdx;             // Index of our team (0 or 1)
    
    // Constructor
    public GameNode(BattleView battleView, NodeType type, int depth, MoveView lastMove, 
                   double probability, int myTeamIdx) {
        this.battleView = battleView;
        this.type = type;
        this.depth = depth;
        this.lastMove = lastMove;
        this.probability = probability;
        this.myTeamIdx = myTeamIdx;
        this.utilityValue = 0.0; // Will be calculated later
    }
    
    // Getters and setters
    public BattleView getBattleView() { return battleView; }
    public NodeType getType() { return type; }
    public int getDepth() { return depth; }
    public MoveView getLastMove() { return lastMove; }
    public double getProbability() { return probability; }
    public double getUtilityValue() { return utilityValue; }
    public void setUtilityValue(double utilityValue) { this.utilityValue = utilityValue; }
    public int getMyTeamIdx() { return myTeamIdx; }

    public boolean isTerminal() {
        // A node is terminal if the battle is over (one team has no Pokémon left)
        return battleView.isOver();
    }
    
    /**
     * Generates all possible child nodes from the current game state.
     * This is a complex method that handles all the randomness layers in Pokémon battles.
     */
    public List<GameNode> getChildren() {
        List<GameNode> children = new ArrayList<>();
        
        // If this is a terminal node, return empty list
        if (isTerminal()) {
            return children;
        }
        
        // The implementation depends on the node type
        switch (type) {
            case MAX:
                // Our turn - generate nodes for each move we can make
                generateMaxNodeChildren(children);
                break;
                
            case MIN:
                // Opponent's turn - generate nodes for each move they can make
                generateMinNodeChildren(children);
                break;
                
            case CHANCE:
                // Random outcomes - generate nodes for each possible outcome
                generateChanceNodeChildren(children);
                break;
        }
        
        return children;
    }
    
    /**
     * Generate child nodes for MAX nodes (our turn)
     */
    private void generateMaxNodeChildren(List<GameNode> children) {
        // Get our active Pokémon
        PokemonView activePokemon = battleView.getTeamView(myTeamIdx).getActivePokemonView();
        
        // Get all available moves for our active Pokémon
        List<MoveView> availableMoves = activePokemon.getAvailableMoves();
        
        // Create a CHANCE node for each move we can make
        for (MoveView move : availableMoves) {
            // At this point, we don't know who goes first, or if status effects will prevent our move
            // So we create a CHANCE node to represent that uncertainty
            GameNode chanceNode = new GameNode(
                battleView, // Same battle view
                NodeType.CHANCE, 
                depth + 1,
                move, 
                1.0, // Probability 1.0 because we're choosing this move
                myTeamIdx
            );
            
            children.add(chanceNode);
        }
    }
    
    /**
     * Generate child nodes for MIN nodes (opponent's turn)
     */
    private void generateMinNodeChildren(List<GameNode> children) {
        // Get opponent's active Pokémon
        PokemonView activePokemon = battleView.getTeamView(1 - myTeamIdx).getActivePokemonView();
        
        // Get all available moves for opponent's active Pokémon
        List<MoveView> availableMoves = activePokemon.getAvailableMoves();
        
        // Create a CHANCE node for each move the opponent can make
        for (MoveView move : availableMoves) {
            GameNode chanceNode = new GameNode(
                battleView,
                NodeType.CHANCE,
                depth + 1,
                move,
                1.0,
                myTeamIdx
            );
            
            children.add(chanceNode);
        }
    }
    

    private void generateChanceNodeChildren(List<GameNode> children) {
        // Get our move (stored in lastMove of this CHANCE node)
        MoveView ourMove = this.lastMove;
    
        // Get opponent's move (from the parent of this node, if available)
        MoveView opponentMove = null; // Will need to be obtained
    
        // Get active Pokémon for both teams
        PokemonView ourPokemon = battleView.getTeamView(myTeamIdx).getActivePokemonView();
        PokemonView opponentPokemon = battleView.getTeamView(1 - myTeamIdx).getActivePokemonView();
    
        // LAYER 1: Move ordering randomness
        // Determine who goes first based on move priority, speed, and randomness
        List<Pair<Double, Boolean>> moveOrderProbabilities = determineMoveOrder(ourPokemon, opponentPokemon, ourMove, opponentMove);
    
        for (Pair<Double, Boolean> orderPair : moveOrderProbabilities) {
            double orderProbability = orderPair.getFirst();
            boolean weGoFirst = orderPair.getSecond();
        
            // LAYER 2: Status effect randomness (sleep/frozen/paralyzed/confusion)
            processStatusEffects(children, ourPokemon, opponentPokemon, ourMove, opponentMove, 
                            orderProbability, weGoFirst);
        }
    }

  

    /**
    * Determines the possible move orders and their probabilities
    * @return List of pairs (probability, weGoFirst)
    */
    private List<Pair<Double, Boolean>> determineMoveOrder(PokemonView ourPokemon, PokemonView opponentPokemon, 
            MoveView ourMove, MoveView opponentMove) {
        
        List<Pair<Double, Boolean>> results = new ArrayList<>();

        // If moves aren't available yet (e.g., at simulation start), use placeholders
        if (ourMove == null || opponentMove == null) {
            // Default to 50/50 if we can't determine
            results.add(new Pair<>(0.5, true));  // We go first with 50% probability
            results.add(new Pair<>(0.5, false)); // Opponent goes first with 50% probability
            return results;
        }

        // Compare move priorities
        int ourPriority = ourMove.getPriority();
        int opponentPriority = opponentMove.getPriority();

        if (ourPriority > opponentPriority) {
            // Our move has higher priority, we go first with 100% probability
            results.add(new Pair<>(1.0, true));
        } else if (opponentPriority > ourPriority) {
            // Opponent's move has higher priority, they go first with 100% probability
            results.add(new Pair<>(1.0, false));
        } else {
            // Same priority, compare speed
            int ourSpeed = ourPokemon.getCurrentStat(Stat.SPD);
            int opponentSpeed = opponentPokemon.getCurrentStat(Stat.SPD);

            // Adjust speed for paralysis (75% of original)
            if (ourPokemon.getNonVolatileStatus() == NonVolatileStatus.PARALYSIS) {
                ourSpeed = (int)(ourSpeed * 0.75);
            }
            if (opponentPokemon.getNonVolatileStatus() == NonVolatileStatus.PARALYSIS) {
                opponentSpeed = (int)(opponentSpeed * 0.75);
            }

            if (ourSpeed > opponentSpeed) {
                // We're faster, go first with 100% probability
                results.add(new Pair<>(1.0, true));
            } else if (opponentSpeed > ourSpeed) {
                // Opponent is faster, they go first with 100% probability
                results.add(new Pair<>(1.0, false));
            } else {
                // Same speed, 50/50 chance
                results.add(new Pair<>(0.5, true));
                results.add(new Pair<>(0.5, false));
            }
        }

        return results;
    }



    /**
     * Process status effects for both Pokémon
     */
    private void processStatusEffects(List<GameNode> children, PokemonView ourPokemon, PokemonView opponentPokemon,
                                    MoveView ourMove, MoveView opponentMove, double orderProbability, 
                                    boolean weGoFirst) {
        // Calculate probabilities for our Pokémon's status effects
        List<Pair<Double, MoveView>> ourOutcomes = getPossibleMoveOutcomes(ourPokemon, ourMove);
        
        // Calculate probabilities for opponent's Pokémon's status effects
        List<Pair<Double, MoveView>> opponentOutcomes = getPossibleMoveOutcomes(opponentPokemon, opponentMove);
        
        // For each combination of our status outcome and opponent's status outcome
        for (Pair<Double, MoveView> ourOutcome : ourOutcomes) {
            double ourProb = ourOutcome.getFirst();
            MoveView ourEffectiveMove = ourOutcome.getSecond();
            
            for (Pair<Double, MoveView> opponentOutcome : opponentOutcomes) {
                double opponentProb = opponentOutcome.getFirst();
                MoveView opponentEffectiveMove = opponentOutcome.getSecond();
                
                // Combined probability of this particular status outcome
                double combinedProb = orderProbability * ourProb * opponentProb;
                
                // LAYER 3: Move resolution randomness
                processMoveResolution(children, ourPokemon, opponentPokemon, ourEffectiveMove, 
                                    opponentEffectiveMove, combinedProb, weGoFirst);
            }
        }
    }

    /**
     * Get possible move outcomes based on status effects
     * @return List of pairs (probability, effectiveMove)
     */
    private List<Pair<Double, MoveView>> getPossibleMoveOutcomes(PokemonView pokemon, MoveView move) {
        List<Pair<Double, MoveView>> outcomes = new ArrayList<>();
        
        // If no move (e.g., simulation start), return no-op
        if (move == null) {
            outcomes.add(new Pair<>(1.0, null));
            return outcomes;
        }
        
        // Check for sleep, freeze, or paralysis (preventing move)
        NonVolatileStatus status = pokemon.getNonVolatileStatus();
        
        double successProb = 1.0;
        
        if (status == NonVolatileStatus.SLEEP || status == NonVolatileStatus.FREEZE) {
            // 9.8% chance to wake up/thaw
            successProb = 0.098;
            outcomes.add(new Pair<>(1.0 - successProb, null)); // Can't move
        } else if (status == NonVolatileStatus.PARALYSIS) {
            // 25% chance of full paralysis
            successProb = 0.75;
            outcomes.add(new Pair<>(1.0 - successProb, null)); // Can't move
        }
        
        // Check for confusion (50% chance to hurt yourself)
        if (pokemon.getFlag(Flag.CONFUSED)) {
            double confusionDamageProb = 0.5 * successProb;
            outcomes.add(new Pair<>(confusionDamageProb, createSelfDamageMove()));
            successProb = 0.5 * successProb;
        }
        
        // Success: use the intended move
        if (successProb > 0) {
            outcomes.add(new Pair<>(successProb, move));
        }
        
        return outcomes;
    }


    /**
     * Creates a synthetic move for self-damage due to confusion
     * Based on the instructions from your professor
     */
    private MoveView createSelfDamageMove() {
        try {
            // Based on the instructions, confusion damage:
            // - Uses Type.NORMAL
            // - Is a physical attack
            // - Has base power of 40
            // - Has perfect accuracy
            // - Ignores STAB and type effectiveness
            // - Targets the caster (self)
            
            // We may need to use reflection to create a Move object since we don't have direct constructor access
            Class<?> moveClass = Class.forName("edu.bu.pas.pokemon.core.Move");
            
            // Try to get constructor
            java.lang.reflect.Constructor<?> constructor = moveClass.getDeclaredConstructor(
                String.class, // name
                Class.forName("edu.bu.pas.pokemon.core.Type"), // type
                Class.forName("edu.bu.pas.pokemon.core.Category"), // category
                int.class, // base power
                Integer.class, // accuracy
                Integer.class, // max uses
                int.class, // critical hit ratio
                int.class // priority
            );
            
            constructor.setAccessible(true);
            
            // Get Type.NORMAL and Category.PHYSICAL through reflection
            Class<?> typeClass = Class.forName("edu.bu.pas.pokemon.core.Type");
            Object normalType = typeClass.getField("NORMAL").get(null);
            
            Class<?> categoryClass = Class.forName("edu.bu.pas.pokemon.core.Category");
            Object physicalCategory = categoryClass.getField("PHYSICAL").get(null);
            
            // Create the move
            Object move = constructor.newInstance(
                "SelfDamage", // move name
                normalType, // Type.NORMAL
                physicalCategory, // Category.PHYSICAL
                40, // base power for confusion is 40
                Integer.MAX_VALUE, // perfect accuracy
                1, // number of uses
                0, // critical hit ratio
                0 // priority
            );
            
            // Set damage callback properties
            // This would require more reflection and depends on your API structure
            // For a simple implementation, we can just return the Move object
            
            // Get the MoveView for this Move
            java.lang.reflect.Method getViewMethod = moveClass.getMethod("getView");
            MoveView moveView = (MoveView) getViewMethod.invoke(move);
            
            return moveView;
        } catch (Exception e) {
            System.out.println("Error creating self-damage move: " + e.getMessage());
            e.printStackTrace();
            
            // Fallback: If we can't create a proper move, return null
            // The calling code should handle this case
            return null;
        }
    }


    /**
     * Process move resolution for both moves
     */
    private void processMoveResolution(List<GameNode> children, PokemonView ourPokemon, PokemonView opponentPokemon,
                                    MoveView ourEffectiveMove, MoveView opponentEffectiveMove, 
                                    double probability, boolean weGoFirst) {
        // Determine order of execution
        MoveView firstMove = weGoFirst ? ourEffectiveMove : opponentEffectiveMove;
        MoveView secondMove = weGoFirst ? opponentEffectiveMove : ourEffectiveMove;
        int firstMoveTeam = weGoFirst ? myTeamIdx : (1 - myTeamIdx);
        
        // Start with current battle state
        BattleView currentState = this.battleView;
        
        // Apply first move if it exists
        if (firstMove != null) {
            // Use getPotentialEffects to get all possible outcomes
            List<Pair<Double, BattleView>> firstMoveOutcomes = firstMove.getPotentialEffects(battleView, myTeamIdx, 1 - myTeamIdx);
            
            for (Pair<Double, BattleView> firstOutcome : firstMoveOutcomes) {
                double firstProb = firstOutcome.getFirst();
                BattleView afterFirstMove = firstOutcome.getSecond();
                
                // Check if battle is over after first move
                if (afterFirstMove.isOver()) {
                    // Create terminal node
                    GameNode terminalNode = new GameNode(
                        afterFirstMove,
                        NodeType.MAX, // Next player doesn't matter for terminal nodes
                        depth + 1,
                        firstMove,
                        probability * firstProb,
                        myTeamIdx
                    );
                    children.add(terminalNode);
                    continue;
                }
                
                // Apply second move if it exists
                if (secondMove != null) {
                    // Use getPotentialEffects to get all possible outcomes
                    List<Pair<Double, BattleView>> secondMoveOutcomes = secondMove.getPotentialEffects(afterFirstMove, myTeamIdx, 1 - myTeamIdx);
                    
                    for (Pair<Double, BattleView> secondOutcome : secondMoveOutcomes) {
                        double secondProb = secondOutcome.getFirst();
                        BattleView afterSecondMove = secondOutcome.getSecond();
                        
                        // LAYER 4: Post-turn effects (applies after both moves)
                        processPostTurnEffects(children, afterSecondMove, probability * firstProb * secondProb);
                    }
                } else {
                    // No second move (e.g., due to status effects)
                    // LAYER 4: Post-turn effects
                    processPostTurnEffects(children, afterFirstMove, probability * firstProb);
                }
            }
        } else if (secondMove != null) {
            // First move couldn't be executed, but second move can
            List<Pair<Double, BattleView>> secondMoveOutcomes = secondMove.getPotentialEffects(battleView, myTeamIdx, 1 - myTeamIdx);
            
            for (Pair<Double, BattleView> secondOutcome : secondMoveOutcomes) {
                double secondProb = secondOutcome.getFirst();
                BattleView afterSecondMove = secondOutcome.getSecond();
                
                // LAYER 4: Post-turn effects
                processPostTurnEffects(children, afterSecondMove, probability * secondProb);
            }
        } else {
            // Neither move could be executed
            // LAYER 4: Post-turn effects
            processPostTurnEffects(children, currentState, probability);
        }
    }


    /**
     * Process post-turn effects like poison damage, burn damage, etc.
     */
    private void processPostTurnEffects(List<GameNode> children, BattleView state, double probability) {
        // Apply all post-turn effects:
        // - Poison/Toxic/Burn/Seeding damage
        // - Resetting flags like FLINCHING
        // - Decrementing counters for FREEZE/SLEEP
        // - Handling fainted Pokémon
        
        // This would need detailed implementation based on the Pokémon battle rules
        // For simplicity, we'll create a placeholder that just creates new nodes
        
        // Check if any Pokémon have fainted and need to be replaced
        boolean ourPokemonFainted = state.getTeamView(myTeamIdx).getActivePokemonView().hasFainted();
        boolean opponentPokemonFainted = state.getTeamView(1 - myTeamIdx).getActivePokemonView().hasFainted();
        
        if (ourPokemonFainted || opponentPokemonFainted) {
            // Handle Pokémon replacement
            handleFaintedPokemon(children, state, probability, ourPokemonFainted, opponentPokemonFainted);
        } else {
            // No fainted Pokémon, create nodes for next turn
            createNextTurnNodes(children, state, probability);
        }
    }



    /**
     * Handle Pokémon replacement when one or both Pokémon have fainted
     */
    private void handleFaintedPokemon(List<GameNode> children, BattleView state, double probability,
                                boolean ourPokemonFainted, boolean opponentPokemonFainted) {
    // Simplified version - just create terminal nodes or next turn nodes
        if (ourPokemonFainted && opponentPokemonFainted) {
            // Both fainted - could be a draw
            GameNode terminalNode = new GameNode(
                state,
                NodeType.MAX,
                depth + 1,
                null,
                probability,
                myTeamIdx
            );
            children.add(terminalNode);
        } else if (ourPokemonFainted) {
            // Our Pokémon fainted - opponent advantage
            GameNode minNode = new GameNode(
                state,
                NodeType.MIN,
                depth + 1,
                null,
                probability,
                myTeamIdx
            );
            children.add(minNode);
        } else if (opponentPokemonFainted) {
            // Opponent's Pokémon fainted - our advantage
            GameNode maxNode = new GameNode(
                state,
                NodeType.MAX,
                depth + 1,
                null,
                probability,
                myTeamIdx
            );
            children.add(maxNode);
        }
    }

    /**
     * Handle opponent's Pokémon replacement
     */

    private void handleOpponentReplacement(List<GameNode> children, BattleView state, double probability) {
        // Simplified version - just create a MIN node
        GameNode minNode = new GameNode(
            state,
            NodeType.MIN,
            depth + 1,
            null,
            probability,
            myTeamIdx
        );
        children.add(minNode);
    }

    /**
     * Simulates replacing a fainted Pokémon with a new one
     * @param state The current battle state
     * @param teamIdx The team index (0 or 1)
     * @param replacementIdx The index of the replacement Pokémon
     * @return A new battle state with the replacement Pokémon as active
     */
    private BattleView simulateReplacement(BattleView state, int teamIdx, int replacementIdx) {
        // Instead of trying to modify the state, just return it as-is
        // The expectiminimax will still evaluate the possibility of different replacements
        // but won't attempt to actually create a new state with the replacement
        return state;
    }


    /**
     * Attempts to clone a BattleView using reflection
     */
    private BattleView cloneBattleView(BattleView original) {
        try {
            // Try to find a clone method
            java.lang.reflect.Method cloneMethod = null;
            
            try {
                cloneMethod = original.getClass().getMethod("clone");
            } catch (NoSuchMethodException e) {
                // No clone method, try to find a copy constructor
                try {
                    java.lang.reflect.Constructor<?> copyConstructor = 
                        original.getClass().getConstructor(original.getClass());
                        
                    return (BattleView) copyConstructor.newInstance(original);
                } catch (NoSuchMethodException e2) {
                    // No copy constructor either
                    
                    // Try to call a potential copy or duplicate method
                    Method[] methods = original.getClass().getMethods();
                    for (Method method : methods) {
                        String name = method.getName().toLowerCase();
                        if ((name.contains("copy") || name.contains("duplicate") || 
                            name.contains("clone")) && 
                            method.getParameterCount() == 0) {
                            return (BattleView) method.invoke(original);
                        }
                    }
                    
                    // If we get here, we couldn't find any way to clone
                    throw new RuntimeException("No way to clone BattleView found");
                }
            }
            
            // If we found a clone method, use it
            return (BattleView) cloneMethod.invoke(original);
        } catch (Exception e) {
            System.out.println("Error cloning BattleView: " + e.getMessage());
            
            // Last resort: Return the original
            // Note that this isn't ideal since modifications will affect the original
            return original;
        }
    }

    /**
     * Sets the active Pokémon index for a team
     */
    private void setActiveIndex(BattleView state, int teamIdx, int activeIdx) {
        try {
            // Try to find a method to set the active Pokémon
            TeamView team = state.getTeamView(teamIdx);
            
            // Try various method names that might exist
            Method setActiveMethod = null;
            
            try {
                setActiveMethod = team.getClass().getMethod("setActiveIndex", int.class);
            } catch (NoSuchMethodException e1) {
                try {
                    setActiveMethod = team.getClass().getMethod("setActiveIdx", int.class);
                } catch (NoSuchMethodException e2) {
                    try {
                        setActiveMethod = team.getClass().getMethod("setActivePokemon", int.class);
                    } catch (NoSuchMethodException e3) {
                        // No standard method found, try to find any method that looks right
                        Method[] methods = team.getClass().getMethods();
                        for (Method method : methods) {
                            String name = method.getName().toLowerCase();
                            if (name.contains("active") && name.contains("set") && 
                                method.getParameterCount() == 1 && 
                                method.getParameterTypes()[0] == int.class) {
                                setActiveMethod = method;
                                break;
                            }
                        }
                        
                        if (setActiveMethod == null) {
                            throw new RuntimeException("No method to set active Pokémon found");
                        }
                    }
                }
            }
            
            // Invoke the method to set the active Pokémon
            setActiveMethod.invoke(team, activeIdx);
            
        } catch (Exception e) {
            System.out.println("Error setting active Pokémon: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Create nodes for the next turn
     */
    private void createNextTurnNodes(List<GameNode> children, BattleView state, double probability) {
        // Determine whose turn is next based on our policy
        // For simplicity, we'll use MAX-MIN alternation
        
        // If current node is from our move, next node is MIN
        if (this.type == NodeType.MAX) {
            GameNode minNode = new GameNode(
                state,
                NodeType.MIN,
                depth + 1,
                null,
                probability,
                myTeamIdx
            );
            children.add(minNode);
        } 
        // If current node is from opponent's move, next node is MAX
        else {
            GameNode maxNode = new GameNode(
                state,
                NodeType.MAX,
                depth + 1,
                null,
                probability,
                myTeamIdx
            );
            children.add(maxNode);
        }
    }

    
}

