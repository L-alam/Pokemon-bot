package src.pas.pokemon.agents;


// SYSTEM IMPORTS....feel free to add your own imports here! You may need/want to import more from the .jar!
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
import src.pas.pokemon.agents.GameNode;
import src.pas.pokemon.agents.UtilityCalculator;
import edu.bu.pas.pokemon.core.enums.Stat;

import java.io.InputStream;
import java.io.OutputStream;
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


// JAVA PROJECT IMPORTS


public class TreeTraversalAgent
    extends Agent
{

	private class StochasticTreeSearcher
        extends Object
        implements Callable<Pair<MoveView, Long> >  // so this object can be run in a background thread
	{

        // TODO: feel free to add any fields here! If you do, you should probably modify the constructor
        // of this class and add some getters for them. If the fields you add aren't final you should add setters too!
		private final BattleView rootView;
        private final int maxDepth;
        private final int myTeamIdx;

        // If you change the parameters of the constructor, you will also have to change
        // the getMove(...) method of TreeTraversalAgent!
		public StochasticTreeSearcher(BattleView rootView, int maxDepth, int myTeamIdx)
        {
            this.rootView = rootView;
            this.maxDepth = maxDepth;
            this.myTeamIdx = myTeamIdx;
        }

        // Getter methods. Since the default fields are declared final, we don't need setters
        // but if you make any fields that aren't final you should give them setters!
		public BattleView getRootView() { return this.rootView; }
        public int getMaxDepth() { return this.maxDepth; }
        public int getMyTeamIdx() { return this.myTeamIdx; }

		/**
		 * TODO: implement me!
		 * This method should perform your tree-search from the root of the entire tree.
         * You are welcome to add any extra parameters that you want! If you do, you will also have to change
         * The call method in this class!
		 * @param node the node to perform the search on (i.e. the root of the entire tree)
		 * @return The MoveView that your agent should execute
		 */

        public MoveView stochasticTreeSearch(BattleView rootView) {
            // Create the root node
            GameNode rootNode = new GameNode(rootView, GameNode.NodeType.MAX, 0, null, 1.0, this.getMyTeamIdx());
            
            // Get all available moves for our active Pokémon
            PokemonView activePokemon = rootView.getTeamView(this.getMyTeamIdx()).getActivePokemonView();
            List<MoveView> availableMoves = activePokemon.getAvailableMoves();
            
            if (availableMoves.isEmpty()) {
                return null; // No moves available
            }
            
            MoveView bestMove = availableMoves.get(0);
            double bestValue = Double.NEGATIVE_INFINITY;
            
            // For each available move
            for (MoveView move : availableMoves) {
                // Create a node for this move
                GameNode moveNode = new GameNode(rootView, GameNode.NodeType.CHANCE, 1, move, 1.0, this.getMyTeamIdx());
                
                // Calculate the expected value of this move
                double value = expectiminimax(moveNode, this.getMaxDepth());
                
                System.out.println("Move: " + move.getName() + ", Value: " + value);
                
                // Update best move if this one is better
                if (value > bestValue) {
                    bestValue = value;
                    bestMove = move;
                }
            }
            
            return bestMove;
        }


        @Override
        public Pair<MoveView, Long> call() throws Exception
        {
            double startTime = System.nanoTime();

            MoveView move = this.stochasticTreeSearch(this.getRootView());
            double endTime = System.nanoTime();

            return new Pair<MoveView, Long>(move, (long)((endTime-startTime)/1000000));
        }
		
	}

	private final int maxDepth;
    private long maxThinkingTimePerMoveInMS;

	public TreeTraversalAgent()
    {
        super();
        this.maxThinkingTimePerMoveInMS = 180000 * 2; // 6 min/move
        this.maxDepth = 1000; // set this however you want
    }

    /**
     * Some constants
     */
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
        
        // Evaluate each available Pokémon
        int bestPokemonIdx = -1;
        double bestValue = Double.NEGATIVE_INFINITY;
        
        for (Integer idx : availablePokemon) {
            // Create a hypothetical battle view with this Pokémon active
            // BattleView simulatedView = simulateChoosing(view, idx);
            
            // For now, use a simpler heuristic based on matchups
            double value = evaluatePokemonChoice(view, idx);
            
            if (value > bestValue) {
                bestValue = value;
                bestPokemonIdx = idx;
            }
        }
        
        return bestPokemonIdx;
    }

    /**
     * This method is responsible for getting a move selected via the minimax algorithm.
     * There is some setup for this to work, namely making sure the agent doesn't run out of time.
     * Please do not modify.
     */
    @Override
    public MoveView getMove(BattleView battleView)
    {

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

        try
        {
            // set the timeout
            Pair<MoveView, Long> moveAndDuration = future.get(
                this.getMaxThinkingTimePerMoveInMS(),
                TimeUnit.MILLISECONDS
            );

            // if we get here the move was chosen quick enough! :)
            move = moveAndDuration.getFirst();
            durationInMs = moveAndDuration.getSecond();

            // convert the move into a text form (algebraic notation) and stream it somewhere
            // Streamer.getStreamer(this.getFilePath()).streamMove(move, Planner.getPlanner().getGame());
        } catch(TimeoutException e)
        {
            // timeout = out of time...you lose!
            System.err.println("Timeout!");
            System.err.println("Team [" + (this.getMyTeamIdx()+1) + " loses!");
            System.exit(-1);
        } catch(InterruptedException e)
        {
            e.printStackTrace();
            System.exit(-1);
        } catch(ExecutionException e)
        {
            e.printStackTrace();
            System.exit(-1);
        }

        return move;
    }



    private double expectiminimax(GameNode node, int depth) {
        // Base cases: terminal node or maximum depth reached
        if (node.isTerminal() || depth <= 0) {
            return evaluateNode(node);
        }
        
        // Get children nodes
        List<GameNode> children = node.getChildren();
        
        // If no children, evaluate current node
        if (children.isEmpty()) {
            return evaluateNode(node);
        }
        
        // Different behavior based on node type
        switch (node.getType()) {
            case MAX:
                // MAX node (our turn) - choose maximum value
                return maxValue(children, depth);
                
            case MIN:
                // MIN node (opponent's turn) - choose minimum value
                return minValue(children, depth);
                
            case CHANCE:
                // CHANCE node - calculate expected value
                return expectedValue(children, depth);
                
            default:
                throw new IllegalStateException("Unknown node type");
        }
    }

    
    /**
     * Handle MAX node in Expectiminimax
     */
    private double maxValue(List<GameNode> children, int depth) {
        double bestValue = Double.NEGATIVE_INFINITY;
        
        // Order children to improve pruning (best moves first)
        children = orderNodes(children, true);
        
        // Find maximum value among children
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
        
        // Order children to improve pruning (best moves first)
        children = orderNodes(children, false);
        
        // Find minimum value among children
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
        
        // Calculate weighted sum of child values
        for (GameNode child : children) {
            double probability = child.getProbability();
            double value = expectiminimax(child, depth - 1);
            expectedValue += probability * value;
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
            if (getWinner(battleView) == this.getMyTeamIdx()) {
                return 10000.0; // We won
            } else if (getWinner(battleView) == 1 - this.getMyTeamIdx()) {
                return -10000.0; // We lost
            } else {
                return 0.0; // Draw or ongoing game
            }
        }
        
        // For non-terminal nodes, use the utility heuristic
        return UtilityCalculator.calculateUtility(node.getBattleView(), this.getMyTeamIdx());
    }

    /**
     * Order nodes to improve pruning
     */
    private List<GameNode> orderNodes(List<GameNode> children, boolean isMaxNode) {
        // Create a copy of the list to sort
        List<GameNode> orderedChildren = new ArrayList<>(children);
        
        // Evaluate each child
        for (GameNode child : orderedChildren) {
            double value = evaluateNode(child);
            child.setUtilityValue(value);
        }
        
        // Sort based on node type
        if (isMaxNode) {
            // For MAX nodes, sort in descending order (highest first)
            orderedChildren.sort((a, b) -> Double.compare(b.getUtilityValue(), a.getUtilityValue()));
        } else {
            // For MIN nodes, sort in ascending order (lowest first)
            orderedChildren.sort((a, b) -> Double.compare(a.getUtilityValue(), b.getUtilityValue()));
        }
        
        return orderedChildren;
    }


    /**
     * Evaluate the choice of a Pokémon
     */
    private double evaluatePokemonChoice(BattleView view, int pokemonIdx) {
        PokemonView pokemon = view.getTeamView(this.getMyTeamIdx()).getPokemonView(pokemonIdx);
        PokemonView opponentPokemon = view.getTeamView(1 - this.getMyTeamIdx()).getActivePokemonView();
        
        // Calculate type advantage
        double typeAdvantage = calculateTypeAdvantage(pokemon, opponentPokemon);
        
        // Consider HP ratio
        double hpRatio = (double) pokemon.getCurrentStat(Stat.HP) / pokemon.getBaseStat(Stat.HP);
        
        // Consider move effectiveness
        double moveEffectiveness = 0.0;
        // for (int i = 0; i < pokemon.getAvailableMoves().length; i++) {
        //     MoveView move = pokemon.getAvailableMoves(i);
        //     if (move.getNumUses() > 0) {
        //         // Calculate move effectiveness against opponent
        //         double effectiveness = calculateMoveEffectiveness(move, opponentPokemon);
        //         moveEffectiveness = Math.max(moveEffectiveness, effectiveness);
        //     }
        // }
        
        // Combine factors (weights can be adjusted)
        return 2.0 * typeAdvantage + 1.5 * hpRatio + 3.0 * moveEffectiveness;
    }

    /**
     * Calculate type advantage between Pokémon
     */
    private double calculateTypeAdvantage(PokemonView myPokemon, PokemonView opponentPokemon) {
        // This should be similar to the method in UtilityCalculator
        // ...
        
        return 0.0; // Placeholder
    }

    /**
     * Calculate effectiveness of a move against a Pokémon
     */
    private double calculateMoveEffectiveness(MoveView move, PokemonView opponentPokemon) {
        // Calculate damage potential and other effects
        // ...
        
        return 0.0; // Placeholder
    }


        /**
     * Determines which team has won the battle.
     * @param battleView The current battle state
     * @return 0 if team 1 has won, 1 if team 2 has won, -1 if the battle is still ongoing
     */
     public int getWinner(BattleView battleView) {
        // Get both team views
        TeamView team1 = battleView.getTeam1View();
        TeamView team2 = battleView.getTeam2View();
        
        // Check if all Pokémon in team 1 have fainted
        boolean team1AllFainted = true;
        for (int i = 0; i < team1.size(); i++) {
            if (!team1.getPokemonView(i).hasFainted()) {
                team1AllFainted = false;
                break;
            }
        }
        
        // Check if all Pokémon in team 2 have fainted
        boolean team2AllFainted = true;
        for (int i = 0; i < team2.size(); i++) {
            if (!team2.getPokemonView(i).hasFainted()) {
                team2AllFainted = false;
                break;
            }
        }
        
        // Determine winner
        if (team1AllFainted && !team2AllFainted) {
            return 1; // Team 2 wins
        } else if (!team1AllFainted && team2AllFainted) {
            return 0; // Team 1 wins
        } else if (team1AllFainted && team2AllFainted) {
            // This is a draw, but according to battle rules, probably shouldn't happen
            // You might want to handle this case based on your game's rules
            return -1;
        } else {
            // Battle is still ongoing
            return -1;
        }
    }


}


