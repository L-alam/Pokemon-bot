package src.pas.pokemon.agents;

import edu.bu.pas.pokemon.core.Battle.BattleView;
import edu.bu.pas.pokemon.core.Team.TeamView;
import edu.bu.pas.pokemon.core.Pokemon.PokemonView;
import edu.bu.pas.pokemon.core.enums.Type;
import edu.bu.pas.pokemon.core.enums.NonVolatileStatus;
import edu.bu.pas.pokemon.core.enums.Height;
import edu.bu.pas.pokemon.core.enums.Flag;
import edu.bu.pas.pokemon.core.enums.Stat;
import edu.bu.pas.pokemon.core.enums.Height;

/**
 * Utility calculator for evaluating Pokémon battle states
 */
public class UtilityCalculator {
    
    /**
     * Calculate the utility value of a battle state for the specified team
     * Higher values are better for the team
     */
    public static double calculateUtility(BattleView battleView, int myTeamIdx) {
        // If the game is over, return a very high/low utility
        if (battleView.isOver()) {
            if (battleView.getTeamView(myTeamIdx).size() == 0) {                        //not sure if the size reduces when pokemon feints. Might need isWinner method
                return 10000.0; // We won
            } else {
                return -10000.0; // We lost
            }
        }
        
        // Core component: HP ratio
        double hpRatio = calculateHPRatio(battleView, myTeamIdx);
        
        // Team composition advantage
        double teamAdvantage = calculateTeamAdvantage(battleView, myTeamIdx);
        
        // Status effects advantage
        double statusAdvantage = calculateStatusEffectsAdvantage(battleView, myTeamIdx);
        
        // Stage multipliers advantage (the 7 stats: ATK, DEF, SPD, etc.)
        double statMultipliersAdvantage = calculateStatMultipliersAdvantage(battleView, myTeamIdx);

        // TODO: Implement Height Calculations. (Flying type no damadge on Ground type; vis versa)
        //double heightAdvantage = calculateHeightAdvantage(battleView, myTeamIdx);
        
        // Combine all components with different weights
        return 5.0 * hpRatio + 2.0 * teamAdvantage + 1.5 * statusAdvantage 
            + 1.0 * statMultipliersAdvantage + 0.8; //* heightAdvantage;
    }
    
    /**
     * Calculate HP ratio advantage
     * This considers both current active Pokémon and the entire team
     */
    private static double calculateHPRatio(BattleView battleView, int myTeamIdx) {
        TeamView myTeam = battleView.getTeamView(myTeamIdx);
        TeamView opponentTeam = battleView.getTeamView(1 - myTeamIdx);
        
        // Active Pokémon HP ratio (more important)
        double myActiveHPRatio = getHPRatio(myTeam.getActivePokemonView());
        double opponentActiveHPRatio = getHPRatio(opponentTeam.getActivePokemonView());
        double activeHPAdvantage = myActiveHPRatio - opponentActiveHPRatio;
        
        // Team overall HP ratio
        double myTeamTotalHP = 0;
        double myTeamMaxHP = 0;
        double opponentTeamTotalHP = 0;
        double opponentTeamMaxHP = 0;
        
        for (int i = 0; i < myTeam.size(); i++) {
            PokemonView pokemon = myTeam.getPokemonView(i);
            myTeamTotalHP += pokemon.getCurrentStat(Stat.HP);
            myTeamMaxHP += pokemon.getBaseStat(Stat.HP);
        }
        
        for (int i = 0; i < opponentTeam.size(); i++) {
            PokemonView pokemon = opponentTeam.getPokemonView(i);
            opponentTeamTotalHP += pokemon.getCurrentStat(Stat.HP);
            opponentTeamMaxHP += pokemon.getBaseStat(Stat.HP);
        }
        
        double myTeamHPRatio = myTeamTotalHP / myTeamMaxHP;
        double opponentTeamHPRatio = opponentTeamTotalHP / opponentTeamMaxHP;
        double teamHPAdvantage = myTeamHPRatio - opponentTeamHPRatio;
        
        // Combine (active Pokémon is more important)
        return 0.7 * activeHPAdvantage + 0.3 * teamHPAdvantage;
    }
    
    /**
     * Get HP ratio for a single Pokémon (0.0 to 1.0)
     */
    private static double getHPRatio(PokemonView pokemon) {
        if (pokemon.getBaseStat(Stat.HP) == 0) {
            return 0.0;
        }
        return (double) pokemon.getCurrentStat(Stat.HP) / pokemon.getBaseStat(Stat.HP);
    }
    
    /**
     * Calculate team composition advantage
     * This evaluates type advantages and number of remaining Pokémon
     */
    private static double calculateTeamAdvantage(BattleView battleView, int myTeamIdx) {
        TeamView myTeam = battleView.getTeamView(myTeamIdx);
        TeamView opponentTeam = battleView.getTeamView(1 - myTeamIdx);
        
        // Count active Pokémon
        int myActiveCount = 0;
        int opponentActiveCount = 0;
        
        for (int i = 0; i < myTeam.size(); i++) {
            if (!myTeam.getPokemonView(i).hasFainted()) {
                myActiveCount++;
            }
        }
        
        for (int i = 0; i < opponentTeam.size(); i++) {
            if (!opponentTeam.getPokemonView(i).hasFainted()) {
                opponentActiveCount++;
            }
        }
        
        // Calculate advantage based on remaining Pokémon count
        double countAdvantage = (double)(myActiveCount - opponentActiveCount) / 
                                Math.max(myTeam.size(), opponentTeam.size());
        
        // Type advantage of our active Pokémon vs opponent's active
        double typeAdvantage = calculateTypeAdvantage(
            myTeam.getActivePokemonView(), 
            opponentTeam.getActivePokemonView()
        );
        
        return 0.6 * countAdvantage + 0.4 * typeAdvantage;
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
        return ourOffensiveAdvantage - theirOffensiveAdvantage;
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
        // This would be a comprehensive map of type effectiveness
        // For brevity, here's a simplified version with some common matchups
        
        if (attackType == Type.WATER && defenderType == Type.FIRE) return 2.0;
        if (attackType == Type.WATER && defenderType == Type.GRASS) return 0.5;
        if (attackType == Type.FIRE && defenderType == Type.GRASS) return 2.0;
        if (attackType == Type.FIRE && defenderType == Type.WATER) return 0.5;
        if (attackType == Type.GRASS && defenderType == Type.WATER) return 2.0;
        if (attackType == Type.GRASS && defenderType == Type.FIRE) return 0.5;
        
        if (attackType == Type.ELECTRIC && defenderType == Type.WATER) return 2.0;
        if (attackType == Type.ELECTRIC && defenderType == Type.GROUND) return 0.0;
        
        if (attackType == Type.PSYCHIC && defenderType == Type.FIGHTING) return 2.0;
        if (attackType == Type.PSYCHIC && defenderType == Type.POISON) return 2.0;
        
        // Add more type matchups as needed
        
        // Default to neutral effectiveness
        return 1.0;
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
        
        // Include volatile status effects
        double myVolatileScore = 0.0;
        double opponentVolatileScore = 0.0;
        
        // Confusion
        if (myPokemon.getFlag(Flag.CONFUSED)) myVolatileScore -= 0.2;
        if (opponentPokemon.getFlag(Flag.CONFUSED)) opponentVolatileScore -= 0.2;
        
        // Seeded
        if (myPokemon.getFlag(Flag.SEEDED)) myVolatileScore -= 0.15;
        if (opponentPokemon.getFlag(Flag.SEEDED)) opponentVolatileScore -= 0.15;
        
        // Other volatile effects can be added here
        
        // Combine scores (negative for our Pokémon, positive for opponent)
        return (opponentStatusScore + opponentVolatileScore) - (myStatusScore + myVolatileScore);
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
     * Calculate advantage from stat modifiers
     */
    private static double calculateStatMultipliersAdvantage(BattleView battleView, int myTeamIdx) {
        PokemonView myPokemon = battleView.getTeamView(myTeamIdx).getActivePokemonView();
        PokemonView opponentPokemon = battleView.getTeamView(1 - myTeamIdx).getActivePokemonView();
        
        double advantage = 0.0;
        
        // ATK modifier
        advantage += 0.03 * (myPokemon.getBaseStat(Stat.ATK) - opponentPokemon.getBaseStat(Stat.ATK));
        
        // DEF modifier
        advantage += 0.03 * (myPokemon.getBaseStat(Stat.DEF) - opponentPokemon.getBaseStat(Stat.DEF));
        
        // SPD modifier
        advantage += 0.04 * (myPokemon.getBaseStat(Stat.SPD) - opponentPokemon.getBaseStat(Stat.SPD));
        
        // SPATK modifier
        advantage += 0.03 * (myPokemon.getBaseStat(Stat.SPATK) - opponentPokemon.getBaseStat(Stat.SPATK));
        
        // SPDEF modifier
        advantage += 0.03 * (myPokemon.getBaseStat(Stat.SPDEF) - opponentPokemon.getBaseStat(Stat.SPDEF));
        
        // ACC modifier
        advantage += 0.02 * (myPokemon.getBaseStat(Stat.ACC) - opponentPokemon.getBaseStat(Stat.ACC));
        
        // EVASIVE modifier
        advantage += 0.02 * (myPokemon.getBaseStat(Stat.EVASIVE) - opponentPokemon.getBaseStat(Stat.EVASIVE));
        
        return advantage;
    }

    // private static double calculateHeightAdvantage(BattleView battleView, int myTeamIdx) {
    //     PokemonView myPokemon = battleView.getTeamView(myTeamIdx).getActivePokemonView();
    //     PokemonView opponentPokemon = battleView.getTeamView(1 - myTeamIdx).getActivePokemonView();
        
    //     // Check height status
    //     boolean myPokemonUnderground = (myPokemon.getHeight(Height.UNDERGROUND) < 0);
    //     boolean myPokemonFlying = (myPokemon.getHeight(Height.IN_AIR) > 0);
    //     boolean opponentUnderground = (opponentPokemon.getHeight(Height.UNDERGROUND) < 0);
    //     boolean opponentFlying = (opponentPokemon.getHeight(Height.IN_AIR) > 0);
        
    //     double advantage = 0.0;
        
    //     // Add advantageous situations
    //     if (myPokemonUnderground && !opponentUnderground) {
    //         advantage += 0.3; // Being underground is often advantageous
    //     }
    //     if (myPokemonFlying && !opponentFlying) {
    //         advantage += 0.2; // Being flying can provide an advantage
    //     }
        
    //     return advantage;
    // }



    // private double calculateMoveEffectiveness(MoveView move, PokemonView targetPokemon) {
    //     // Base effectiveness calculation...
        
    //     // Adjust for height
    //     if (targetPokemon.getHeight(Height.IN_AIR) && move.getType() == Type.GROUND) {
    //         return 0.0; // Ground moves don't affect flying Pokémon
    //     }
    //     if (targetPokemon.getHeight(Height.UNDERGROUND) && !move.canHitUnderground()) {
    //         return 0.0; // Most moves can't hit underground Pokémon
    //     }
        
    //     // Return the adjusted effectiveness
    // }
}

