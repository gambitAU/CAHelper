package com.CAHelper;

import lombok.extern.slf4j.Slf4j;
import javax.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class RoutingAlgorithm
{
    private final WikiDataLoader wikiDataLoader;
    private final List<CombatAchievement> allAchievements;

    // Injected constructor so wikiDataLoader is available
    public RoutingAlgorithm(WikiDataLoader wikiDataLoader)
    {
        this.wikiDataLoader = wikiDataLoader;
        this.allAchievements = wikiDataLoader.loadAllAchievements();
    }

    /**
     * Generates a routing recommendation based on player progress
     * @param progress Player progress, including completed achievements
     * @return Ordered list of boss recommendations
     */
    public List<BossRecommendation> getRecommendations(WikiSyncService.PlayerProgress progress)
    {
        return generateRoute(progress.getCompletedIds(), 5);
    }

    /**
     * Generates a list of boss recommendations based on the player's completed achievements
     * @param completedIds List of achievement IDs the player has completed
     * @param maxSuggestions Maximum number of boss groups to suggest
     * @return List of recommended boss tasks with their details
     */
    public List<BossRecommendation> generateRoute(List<Integer> completedIds, int maxSuggestions)
    {
        // Filter to only available (incomplete) achievements
        List<CombatAchievement> availableAchievements = allAchievements.stream()
                .filter(ca -> !completedIds.contains(ca.getId())) // Not completed by the player
                .filter(ca -> hasPrerequisites(ca, completedIds)) // Player meets prerequisites
                .collect(Collectors.toList());

        // Group achievements by boss
        Map<String, List<CombatAchievement>> bossGroups = availableAchievements.stream()
                .collect(Collectors.groupingBy(CombatAchievement::getBoss));

        // Calculate scores for each boss
        List<BossRecommendation> recommendations = new ArrayList<>();

        for (Map.Entry<String, List<CombatAchievement>> entry : bossGroups.entrySet())
        {
            String boss = entry.getKey();
            List<CombatAchievement> tasks = entry.getValue();

            // Calculate aggregate completion rate (average of all tasks)
            double avgCompletionRate = tasks.stream()
                    .mapToDouble(CombatAchievement::getCompletionRate)
                    .average()
                    .orElse(0.0);

            // Calculate total points available
            int totalPoints = tasks.stream()
                    .mapToInt(ca -> ca.getDifficulty().getPoints())
                    .sum();

            // Sort tasks by completion rate (highest first)
            List<CombatAchievement> sortedTasks = tasks.stream()
                    .sorted(Comparator.comparingDouble(CombatAchievement::getCompletionRate).reversed())
                    .collect(Collectors.toList());

            // Calculate efficiency score
            double efficiencyScore = calculateEfficiencyScore(
                    avgCompletionRate,
                    tasks.size(),
                    totalPoints,
                    sortedTasks
            );

            recommendations.add(new BossRecommendation(
                    boss,
                    sortedTasks,
                    avgCompletionRate,
                    totalPoints,
                    efficiencyScore
            ));
        }

        // Sort by efficiency score (highest first)
        recommendations.sort(Comparator.comparingDouble(BossRecommendation::getEfficiencyScore).reversed());

        // Return top N recommendations
        return recommendations.stream()
                .limit(maxSuggestions)
                .collect(Collectors.toList());
    }

    /**
     * Calculates efficiency score for routing priority
     * Formula weights:
     * - Average completion rate (60% weight) - easier tasks first
     * - Number of tasks (25% weight) - more tasks = more efficient
     * - Total points (15% weight) - higher rewards are nice
     */
    private double calculateEfficiencyScore(
            double avgCompletionRate,
            int taskCount,
            int totalPoints,
            List<CombatAchievement> tasks)
    {
        // Normalize values to 0-100 scale
        double completionScore = avgCompletionRate; // Already 0-100
        double taskCountScore = Math.min(taskCount * 10, 100); // Cap at 10 tasks
        double pointsScore = Math.min(totalPoints * 2, 100); // Rough normalization

        // Bonus for having multiple high-completion tasks (e.g., Barrows scenario)
        double clusterBonus = 0;
        long highCompletionTasks = tasks.stream()
                .filter(ca -> ca.getCompletionRate() > 50)
                .count();

        if (highCompletionTasks >= 3)
        {
            clusterBonus = 15; // 15% bonus for 3+ easy tasks
        }

        // Weighted score
        return (completionScore * 0.60) +
                (taskCountScore * 0.25) +
                (pointsScore * 0.15) +
                clusterBonus;
    }

    /**
     * Check if player has completed all prerequisites for a task
     */
    private boolean hasPrerequisites(CombatAchievement ca, List<Integer> completedIds)
    {
        if (ca.getPrerequisiteIds() == null || ca.getPrerequisiteIds().isEmpty())
        {
            return true;
        }

        return completedIds.containsAll(ca.getPrerequisiteIds());
    }

    /**
     * Combat Achievement data model
     */
    @lombok.Value
    public static class CombatAchievement
    {
        int id;
        String name;
        String description;
        Difficulty difficulty;
        double completionRate; // Percentage (0-100)
        String boss; // Boss/Monster name
        List<Integer> prerequisiteIds;

        public int getPoints()
        {
            return difficulty.getPoints();
        }
    }

    /**
     * Boss recommendation with ranked tasks
     */
    @lombok.Value
    public static class BossRecommendation
    {
        String bossName;
        List<CombatAchievement> availableTasks;
        double avgCompletionRate;
        int totalPoints;
        double efficiencyScore;

        public String getDisplaySummary()
        {
            return String.format(
                    "%s - %d tasks (%.1f%% avg completion) - %d points - Score: %.1f",
                    bossName,
                    availableTasks.size(),
                    avgCompletionRate,
                    totalPoints,
                    efficiencyScore
            );
        }

        /**
         * Get the top N easiest tasks for this boss
         */
        public List<CombatAchievement> getTopEasiestTasks(int count)
        {
            return availableTasks.stream()
                    .limit(count)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Task difficulty tiers
     */
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
}
