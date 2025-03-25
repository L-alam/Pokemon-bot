package src.pas.pokemon.agents;

import edu.bu.pas.pokemon.core.Agent;
import edu.bu.pas.pokemon.core.Battle.BattleView;
import edu.bu.pas.pokemon.core.Team.TeamView;
import edu.bu.pas.pokemon.core.Move.MoveView;
import edu.bu.pas.pokemon.core.Pokemon.PokemonView;
import edu.bu.pas.pokemon.utils.Pair;
import edu.bu.pas.pokemon.core.enums.Stat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class OptimizedTreeTraversalAgent extends TreeTraversalAgent {

    // Override the maxDepth to limit the search depth
    private final int MAX_DEPTH = 4;  // Reduced from 1000
    
    @Override
    public int getMaxDepth() {
        return MAX_DEPTH;  // Use our custom depth limit
    }
    
    @Override
    public MoveView getMove(BattleView battleView) {
        // Create our optimized searcher instead of the default one
        OptimizedStochasticTreeSearcher searcherObject = new OptimizedStochasticTreeSearcher(
            battleView,
            this.getMaxDepth(),
            this.getMyTeamIdx()
        );
        
        // Run the search with timeout
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Pair<MoveView, Long>> future = executor.submit(searcherObject);
        
        MoveView move = null;
        long durationInMs = 0;
        
        try {
            // Set timeout
            Pair<MoveView, Long> moveAndDuration = future.get(
                this.getMaxThinkingTimePerMoveInMS(),
                TimeUnit.MILLISECONDS
            );
            
            move = moveAndDuration.getFirst();
            durationInMs = moveAndDuration.getSecond();
            
            System.out.println("Move selected in " + durationInMs + "ms: " + 
                              (move != null ? move.getName() : "null"));
        } catch (Exception e) {
            System.err.println("Error in search: " + e.getMessage());
            
            // Try to get any available move
            move = getFirstAvailableMove(battleView);
        }
        
        executor.shutdownNow();
        return move;
    }
    
    /**
     * Emergency fallback to prevent timeout
     */
    private MoveView getFirstAvailableMove(BattleView battleView) {
        PokemonView activePokemon = battleView.getTeamView(this.getMyTeamIdx()).getActivePokemonView();
        List<MoveView> availableMoves = activePokemon.getAvailableMoves();
        
        if (!availableMoves.isEmpty()) {
            return availableMoves.get(0);
        }
        
        return null;
    }

    private class OptimizedStochasticTreeSearcher extends Object
        implements Callable<Pair<MoveView, Long>> {

        private final BattleView rootView;
        private final int maxDepth;
        private final int myTeamIdx;
        
        // Cache for evaluated states to avoid redundant computation
        private Map<String, Double> stateCache;
        
        // Time tracking to prevent timeouts
        private long startTimeMs;
        private long timeoutThresholdMs;
        
        // Maximum number of children to consider at MAX and MIN nodes
        private final int MAX_BRANCHING = 4;
        
        // Maximum number of chance outcomes to consider
        private final int MAX_CHANCE_OUTCOMES = 5;

        public OptimizedStochasticTreeSearcher(BattleView rootView, int maxDepth, int myTeamIdx) {
            this.rootView = rootView;
            this.maxDepth = maxDepth; // This should be set to 3-5, not 1000
            this.myTeamIdx = myTeamIdx;
            this.stateCache = new HashMap<>();
            this.startTimeMs = System.currentTimeMillis();
            this.timeoutThresholdMs = 90000; // 90 seconds (half the allowed time)
        }

        public BattleView getRootView() { return this.rootView; }
        public int getMaxDepth() { return this.maxDepth; }
        public int getMyTeamIdx() { return this.myTeamIdx; }

        /**
         * Optimized stochastic tree search implementation
         */
        public MoveView stochasticTreeSearch(BattleView rootView) {
            // Reset cache and start time
            stateCache.clear();
            startTimeMs = System.currentTimeMillis();
            
            // Create the root node
            GameNode rootNode = new GameNode(rootView, GameNode.NodeType.MAX, 0, null, 1.0, this.getMyTeamIdx());
            
            // Get available moves for our active Pokémon
            PokemonView activePokemon = rootView.getTeamView(this.getMyTeamIdx()).getActivePokemonView();
            List<MoveView> availableMoves = activePokemon.getAvailableMoves();
            
            if (availableMoves.isEmpty()) {
                return null; // No moves available
            }
            
            MoveView bestMove = availableMoves.get(0);
            double bestValue = Double.NEGATIVE_INFINITY;
            
            // For each available move
            for (MoveView move : availableMoves) {
                // Check if search is taking too long
                if (isTimeRunningOut()) {
                    System.out.println("Search taking too long, returning best move found so far");
                    return bestMove;
                }
                
                // Create a node for this move
                GameNode moveNode = new GameNode(rootView, GameNode.NodeType.CHANCE, 1, move, 1.0, this.getMyTeamIdx());
                
                // Calculate the expected value of this move
                double value = expectiminimax(moveNode, this.getMaxDepth());
                
                // Update best move if this one is better
                if (value > bestValue) {
                    bestValue = value;
                    bestMove = move;
                }
            }
            
            return bestMove;
        }

        /**
         * Check if we're approaching the time limit
         */
        private boolean isTimeRunningOut() {
            long currentTime = System.currentTimeMillis();
            return (currentTime - startTimeMs) > timeoutThresholdMs;
        }

        /**
         * Optimized expectiminimax algorithm with caching and pruning
         */
        private double expectiminimax(GameNode node, int depth) {
            // Check if we're running out of time
            if (isTimeRunningOut()) {
                return evaluateNode(node);
            }
            
            // Check cache first
            String stateKey = generateStateKey(node);
            if (stateCache.containsKey(stateKey)) {
                return stateCache.get(stateKey);
            }
            
            // Base cases: terminal node or maximum depth reached
            if (node.isTerminal() || depth <= 0) {
                double value = evaluateNode(node);
                stateCache.put(stateKey, value);
                return value;
            }
            
            // Get children nodes with pruning
            List<GameNode> children = getLimitedChildren(node);
            
            // If no children, evaluate current node
            if (children.isEmpty()) {
                double value = evaluateNode(node);
                stateCache.put(stateKey, value);
                return value;
            }
            
            double result;
            
            // Different behavior based on node type
            switch (node.getType()) {
                case MAX:
                    // MAX node (our turn) - choose maximum value
                    result = maxValue(children, depth);
                    break;
                    
                case MIN:
                    // MIN node (opponent's turn) - choose minimum value
                    result = minValue(children, depth);
                    break;
                    
                case CHANCE:
                    // CHANCE node - calculate expected value
                    result = expectedValue(children, depth);
                    break;
                    
                default:
                    throw new IllegalStateException("Unknown node type");
            }
            
            // Cache the result
            stateCache.put(stateKey, result);
            return result;
        }
        
        /**
         * Get a limited set of children to prevent explosion of search space
         */
        private List<GameNode> getLimitedChildren(GameNode node) {
            List<GameNode> allChildren = node.getChildren();
            
            // If we already have few enough children, return all of them
            if (allChildren.size() <= getMaxBranchingForNodeType(node.getType())) {
                return allChildren;
            }
            
            // For different node types, use different strategies
            switch (node.getType()) {
                case MAX:
                case MIN:
                    return getLimitedMovesChildren(node, allChildren);
                    
                case CHANCE:
                    return getLimitedChanceChildren(allChildren);
                    
                default:
                    return allChildren;
            }
        }
        
        /**
         * Get the maximum branching factor for a node type
         */
        private int getMaxBranchingForNodeType(GameNode.NodeType type) {
            switch (type) {
                case MAX:
                case MIN:
                    return MAX_BRANCHING;
                case CHANCE:
                    return MAX_CHANCE_OUTCOMES;
                default:
                    return Integer.MAX_VALUE;
            }
        }
        
        /**
         * Get limited children for MAX or MIN nodes
         */
        private List<GameNode> getLimitedMovesChildren(GameNode node, List<GameNode> allChildren) {
            List<GameNode> limitedChildren = new ArrayList<>();
            
            // Evaluate all children
            for (GameNode child : allChildren) {
                // Quick heuristic evaluation
                double value = evaluateNode(child);
                child.setUtilityValue(value);
            }
            
            // Sort children by utility value
            if (node.getType() == GameNode.NodeType.MAX) {
                // Sort in descending order for MAX
                allChildren.sort((a, b) -> Double.compare(b.getUtilityValue(), a.getUtilityValue()));
            } else {
                // Sort in ascending order for MIN
                allChildren.sort((a, b) -> Double.compare(a.getUtilityValue(), b.getUtilityValue()));
            }
            
            // Take only top N children
            int limit = Math.min(MAX_BRANCHING, allChildren.size());
            for (int i = 0; i < limit; i++) {
                limitedChildren.add(allChildren.get(i));
            }
            
            return limitedChildren;
        }
        
        /**
         * Get limited children for CHANCE nodes
         */
        private List<GameNode> getLimitedChanceChildren(List<GameNode> allChildren) {
            List<GameNode> limitedChildren = new ArrayList<>();
            
            // Sort by probability (highest first)
            allChildren.sort((a, b) -> Double.compare(b.getProbability(), a.getProbability()));
            
            // Take only top N children
            int limit = Math.min(MAX_CHANCE_OUTCOMES, allChildren.size());
            double totalProbability = 0.0;
            
            for (int i = 0; i < limit; i++) {
                GameNode child = allChildren.get(i);
                limitedChildren.add(child);
                totalProbability += child.getProbability();
            }
            
            // Normalize probabilities to sum to 1.0
            if (totalProbability > 0) {
                for (GameNode child : limitedChildren) {
                    double normalizedProbability = child.getProbability() / totalProbability;
                    // We can't modify the probability directly, so create a new node
                    // This is a workaround - ideally we'd modify the existing node
                    GameNode normalizedNode = new GameNode(
                        child.getBattleView(),
                        child.getType(),
                        child.getDepth(),
                        child.getLastMove(),
                        normalizedProbability,
                        child.getMyTeamIdx()
                    );
                    // Replace the old node with the normalized one
                    int index = limitedChildren.indexOf(child);
                    limitedChildren.set(index, normalizedNode);
                }
            }
            
            return limitedChildren;
        }
        
        /**
         * Generate a unique key for a game state for caching
         */
        private String generateStateKey(GameNode node) {
            BattleView state = node.getBattleView();
            StringBuilder key = new StringBuilder();
            
            // Add node type and depth
            key.append(node.getType()).append("|")
               .append(node.getDepth()).append("|");
            
            // Add simplified battle state representation
            for (int teamIdx = 0; teamIdx < 2; teamIdx++) {
                TeamView team = state.getTeamView(teamIdx);
                
                // Active Pokémon info
                PokemonView activePokemon = team.getActivePokemonView();
                key.append(activePokemon.getName())
                   .append(",")
                   .append(activePokemon.getCurrentStat(Stat.HP))
                   .append(",")
                   .append(activePokemon.getNonVolatileStatus());
                
                // Count remaining Pokémon
                int remainingCount = 0;
                for (int i = 0; i < team.size(); i++) {
                    if (!team.getPokemonView(i).hasFainted()) {
                        remainingCount++;
                    }
                }
                key.append(",").append(remainingCount);
                
                key.append(";");
            }
            
            return key.toString();
        }
        
        /**
         * Handle MAX node in Expectiminimax
         */
        private double maxValue(List<GameNode> children, int depth) {
            double bestValue = Double.NEGATIVE_INFINITY;
            
            for (GameNode child : children) {
                double value = expectiminimax(child, depth - 1);
                bestValue = Math.max(bestValue, value);
            }
            
            return bestValue;
        }
        
        /**
         * Handle MIN node in Expectiminimax
         */
        private double minValue(List<GameNode> children, int depth) {
            double bestValue = Double.POSITIVE_INFINITY;
            
            for (GameNode child : children) {
                double value = expectiminimax(child, depth - 1);
                bestValue = Math.min(bestValue, value);
            }
            
            return bestValue;
        }
        
        /**
         * Handle CHANCE node in Expectiminimax
         */
        private double expectedValue(List<GameNode> children, int depth) {
            double expectedValue = 0.0;
            double totalProbability = 0.0;
            
            for (GameNode child : children) {
                double probability = child.getProbability();
                totalProbability += probability;
                double value = expectiminimax(child, depth - 1);
                expectedValue += probability * value;
            }
            
            // Normalize if probabilities don't sum to 1
            if (totalProbability > 0 && Math.abs(totalProbability - 1.0) > 0.001) {
                expectedValue /= totalProbability;
            }
            
            return expectedValue;
        }
        
        /**
         * Evaluate a leaf node or terminal node
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
            // Check if all Pokémon in the opponent's team have fainted
            TeamView opponentTeam = battleView.getTeamView(1 - teamIdx);
            
            for (int i = 0; i < opponentTeam.size(); i++) {
                if (!opponentTeam.getPokemonView(i).hasFainted()) {
                    return false; // At least one opponent Pokémon is still active
                }
            }
            
            return true; // All opponent Pokémon have fainted
        }

    }

}