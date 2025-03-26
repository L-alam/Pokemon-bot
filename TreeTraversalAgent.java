package src.pas.pokemon.agents;

import edu.bu.pas.pokemon.core.Agent;
import edu.bu.pas.pokemon.core.Battle;
import edu.bu.pas.pokemon.core.Battle.BattleView;
import edu.bu.pas.pokemon.core.Team;
import edu.bu.pas.pokemon.core.Team.TeamView;
import edu.bu.pas.pokemon.core.Move;
import edu.bu.pas.pokemon.core.Move.MoveView;
import edu.bu.pas.pokemon.core.Pokemon.PokemonView;
import edu.bu.pas.pokemon.core.enums.Type;
import edu.bu.pas.pokemon.utils.Pair;
import edu.bu.pas.pokemon.core.enums.Stat;
import edu.bu.pas.pokemon.core.enums.NonVolatileStatus;
import edu.bu.pas.pokemon.core.enums.Flag;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TreeTraversalAgent extends Agent {

    private class StochasticTreeSearcher
        extends Object
        implements Callable<Pair<MoveView, Long>>
    {
        // Fields - keep required fields
        private final BattleView rootView;
        private final int maxDepth;
        private final int myTeamIdx;

        // Optimization fields
        private final Map<String, Double> stateCache = new HashMap<>();
        private final Map<String, List<GameNode>> childrenCache = new HashMap<>();
        private long startTimeMs;
        private final long timeoutThresholdMs = 75000; // 75 seconds - reduced for safety
        private int adaptiveMaxDepth = 2;
        private final int MAX_BRANCHING = 2;
        private final Random random = new Random();
        private int nodesEvaluated = 0;
        private int cacheHits = 0;
        private int betaCutoffs = 0;
        private int alphaCutoffs = 0;

        // Constructor
        public StochasticTreeSearcher(BattleView rootView, int maxDepth, int myTeamIdx) {
            this.rootView = rootView;
            this.maxDepth = maxDepth;
            this.myTeamIdx = myTeamIdx;
            this.startTimeMs = System.currentTimeMillis();
        }

        // Getters
        public BattleView getRootView() { return this.rootView; }
        public int getMaxDepth() { return this.maxDepth; }
        public int getMyTeamIdx() { return this.myTeamIdx; }

        /**
         * Check if we're approaching the time limit
         */
        private boolean isTimeRunningOut() {
            long currentTime = System.currentTimeMillis();
            return (currentTime - startTimeMs) > timeoutThresholdMs;
        }

        /**
         * Aggressive checking for early termination
         */
        private boolean shouldTerminateEarly(double value, boolean isMax) {
            // Terminate early if we found a very good or very bad position
            if (isMax && value > 5000) return true;  // Winning for us
            if (!isMax && value < -5000) return true; // Winning for opponent
            return false;
        }

        /**
         * Print battle info for debugging
         */
        private void printBattleInfo(BattleView battleView) {
            TeamView myTeam = battleView.getTeamView(this.getMyTeamIdx());
            TeamView opponentTeam = battleView.getTeamView(1 - this.getMyTeamIdx());
            
            System.out.println("\n=== BATTLE INFO ===");
            
            // Our team
            PokemonView myActive = myTeam.getActivePokemonView();
            System.out.println("OUR ACTIVE: " + myActive.getName() + 
                              " (HP: " + myActive.getCurrentStat(Stat.HP) + "/" + myActive.getBaseStat(Stat.HP) + 
                              ", Types: " + myActive.getCurrentType1() + 
                              (myActive.getCurrentType2() != null ? "/" + myActive.getCurrentType2() : "") + 
                              ", Status: " + myActive.getNonVolatileStatus() + ")");
            
            // Opponent team
            PokemonView oppActive = opponentTeam.getActivePokemonView();
            System.out.println("OPP ACTIVE: " + oppActive.getName() + 
                              " (HP: " + oppActive.getCurrentStat(Stat.HP) + "/" + oppActive.getBaseStat(Stat.HP) + 
                              ", Types: " + oppActive.getCurrentType1() + 
                              (oppActive.getCurrentType2() != null ? "/" + oppActive.getCurrentType2() : "") + 
                              ", Status: " + oppActive.getNonVolatileStatus() + ")");
            
            // Count remaining Pokémon for both teams
            int myRemaining = 0;
            int oppRemaining = 0;
            
            for (int i = 0; i < myTeam.size(); i++) {
                if (!myTeam.getPokemonView(i).hasFainted()) {
                    myRemaining++;
                }
            }
            
            for (int i = 0; i < opponentTeam.size(); i++) {
                if (!opponentTeam.getPokemonView(i).hasFainted()) {
                    oppRemaining++;
                }
            }
            
            System.out.println("REMAINING: Us: " + myRemaining + ", Opponent: " + oppRemaining);
            System.out.println("===================\n");
        }

        /**
         * Main stochastic tree search implementation
         * - Now with more aggressive pruning and early stopping
         */
        public MoveView stochasticTreeSearch(BattleView rootView) {
            // Reset state
            stateCache.clear();
            childrenCache.clear();
            startTimeMs = System.currentTimeMillis();
            nodesEvaluated = 0;
            cacheHits = 0;
            alphaCutoffs = 0;
            betaCutoffs = 0;
            
            // Print useful battle information
            printBattleInfo(rootView);
            
            // Get our active Pokémon
            PokemonView activePokemon = rootView.getTeamView(this.getMyTeamIdx()).getActivePokemonView();
            List<MoveView> availableMoves = activePokemon.getAvailableMoves();
            
            if (availableMoves.isEmpty()) {
                System.out.println("No moves available - likely need to switch Pokémon");
                return null;
            }

            // Print available moves
            System.out.println("Available moves for " + activePokemon.getName() + ":");
            for (MoveView move : availableMoves) {
                String powerStr = move.getPower() != null ? move.getPower().toString() : "N/A"; 
                String accuracyStr = move.getAccuracy() != null ? move.getAccuracy().toString() : "N/A";
                System.out.println("  - " + move.getName() + 
                                  " (Type: " + move.getType() + 
                                  ", Power: " + powerStr + 
                                  ", Accuracy: " + accuracyStr + ")");
            }
            
            // Calculate direct move evaluation bonuses
            Map<MoveView, Double> moveBonuses = new HashMap<>();
            for (MoveView move : availableMoves) {
                double bonus = UtilityCalculator.evaluateMove(move, activePokemon, 
                            rootView.getTeamView(1 - this.getMyTeamIdx()).getActivePokemonView());
                moveBonuses.put(move, bonus);
            }
            
            // Use alpha-beta search with an initial depth, then increase if time permits
            MoveView bestMove = availableMoves.get(0);
            Map<MoveView, Double> moveValues = new HashMap<>();
            boolean timeOut = false;
            
            // Quick check for high-value moves - might save time by picking obvious good moves
            for (MoveView move : availableMoves) {
                double bonus = moveBonuses.getOrDefault(move, 0.0);
                // If we have a very strong move, use it immediately
                if (bonus > 150.0) {
                    System.out.println("Found a very strong move early: " + move.getName() + " (value: " + bonus + ")");
                    return move;
                }
            }
            
            // Perform a 2-stage search: first quick, then deeper if time permits
            for (int currentDepth = 2; currentDepth <= 3 && !timeOut; currentDepth++) {
                adaptiveMaxDepth = currentDepth;
                System.out.println("Searching with depth " + adaptiveMaxDepth + "...");
                
                // Reset for new iteration
                double highestValue = Double.NEGATIVE_INFINITY;
                MoveView localBestMove = null;
                
                // For each available move
                for (MoveView move : availableMoves) {
                    if (isTimeRunningOut()) {
                        System.out.println("Search depth " + adaptiveMaxDepth + " taking too long, using previous results");
                        timeOut = true;
                        break;
                    }
                    
                    // Create a node for this move
                    GameNode moveNode = new GameNode(rootView, GameNode.NodeType.CHANCE, 1, move, 1.0, this.getMyTeamIdx());
                    
                    // Calculate the expected value with alpha-beta pruning
                    double baseValue = expectiminimax(moveNode, adaptiveMaxDepth, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
                    double bonusValue = moveBonuses.getOrDefault(move, 0.0) * 0.2; // Scale bonus appropriately
                    double totalValue = baseValue + bonusValue;
                    
                    // Store value for this move
                    moveValues.put(move, totalValue);
                    
                    // Update best move if this one is better
                    if (totalValue > highestValue) {
                        highestValue = totalValue;
                        localBestMove = move;
                    }
                    
                    System.out.println("  Move: " + move.getName() + ", Value: " + totalValue);
                }
                
                // Update best move if we found a better one
                if (localBestMove != null) {
                    bestMove = localBestMove;
                }
                
                System.out.println("Depth " + adaptiveMaxDepth + " complete. Current best move: " + bestMove.getName());
            }

            // Print the evaluation results
            System.out.println("\nFinal move evaluations:");
            for (MoveView move : availableMoves) {
                System.out.println("  Move: " + move.getName() + 
                                ", Value: " + moveValues.getOrDefault(move, 0.0));
            }
            System.out.println("Selected move: " + bestMove.getName());
            System.out.println("Stats: Nodes evaluated: " + nodesEvaluated + 
                              ", Cache hits: " + cacheHits + 
                              ", Alpha cutoffs: " + alphaCutoffs + 
                              ", Beta cutoffs: " + betaCutoffs);
            
            return bestMove;
        }

        /**
         * Improved Expectiminimax with alpha-beta pruning
         * - Added alpha-beta bounds for MAX/MIN nodes
         * - Early cutoffs for chance nodes
         * - More aggressive state caching
         */
        private double expectiminimax(GameNode node, int depth, double alpha, double beta) {
            nodesEvaluated++;
            
            // Check for timeout
            if (isTimeRunningOut()) {
                return evaluateNode(node);
            }
            
            // Check cache first
            String stateKey = generateStateKey(node, depth);
            if (stateCache.containsKey(stateKey)) {
                cacheHits++;
                return stateCache.get(stateKey);
            }
            
            // Base cases: terminal node or max depth
            if (node.isTerminal() || depth <= 0) {
                double value = evaluateNode(node);
                stateCache.put(stateKey, value);
                return value;
            }
            
            // Get children with caching
            List<GameNode> children;
            String childrenKey = generateStateKey(node, -1); // Depth-independent key for children
            
            if (childrenCache.containsKey(childrenKey)) {
                children = childrenCache.get(childrenKey);
            } else {
                children = node.getChildren();
                childrenCache.put(childrenKey, children);
            }
            
            // If no children, evaluate current node
            if (children.isEmpty()) {
                double value = evaluateNode(node);
                stateCache.put(stateKey, value);
                return value;
            }
            
            double result;
            
            // Process based on node type
            switch (node.getType()) {
                case MAX:
                    // MAX node (our turn) - choose maximum value
                    result = maxValue(children, depth, alpha, beta);
                    break;
                    
                case MIN:
                    // MIN node (opponent's turn) - choose minimum value
                    result = minValue(children, depth, alpha, beta);
                    break;
                    
                case CHANCE:
                    // CHANCE node - calculate expected value
                    result = expectedValue(children, depth, alpha, beta);
                    break;
                    
                default:
                    throw new IllegalStateException("Unknown node type");
            }
            
            // Cache the result
            stateCache.put(stateKey, result);
            return result;
        }
        
        /**
         * Generate a more compact state key
         */
        private String generateStateKey(GameNode node, int depth) {
            BattleView state = node.getBattleView();
            StringBuilder key = new StringBuilder();
            
            // Add node type and our team index
            key.append(node.getType().ordinal())
               .append("|")
               .append(depth)
               .append("|");
            
            // Add last move if available
            if (node.getLastMove() != null) {
                key.append(node.getLastMove().getName().hashCode());
            } else {
                key.append("0");
            }
            
            key.append("|");
            
            // Add simplified battle state representation
            for (int teamIdx = 0; teamIdx < 2; teamIdx++) {
                TeamView team = state.getTeamView(teamIdx);
                
                // Active Pokémon info - just the essentials
                PokemonView activePokemon = team.getActivePokemonView();
                key.append(activePokemon.getName().hashCode())
                   .append(",")
                   .append(activePokemon.getCurrentStat(Stat.HP))
                   .append(",")
                   .append(activePokemon.getNonVolatileStatus().ordinal());
                
                // Count remaining Pokémon
                int remainingCount = 0;
                for (int i = 0; i < team.size(); i++) {
                    if (!team.getPokemonView(i).hasFainted()) {
                        remainingCount++;
                    }
                }
                key.append(",").append(remainingCount);
            }
            
            return key.toString();
        }
        
        /**
         * Handle MAX node with alpha-beta pruning
         */
        private double maxValue(List<GameNode> children, int depth, double alpha, double beta) {
            double bestValue = Double.NEGATIVE_INFINITY;
            
            for (GameNode child : children) {
                double value = expectiminimax(child, depth - 1, alpha, beta);
                bestValue = Math.max(bestValue, value);
                
                // Update alpha
                alpha = Math.max(alpha, bestValue);
                
                // Alpha-beta pruning
                if (bestValue >= beta) {
                    betaCutoffs++;
                    break;
                }
                
                // Early termination if we found a very good position
                if (shouldTerminateEarly(bestValue, true)) {
                    break;
                }
            }
            
            return bestValue;
        }
        
        /**
         * Handle MIN node with alpha-beta pruning
         */
        private double minValue(List<GameNode> children, int depth, double alpha, double beta) {
            double bestValue = Double.POSITIVE_INFINITY;
            
            for (GameNode child : children) {
                double value = expectiminimax(child, depth - 1, alpha, beta);
                bestValue = Math.min(bestValue, value);
                
                // Update beta
                beta = Math.min(beta, bestValue);
                
                // Alpha-beta pruning
                if (bestValue <= alpha) {
                    alphaCutoffs++;
                    break;
                }
                
                // Early termination if we found a very bad position
                if (shouldTerminateEarly(bestValue, false)) {
                    break;
                }
            }
            
            return bestValue;
        }
        
        /**
         * Handle CHANCE node with pruning for unlikely outcomes
         */
        private double expectedValue(List<GameNode> children, int depth, double alpha, double beta) {
            double expectedValue = 0.0;
            double totalProbability = 0.0;
            
            // Sort children by probability (highest first)
            children.sort((a, b) -> Double.compare(b.getProbability(), a.getProbability()));
            
            // Take only the most likely outcomes
            double probabilityCovered = 0.0;
            int childrenConsidered = 0;
            
            for (GameNode child : children) {
                double probability = child.getProbability();
                
                // Skip very unlikely outcomes for efficiency
                if (probabilityCovered > 0.9 && probability < 0.1) {
                    continue;
                }
                
                // Limit the total number of children considered
                if (childrenConsidered >= 3) {
                    break;
                }
                
                double value = expectiminimax(child, depth - 1, alpha, beta);
                expectedValue += probability * value;
                totalProbability += probability;
                probabilityCovered += probability;
                childrenConsidered++;
            }
            
            // Normalize if probabilities don't sum to 1
            if (totalProbability > 0 && Math.abs(totalProbability - 1.0) > 0.001) {
                expectedValue /= totalProbability;
            }
            
            return expectedValue;
        }
        
        /**
         * Evaluate a node using the utility calculator
         */
        private double evaluateNode(GameNode node) {
            // For terminal nodes, use the game outcome
            if (node.isTerminal()) {
                BattleView battleView = node.getBattleView();
                if (isWinner(battleView, this.getMyTeamIdx())) {
                    return 10000.0; // We won
                } else if (isWinner(battleView, 1 - this.getMyTeamIdx())) {
                    return -10000.0; // We lost
                } else {
                    return 0.0; // Draw or ongoing game
                }
            }
            
            // For non-terminal nodes, use the utility heuristic
            return UtilityCalculator.calculateUtility(node.getBattleView(), this.getMyTeamIdx());
        }
        
        /**
         * Determine if a team has won the battle
         */
        private boolean isWinner(BattleView battleView, int teamIdx) {
            // A team wins if all Pokémon in the opponent's team have fainted
            TeamView opponentTeam = battleView.getTeamView(1 - teamIdx);
            
            for (int i = 0; i < opponentTeam.size(); i++) {
                if (!opponentTeam.getPokemonView(i).hasFainted()) {
                    return false; // At least one opponent Pokémon is still active
                }
            }
            
            return true; // All opponent Pokémon have fainted
        }
        
        @Override
        public Pair<MoveView, Long> call() throws Exception {
            double startTime = System.nanoTime();
            MoveView move = this.stochasticTreeSearch(this.getRootView());
            double endTime = System.nanoTime();
            return new Pair<MoveView, Long>(move, (long)((endTime-startTime)/1000000));
        }
    }
    
    private final int maxDepth;
    private long maxThinkingTimePerMoveInMS;
    private final Map<String, Type> typeCache = new HashMap<>();

    public TreeTraversalAgent() {
        super();
        this.maxThinkingTimePerMoveInMS = 180000 * 2; // 6 min/move
        this.maxDepth = 1000; // Keep this value as given
        initializeTypeCache();
    }
    
    /**
     * Initialize type information for faster lookups
     */
    private void initializeTypeCache() {
        // Pokemon types for quick lookup
        typeCache.put("Bulbasaur", Type.GRASS);
        typeCache.put("Geodude", Type.ROCK);
        typeCache.put("Onix", Type.ROCK);
        typeCache.put("Kadabra", Type.PSYCHIC);
        typeCache.put("Mr. Mime", Type.PSYCHIC);
        typeCache.put("Venemoth", Type.BUG);
        typeCache.put("Alakazam", Type.PSYCHIC);
        typeCache.put("Snorlax", Type.NORMAL);
        typeCache.put("Charizard", Type.FIRE);
        typeCache.put("Parasect", Type.BUG);
        typeCache.put("Beedrill", Type.BUG);
        typeCache.put("Gyarados", Type.WATER);
        typeCache.put("Dragonair", Type.DRAGON);
        typeCache.put("Aerodactyl", Type.ROCK);
        typeCache.put("Dragonite", Type.DRAGON);
        typeCache.put("Venusaur", Type.GRASS);
        typeCache.put("Lapras", Type.WATER);
        typeCache.put("Machamp", Type.FIGHTING);
    }

    public int getMaxDepth() { return this.maxDepth; }
    public long getMaxThinkingTimePerMoveInMS() { return this.maxThinkingTimePerMoveInMS; }

    @Override
    public Integer chooseNextPokemon(BattleView view) {
        // If only one Pokémon is available, choose it
        List<Integer> availablePokemon = new ArrayList<>();
        for (int idx = 0; idx < this.getMyTeamView(view).size(); ++idx) {
            if (!this.getMyTeamView(view).getPokemonView(idx).hasFainted()) {
                availablePokemon.add(idx);
            }
        }
        
        if (availablePokemon.size() == 1) {
            return availablePokemon.get(0);
        }
        
        // Get opponent's active Pokémon for type matching
        PokemonView opponentPokemon = view.getTeamView(1 - this.getMyTeamIdx()).getActivePokemonView();
        System.out.println("Choosing Pokémon against opponent: " + opponentPokemon.getName());
        
        // Evaluate each available Pokémon with optimized selection
        int bestPokemonIdx = -1;
        double bestValue = Double.NEGATIVE_INFINITY;
        
        for (Integer idx : availablePokemon) {
            // Use enhanced evaluation function
            double value = evaluatePokemonChoice(view, idx);
            PokemonView pokemon = view.getTeamView(this.getMyTeamIdx()).getPokemonView(idx);
            
            System.out.println("Evaluating " + pokemon.getName() + ": " + value);
            
            if (value > bestValue) {
                bestValue = value;
                bestPokemonIdx = idx;
            }
            
            // Choose immediately if we have a very good matchup
            if (value > 10.0) {
                System.out.println("Found excellent matchup with " + pokemon.getName() + ", selecting immediately");
                return idx;
            }
        }
        
        if (bestPokemonIdx == -1 && !availablePokemon.isEmpty()) {
            bestPokemonIdx = availablePokemon.get(0);
        }
        
        PokemonView chosenPokemon = view.getTeamView(this.getMyTeamIdx()).getPokemonView(bestPokemonIdx);
        System.out.println("Chosen Pokémon: " + chosenPokemon.getName() + " with value: " + bestValue);
        
        return bestPokemonIdx;
    }

    /**
     * This method is responsible for getting a move selected via the minimax algorithm.
     * There is some setup for this to work, namely making sure the agent doesn't run out of time.
     * DO NOT MODIFY THIS METHOD as instructed by your professor.
     */
    @Override
    public MoveView getMove(BattleView battleView) {
        // will run the minimax algorithm in a background thread with a timeout
        ExecutorService backgroundThreadManager = Executors.newSingleThreadExecutor();
        // preallocate so we don't spend precious time doing it when we are recording duration
        MoveView move = null;
        long durationInMs = 0;
        // this obj will run in the background
        StochasticTreeSearcher searcherObject = new StochasticTreeSearcher(
            battleView,
            this.getMaxDepth(),
            this.getMyTeamIdx()
        );
        // submit the job
        Future<Pair<MoveView, Long> > future = backgroundThreadManager.submit(searcherObject);
        try {
            // set the timeout
            Pair<MoveView, Long> moveAndDuration = future.get(
                this.getMaxThinkingTimePerMoveInMS(),
                TimeUnit.MILLISECONDS
            );
            // if we get here the move was chosen quick enough! :)
            move = moveAndDuration.getFirst();
            durationInMs = moveAndDuration.getSecond();
        } catch(TimeoutException e) {
            // timeout = out of time...you lose!
            System.err.println("Timeout!");
            System.err.println("Team [" + (this.getMyTeamIdx()+1) + " loses!");
            System.exit(-1);
        } catch(InterruptedException e) {
            e.printStackTrace();
            System.exit(-1);
        } catch(ExecutionException e) {
            e.printStackTrace();
            System.exit(-1);
        }
        return move;
    }

    /**
     * Enhanced Pokémon selection evaluation with aggressive pruning
     */
    private double evaluatePokemonChoice(BattleView view, int pokemonIdx) {
        PokemonView pokemon = view.getTeamView(this.getMyTeamIdx()).getPokemonView(pokemonIdx);
        PokemonView opponentPokemon = view.getTeamView(1 - this.getMyTeamIdx()).getActivePokemonView();
        
        // HP ratio (higher is better)
        double hpRatio = (double) pokemon.getCurrentStat(Stat.HP) / pokemon.getBaseStat(Stat.HP);
        
        // Early disqualification for very low HP
        if (hpRatio < 0.2) {
            return -5.0; // Very low HP is a big disadvantage
        }
        
        // Status penalty
        double statusPenalty = 0.0;
        if (pokemon.getNonVolatileStatus() != NonVolatileStatus.NONE) {
            switch (pokemon.getNonVolatileStatus()) {
                case PARALYSIS:
                    statusPenalty = 0.3;
                    break;
                case POISON:
                    statusPenalty = 0.25;
                    break;
                case TOXIC:
                    statusPenalty = 0.4;
                    break;
                case BURN:
                    statusPenalty = 0.35;
                    break;
                case FREEZE:
                case SLEEP:
                    // Sleep and freeze are major disadvantages
                    return -10.0; // Immediate penalty to avoid selecting sleeping/frozen Pokemon
                default:
                    statusPenalty = 0.0;
            }
        }
        
        // Type advantage calculation (most important factor)
        double typeAdvantage = calculateTypeMatchup(pokemon, opponentPokemon);
        
        // Check if we have any moves that are effective
        double bestMoveValue = 0.0;
        List<MoveView> availableMoves = pokemon.getAvailableMoves();
        
        for (MoveView move : availableMoves) {
            double moveValue = 0.0;
            
            // Power-based evaluation
            if (move.getPower() != null && move.getPower() > 0) {
                moveValue += move.getPower() / 100.0;
                
                // STAB bonus
                if (move.getType() == pokemon.getCurrentType1() || 
                    (pokemon.getCurrentType2() != null && move.getType() == pokemon.getCurrentType2())) {
                    moveValue *= 1.5;
                }
                
                // Type effectiveness against opponent
                Type oppType1 = opponentPokemon.getCurrentType1();
                Type oppType2 = opponentPokemon.getCurrentType2();
                
                double effectiveness = getTypeEffectiveness(move.getType(), oppType1);
                if (oppType2 != null) {
                    effectiveness *= getTypeEffectiveness(move.getType(), oppType2);
                }
                
                moveValue *= effectiveness;
            } 
            else if (move.getCategory() != null && move.getCategory().toString().equals("STATUS")) {
                // Status moves evaluation
                if (move.getName().contains("Sharpen") || 
                    move.getName().contains("Growth") ||
                    move.getName().contains("Swords Dance")) {
                    moveValue += 0.6;
                }
            }
            
            bestMoveValue = Math.max(bestMoveValue, moveValue);
        }
        
        // Special matchup bonuses
        double specialBonus = getSpecialMatchupBonus(pokemon, opponentPokemon);
        
        // Immediately select Pokemon with a strong type advantage against known opponents
        if (opponentPokemon.getName().equals("Geodude") || opponentPokemon.getName().equals("Onix")) {
            if (pokemon.getCurrentType1() == Type.WATER || pokemon.getCurrentType2() == Type.WATER) {
                return 20.0; // Water is super effective against Rock/Ground
            }
            if (pokemon.getCurrentType1() == Type.GRASS || pokemon.getCurrentType2() == Type.GRASS) {
                return 15.0; // Grass is super effective against Rock/Ground
            }
        }
        
        if (opponentPokemon.getName().equals("Kadabra") || 
            opponentPokemon.getName().equals("Alakazam") || 
            opponentPokemon.getName().equals("Mr. Mime")) {
            if (pokemon.getCurrentType1() == Type.BUG || pokemon.getCurrentType2() == Type.BUG) {
                return 15.0; // Bug is super effective against Psychic
            }
        }
        
        if (opponentPokemon.getName().equals("Dragonair") || opponentPokemon.getName().equals("Dragonite")) {
            if (pokemon.getCurrentType1() == Type.ICE || pokemon.getCurrentType2() == Type.ICE) {
                return 20.0; // Ice is super effective against Dragon
            }
        }
        
        // Combine factors with appropriate weights
        return 3.0 * hpRatio + 
               4.0 * typeAdvantage + 
               3.0 * bestMoveValue + 
               2.0 * specialBonus - 
               statusPenalty;
    }
    
    /**
     * Calculate type matchup advantage for Pokémon selection
     */
    private double calculateTypeMatchup(PokemonView ourPokemon, PokemonView opponentPokemon) {
        Type ourType1 = ourPokemon.getCurrentType1();
        Type ourType2 = ourPokemon.getCurrentType2();
        Type oppType1 = opponentPokemon.getCurrentType1();
        Type oppType2 = opponentPokemon.getCurrentType2();
        
        // Calculate our offensive advantage against opponent
        double offensiveAdvantage = 0.0;
        
        // Type 1 vs opponent
        double type1OffensiveValue = calculateSingleTypeEffectiveness(ourType1, oppType1, oppType2);
        offensiveAdvantage += type1OffensiveValue;
        
        // Type 2 vs opponent (if we have a second type)
        if (ourType2 != null) {
            double type2OffensiveValue = calculateSingleTypeEffectiveness(ourType2, oppType1, oppType2);
            offensiveAdvantage += type2OffensiveValue;
        }
        
        // Calculate our defensive advantage against opponent
        double defensiveAdvantage = 0.0;
        
        // Opponent type 1 vs us
        double oppType1DefensiveValue = calculateSingleTypeEffectiveness(oppType1, ourType1, ourType2);
        defensiveAdvantage += (1.0 / Math.max(0.1, oppType1DefensiveValue)); // Invert for defensive advantage
        
        // Opponent type 2 vs us (if opponent has a second type)
        if (oppType2 != null) {
            double oppType2DefensiveValue = calculateSingleTypeEffectiveness(oppType2, ourType1, ourType2);
            defensiveAdvantage += (1.0 / Math.max(0.1, oppType2DefensiveValue)); // Invert for defensive advantage
        }
        
        // Combine offensive and defensive advantages
        return (offensiveAdvantage * 0.6) + (defensiveAdvantage * 0.4);
    }
    
    /**
     * Calculate effectiveness of one type against another type (or pair of types)
     */
    private double calculateSingleTypeEffectiveness(Type attackType, Type defenderType1, Type defenderType2) {
        if (attackType == null || defenderType1 == null) {
            return 1.0;
        }
        
        double effectiveness = getTypeEffectiveness(attackType, defenderType1);
        
        // If defender has a second type, multiply by effectiveness against it
        if (defenderType2 != null) {
            effectiveness *= getTypeEffectiveness(attackType, defenderType2);
        }
        
        return effectiveness;
    }
    
    /**
     * Get special matchup bonus for certain Pokémon combinations
     */
    private double getSpecialMatchupBonus(PokemonView ourPokemon, PokemonView opponentPokemon) {
        double bonus = 0.0;
        
        // Special bonuses for known opponent teams
        if (opponentPokemon.getName().equals("Geodude") || opponentPokemon.getName().equals("Onix")) {
            if (ourPokemon.getCurrentType1() == Type.GRASS || ourPokemon.getCurrentType2() == Type.GRASS) {
                bonus += 2.0; // Grass is super effective against Rock/Ground
            }
            if (ourPokemon.getCurrentType1() == Type.WATER || ourPokemon.getCurrentType2() == Type.WATER) {
                bonus += 2.0; // Water is super effective against Rock/Ground
            }
        }
        
        if (opponentPokemon.getName().equals("Kadabra") || 
            opponentPokemon.getName().equals("Alakazam") || 
            opponentPokemon.getName().equals("Mr. Mime")) {
            if (ourPokemon.getCurrentType1() == Type.BUG || ourPokemon.getCurrentType2() == Type.BUG) {
                bonus += 1.5; // Bug is super effective against Psychic
            }
            if (ourPokemon.getCurrentType1() == Type.GHOST || ourPokemon.getCurrentType2() == Type.GHOST) {
                bonus += 1.5; // Ghost is super effective against Psychic
            }
        }
        
        if (opponentPokemon.getName().equals("Dragonair") || opponentPokemon.getName().equals("Dragonite")) {
            if (ourPokemon.getCurrentType1() == Type.ICE || ourPokemon.getCurrentType2() == Type.ICE) {
                bonus += 2.5; // Ice is super effective against Dragon
            }
        }
        
        if (opponentPokemon.getName().equals("Gyarados")) {
            if (ourPokemon.getCurrentType1() == Type.ELECTRIC || ourPokemon.getCurrentType2() == Type.ELECTRIC) {
                bonus += 2.0; // Electric is super effective against Water/Flying
            }
        }
        
        // Other special cases
        if (ourPokemon.getName().equals("Snorlax")) {
            bonus += 0.8; // Snorlax has high HP and decent moves
        }
        
        return bonus;
    }
    
    /**
     * Get type effectiveness multiplier
     */
    private double getTypeEffectiveness(Type attackType, Type defenderType) {
        if (attackType == null || defenderType == null) {
            return 1.0;
        }
        
        // Normal effectiveness
        if (attackType == Type.NORMAL) {
            if (defenderType == Type.ROCK) return 0.5;
            if (defenderType == Type.GHOST) return 0.0;
        }
        
        // Fire effectiveness
        if (attackType == Type.FIRE) {
            if (defenderType == Type.GRASS || defenderType == Type.ICE || 
                defenderType == Type.BUG) return 2.0;
            if (defenderType == Type.FIRE || defenderType == Type.WATER || 
                defenderType == Type.ROCK || defenderType == Type.DRAGON) return 0.5;
        }
        
        // Water effectiveness
        if (attackType == Type.WATER) {
            if (defenderType == Type.FIRE || defenderType == Type.GROUND || defenderType == Type.ROCK) return 2.0;
            if (defenderType == Type.WATER || defenderType == Type.GRASS || defenderType == Type.DRAGON) return 0.5;
        }
        
        // Electric effectiveness
        if (attackType == Type.ELECTRIC) {
            if (defenderType == Type.WATER || defenderType == Type.FLYING) return 2.0;
            if (defenderType == Type.ELECTRIC || defenderType == Type.GRASS || defenderType == Type.DRAGON) return 0.5;
            if (defenderType == Type.GROUND) return 0.0;
        }
        
        // Grass effectiveness
        if (attackType == Type.GRASS) {
            if (defenderType == Type.WATER || defenderType == Type.GROUND || defenderType == Type.ROCK) return 2.0;
            if (defenderType == Type.FIRE || defenderType == Type.GRASS || defenderType == Type.POISON || 
                defenderType == Type.FLYING || defenderType == Type.BUG || defenderType == Type.DRAGON) return 0.5;
        }
        
        // Ice effectiveness
        if (attackType == Type.ICE) {
            if (defenderType == Type.GRASS || defenderType == Type.GROUND || 
                defenderType == Type.FLYING || defenderType == Type.DRAGON) return 2.0;
            if (defenderType == Type.FIRE || defenderType == Type.WATER || 
                defenderType == Type.ICE) return 0.5;
        }
        
        // Fighting effectiveness
        if (attackType == Type.FIGHTING) {
            if (defenderType == Type.NORMAL || defenderType == Type.ICE || 
                defenderType == Type.ROCK) return 2.0;
            if (defenderType == Type.POISON || defenderType == Type.FLYING || 
                defenderType == Type.PSYCHIC || defenderType == Type.BUG) return 0.5;
            if (defenderType == Type.GHOST) return 0.0;
        }
        
        // Default to neutral effectiveness for other combinations
        return 1.0;
    }
}