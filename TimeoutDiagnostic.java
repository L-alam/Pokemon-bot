package src.pas.pokemon.agents;

import edu.bu.pas.pokemon.core.Battle.BattleView;
import edu.bu.pas.pokemon.core.Move.MoveView;
import edu.bu.pas.pokemon.core.Pokemon.PokemonView;
import edu.bu.pas.pokemon.utils.Pair;

import java.util.List;
import java.util.ArrayList;

/**
 * This class contains diagnostic tests to help identify performance issues
 * in the TreeTraversalAgent implementation.
 */
public class TimeoutDiagnostic {
    
    /**
     * Main method to run diagnostics
     */
    public static void main(String[] args) {
        // Run a series of tests with increasing complexity
        try {
            System.out.println("Starting diagnostics for TreeTraversalAgent...");
            
            // Test 1: Node generation
            testNodeGeneration();
            
            // Test 2: Tree depth limits
            testTreeDepthLimits();
            
            // Test 3: Expectiminimax algorithm
            testExpectiminimaxAlgorithm();
            
            System.out.println("All diagnostics completed successfully!");
        } catch (Exception e) {
            System.err.println("Diagnostic failed with exception: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Test 1: Identify if the node generation is efficient
     * This will test if the getChildren() method is creating too many nodes
     */
    private static void testNodeGeneration() {
        System.out.println("\n=== Testing Node Generation ===");
        
        // Create a simple game node (you'll need to mock a BattleView or use a real one)
        BattleView mockBattleView = createMockBattleView();
        
        GameNode rootNode = new GameNode(mockBattleView, GameNode.NodeType.MAX, 0, null, 1.0, 0);
        
        // Measure the time to generate children
        long startTime = System.currentTimeMillis();
        List<GameNode> children = rootNode.getChildren();
        long endTime = System.currentTimeMillis();
        
        System.out.println("Generated " + children.size() + " child nodes in " + (endTime - startTime) + "ms");
        
        // Check if too many nodes are being generated at the first level
        if (children.size() > 20) {
            System.out.println("WARNING: Very high branching factor at root! Consider pruning strategies.");
        }
        
        // Test child node generation time
        if (!children.isEmpty()) {
            GameNode firstChild = children.get(0);
            startTime = System.currentTimeMillis();
            List<GameNode> grandchildren = firstChild.getChildren();
            endTime = System.currentTimeMillis();
            
            System.out.println("Generated " + grandchildren.size() + " grandchild nodes in " + (endTime - startTime) + "ms");
            
            // Calculate approximate branching factor
            double branchingFactor = (children.size() > 0) ? 
                                     (double) grandchildren.size() / children.size() : 0;
            System.out.println("Approximate branching factor: " + branchingFactor);
            
            if (branchingFactor > 10) {
                System.out.println("WARNING: Very high branching factor! Tree will grow exponentially.");
            }
        }
    }
    
    /**
     * Test 2: Check if depth limits are working properly
     */
    private static void testTreeDepthLimits() {
        System.out.println("\n=== Testing Tree Depth Limits ===");
        
        // Create a TreeTraversalAgent with low depth limit
        TreeTraversalAgent agent = new TreeTraversalAgent();
        
        // Call the expectiminimax algorithm with different depth limits
        BattleView mockBattleView = createMockBattleView();
        GameNode rootNode = new GameNode(mockBattleView, GameNode.NodeType.MAX, 0, null, 1.0, 0);
        
        // Test with depth = 1
        long startTime = System.currentTimeMillis();
        double value1 = agent.expectiminimax(rootNode, 1);
        long duration1 = System.currentTimeMillis() - startTime;
        System.out.println("Expectiminimax with depth 1: " + value1 + " (took " + duration1 + "ms)");
        
        // Test with depth = 2
        startTime = System.currentTimeMillis();
        double value2 = agent.expectiminimax(rootNode, 2);
        long duration2 = System.currentTimeMillis() - startTime;
        System.out.println("Expectiminimax with depth 2: " + value2 + " (took " + duration2 + "ms)");
        
        // Test with depth = 3
        startTime = System.currentTimeMillis();
        double value3 = agent.expectiminimax(rootNode, 3);
        long duration3 = System.currentTimeMillis() - startTime;
        System.out.println("Expectiminimax with depth 3: " + value3 + " (took " + duration3 + "ms)");
        
        // Check growth rate
        if (duration3 > 0 && duration2 > 0) {
            double growthRate2to3 = (double) duration3 / duration2;
            System.out.println("Growth rate from depth 2 to 3: " + growthRate2to3 + "x");
            
            if (growthRate2to3 > 10) {
                System.out.println("WARNING: Exponential growth detected! Search will time out at higher depths.");
                System.out.println("Consider implementing more aggressive pruning or caching.");
            }
        }
    }
    
    /**
     * Test 3: Measure the performance of the expectiminimax algorithm
     */
    private static void testExpectiminimaxAlgorithm() {
        System.out.println("\n=== Testing Expectiminimax Algorithm ===");
        
        // Create a TreeTraversalAgent
        TreeTraversalAgent agent = new TreeTraversalAgent();
        
        // Test the stochasticTreeSearch method with timing
        BattleView mockBattleView = createMockBattleView();
        
        long startTime = System.currentTimeMillis();
        MoveView move = agent.stochasticTreeSearch(mockBattleView);
        long endTime = System.currentTimeMillis();
        
        System.out.println("Full stochasticTreeSearch completed in " + (endTime - startTime) + "ms");
        System.out.println("Selected move: " + (move != null ? move.getName() : "null"));
        
        // If it took more than 30 seconds, it's likely to time out in the real environment
        if (endTime - startTime > 30000) {
            System.out.println("WARNING: Search took more than 30 seconds! Will likely time out in autograder.");
            System.out.println("Recommendations:");
            System.out.println("1. Reduce maximum depth (currently: " + agent.getMaxDepth() + ")");
            System.out.println("2. Implement more aggressive pruning");
            System.out.println("3. Cache repeated states");
            System.out.println("4. Optimize node generation");
        }
    }
    
    /**
     * Create a mock BattleView for testing
     * This is a placeholder - you would need to implement a proper mock or use the real BattleView
     */
    private static BattleView createMockBattleView() {
        // This is just a placeholder - you need to implement a proper mock
        // or obtain a real BattleView instance for testing
        return null; // Replace with actual implementation
    }
    
    /**
     * Helper method to count nodes in a tree to a certain depth
     */
    private static int countNodes(GameNode node, int maxDepth) {
        if (node == null || node.isTerminal() || node.getDepth() >= maxDepth) {
            return 1;
        }
        
        int count = 1; // Count this node
        List<GameNode> children = node.getChildren();
        
        for (GameNode child : children) {
            count += countNodes(child, maxDepth);
        }
        
        return count;
    }
}