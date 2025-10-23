package com.CAHelper;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for loading and tracking Combat Achievement tasks.
 * Reads from cache structs and checking varp bits.
 */
@Slf4j
@Singleton
public class CombatAchievementService
{
    @Inject
    private Client client;

    // Enum IDs for each tier
    private static final int[] TIER_ENUM_IDS = {
            3981, // Easy
            3982, // Medium
            3983, // Hard
            3984, // Elite
            3985, // Master
            3986  // Grandmaster
    };

    // Varp IDs that store completion bits - this will need updating from gamevals every time new CAs are added - can be found in VarPlayerID
    private static final int[] VARP_IDS = {
            3116, 3117, 3118, 3119, 3120, 3121, 3122, 3123, 3124, 3125,
            3126, 3127, 3128, 3387, 3718, 3773, 3774, 4204, 4496,4721
    };

    // Varbit IDs for tier thresholds
    private static final int VARBIT_THRESHOLD_EASY = 4132;
    private static final int VARBIT_THRESHOLD_MEDIUM = 10660;
    private static final int VARBIT_THRESHOLD_HARD = 10661;
    private static final int VARBIT_THRESHOLD_ELITE = 14812;
    private static final int VARBIT_THRESHOLD_MASTER = 14813;
    private static final int VARBIT_THRESHOLD_GRANDMASTER = 14814;

    // Fallback hardcoded thresholds (in case varbits fail) - will get around to just removing this - if varbits fail we probably have bigger issues to worry about
    private static final int FALLBACK_THRESHOLD_EASY = 33;
    private static final int FALLBACK_THRESHOLD_MEDIUM = 143;
    private static final int FALLBACK_THRESHOLD_HARD = 394;
    private static final int FALLBACK_THRESHOLD_ELITE = 1038;
    private static final int FALLBACK_THRESHOLD_MASTER = 1561;
    private static final int FALLBACK_THRESHOLD_GRANDMASTER = 2277;

    // Struct param IDs
    private static final int PARAM_TASK_ID = 1306;
    private static final int PARAM_TASK_NAME = 1308;
    private static final int PARAM_TASK_DESCRIPTION = 1309;

    // Map of task ID -> task data
    private Map<Integer, CombatAchievementTask> taskMap = new HashMap<>();

    // Map of task name -> task (for lookups by name)
    private Map<String, CombatAchievementTask> taskNameMap = new HashMap<>();

    private boolean initialized = false;

    /**
     * Initialize by loading all CA tasks from cache.
     */
    public void initialize()
    {
        if (initialized)
        {
            log.info("CombatAchievementService already initialized");
            return;
        }

        log.info("Loading Combat Achievement tasks from cache...");

        try
        {
            taskMap.clear();
            taskNameMap.clear();

            // Load tasks from each tier enum
            for (int i = 0; i < TIER_ENUM_IDS.length; i++)
            {
                int enumId = TIER_ENUM_IDS[i];
                RoutingAlgorithm.Difficulty difficulty = getDifficultyForTier(i);

                loadTasksFromEnum(enumId, difficulty);
            }

            log.info("Loaded {} Combat Achievement tasks from cache", taskMap.size());
            initialized = true;
        }
        catch (Exception e)
        {
            log.error("Failed to load CA tasks from cache", e);
            initialized = false;
        }
    }

    /**
     * Load tasks from a specific tier enum.
     */
    private void loadTasksFromEnum(int enumId, RoutingAlgorithm.Difficulty difficulty)
    {
        try
        {
            EnumComposition tierEnum = client.getEnum(enumId);
            if (tierEnum == null)
            {
                log.warn("Enum {} is null", enumId);
                return;
            }

            int[] structIds = tierEnum.getIntVals();
            log.info("Loading {} tasks from enum {} ({})", structIds.length, enumId, difficulty);

            for (int structId : structIds)
            {
                try
                {
                    StructComposition struct = client.getStructComposition(structId);
                    if (struct == null)
                    {
                        log.warn("Struct {} is null", structId);
                        continue;
                    }

                    // Extract task data from struct
                    int taskId = struct.getIntValue(PARAM_TASK_ID);
                    String name = struct.getStringValue(PARAM_TASK_NAME);
                    String description = struct.getStringValue(PARAM_TASK_DESCRIPTION);

                    int points = getPointsForDifficulty(difficulty);

                    // Create task object
                    CombatAchievementTask task = new CombatAchievementTask(
                            taskId,
                            name,
                            description,
                            difficulty,
                            points,
                            structId
                    );

                    taskMap.put(taskId, task);
                    taskNameMap.put(name, task);
                }
                catch (Exception e)
                {
                    log.warn("Failed to load struct {}: {}", structId, e.getMessage());
                }
            }
        }
        catch (Exception e)
        {
            log.error("Failed to load enum {}", enumId, e);
        }
    }

    /**
     * Check if a task is complete using the bit formula.
     */
    public boolean isTaskComplete(int taskId)
    {
        if (taskId < 0 || taskId >= 625)
        {
            return false;
        }

        try
        {
            // Calculate which varp and which bit
            int varpIndex = taskId / 32;
            int bitIndex = taskId % 32;

            if (varpIndex >= VARP_IDS.length)
            {
                // Newer tasks added before plugin gets updated with new varp
                log.debug("Task {} needs unknown varp index {} - treating as incomplete", taskId, varpIndex);
                return false;
            }

            // Get varp value and check the bit
            int varpValue = client.getVarpValue(VARP_IDS[varpIndex]);
            boolean complete = (varpValue & (1 << bitIndex)) != 0;

            return complete;
        }
        catch (Exception e)
        {
            log.warn("Failed to check completion for task {}: {}", taskId, e.getMessage());
            return false;
        }
    }

    /**
     * Check if a task is complete by name.
     */
    public boolean isTaskComplete(String taskName)
    {
        CombatAchievementTask task = taskNameMap.get(taskName);
        if (task == null)
        {
            return false;
        }
        return isTaskComplete(task.id);
    }

    /**
     * Get all incomplete tasks.
     */
    public List<RoutingAlgorithm.CombatAchievement> getIncompleteTasks()
    {
        if (!initialized)
        {
            log.warn("Service not initialized");
            return Collections.emptyList();
        }

        List<RoutingAlgorithm.CombatAchievement> incomplete = new ArrayList<>();

        for (CombatAchievementTask task : taskMap.values())
        {
            if (!isTaskComplete(task.id))
            {
                incomplete.add(task.toRoutingTask());
            }
        }

        log.info("=== getIncompleteTasks() called ===");
        log.info("Found {} incomplete tasks out of {} total", incomplete.size(), taskMap.size());

        if (!incomplete.isEmpty())
        {
            log.info("Sample incomplete tasks:");
            incomplete.stream()
                    .limit(5)
                    .forEach(t -> log.info("  - {} (monster: {})", t.getName(), t.getMonster()));
        }
        else
        {
            log.warn("getIncompleteTasks returned empty! Initialized={}, TaskMapSize={}, CompletedCount={}",
                    initialized, taskMap.size(), getCompletedTaskCount());
        }

        return incomplete;
    }

    /**
     * Get incomplete tasks grouped by extracted monster name.
     */
    public Map<String, List<RoutingAlgorithm.CombatAchievement>> getIncompleteTasksByMonster()
    {
        return getIncompleteTasks().stream()
                .collect(Collectors.groupingBy(task -> task.getMonster()));
    }

    /**
     * Get all tasks for a specific boss/monster (completed and incomplete).
     */
    public List<RoutingAlgorithm.CombatAchievement> getAllTasksForBoss(String monsterName)
    {
        if (!initialized)
        {
            return Collections.emptyList();
        }

        log.warn("getAllTasksForBoss not fully implemented - monster info not in cache structs");
        return Collections.emptyList();
    }

    /**
     * Get total number of completed tasks.
     */
    public int getCompletedTaskCount()
    {
        if (!initialized)
        {
            return 0;
        }

        int count = 0;
        for (CombatAchievementTask task : taskMap.values())
        {
            if (isTaskComplete(task.id))
            {
                count++;
            }
        }
        return count;
    }

    /**
     * Get total number of tasks.
     */
    public int getTotalTaskCount()
    {
        return taskMap.size();
    }

    /**
     * Get current points from completed tasks.
     */
    public int getCurrentTierPoints()
    {
        try
        {
            int points = 0;
            for (CombatAchievementTask task : taskMap.values())
            {
                if (isTaskComplete(task.id))
                {
                    points += task.points;
                }
            }

            log.debug("Calculated {} points from {} completed tasks", points, getCompletedTaskCount());

            return points;
        }
        catch (Exception e)
        {
            log.warn("Failed to get current points", e);
            return 0;
        }
    }

    /**
     * Debug method: Get detailed points breakdown by tier.
     */
    public Map<RoutingAlgorithm.Difficulty, Integer> getPointsBreakdown()
    {
        Map<RoutingAlgorithm.Difficulty, Integer> breakdown = new HashMap<>();

        for (CombatAchievementTask task : taskMap.values())
        {
            if (isTaskComplete(task.id))
            {
                breakdown.merge(task.difficulty, task.points, Integer::sum);
            }
        }

        return breakdown;
    }

    /**
     * Get current tier based on points.
     */
    public String getCurrentTier()
    {
        int points = getCurrentTierPoints();

        // Read thresholds from varbits
        int grandmasterThreshold = getThresholdFromVarbit(VARBIT_THRESHOLD_GRANDMASTER, FALLBACK_THRESHOLD_GRANDMASTER);
        int masterThreshold = getThresholdFromVarbit(VARBIT_THRESHOLD_MASTER, FALLBACK_THRESHOLD_MASTER);
        int eliteThreshold = getThresholdFromVarbit(VARBIT_THRESHOLD_ELITE, FALLBACK_THRESHOLD_ELITE);
        int hardThreshold = getThresholdFromVarbit(VARBIT_THRESHOLD_HARD, FALLBACK_THRESHOLD_HARD);
        int mediumThreshold = getThresholdFromVarbit(VARBIT_THRESHOLD_MEDIUM, FALLBACK_THRESHOLD_MEDIUM);
        int easyThreshold = getThresholdFromVarbit(VARBIT_THRESHOLD_EASY, FALLBACK_THRESHOLD_EASY);

        if (points >= grandmasterThreshold) return "Grandmaster";
        if (points >= masterThreshold) return "Master";
        if (points >= eliteThreshold) return "Elite";
        if (points >= hardThreshold) return "Hard";
        if (points >= mediumThreshold) return "Medium";
        if (points >= easyThreshold) return "Easy";
        return "None";
    }

    /**
     * Get threshold for current tier.
     */
    public int getCurrentTierThreshold()
    {
        String tier = getCurrentTier();
        return getThresholdForTier(tier);
    }

    /**
     * Get next tier name.
     */
    public String getNextTierName()
    {
        String current = getCurrentTier();
        switch (current)
        {
            case "None": return "Easy";
            case "Easy": return "Medium";
            case "Medium": return "Hard";
            case "Hard": return "Elite";
            case "Elite": return "Master";
            case "Master": return "Grandmaster";
            case "Grandmaster": return "Complete";
            default: return "Easy";
        }
    }

    /**
     * Get points to next tier.
     */
    public int getPointsToNextTier()
    {
        String nextTier = getNextTierName();
        if (nextTier.equals("Complete"))
        {
            return 0;
        }

        int currentPoints = getCurrentTierPoints();
        int nextThreshold = getThresholdForTier(nextTier);

        return Math.max(0, nextThreshold - currentPoints);
    }

    /**
     * Get threshold for a specific tier.
     */
    private int getThresholdForTier(String tier)
    {
        switch (tier)
        {
            case "Easy":
                return getThresholdFromVarbit(VARBIT_THRESHOLD_EASY, FALLBACK_THRESHOLD_EASY);
            case "Medium":
                return getThresholdFromVarbit(VARBIT_THRESHOLD_MEDIUM, FALLBACK_THRESHOLD_MEDIUM);
            case "Hard":
                return getThresholdFromVarbit(VARBIT_THRESHOLD_HARD, FALLBACK_THRESHOLD_HARD);
            case "Elite":
                return getThresholdFromVarbit(VARBIT_THRESHOLD_ELITE, FALLBACK_THRESHOLD_ELITE);
            case "Master":
                return getThresholdFromVarbit(VARBIT_THRESHOLD_MASTER, FALLBACK_THRESHOLD_MASTER);
            case "Grandmaster":
                return getThresholdFromVarbit(VARBIT_THRESHOLD_GRANDMASTER, FALLBACK_THRESHOLD_GRANDMASTER);
            default:
                return 0;
        }
    }

    /**
     * Read threshold from varbit with fallback.
     */
    private int getThresholdFromVarbit(int varbitId, int fallback)
    {
        try
        {
            int value = client.getVarbitValue(varbitId);
            if (value > 0)
            {
                return value;
            }
            else
            {
                log.debug("Varbit {} returned 0 or negative, using fallback: {}", varbitId, fallback);
                return fallback;
            }
        }
        catch (Exception e)
        {
            log.warn("Failed to read threshold varbit {}, using fallback: {}", varbitId, fallback);
            return fallback;
        }
    }

    /**
     * Check if initialized.
     */
    public boolean isInitialized()
    {
        return initialized;
    }

    /**
     * Reset the service.
     */
    public void reset()
    {
        taskMap.clear();
        taskNameMap.clear();
        initialized = false;
        log.info("CombatAchievementService reset");
    }

    /**
     * Get a specific task by ID for debugging.
     */
    public CombatAchievementTask getTask(int taskId)
    {
        return taskMap.get(taskId);
    }

    /**
     * Get all tasks (for debugging).
     */
    public Collection<CombatAchievementTask> getAllTasks()
    {
        return taskMap.values();
    }

    /**
     * Debug method: Find tasks for a specific boss name.
     */
    public void debugBossTasks(String bossNamePart)
    {
        log.info("=== DEBUG: Tasks containing '{}' ===", bossNamePart);

        int matchCount = 0;
        for (CombatAchievementTask task : taskMap.values())
        {
            if (task.name.toLowerCase().contains(bossNamePart.toLowerCase()))
            {
                matchCount++;
                int varpIndex = task.id / 32;
                int bitIndex = task.id % 32;
                boolean beyondKnownVarps = varpIndex >= VARP_IDS.length;

                log.info("Task ID {}: '{}' - {} - {} pts",
                        task.id, task.name, task.difficulty, task.points);
                log.info("  -> Varp Index: {}, Bit Index: {}, Beyond Known Varps: {}",
                        varpIndex, bitIndex, beyondKnownVarps);

                if (!beyondKnownVarps)
                {
                    try
                    {
                        int varpId = VARP_IDS[varpIndex];
                        int varpValue = client.getVarpValue(varpId);
                        boolean complete = (varpValue & (1 << bitIndex)) != 0;
                        log.info("  -> Varp {} (index {}) value: {}, Bit {} set: {}",
                                varpId, varpIndex, varpValue, bitIndex, complete);
                    }
                    catch (Exception e)
                    {
                        log.error("  -> Error reading varp: {}", e.getMessage());
                    }
                }
                else
                {
                    log.warn("  -> CANNOT CHECK: Task ID {} requires varp index {} but we only have {} varps!",
                            task.id, varpIndex, VARP_IDS.length);
                }
            }
        }

        log.info("Found {} tasks matching '{}'", matchCount, bossNamePart);

        if (matchCount == 0)
        {
            log.warn("No tasks found! Make sure wiki data is loaded. Try searching for partial names.");
            log.info("Total tasks in cache: {}", taskMap.size());
        }
    }

    /**
     * Debug method: Print detailed task status.
     */
    public void debugTaskStatus()
    {
        log.info("=== COMBAT ACHIEVEMENT DEBUG ===");
        log.info("Initialized: {}", initialized);
        log.info("Total tasks in map: {}", taskMap.size());
        log.info("Completed tasks: {}", getCompletedTaskCount());
        log.info("Current points: {}", getCurrentTierPoints());
        log.info("Current tier: {}", getCurrentTier());

        // Show tier thresholds from varbits
        log.info("=== Tier Thresholds (from varbits) ===");
        log.info("Easy: {} pts", getThresholdFromVarbit(VARBIT_THRESHOLD_EASY, FALLBACK_THRESHOLD_EASY));
        log.info("Medium: {} pts", getThresholdFromVarbit(VARBIT_THRESHOLD_MEDIUM, FALLBACK_THRESHOLD_MEDIUM));
        log.info("Hard: {} pts", getThresholdFromVarbit(VARBIT_THRESHOLD_HARD, FALLBACK_THRESHOLD_HARD));
        log.info("Elite: {} pts", getThresholdFromVarbit(VARBIT_THRESHOLD_ELITE, FALLBACK_THRESHOLD_ELITE));
        log.info("Master: {} pts", getThresholdFromVarbit(VARBIT_THRESHOLD_MASTER, FALLBACK_THRESHOLD_MASTER));
        log.info("Grandmaster: {} pts", getThresholdFromVarbit(VARBIT_THRESHOLD_GRANDMASTER, FALLBACK_THRESHOLD_GRANDMASTER));

        // Check first 10 tasks
        log.info("=== First 10 Tasks ===");
        taskMap.values().stream()
                .sorted(Comparator.comparingInt(t -> t.id))
                .limit(10)
                .forEach(task -> {
                    boolean complete = isTaskComplete(task.id);
                    log.info("Task {}: {} - {} - {} pts - Complete: {}",
                            task.id, task.name, task.difficulty, task.points, complete);
                });

        // Try calling getIncompleteTasks
        log.info("=== Calling getIncompleteTasks ===");
        List<RoutingAlgorithm.CombatAchievement> incomplete = getIncompleteTasks();
        log.info("Returned {} incomplete tasks", incomplete.size());

        if (!incomplete.isEmpty())
        {
            log.info("=== First 5 Incomplete Tasks ===");
            incomplete.stream()
                    .limit(5)
                    .forEach(task -> {
                        log.info("  - {} (monster: {})", task.getName(), task.getMonster());
                    });
        }

        log.info("=== END DEBUG ===");
    }

    /**
     * Map tier index to Difficulty enum.
     */
    private RoutingAlgorithm.Difficulty getDifficultyForTier(int tierIndex)
    {
        switch (tierIndex)
        {
            case 0: return RoutingAlgorithm.Difficulty.EASY;
            case 1: return RoutingAlgorithm.Difficulty.MEDIUM;
            case 2: return RoutingAlgorithm.Difficulty.HARD;
            case 3: return RoutingAlgorithm.Difficulty.ELITE;
            case 4: return RoutingAlgorithm.Difficulty.MASTER;
            case 5: return RoutingAlgorithm.Difficulty.GRANDMASTER;
            default: return RoutingAlgorithm.Difficulty.EASY;
        }
    }

    /**
     * Get point value for a difficulty tier.
     */
    private int getPointsForDifficulty(RoutingAlgorithm.Difficulty difficulty)
    {
        switch (difficulty)
        {
            case EASY: return 1;
            case MEDIUM: return 2;
            case HARD: return 3;
            case ELITE: return 4;
            case MASTER: return 5;
            case GRANDMASTER: return 6;
            default: return 1;
        }
    }

    /**
     * Internal task representation.
     * Made public for debugging access.
     */
    public static class CombatAchievementTask
    {
        public final int id;
        public final String name;
        public final String description;
        public final RoutingAlgorithm.Difficulty difficulty;
        public final int points;
        public final int structId;

        CombatAchievementTask(int id, String name, String description,
                              RoutingAlgorithm.Difficulty difficulty, int points, int structId)
        {
            this.id = id;
            this.name = name;
            this.description = description;
            this.difficulty = difficulty;
            this.points = points;
            this.structId = structId;
        }

        @Override
        public String toString()
        {
            return String.format("Task[id=%d, name='%s', difficulty=%s, points=%d]",
                    id, name, difficulty, points);
        }

        /**
         * Convert to RoutingAlgorithm.CombatAchievement.
         */
        public RoutingAlgorithm.CombatAchievement toRoutingTask()
        {
            // Try to extract monster name from task name
            String monsterName = extractMonsterName(name);

            return new RoutingAlgorithm.CombatAchievement(
                    id,
                    name,
                    monsterName,
                    difficulty,
                    RoutingAlgorithm.TaskType.MECHANICAL,
                    0.0,
                    description,
                    Collections.emptyList()
            );
        }

        /**
         * extract monster/boss name from task name.
         * Fall back if wiki is down
         */
        private String extractMonsterName(String taskName)
        {
            if (taskName == null || taskName.isEmpty())
            {
                return "Unknown";
            }

            String cleaned = taskName;

            // Strip common action prefixes
            String[] actionPrefixes = {
                    "Kill the ", "Kill ", "Defeat the ", "Defeat ",
                    "Complete the ", "Complete ", "Finish the ", "Finish ",
                    "Win ", "Successfully ", "Beat the ", "Beat ",
            };

            for (String prefix : actionPrefixes)
            {
                if (cleaned.startsWith(prefix))
                {
                    cleaned = cleaned.substring(prefix.length());
                    break;
                }
            }

            // Strip common task description suffixes
            String[] suffixes = {
                    " in a solo raid", " solo raid", " raid",
                    " in a private instance", " without taking damage",
                    " without leaving", " speedrun", " challenge",
            };

            for (String suffix : suffixes)
            {
                if (cleaned.toLowerCase().endsWith(suffix.toLowerCase()))
                {
                    cleaned = cleaned.substring(0, cleaned.length() - suffix.length());
                }
            }

            if (cleaned.trim().isEmpty())
            {
                return taskName;
            }

            return cleaned.trim();
        }
    }

}