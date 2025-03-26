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

/**
 * Enhanced Utility calculator for evaluating Pokémon battle states
 */
public class UtilityCalculator {
    
    /**
     * Calculate the utility value of a battle state for the specified team
     * Higher values are better for the team
     */
    public static double calculateUtility(BattleView battleView, int myTeamIdx) {
        // If the game is over, return a very high/low utility
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
        
        // HP ratio advantage
        double hpRatio = calculateHPRatio(battleView, myTeamIdx);
        
        // Team composition advantage
        double teamAdvantage = calculateTeamAdvantage(battleView, myTeamIdx);
        
        // Status effects advantage
        double statusAdvantage = calculateStatusEffectsAdvantage(battleView, myTeamIdx);
        
        // Stage multipliers advantage (the 7 stats: ATK, DEF, SPD, etc.)
        double statMultipliersAdvantage = calculateStatMultipliersAdvantage(battleView, myTeamIdx);
        
        // Height advantage (flying vs ground, etc.)
        double heightAdvantage = calculateHeightAdvantage(battleView, myTeamIdx);
        
        // Volatile status effects advantage (confusion, seeded, etc.)
        double volatileStatusAdvantage = calculateVolatileStatusAdvantage(battleView, myTeamIdx);
        
        // Move advantage calculation (what moves are available)
        double moveAdvantage = calculateMoveAdvantage(battleView, myTeamIdx);
        
        // Combine all components with different weights
        return 6.0 * hpRatio + 
               2.0 * teamAdvantage + 
               2.0 * statusAdvantage + 
               1.5 * statMultipliersAdvantage +
               1.0 * heightAdvantage +
               1.5 * volatileStatusAdvantage +
               2.0 * moveAdvantage;
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
     * Calculate team composition advantage
     * This evaluates type advantages and number of remaining Pokémon
     */
    private static double calculateTeamAdvantage(BattleView battleView, int myTeamIdx) {
        TeamView myTeam = battleView.getTeamView(myTeamIdx);
        TeamView opponentTeam = battleView.getTeamView(1 - myTeamIdx);
        
        // Count active Pokémon for each team
        int myActiveCount = countRemainingPokemon(battleView, myTeamIdx);
        int opponentActiveCount = countRemainingPokemon(battleView, 1 - myTeamIdx);
        
        // Calculate advantage based on remaining Pokémon count
        double countAdvantage = 0;
        if (myActiveCount + opponentActiveCount > 0) {
            countAdvantage = (double)(myActiveCount - opponentActiveCount) / (myActiveCount + opponentActiveCount);
        }
        
        // Type advantage of our active Pokémon vs opponent's active
        double typeAdvantage = calculateTypeAdvantage(
            myTeam.getActivePokemonView(), 
            opponentTeam.getActivePokemonView()
        );
        
        // Team diversity advantage (having different types in your team)
        double diversityAdvantage = calculateTeamDiversity(myTeam) - calculateTeamDiversity(opponentTeam);
        
        return 0.5 * countAdvantage + 0.4 * typeAdvantage + 0.1 * diversityAdvantage;
    }
    
    /**
     * Calculate team diversity based on type coverage
     */
    private static double calculateTeamDiversity(TeamView team) {
        boolean[] typesCovered = new boolean[Type.values().length];
        int typesCount = 0;
        
        for (int i = 0; i < team.size(); i++) {
            PokemonView pokemon = team.getPokemonView(i);
            if (!pokemon.hasFainted()) {
                Type type1 = pokemon.getCurrentType1();
                Type type2 = pokemon.getCurrentType2();
                
                if (type1 != null && !typesCovered[type1.ordinal()]) {
                    typesCovered[type1.ordinal()] = true;
                    typesCount++;
                }
                
                if (type2 != null && !typesCovered[type2.ordinal()]) {
                    typesCovered[type2.ordinal()] = true;
                    typesCount++;
                }
            }
        }
        
        return (double) typesCount / Type.values().length;
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
        
        // Poison effectiveness
        if (attackType == Type.POISON) {
            if (defenderType == Type.GRASS) return 2.0;
            if (defenderType == Type.POISON || defenderType == Type.GROUND || 
                defenderType == Type.ROCK || defenderType == Type.GHOST) return 0.5;
        }
        
        // Ground effectiveness
        if (attackType == Type.GROUND) {
            if (defenderType == Type.FIRE || defenderType == Type.ELECTRIC || 
                defenderType == Type.POISON || defenderType == Type.ROCK) return 2.0;
            if (defenderType == Type.GRASS || defenderType == Type.BUG) return 0.5;
            if (defenderType == Type.FLYING) return 0.0;
        }
        
        // Flying effectiveness
        if (attackType == Type.FLYING) {
            if (defenderType == Type.GRASS || defenderType == Type.FIGHTING || defenderType == Type.BUG) return 2.0;
            if (defenderType == Type.ELECTRIC || defenderType == Type.ROCK) return 0.5;
        }
        
        // Psychic effectiveness
        if (attackType == Type.PSYCHIC) {
            if (defenderType == Type.FIGHTING || defenderType == Type.POISON) return 2.0;
            if (defenderType == Type.PSYCHIC) return 0.5;
        }
        
        // Bug effectiveness
        if (attackType == Type.BUG) {
            if (defenderType == Type.GRASS || defenderType == Type.PSYCHIC) return 2.0;
            if (defenderType == Type.FIRE || defenderType == Type.FIGHTING || 
                defenderType == Type.POISON || defenderType == Type.FLYING || 
                defenderType == Type.GHOST) return 0.5;
        }
        
        // Rock effectiveness
        if (attackType == Type.ROCK) {
            if (defenderType == Type.FIRE || defenderType == Type.ICE || 
                defenderType == Type.FLYING || defenderType == Type.BUG) return 2.0;
            if (defenderType == Type.FIGHTING || defenderType == Type.GROUND) return 0.5;
        }
        
        // Ghost effectiveness
        if (attackType == Type.GHOST) {
            if (defenderType == Type.PSYCHIC || defenderType == Type.GHOST) return 2.0;
            if (defenderType == Type.NORMAL) return 0.0;
        }
        
        // Dragon effectiveness
        if (attackType == Type.DRAGON) {
            if (defenderType == Type.DRAGON) return 2.0;
        }
        
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
     * Calculate advantage from volatile status effects
     */
    private static double calculateVolatileStatusAdvantage(BattleView battleView, int myTeamIdx) {
        PokemonView myPokemon = battleView.getTeamView(myTeamIdx).getActivePokemonView();
        PokemonView opponentPokemon = battleView.getTeamView(1 - myTeamIdx).getActivePokemonView();
        
        double myVolatileScore = 0.0;
        double opponentVolatileScore = 0.0;
        
        // Confusion
        if (myPokemon.getFlag(Flag.CONFUSED)) myVolatileScore -= 0.25;
        if (opponentPokemon.getFlag(Flag.CONFUSED)) opponentVolatileScore -= 0.25;
        
        // Seeded
        if (myPokemon.getFlag(Flag.SEEDED)) myVolatileScore -= 0.15;
        if (opponentPokemon.getFlag(Flag.SEEDED)) opponentVolatileScore -= 0.15;
        
        // Trapped
        if (myPokemon.getFlag(Flag.TRAPPED)) myVolatileScore -= 0.2;
        if (opponentPokemon.getFlag(Flag.TRAPPED)) opponentVolatileScore -= 0.2;
        
        // Focus Energy
        if (myPokemon.getFlag(Flag.FOCUS_ENERGY)) myVolatileScore += 0.15;
        if (opponentPokemon.getFlag(Flag.FOCUS_ENERGY)) opponentVolatileScore += 0.15;
        
        // Flinched
        if (myPokemon.getFlag(Flag.FLINCHED)) myVolatileScore -= 0.15;
        if (opponentPokemon.getFlag(Flag.FLINCHED)) opponentVolatileScore -= 0.15;
        
        // Return net advantage (positive is good for us)
        return opponentVolatileScore - myVolatileScore;
    }
    
    /**
     * Calculate advantage from stat modifiers
     */
    private static double calculateStatMultipliersAdvantage(BattleView battleView, int myTeamIdx) {
        PokemonView myPokemon = battleView.getTeamView(myTeamIdx).getActivePokemonView();
        PokemonView opponentPokemon = battleView.getTeamView(1 - myTeamIdx).getActivePokemonView();
        
        double advantage = 0.0;
        
        // ATK modifier
        advantage += 0.15 * (myPokemon.getStageMultiplier(Stat.ATK) - opponentPokemon.getStageMultiplier(Stat.ATK));
        
        // DEF modifier
        advantage += 0.15 * (myPokemon.getStageMultiplier(Stat.DEF) - opponentPokemon.getStageMultiplier(Stat.DEF));
        
        // SPD modifier
        advantage += 0.2 * (myPokemon.getStageMultiplier(Stat.SPD) - opponentPokemon.getStageMultiplier(Stat.SPD));
        
        // SPATK modifier
        advantage += 0.15 * (myPokemon.getStageMultiplier(Stat.SPATK) - opponentPokemon.getStageMultiplier(Stat.SPATK));
        
        // SPDEF modifier
        advantage += 0.15 * (myPokemon.getStageMultiplier(Stat.SPDEF) - opponentPokemon.getStageMultiplier(Stat.SPDEF));
        
        // ACC modifier
        advantage += 0.1 * (myPokemon.getStageMultiplier(Stat.ACC) - opponentPokemon.getStageMultiplier(Stat.ACC));
        
        // EVASIVE modifier (positive for us means we're harder to hit)
        advantage += 0.1 * (myPokemon.getStageMultiplier(Stat.EVASIVE) - opponentPokemon.getStageMultiplier(Stat.EVASIVE));
        
        return advantage;
    }
    
    /**
     * Calculate advantage from flying/underground height differences
     */
    private static double calculateHeightAdvantage(BattleView battleView, int myTeamIdx) {
        PokemonView myPokemon = battleView.getTeamView(myTeamIdx).getActivePokemonView();
        PokemonView opponentPokemon = battleView.getTeamView(1 - myTeamIdx).getActivePokemonView();
        
        double advantage = 0.0;
        
        // Check if either Pokémon is flying (F) or underground (U)
        boolean myPokemonFlying = false;
        boolean myPokemonUnderground = false;
        boolean opponentFlying = false;
        boolean opponentUnderground = false;
        
        // This is a placeholder - actual implementation would use getHeight if available
        // Until we can directly check height, we'll rely on type as a proxy
        
        // Flying types or Pokémon using Fly are considered flying
        if (myPokemon.getCurrentType1() == Type.FLYING || 
            myPokemon.getCurrentType2() == Type.FLYING) {
            myPokemonFlying = true;
        }
        
        if (opponentPokemon.getCurrentType1() == Type.FLYING || 
            opponentPokemon.getCurrentType2() == Type.FLYING) {
            opponentFlying = true;
        }
        
        // Flying Pokémon immune to Ground moves
        if (myPokemonFlying && (opponentPokemon.getCurrentType1() == Type.GROUND || 
                               opponentPokemon.getCurrentType2() == Type.GROUND)) {
            advantage += 0.3;
        }
        
        if (opponentFlying && (myPokemon.getCurrentType1() == Type.GROUND || 
                              myPokemon.getCurrentType2() == Type.GROUND)) {
            advantage -= 0.3;
        }
        
        // Special advantage for flying vs certain types
        if (myPokemonFlying && (opponentPokemon.getCurrentType1() == Type.FIGHTING || 
                               opponentPokemon.getCurrentType1() == Type.BUG ||
                               opponentPokemon.getCurrentType2() == Type.FIGHTING || 
                               opponentPokemon.getCurrentType2() == Type.BUG)) {
            advantage += 0.2;
        }
        
        if (opponentFlying && (myPokemon.getCurrentType1() == Type.FIGHTING || 
                              myPokemon.getCurrentType1() == Type.BUG ||
                              myPokemon.getCurrentType2() == Type.FIGHTING || 
                              myPokemon.getCurrentType2() == Type.BUG)) {
            advantage -= 0.2;
        }
        
        return advantage;
    }
    
    /**
     * Calculate advantage from available moves
     */
    private static double calculateMoveAdvantage(BattleView battleView, int myTeamIdx) {
        PokemonView myPokemon = battleView.getTeamView(myTeamIdx).getActivePokemonView();
        PokemonView opponentPokemon = battleView.getTeamView(1 - myTeamIdx).getActivePokemonView();
        
        double myMoveScore = 0.0;
        
        // Get our available moves
        List<MoveView> availableMoves = myPokemon.getAvailableMoves();
        
        // If we have no moves, big disadvantage
        if (availableMoves.isEmpty()) {
            return -5.0;
        }
        
        // Evaluate each move
        for (MoveView move : availableMoves) {
            double moveValue = evaluateMove(move, myPokemon, opponentPokemon);
            myMoveScore = Math.max(myMoveScore, moveValue); // Consider our best move
        }
        
        // Scale to appropriate range
        return myMoveScore / 100.0;
    }
    
    /**
     * Evaluate a single move
     */
    private static double evaluateMove(MoveView move, PokemonView user, PokemonView target) {
        if (move == null) {
            return 0.0;
        }
        
        double value = 0.0;
        
        // Base value on power
        if (move.getPower() != null && move.getPower() > 0) {
            value = move.getPower();
            
            // Adjust for accuracy
            if (move.getAccuracy() != null) {
                value *= (move.getAccuracy() / 100.0);
            }
            
            // STAB bonus
            if (move.getType() == user.getCurrentType1() || 
                (user.getCurrentType2() != null && move.getType() == user.getCurrentType2())) {
                value *= 1.5;
            }
            
            // Type effectiveness
            value *= calculateMoveEffectiveness(move.getType(), target);
        } 
        // Status moves can be valuable too
        else if (move.getCategory() != null && move.getCategory().toString().equals("STATUS")) {
            // Simple heuristic for status moves
            value = 30.0;
            
            // Stat boosting moves are valuable
            if (move.getName().contains("Growth") || 
                move.getName().contains("Swords Dance") ||
                move.getName().contains("Sharpen")) {
                value += 20.0;
            }
        }
        
        return value;
    }
    
    /**
     * Calculate effectiveness of a move type against a Pokémon
     */
    private static double calculateMoveEffectiveness(Type moveType, PokemonView defender) {
        if (moveType == null) {
            return 1.0;
        }
        
        Type defenderType1 = defender.getCurrentType1();
        Type defenderType2 = defender.getCurrentType2();
        
        double effectiveness = getTypeEffectiveness(moveType, defenderType1);
        
        if (defenderType2 != null) {
            effectiveness *= getTypeEffectiveness(moveType, defenderType2);
        }
        
        return effectiveness;
    }
}