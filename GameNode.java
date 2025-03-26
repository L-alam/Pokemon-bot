package src.pas.pokemon.agents;

import edu.bu.pas.pokemon.core.Battle.BattleView;
import edu.bu.pas.pokemon.core.Move.MoveView;
import edu.bu.pas.pokemon.core.Team.TeamView;
import edu.bu.pas.pokemon.core.Pokemon.PokemonView;
import edu.bu.pas.pokemon.utils.Pair;
import edu.bu.pas.pokemon.core.enums.Stat;
import edu.bu.pas.pokemon.core.enums.NonVolatileStatus;
import edu.bu.pas.pokemon.core.enums.Flag;
import edu.bu.pas.pokemon.core.enums.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Simplified GameNode class for Pokémon battles
 * - Reduced field count
 * - Simplified logic for generating children
 * - Added early pruning and cutoffs
 */
public class GameNode {
    // Enum to represent the three types of nodes in our stochastic game tree
    public enum NodeType {
        MAX,       // Our turn to maximize utility
        MIN,       // Opponent's turn to minimize utility
        CHANCE     // Random events (move order, status effects, move outcomes, etc.)
    }

    // Essential fields only
    private BattleView battleView;     // Current game state
    private NodeType type;             // Type of this node
    private int depth;                 // Depth in the tree
    private MoveView lastMove;         // Move that led to this state (null for root)
    private double probability;        // Probability of reaching this node
    private double utilityValue;       // Evaluated utility of this node
    private int myTeamIdx;             // Index of our team (0 or 1)
    
    // Static cache to avoid recreating move lists
    private static final Map<String, List<MoveView>> MOVE_CACHE = new HashMap<>();
    private static final Random RANDOM = new Random();
    
    // Constructor
    public GameNode(BattleView battleView, NodeType type, int depth, MoveView lastMove, 
                   double probability, int myTeamIdx) {
        this.battleView = battleView;
        this.type = type;
        this.depth = depth;
        this.lastMove = lastMove;
        this.probability = probability;
        this.myTeamIdx = myTeamIdx;
        this.utilityValue = 0.0;
    }
    
    // Getters
    public BattleView getBattleView() { return battleView; }
    public NodeType getType() { return type; }
    public int getDepth() { return depth; }
    public MoveView getLastMove() { return lastMove; }
    public double getProbability() { return probability; }
    public double getUtilityValue() { return utilityValue; }
    public void setUtilityValue(double utilityValue) { this.utilityValue = utilityValue; }
    public int getMyTeamIdx() { return myTeamIdx; }

    /**
     * Determines if this node is a terminal state
     */
    public boolean isTerminal() {
        return battleView.isOver();
    }
    
    /**
     * Get cached available moves for a Pokémon
     */
    private List<MoveView> getCachedMoves(PokemonView pokemon) {
        String cacheKey = pokemon.getName() + "_" + pokemon.getCurrentStat(Stat.HP);
        
        // Check cache first
        if (MOVE_CACHE.containsKey(cacheKey)) {
            return MOVE_CACHE.get(cacheKey);
        }
        
        // Cache miss - get available moves
        List<MoveView> availableMoves = pokemon.getAvailableMoves();
        
        // Store in cache
        MOVE_CACHE.put(cacheKey, availableMoves);
        
        return availableMoves;
    }
    
    /**
     * Generate child nodes based on node type
     * - Simplified to reduce object creation
     * - Added early pruning
     */
    public List<GameNode> getChildren() {
        List<GameNode> children = new ArrayList<>();
        
        // Early return for terminal nodes
        if (isTerminal()) {
            return children;
        }
        
        // Generate children based on node type
        switch (type) {
            case MAX:
                generateMaxNodeChildren(children);
                break;
            case MIN:
                generateMinNodeChildren(children);
                break;
            case CHANCE:
                generateChanceNodeChildren(children);
                break;
        }
        
        return children;
    }
    
    /**
     * Generate children for MAX nodes (our turn)
     * - Now sorts moves by a quick heuristic evaluation
     * - Limits the number of children generated
     */
    private void generateMaxNodeChildren(List<GameNode> children) {
        // Get our active Pokémon
        PokemonView activePokemon = battleView.getTeamView(myTeamIdx).getActivePokemonView();
        PokemonView opponentPokemon = battleView.getTeamView(1 - myTeamIdx).getActivePokemonView();
        
        // Get available moves
        List<MoveView> availableMoves = getCachedMoves(activePokemon);
        
        // Early return if no moves available
        if (availableMoves.isEmpty()) {
            return;
        }
        
        // Maximum moves to consider for performance
        final int MAX_MOVES = Math.min(3, availableMoves.size());
        
        // Evaluate moves and sort by potential
        Map<MoveView, Double> moveScores = new HashMap<>();
        for (MoveView move : availableMoves) {
            double score = UtilityCalculator.evaluateMove(move, activePokemon, opponentPokemon);
            moveScores.put(move, score);
        }
        
        // Sort moves by score (descending)
        List<MoveView> sortedMoves = new ArrayList<>(availableMoves);
        sortedMoves.sort((m1, m2) -> Double.compare(moveScores.getOrDefault(m2, 0.0), 
                                                   moveScores.getOrDefault(m1, 0.0)));
        
        // Create CHANCE nodes for top moves only
        for (int i = 0; i < MAX_MOVES; i++) {
            if (i >= sortedMoves.size()) break;
            
            MoveView move = sortedMoves.get(i);
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
    
    /**
     * Generate children for MIN nodes (opponent's turn)
     * - Limited to top moves by heuristic evaluation
     */
    private void generateMinNodeChildren(List<GameNode> children) {
        // Get opponent's active Pokémon
        PokemonView opponentPokemon = battleView.getTeamView(1 - myTeamIdx).getActivePokemonView();
        PokemonView ourPokemon = battleView.getTeamView(myTeamIdx).getActivePokemonView();
        
        // Get opponent's available moves
        List<MoveView> availableMoves = getCachedMoves(opponentPokemon);
        
        // Early return if no moves available
        if (availableMoves.isEmpty()) {
            return;
        }
        
        // Maximum moves to consider for performance
        final int MAX_MOVES = Math.min(2, availableMoves.size());
        
        // Quick evaluation of opponent moves
        Map<MoveView, Double> moveScores = new HashMap<>();
        for (MoveView move : availableMoves) {
            double score = UtilityCalculator.evaluateMove(move, opponentPokemon, ourPokemon);
            moveScores.put(move, score);
        }
        
        // Sort moves by score (descending) - opponent wants to use their best moves
        List<MoveView> sortedMoves = new ArrayList<>(availableMoves);
        sortedMoves.sort((m1, m2) -> Double.compare(moveScores.getOrDefault(m2, 0.0), 
                                                   moveScores.getOrDefault(m1, 0.0)));
        
        // Create CHANCE nodes for top moves only
        for (int i = 0; i < MAX_MOVES; i++) {
            if (i >= sortedMoves.size()) break;
            
            MoveView move = sortedMoves.get(i);
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
    
    /**
     * Generate children for CHANCE nodes
     * - Handles move execution and outcomes
     * - Greatly simplified from original version
     */
    private void generateChanceNodeChildren(List<GameNode> children) {
        // Get active Pokémon for both teams
        PokemonView ourPokemon = battleView.getTeamView(myTeamIdx).getActivePokemonView();
        PokemonView opponentPokemon = battleView.getTeamView(1 - myTeamIdx).getActivePokemonView();
        
        // Determine our move and opponent's move based on context
        MoveView ourMove = null;
        MoveView opponentMove = null;
        
        // Our turn's CHANCE node
        if (type == NodeType.CHANCE && depth % 2 == 1) {
            ourMove = lastMove;
            
            // Get opponent's move (use first available for simplicity)
            List<MoveView> opponentMoves = getCachedMoves(opponentPokemon);
            if (!opponentMoves.isEmpty()) {
                // To avoid explosion, only consider opponent's best move
                double bestScore = -Double.MAX_VALUE;
                for (MoveView move : opponentMoves) {
                    double score = UtilityCalculator.evaluateMove(move, opponentPokemon, ourPokemon);
                    if (score > bestScore) {
                        bestScore = score;
                        opponentMove = move;
                    }
                }
            }
        } 
        // Opponent's turn CHANCE node
        else if (type == NodeType.CHANCE && depth % 2 == 0) {
            opponentMove = lastMove;
            
            // Get our move (use first available for simplicity)
            List<MoveView> ourMoves = getCachedMoves(ourPokemon);
            if (!ourMoves.isEmpty()) {
                // To avoid explosion, only consider our best move
                double bestScore = -Double.MAX_VALUE;
                for (MoveView move : ourMoves) {
                    double score = UtilityCalculator.evaluateMove(move, ourPokemon, opponentPokemon);
                    if (score > bestScore) {
                        bestScore = score;
                        ourMove = move;
                    }
                }
            }
        }
        
        // Skip if we don't have needed moves
        if (ourMove == null && opponentMove == null) {
            return;
        }
        
        // Determine who goes first based on speed and priority
        boolean weGoFirst = determineWhoGoesFirst(ourPokemon, opponentPokemon, ourMove, opponentMove);
        
        // First move
        MoveView firstMove = weGoFirst ? ourMove : opponentMove;
        int firstTeamIdx = weGoFirst ? myTeamIdx : (1 - myTeamIdx);
        
        // Apply first move
        if (firstMove != null) {
            // Get potential outcomes of the first move
            List<Pair<Double, BattleView>> firstMoveOutcomes = firstMove.getPotentialEffects(
                battleView, myTeamIdx, 1 - myTeamIdx);
            
            // For performance, limit number of outcomes considered
            int maxOutcomes = Math.min(firstMoveOutcomes.size(), 2);
            for (int i = 0; i < maxOutcomes; i++) {
                Pair<Double, BattleView> outcome = firstMoveOutcomes.get(i);
                double probability = outcome.getFirst();
                BattleView afterFirstMove = outcome.getSecond();
                
                // Check if battle is over after first move
                if (afterFirstMove.isOver()) {
                    // Create a terminal node
                    GameNode terminalNode = createNextNode(afterFirstMove, probability);
                    children.add(terminalNode);
                    continue;
                }
                
                // Second move
                MoveView secondMove = weGoFirst ? opponentMove : ourMove;
                int secondTeamIdx = weGoFirst ? (1 - myTeamIdx) : myTeamIdx;
                
                // Apply second move if it exists
                if (secondMove != null) {
                    List<Pair<Double, BattleView>> secondMoveOutcomes = secondMove.getPotentialEffects(
                        afterFirstMove, myTeamIdx, 1 - myTeamIdx);
                    
                    // For performance, limit number of outcomes considered
                    int maxSecondOutcomes = Math.min(secondMoveOutcomes.size(), 2);
                    for (int j = 0; j < maxSecondOutcomes; j++) {
                        Pair<Double, BattleView> secondOutcome = secondMoveOutcomes.get(j);
                        double secondProbability = secondOutcome.getFirst();
                        BattleView afterSecondMove = secondOutcome.getSecond();
                        
                        // Create next node after both moves
                        GameNode nextNode = createNextNode(afterSecondMove, probability * secondProbability);
                        children.add(nextNode);
                    }
                } else {
                    // No second move
                    GameNode nextNode = createNextNode(afterFirstMove, probability);
                    children.add(nextNode);
                }
            }
        }
    }
    
    /**
     * Create the next node with appropriate type based on game state
     */
    private GameNode createNextNode(BattleView state, double probability) {
        // Game over
        if (state.isOver()) {
            return new GameNode(
                state,
                NodeType.MAX, // Type doesn't matter for terminal nodes
                depth + 1,
                null,
                probability,
                myTeamIdx
            );
        }
        
        // Check if any Pokémon needs to be replaced
        boolean ourPokemonFainted = state.getTeamView(myTeamIdx).getActivePokemonView().hasFainted();
        boolean opponentPokemonFainted = state.getTeamView(1 - myTeamIdx).getActivePokemonView().hasFainted();
        
        if (ourPokemonFainted || opponentPokemonFainted) {
            // Determine next node type based on who needs to replace Pokémon
            NodeType nextType;
            if (ourPokemonFainted && opponentPokemonFainted) {
                // Both fainted - our choice first (MAX)
                nextType = NodeType.MAX;
            } else if (ourPokemonFainted) {
                // Our Pokémon fainted - our choice (MAX)
                nextType = NodeType.MAX;
            } else {
                // Opponent's Pokémon fainted - their choice (MIN)
                nextType = NodeType.MIN;
            }
            
            return new GameNode(
                state,
                nextType,
                depth + 1,
                null,
                probability,
                myTeamIdx
            );
        }
        
        // Normal turn progression - alternate MAX and MIN
        NodeType nextType = (type == NodeType.MAX || 
                           (type == NodeType.CHANCE && depth % 2 == 1)) ? 
                           NodeType.MIN : NodeType.MAX;
        
        return new GameNode(
            state,
            nextType,
            depth + 1,
            null,
            probability,
            myTeamIdx
        );
    }
    
    /**
     * Determine which Pokémon moves first based on move priority and speed
     */
    private boolean determineWhoGoesFirst(PokemonView ourPokemon, PokemonView opponentPokemon,
                                         MoveView ourMove, MoveView opponentMove) {
        // Handle null cases
        if (ourMove == null) return false;
        if (opponentMove == null) return true;
        
        // Compare move priorities
        int ourPriority = ourMove.getPriority();
        int opponentPriority = opponentMove.getPriority();
        
        if (ourPriority > opponentPriority) {
            return true;  // Our move has higher priority
        } else if (opponentPriority > ourPriority) {
            return false; // Opponent's move has higher priority
        }
        
        // Same priority, compare speed
        int ourSpeed = ourPokemon.getCurrentStat(Stat.SPD);
        int opponentSpeed = opponentPokemon.getCurrentStat(Stat.SPD);
        
        // Adjust for paralysis
        if (ourPokemon.getNonVolatileStatus() == NonVolatileStatus.PARALYSIS) {
            ourSpeed = (int)(ourSpeed * 0.75);
        }
        if (opponentPokemon.getNonVolatileStatus() == NonVolatileStatus.PARALYSIS) {
            opponentSpeed = (int)(opponentSpeed * 0.75);
        }
        
        if (ourSpeed > opponentSpeed) {
            return true;  // We're faster
        } else if (opponentSpeed > ourSpeed) {
            return false; // Opponent is faster
        }
        
        // Same speed, 50/50 chance
        return RANDOM.nextBoolean();
    }
}