package com.CAHelper;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class RoutingAlgorithm
{
    private final CombatAchievementEnrichmentService enrichmentService;
    private CAHelperConfig config;

    @Inject
    public RoutingAlgorithm(CombatAchievementEnrichmentService enrichmentService)
    {
        this.enrichmentService = enrichmentService;
    }

    public void setConfig(CAHelperConfig config)
    {
        this.config = config;
    }

    public enum TaskType
    {
        KILLCOUNT("Kill Count"),
        MECHANICAL("Mechanical"),
        PERFECTION("Perfection"),
        RESTRICTION("Restriction"),
        STAMINA("Stamina"),
        SPEED("Speed"),
        FLAWLESS("Flawless"),
        GROUPSIZE("Group Size");

        private final String wikiName;

        TaskType(String wikiName)
        {
            this.wikiName = wikiName;
        }

        public String getWikiName()
        {
            return wikiName;
        }

        public static TaskType fromWikiDifficulty(String difficultyString)
        {
            if (difficultyString == null)
            {
                return KILLCOUNT;
            }

            String normalized = difficultyString.toLowerCase();

            for (TaskType type : TaskType.values())
            {
                if (normalized.startsWith(type.getWikiName().toLowerCase()))
                {
                    return type;
                }
            }

            return KILLCOUNT;
        }
    }

    @lombok.Value
    public static class CombatAchievement
    {
        int id;
        String name;
        String monster;
        Difficulty difficulty;
        TaskType type;
        double completionRate;
        String description;
        List<Integer> prerequisiteIds;

        public int getPoints()
        {
            return difficulty.getPoints();
        }
    }

    public enum Difficulty
    {
        EASY(1),
        MEDIUM(2),
        HARD(3),
        ELITE(4),
        MASTER(5),
        GRANDMASTER(6);

        private final int points;

        Difficulty(int points)
        {
            this.points = points;
        }

        public int getPoints()
        {
            return points;
        }
    }

    @lombok.Value
    public static class BossRecommendation
    {
        String bossName;
        int completedCount;
        int totalCount;
        double completionPercentage;
        List<CombatAchievement> availableTasks;
    }

    /**
     * Get boss recommendations.
     * Sorting: "Low-Hanging Fruit" - recommends bosses with easiest incomplete tasks
     * or simple difficulty sorting if smart routing is disabled.
     */
    public List<BossRecommendation> getRecommendations(int limit)
    {
        log.info("=== getRecommendations() called ===");

        List<CombatAchievement> allTasks = enrichmentService.getAllEnrichedTasks();
        log.info("Got {} total enriched tasks", allTasks.size());

        // Apply all filters
        if (config != null)
        {
            allTasks = filterByDifficulty(allTasks);
            log.info("After difficulty filtering: {} tasks", allTasks.size());

            if (config.soloContentOnly())
            {
                allTasks = filterSoloContent(allTasks);
                log.info("After solo content filtering: {} tasks", allTasks.size());
            }

            if (config.hideWildernessContent())
            {
                allTasks = filterWildernessContent(allTasks);
                log.info("After wilderness filtering: {} tasks", allTasks.size());
            }
        }

        if (allTasks.isEmpty())
        {
            log.info("No tasks found after filtering");
            return Collections.emptyList();
        }

        // Group by boss
        Map<String, List<CombatAchievement>> tasksByBoss = allTasks.stream()
                .collect(Collectors.groupingBy(CombatAchievement::getMonster));

        log.info("Grouped into {} bosses", tasksByBoss.size());

        // Create recommendations
        List<BossRecommendation> recommendations = new ArrayList<>();

        for (Map.Entry<String, List<CombatAchievement>> entry : tasksByBoss.entrySet())
        {
            String bossName = entry.getKey();
            List<CombatAchievement> tasks = entry.getValue();

            int totalTasks = tasks.size();
            int completedTasks = (int) tasks.stream()
                    .filter(t -> t.getCompletionRate() >= 100)
                    .count();

            List<CombatAchievement> incompleteTasks = tasks.stream()
                    .filter(t -> t.getCompletionRate() < 100)
                    .collect(Collectors.toList());

            double score;
            if (incompleteTasks.isEmpty())
            {
                score = -1.0; // Fully complete - sort to bottom
            }
            else
            {
                score = calculateLowHangingFruitScore(incompleteTasks);
            }

            recommendations.add(new BossRecommendation(
                    bossName,
                    completedTasks,
                    totalTasks,
                    score,
                    tasks
            ));
        }

        // Sort based on config
        if (config != null && config.useSmartRouting())
        {
            // Smart routing - low-hanging fruit first
            recommendations.sort(Comparator
                    .comparingDouble(BossRecommendation::getCompletionPercentage)
                    .reversed());

            log.info("Using SMART routing (low-hanging fruit)");
        }
        else
        {
            // Simple mode - sort by easiest difficulty
            recommendations.sort(Comparator
                    .comparing((BossRecommendation r) -> {
                        return r.getAvailableTasks().stream()
                                .filter(t -> t.getCompletionRate() < 100)
                                .map(CombatAchievement::getDifficulty)
                                .min(Comparator.naturalOrder())
                                .orElse(Difficulty.GRANDMASTER);
                    })
                    .thenComparing(BossRecommendation::getBossName));

            log.info("Using SIMPLE routing (difficulty only)");
        }

        log.info("=== Top 10 Recommendations ===");
        recommendations.stream()
                .limit(10)
                .forEach(rec -> {
                    if (rec.getCompletionPercentage() < 0)
                    {
                        log.info("  {}: COMPLETE ({}/{})",
                                rec.getBossName(), rec.getCompletedCount(), rec.getTotalCount());
                    }
                    else
                    {
                        log.info("  {}: {}/{} (score: {:.1f})",
                                rec.getBossName(), rec.getCompletedCount(),
                                rec.getTotalCount(), rec.getCompletionPercentage());
                    }
                });

        if (limit > 0 && limit < recommendations.size())
        {
            recommendations = recommendations.subList(0, limit);
        }

        return recommendations;
    }

    /**
     * Score based on easiest 1-3 incomplete tasks.
     * Promotes "do easy tasks across many bosses" strategy.
     */
    private double calculateLowHangingFruitScore(List<CombatAchievement> incompleteTasks)
    {
        if (incompleteTasks.isEmpty())
        {
            return 0.0;
        }

        // Sort by difficulty, then completion %
        List<CombatAchievement> sorted = incompleteTasks.stream()
                .sorted(Comparator
                        .comparingInt((CombatAchievement t) -> t.getDifficulty().ordinal())
                        .thenComparing(Comparator.comparingDouble(CombatAchievement::getCompletionRate).reversed()))
                .collect(Collectors.toList());

        // Take easiest 1-3 tasks
        int numTasksToConsider = Math.min(3, sorted.size());
        List<CombatAchievement> easiestTasks = sorted.subList(0, numTasksToConsider);

        double totalScore = 0.0;

        for (CombatAchievement task : easiestTasks)
        {
            double difficultyBonus = getDifficultyBonus(task.getDifficulty());
            double completionBonus = task.getCompletionRate();

            // 60% difficulty, 40% completion
            double taskScore = (difficultyBonus * 0.6) + (completionBonus * 0.4);
            totalScore += taskScore;
        }

        return totalScore / numTasksToConsider;
    }

    private double getDifficultyBonus(Difficulty difficulty)
    {
        switch (difficulty)
        {
            case EASY: return 100.0;
            case MEDIUM: return 80.0;
            case HARD: return 60.0;
            case ELITE: return 40.0;
            case MASTER: return 20.0;
            case GRANDMASTER: return 0.0;
            default: return 0.0;
        }
    }

    private List<CombatAchievement> filterByDifficulty(List<CombatAchievement> tasks)
    {
        CAHelperConfig.Difficulty minDiff = config.minDifficulty();
        CAHelperConfig.Difficulty maxDiff = config.maxDifficulty();

        return tasks.stream()
                .filter(task -> {
                    Difficulty taskDiff = task.getDifficulty();
                    int taskOrdinal = taskDiff.ordinal();
                    int minOrdinal = convertConfigDifficulty(minDiff).ordinal();
                    int maxOrdinal = convertConfigDifficulty(maxDiff).ordinal();

                    return taskOrdinal >= minOrdinal && taskOrdinal <= maxOrdinal;
                })
                .collect(Collectors.toList());
    }

    private List<CombatAchievement> filterSoloContent(List<CombatAchievement> tasks)
    {
        List<CombatAchievement> soloTasks = tasks.stream()
                .filter(task -> !isGroupContent(task))
                .collect(Collectors.toList());

        int filtered = tasks.size() - soloTasks.size();
        log.info("Filtered {} group tasks, {} solo remaining", filtered, soloTasks.size());

        return soloTasks;
    }

    private List<CombatAchievement> filterWildernessContent(List<CombatAchievement> tasks)
    {
        List<CombatAchievement> safeTasks = tasks.stream()
                .filter(task -> !isWildernessContent(task))
                .collect(Collectors.toList());

        int filtered = tasks.size() - safeTasks.size();
        log.info("Filtered {} wilderness tasks, {} safe remaining", filtered, safeTasks.size());

        return safeTasks;
    }

    private boolean isGroupContent(CombatAchievement task)
    {
        if (task.getType() == TaskType.GROUPSIZE)
        {
            return true;
        }

        String name = task.getName().toLowerCase();
        String description = task.getDescription() != null ? task.getDescription().toLowerCase() : "";

        String[] namePatterns = {
                "duo", "trio", "4-scale", "5-scale", "6-scale", "7-scale", "8-scale",
                "4-man", "5-man", "4man", "5man", "team of", "group of", "party of"
        };

        for (String pattern : namePatterns)
        {
            if (name.contains(pattern))
            {
                return true;
            }
        }

        String[] descPatterns = {
                "in a group of", "in a team of", "in a party of", "with a team of",
                "with a group of", "with a party of", "as a team", "as a group",
                "in a duo", "in a trio", "with at least 2", "with at least 3",
                "with at least 4", "with at least 5", "with 2 or more",
                "with 3 or more", "with 4 or more", "with 5 or more",
                "alongside", "other players"
        };

        for (String pattern : descPatterns)
        {
            if (description.contains(pattern))
            {
                return true;
            }
        }

        // Exception: explicit solo
        if (name.contains("solo") || description.contains("solo"))
        {
            return false;
        }

        return false;
    }

    private boolean isWildernessContent(CombatAchievement task)
    {
        String name = task.getName().toLowerCase();
        String monster = task.getMonster().toLowerCase();
        String desc = task.getDescription() != null ? task.getDescription().toLowerCase() : "";

        String[] wildyIndicators = {
                "wilderness", "wildy", "callisto", "venenatis", "vet'ion", "vetion",
                "artio", "spindel", "calvar'ion", "calvarion", "scorpia",
                "chaos elemental", "crazy archaeologist", "chaos fanatic",
                "king black dragon", "kbd", "revenant", "lava dragon"
        };

        for (String indicator : wildyIndicators)
        {
            if (name.contains(indicator) || monster.contains(indicator) || desc.contains(indicator))
            {
                return true;
            }
        }

        return false;
    }

    private Difficulty convertConfigDifficulty(CAHelperConfig.Difficulty configDiff)
    {
        switch (configDiff)
        {
            case EASY: return Difficulty.EASY;
            case MEDIUM: return Difficulty.MEDIUM;
            case HARD: return Difficulty.HARD;
            case ELITE: return Difficulty.ELITE;
            case MASTER: return Difficulty.MASTER;
            case GRANDMASTER: return Difficulty.GRANDMASTER;
            default: return Difficulty.EASY;
        }
    }
}