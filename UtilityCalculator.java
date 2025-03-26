package src.pas.pokemon.agents;

import edu.bu.pas.pokemon.core.Battle.BattleView;
import edu.bu.pas.pokemon.core.Team.TeamView;
import edu.bu.pas.pokemon.core.Pokemon.PokemonView;
import edu.bu.pas.pokemon.core.Move.MoveView;
import edu.bu.pas.pokemon.core.enums.Type;
import edu.bu.pas.pokemon.core.enums.NonVolatileStatus;
import edu.bu.pas.pokemon.core.enums.Height;
import edu.bu.pas.pokemon.core.enums.Flag;
import edu.bu.pas.pokemon.core.enums.Stat;
import edu.bu.pas.pokemon.core.enums.Type;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Enhanced Utility calculator for evaluating Pokémon battle states
 */
public class UtilityCalculator {
    
    // Cache for type effectiveness calculations to avoid repeated computations
    private static final Map<String, Double> typeEffectivenessCache = new HashMap<>();
    private static final Map<String, Double> moveEffectivenessCache = new HashMap<>();
    
    /**
     * Calculate the utility value of a battle state for the specified team
     * Higher values are better for the team
     */
    public static double calculateUtility(BattleView battleView, int myTeamIdx) {
        // Fast check for game over condition
        if (battleView.isOver()) {
            // Count remaining Pokémon for both teams
            int myRemaining = countRemainingPokemon(battleView, myTeamIdx);
            int oppRemaining = countRemainingPokemon(battleView, 1 - myTeamIdx);
            
            if (oppRemaining == 0 && myRemaining > 0) {
                return 10000.0; // We won
            } else if (myRemaining == 0 && oppRemaining > 0) {
                return -10000.0; // We lost
            } else {
                return 0.0; // Draw (unlikely)
            }
        }
        
        // Quick HP comparison (most important factor)
        double hpRatio = calculateHPRatio(battleView, myTeamIdx);
        
        // Quick remaining Pokemon check (second most important)
        int myRemaining = countRemainingPokemon(battleView, myTeamIdx);
        int oppRemaining = countRemainingPokemon(battleView, 1 - myTeamIdx);
        double pokemonCountAdvantage = (myRemaining - oppRemaining) / (double)(myRemaining + oppRemaining);
        
        // If we have a significant HP advantage, we can return early
        if (hpRatio > 0.5 && pokemonCountAdvantage > 0.3) {
            return 5.0 * hpRatio + 3.0 * pokemonCountAdvantage;
        }
        
        // If we're at a significant disadvantage, we can return early
        if (hpRatio < -0.5 && pokemonCountAdvantage < -0.3) {
            return 5.0 * hpRatio + 3.0 * pokemonCountAdvantage;
        }
        
        // Team composition advantage - simplified
        double typeAdvantage = calculateTypeAdvantage(
            battleView.getTeamView(myTeamIdx).getActivePokemonView(), 
            battleView.getTeamView(1 - myTeamIdx).getActivePokemonView()
        );
        
        // Status effects advantage
        double statusAdvantage = calculateStatusEffectsAdvantage(battleView, myTeamIdx);
        
        // Simplified stage multipliers calculation (focus on key stats)
        double statMultipliersAdvantage = calculateSimplifiedStatMultipliersAdvantage(battleView, myTeamIdx);
        
        // Combine components with different weights
        return 6.0 * hpRatio + 
               3.0 * pokemonCountAdvantage +
               2.0 * typeAdvantage + 
               2.0 * statusAdvantage + 
               1.5 * statMultipliersAdvantage;
    }
    
    /**
     * Count the number of non-fainted Pokémon in a team
     */
    private static int countRemainingPokemon(BattleView battleView, int teamIdx) {
        TeamView team = battleView.getTeamView(teamIdx);
        int count = 0;
        
        for (int i = 0; i < team.size(); i++) {
            if (!team.getPokemonView(i).hasFainted()) {
                count++;
            }
        }
        
        return count;
    }
    
    /**
     * Calculate HP ratio advantage
     * This considers both current active Pokémon and the entire team
     */
    private static double calculateHPRatio(BattleView battleView, int myTeamIdx) {
        TeamView myTeam = battleView.getTeamView(myTeamIdx);
        TeamView opponentTeam = battleView.getTeamView(1 - myTeamIdx);
        
        // Active Pokémon HP ratio (more important)
        PokemonView myActive = myTeam.getActivePokemonView();
        PokemonView oppActive = opponentTeam.getActivePokemonView();
        
        double myActiveHPRatio = getHPRatio(myActive);
        double opponentActiveHPRatio = getHPRatio(oppActive);
        double activeHPAdvantage = myActiveHPRatio - opponentActiveHPRatio;
        
        // For efficiency, we'll focus mainly on active Pokémon HP
        // Only calculate team HP if the match is close
        if (Math.abs(activeHPAdvantage) > 0.4) {
            return activeHPAdvantage;
        }
        
        // Team overall HP ratio
        double myTeamTotalHP = 0;
        double myTeamMaxHP = 0;
        double opponentTeamTotalHP = 0;
        double opponentTeamMaxHP = 0;
        
        for (int i = 0; i < myTeam.size(); i++) {
            PokemonView pokemon = myTeam.getPokemonView(i);
            if (!pokemon.hasFainted()) {
                myTeamTotalHP += pokemon.getCurrentStat(Stat.HP);
                myTeamMaxHP += pokemon.getBaseStat(Stat.HP);
            }
        }
        
        for (int i = 0; i < opponentTeam.size(); i++) {
            PokemonView pokemon = opponentTeam.getPokemonView(i);
            if (!pokemon.hasFainted()) {
                opponentTeamTotalHP += pokemon.getCurrentStat(Stat.HP);
                opponentTeamMaxHP += pokemon.getBaseStat(Stat.HP);
            }
        }
        
        double myTeamHPRatio = (myTeamMaxHP > 0) ? myTeamTotalHP / myTeamMaxHP : 0;
        double opponentTeamHPRatio = (opponentTeamMaxHP > 0) ? opponentTeamTotalHP / opponentTeamMaxHP : 0;
        double teamHPAdvantage = myTeamHPRatio - opponentTeamHPRatio;
        
        // Combine active and team HP advantages with higher weight on active Pokémon
        return (0.7 * activeHPAdvantage) + (0.3 * teamHPAdvantage);
    }
    
    /**
     * Get HP ratio for a single Pokémon (0.0 to 1.0)
     */
    private static double getHPRatio(PokemonView pokemon) {
        if (pokemon.hasFainted() || pokemon.getBaseStat(Stat.HP) == 0) {
            return 0.0;
        }
        return (double) pokemon.getCurrentStat(Stat.HP) / pokemon.getBaseStat(Stat.HP);
    }
    
    /**
     * Calculate type advantage between two Pokémon
     */
    private static double calculateTypeAdvantage(PokemonView myPokemon, PokemonView opponentPokemon) {
        // Get Pokémon types
        Type myType1 = myPokemon.getCurrentType1();
        Type myType2 = myPokemon.getCurrentType2();
        Type opponentType1 = opponentPokemon.getCurrentType1();
        Type opponentType2 = opponentPokemon.getCurrentType2();
        
        // Cache key for this matchup
        String cacheKey = (myType1 != null ? myType1.toString() : "null") + "|" + 
                         (myType2 != null ? myType2.toString() : "null") + "|" + 
                         (opponentType1 != null ? opponentType1.toString() : "null") + "|" + 
                         (opponentType2 != null ? opponentType2.toString() : "null");
        
        // Check cache first
        if (typeEffectivenessCache.containsKey(cacheKey)) {
            return typeEffectivenessCache.get(cacheKey);
        }
        
        // Calculate effectiveness of our attacks against opponent
        double ourOffensiveAdvantage = calculateTypeEffectiveness(myType1, opponentType1, opponentType2);
        if (myType2 != null) {
            ourOffensiveAdvantage = Math.max(ourOffensiveAdvantage, 
                                             calculateTypeEffectiveness(myType2, opponentType1, opponentType2));
        }
        
        // Calculate effectiveness of opponent's attacks against us
        double theirOffensiveAdvantage = calculateTypeEffectiveness(opponentType1, myType1, myType2);
        if (opponentType2 != null) {
            theirOffensiveAdvantage = Math.max(theirOffensiveAdvantage, 
                                               calculateTypeEffectiveness(opponentType2, myType1, myType2));
        }
        
        // Return net advantage (positive means we have advantage)
        double result = ourOffensiveAdvantage - theirOffensiveAdvantage;
        
        // Cache the result
        typeEffectivenessCache.put(cacheKey, result);
        
        return result;
    }
    
    /**
     * Calculate effectiveness multiplier of an attack type against a defender's types
     */
    private static double calculateTypeEffectiveness(Type attackType, Type defenderType1, Type defenderType2) {
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
     * Get type effectiveness multiplier
     */
    private static double getTypeEffectiveness(Type attackType, Type defenderType) {
        if (attackType == null || defenderType == null) {
            return 1.0;
        }
        
        // Create cache key
        String cacheKey = attackType.toString() + "|" + defenderType.toString();
        
        // Check cache first
        if (typeEffectivenessCache.containsKey(cacheKey)) {
            return typeEffectivenessCache.get(cacheKey);
        }
        
        double effectiveness = 1.0;
        
        // Normal effectiveness
        if (attackType == Type.NORMAL) {
            if (defenderType == Type.ROCK) effectiveness = 0.5;
            else if (defenderType == Type.GHOST) effectiveness = 0.0;
        }
        
        // Fire effectiveness
        else if (attackType == Type.FIRE) {
            if (defenderType == Type.GRASS || defenderType == Type.ICE || 
                defenderType == Type.BUG) effectiveness = 2.0;
            else if (defenderType == Type.FIRE || defenderType == Type.WATER || 
                defenderType == Type.ROCK || defenderType == Type.DRAGON) effectiveness = 0.5;
        }
        
        // Water effectiveness
        else if (attackType == Type.WATER) {
            if (defenderType == Type.FIRE || defenderType == Type.GROUND || defenderType == Type.ROCK) effectiveness = 2.0;
            else if (defenderType == Type.WATER || defenderType == Type.GRASS || defenderType == Type.DRAGON) effectiveness = 0.5;
        }
        
        // Electric effectiveness
        else if (attackType == Type.ELECTRIC) {
            if (defenderType == Type.WATER || defenderType == Type.FLYING) effectiveness = 2.0;
            else if (defenderType == Type.ELECTRIC || defenderType == Type.GRASS || defenderType == Type.DRAGON) effectiveness = 0.5;
            else if (defenderType == Type.GROUND) effectiveness = 0.0;
        }
        
        // Grass effectiveness
        else if (attackType == Type.GRASS) {
            if (defenderType == Type.WATER || defenderType == Type.GROUND || defenderType == Type.ROCK) effectiveness = 2.0;
            else if (defenderType == Type.FIRE || defenderType == Type.GRASS || defenderType == Type.POISON || 
                defenderType == Type.FLYING || defenderType == Type.BUG || defenderType == Type.DRAGON) effectiveness = 0.5;
        }
        
        // Ice effectiveness
        else if (attackType == Type.ICE) {
            if (defenderType == Type.GRASS || defenderType == Type.GROUND || 
                defenderType == Type.FLYING || defenderType == Type.DRAGON) effectiveness = 2.0;
            else if (defenderType == Type.FIRE || defenderType == Type.WATER || 
                defenderType == Type.ICE) effectiveness = 0.5;
        }
        
        // Fighting effectiveness
        else if (attackType == Type.FIGHTING) {
            if (defenderType == Type.NORMAL || defenderType == Type.ICE || 
                defenderType == Type.ROCK) effectiveness = 2.0;
            else if (defenderType == Type.POISON || defenderType == Type.FLYING || 
                defenderType == Type.PSYCHIC || defenderType == Type.BUG) effectiveness = 0.5;
            else if (defenderType == Type.GHOST) effectiveness = 0.0;
        }
        
        // Poison effectiveness
        else if (attackType == Type.POISON) {
            if (defenderType == Type.GRASS) effectiveness = 2.0;
            else if (defenderType == Type.POISON || defenderType == Type.GROUND || 
                defenderType == Type.ROCK || defenderType == Type.GHOST) effectiveness = 0.5;
        }
        
        // Ground effectiveness
        else if (attackType == Type.GROUND) {
            if (defenderType == Type.FIRE || defenderType == Type.ELECTRIC || 
                defenderType == Type.POISON || defenderType == Type.ROCK) effectiveness = 2.0;
            else if (defenderType == Type.GRASS || defenderType == Type.BUG) effectiveness = 0.5;
            else if (defenderType == Type.FLYING) effectiveness = 0.0;
        }
        
        // Flying effectiveness
        else if (attackType == Type.FLYING) {
            if (defenderType == Type.GRASS || defenderType == Type.FIGHTING || defenderType == Type.BUG) effectiveness = 2.0;
            else if (defenderType == Type.ELECTRIC || defenderType == Type.ROCK) effectiveness = 0.5;
        }
        
        // Psychic effectiveness
        else if (attackType == Type.PSYCHIC) {
            if (defenderType == Type.FIGHTING || defenderType == Type.POISON) effectiveness = 2.0;
            else if (defenderType == Type.PSYCHIC) effectiveness = 0.5;
        }
        
        // Bug effectiveness
        else if (attackType == Type.BUG) {
            if (defenderType == Type.GRASS || defenderType == Type.PSYCHIC) effectiveness = 2.0;
            else if (defenderType == Type.FIRE || defenderType == Type.FIGHTING || 
                defenderType == Type.POISON || defenderType == Type.FLYING || 
                defenderType == Type.GHOST) effectiveness = 0.5;
        }
        
        // Rock effectiveness
        else if (attackType == Type.ROCK) {
            if (defenderType == Type.FIRE || defenderType == Type.ICE || 
                defenderType == Type.FLYING || defenderType == Type.BUG) effectiveness = 2.0;
            else if (defenderType == Type.FIGHTING || defenderType == Type.GROUND) effectiveness = 0.5;
        }
        
        // Ghost effectiveness
        else if (attackType == Type.GHOST) {
            if (defenderType == Type.PSYCHIC || defenderType == Type.GHOST) effectiveness = 2.0;
            else if (defenderType == Type.NORMAL) effectiveness = 0.0;
        }
        
        // Dragon effectiveness
        else if (attackType == Type.DRAGON) {
            if (defenderType == Type.DRAGON) effectiveness = 2.0;
        }
        
        // Cache the result
        typeEffectivenessCache.put(cacheKey, effectiveness);
        
        return effectiveness;
    }
    
    /**
     * Calculate advantage from status effects
     */
    private static double calculateStatusEffectsAdvantage(BattleView battleView, int myTeamIdx) {
        PokemonView myPokemon = battleView.getTeamView(myTeamIdx).getActivePokemonView();
        PokemonView opponentPokemon = battleView.getTeamView(1 - myTeamIdx).getActivePokemonView();
        
        // Get status effects for both active Pokémon
        NonVolatileStatus myStatus = myPokemon.getNonVolatileStatus();
        NonVolatileStatus opponentStatus = opponentPokemon.getNonVolatileStatus();
        
        // Score different status effects
        double myStatusScore = getStatusEffectScore(myStatus);
        double opponentStatusScore = getStatusEffectScore(opponentStatus);
        
        // Add volatile status flags
        if (myPokemon.getFlag(Flag.CONFUSED)) myStatusScore += 0.25;
        if (opponentPokemon.getFlag(Flag.CONFUSED)) opponentStatusScore += 0.25;
        
        if (myPokemon.getFlag(Flag.SEEDED)) myStatusScore += 0.15;
        if (opponentPokemon.getFlag(Flag.SEEDED)) opponentStatusScore += 0.15;
        
        // Combine scores (negative for our Pokémon, positive for opponent)
        return opponentStatusScore - myStatusScore;
    }
    
    /**
     * Get score for non-volatile status effect (higher is worse)
     */
    private static double getStatusEffectScore(NonVolatileStatus status) {
        switch (status) {
            case NONE:
                return 0.0;
            case PARALYSIS:
                return 0.3; // Speed reduced, 25% chance to lose turn
            case POISON:
                return 0.25; // 1/8 max HP damage per turn
            case TOXIC:
                return 0.4; // Increasing damage per turn
            case BURN:
                return 0.35; // 1/8 max HP damage per turn, reduced attack
            case FREEZE:
                return 0.5; // Can't move until thawed
            case SLEEP:
                return 0.45; // Can't move until awakened
            default:
                return 0.0;
        }
    }
    
    /**
     * Calculate advantage from stat modifiers (simplified version)
     */
    private static double calculateSimplifiedStatMultipliersAdvantage(BattleView battleView, int myTeamIdx) {
        PokemonView myPokemon = battleView.getTeamView(myTeamIdx).getActivePokemonView();
        PokemonView opponentPokemon = battleView.getTeamView(1 - myTeamIdx).getActivePokemonView();
        
        double advantage = 0.0;
        
        // Just consider the most important stats: ATK, DEF, SPD
        advantage += 0.2 * (myPokemon.getCurrentStat(Stat.ATK) - opponentPokemon.getCurrentStat(Stat.ATK));
        advantage += 0.2 * (myPokemon.getCurrentStat(Stat.DEF) - opponentPokemon.getCurrentStat(Stat.DEF));
        advantage += 0.3 * (myPokemon.getCurrentStat(Stat.SPD) - opponentPokemon.getCurrentStat(Stat.SPD));
        
        return advantage;
    }
    
    /**
     * Evaluate a specific move against a target Pokémon
     * Used for direct move selection
     */
    public static double evaluateMove(MoveView move, PokemonView user, PokemonView target) {
        if (move == null) {
            return 0.0;
        }
        
        // Create cache key for this move evaluation
        String cacheKey = move.getName() + "|" + 
                         user.getName() + "|" + 
                         target.getName() + "|" + 
                         user.getCurrentStat(Stat.HP) + "|" + 
                         target.getCurrentStat(Stat.HP);
        
        // Check cache first
        if (moveEffectivenessCache.containsKey(cacheKey)) {
            return moveEffectivenessCache.get(cacheKey);
        }
        
        double value = 0.0;
        
        // Status moves evaluation (no direct damage)
        if (move.getCategory() != null && move.getCategory().toString().equals("STATUS")) {
            // Stat boosting moves
            if (move.getName().contains("Sharpen") || 
                move.getName().contains("Growth") ||
                move.getName().contains("Swords Dance")) {
                value += 60.0;
            }
            
            // Focus Energy is good for critical hits
            else if (move.getName().contains("Focus Energy")) {
                value += 40.0;
            }
            
            // Recovery moves
            else if (move.getName().contains("Recover") || 
                     move.getName().contains("Rest")) {
                // More valuable when HP is low
                double hpRatio = (double) user.getCurrentStat(Stat.HP) / user.getBaseStat(Stat.HP);
                value += 80.0 * (1.0 - hpRatio);
            }
            
            // Status-inducing moves
            else if (move.getName().contains("Sleep") || 
                     move.getName().contains("Paralyze") ||
                     move.getName().contains("Poison")) {
                value += 50.0;
            }
            
            // Cache and return for status moves
            moveEffectivenessCache.put(cacheKey, value);
            return value;
        }
        
        // Base power calculation for damage moves
        if (move.getPower() != null) {
            value += move.getPower();
            
            // Accuracy adjustment
            if (move.getAccuracy() != null) {
                value *= (move.getAccuracy() / 100.0);
            }
            
            // Apply STAB (Same Type Attack Bonus)
            if (move.getType() == user.getCurrentType1() || 
                (user.getCurrentType2() != null && move.getType() == user.getCurrentType2())) {
                value *= 1.5; // 50% bonus
            }
            
            // Apply type effectiveness
            double typeEffectiveness = calculateTypeEffectiveness(move.getType(), target.getCurrentType1(), target.getCurrentType2());
            value *= typeEffectiveness;
            
            // Special cases for certain types
            if (target.getCurrentType1() == Type.ROCK || target.getCurrentType2() == Type.ROCK) {
                if (move.getType() == Type.WATER || move.getType() == Type.GRASS) {
                    value *= 1.2; // Extra bonus against Rock types
                }
            }
            
            if (target.getCurrentType1() == Type.DRAGON || target.getCurrentType2() == Type.DRAGON) {
                if (move.getType() == Type.ICE) {
                    value *= 1.3; // Extra bonus against Dragon types
                }
            }
        }
        
        // Cache the result
        moveEffectivenessCache.put(cacheKey, value);
        
        return value;
    }
}