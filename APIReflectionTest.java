package src.pas.pokemon.agents;

import edu.bu.pas.pokemon.core.Pokemon.PokemonView;
import edu.bu.pas.pokemon.core.Move.MoveView;
import edu.bu.pas.pokemon.core.Battle.BattleView;

import java.lang.reflect.Method;

public class APIReflectionTest {
    public static void printAllMethods(Object obj) {
        Class<?> cls = obj.getClass();
        System.out.println("Methods for " + cls.getName() + ":");
        
        Method[] methods = cls.getMethods();
        for (Method method : methods) {
            System.out.println("  " + method.getName() + 
                " (returns " + method.getReturnType().getSimpleName() + ")");
        }
        System.out.println();
    }
    
    public static void testAPI(BattleView battleView, int teamIdx) {
        System.out.println("=== API Reflection Test ===");
        
        // Test BattleView
        System.out.println("Testing BattleView methods...");
        printAllMethods(battleView);
        
        // Test PokemonView
        System.out.println("Testing PokemonView methods...");
        PokemonView pokemon = battleView.getTeamView(teamIdx).getActivePokemonView();
        printAllMethods(pokemon);
        
        // Test MoveView if we can get one
        try {
            // Try different ways to get a move
            MoveView move = null;
            
            try {
                move = pokemon.getMove(0);
            } catch (Exception e1) {
                try {
                    move = pokemon.getMoveView(0);
                } catch (Exception e2) {
                    // Try other alternatives
                }
            }
            
            if (move != null) {
                System.out.println("Testing MoveView methods...");
                printAllMethods(move);
            }
        } catch (Exception e) {
            System.out.println("Couldn't get a MoveView to test");
        }
    }
}