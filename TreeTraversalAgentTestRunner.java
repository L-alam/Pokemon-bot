package src.pas.pokemon.agents;

import edu.bu.pas.pokemon.core.Battle.BattleView;
import edu.bu.pas.pokemon.core.Move.MoveView;
import java.util.concurrent.*;

/**
 * A runner to test the performance of the TreeTraversalAgent 
 * and identify where timeouts might be occurring.
 */
public class TreeTraversalAgentTestRunner {
    
    public static void main(String[] args) {
        System.out.println("Starting TreeTraversalAgent performance test...");
        
        // Create a mock battle view for testing
        BattleView mockBattleView = createMockBattleView();
        
        // Run tests with different depth limits
        runDepthTest(mockBattleView, 1);
        runDepthTest(mockBattleView, 2);
        runDepthTest(mockBattleView, 3);
        
        // Run with timeout to find potential timeout issues
        int timeoutInSeconds = 30;
        runWithTimeout(mockBattleView, timeoutInSeconds);
        
        System.out.println("Testing complete!");
    }
    
    /**
     * Run a test with a specific depth limit
     */
    private static void runDepthTest(BattleView battleView, int maxDepth) {
        System.out.println("\n=== Running test with depth limit: " + maxDepth + " ===");
        
        // Create a TreeTraversalAgent with the specified depth
        TreeTraversalAgent agent = new TreeTraversalAgent();
        
        // We're assuming agent has access to a method like setMaxDepth(int)
        // If not, you might need to create a new agent class with this parameter
        // setMaxDepth(agent, maxDepth);
        
        // Measure search time
        long startTime = System.nanoTime();
        
        // Run the search
        GameNode rootNode = new GameNode(battleView, GameNode.NodeType.MAX, 0, null, 1.0, 0);
        double value = agent.expectiminimax(rootNode, maxDepth);
        
        long endTime = System.nanoTime();
        double durationInMs = (endTime - startTime) / 1_000_000.0;
        
        System.out.println("Search completed in " + durationInMs + "ms");
        System.out.println("Result value: " + value);
        
        // Check if the duration is worrying
        if (durationInMs > 10000) {
            System.out.println("WARNING: Search at depth " + maxDepth + " took more than 10 seconds!");
            System.out.println("This could lead to timeouts at higher depths.");
        }
    }
    
    /**
     * Run the agent with a timeout to identify timeout issues
     */
    private static void runWithTimeout(BattleView battleView, int timeoutInSeconds) {
        System.out.println("\n=== Running full search with " + timeoutInSeconds + " second timeout ===");
        
        // Create the agent
        TreeTraversalAgent agent = new TreeTraversalAgent();
        
        // Create an executor for running with timeout
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<MoveView> future = executor.submit(() -> {
            return agent.stochasticTreeSearch(battleView);
        });
        
        try {
            // Wait for completion or timeout
            MoveView result = future.get(timeoutInSeconds, TimeUnit.SECONDS);
            System.out.println("Search completed successfully within timeout!");
            System.out.println("Selected move: " + (result != null ? result.getName() : "null"));
        } catch (TimeoutException e) {
            System.out.println("TIMEOUT DETECTED! Search did not complete within " + timeoutInSeconds + " seconds.");
            System.out.println("This matches the behavior seen in the autograder.");
            future.cancel(true);
        } catch (Exception e) {
            System.out.println("Error during search: " + e.getMessage());
            e.printStackTrace();
        }
        
        executor.shutdownNow();
    }
    
    /**
     * Create a mock BattleView for testing
     */
    private static BattleView createMockBattleView() {
        // In a real implementation, you would create a proper mock
        // or use a real BattleView instance
        // For now, this is a placeholder
        return null;
    }
    
    /**
     * Set the maximum depth for a TreeTraversalAgent
     * This assumes the agent has a setMaxDepth method or similar
     */
    private static void setMaxDepth(TreeTraversalAgent agent, int maxDepth) {
        // This is a placeholder - you would need to implement this
        // based on how your agent is structured
        try {
            java.lang.reflect.Method setMaxDepthMethod = 
                agent.getClass().getMethod("setMaxDepth", int.class);
            setMaxDepthMethod.invoke(agent, maxDepth);
        } catch (Exception e) {
            System.out.println("Unable to set max depth: " + e.getMessage());
        }
    }
}