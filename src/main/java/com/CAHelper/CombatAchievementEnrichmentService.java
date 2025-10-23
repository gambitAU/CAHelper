package com.CAHelper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class CombatAchievementEnrichmentService
{
    private static final String CACHE_FILE = "ca-wiki-cache.json"; // Store data in cache to limit calls to wiki
    private static final long CACHE_EXPIRY_DAYS = 7; // Refresh wiki data weekly in line with RS updates

    @Inject
    private CombatAchievementService combatAchievementService;

    @Inject
    private WikiDataLoader wikiDataLoader;

    @Inject
    private ClientThread clientThread;

    @Inject
    private Gson gson;

    private Map<String, RoutingAlgorithm.CombatAchievement> wikiTasksByName = new HashMap<>();
    private boolean wikiDataLoaded = false;
    private Runnable panelRefreshCallback = null;

    public void setPanelRefreshCallback(Runnable callback)
    {
        this.panelRefreshCallback = callback;
        log.info("Panel refresh callback set");
    }

    public void loadWikiData()
    {
        if (wikiDataLoaded)
        {
            log.info("Wiki data already loaded");

            if (panelRefreshCallback != null)
            {
                log.info("Triggering panel refresh callback (data already loaded)");
                clientThread.invokeLater(panelRefreshCallback);
            }

            return;
        }

        log.info("Attempting to load wiki data from cache...");

        // Try loading from cache first
        if (loadFromCache())
        {
            log.info("Wiki data loaded from cache (fast)");
            wikiDataLoaded = true;

            if (panelRefreshCallback != null)
            {
                clientThread.invokeLater(panelRefreshCallback);
            }
            return;
        }

        // Cache miss or expired - fetch fresh data
        log.info("Cache miss or expired - loading fresh wiki data...");
        loadWikiDataFresh();
    }

    private void loadWikiDataFresh()
    {
        new Thread(() -> {
            try
            {
                List<RoutingAlgorithm.CombatAchievement> wikiTasks = wikiDataLoader.loadAllAchievements();

                wikiTasksByName = wikiTasks.stream()
                        .collect(Collectors.toMap(
                                task -> normalizeTaskName(task.getName()),
                                task -> task,
                                (existing, replacement) -> existing
                        ));

                log.info("Wiki data loaded: {} tasks", wikiTasksByName.size());

                // Save to cache
                saveToCache(wikiTasks);

                wikiDataLoaded = true;

                if (panelRefreshCallback != null)
                {
                    clientThread.invokeLater(panelRefreshCallback);
                }
            }
            catch (Exception e)
            {
                log.error("Failed to load fresh wiki data", e);
                wikiDataLoaded = false;
            }
        }, "WikiDataLoader").start();
    }

    private boolean loadFromCache()
    {
        try
        {
            File cacheFile = new File(RuneLite.RUNELITE_DIR, CACHE_FILE);

            if (!cacheFile.exists())
            {
                log.debug("Cache file doesn't exist");
                return false;
            }

            // Check cache age
            long ageMs = System.currentTimeMillis() - cacheFile.lastModified();
            long ageDays = TimeUnit.MILLISECONDS.toDays(ageMs);

            if (ageDays > CACHE_EXPIRY_DAYS)
            {
                log.info("Cache expired ({} days old), fetching fresh data", ageDays);
                return false;
            }

            log.info("Loading from cache ({} days old)", ageDays);

            // Load and deserialize
            Type listType = new TypeToken<List<RoutingAlgorithm.CombatAchievement>>(){}.getType();
            List<RoutingAlgorithm.CombatAchievement> wikiTasks;

            try (FileReader reader = new FileReader(cacheFile))
            {
                wikiTasks = gson.fromJson(reader, listType);
            }

            if (wikiTasks == null || wikiTasks.isEmpty())
            {
                log.warn("Cache file empty or invalid");
                return false;
            }

            // Index by name
            wikiTasksByName = wikiTasks.stream()
                    .collect(Collectors.toMap(
                            task -> normalizeTaskName(task.getName()),
                            task -> task,
                            (existing, replacement) -> existing
                    ));

            log.info("Loaded {} tasks from cache", wikiTasksByName.size());
            return true;
        }
        catch (Exception e)
        {
            log.error("Failed to load from cache", e);
            return false;
        }
    }

    private void saveToCache(List<RoutingAlgorithm.CombatAchievement> wikiTasks)
    {
        try
        {
            File cacheFile = new File(RuneLite.RUNELITE_DIR, CACHE_FILE);

            try (FileWriter writer = new FileWriter(cacheFile))
            {
                gson.toJson(wikiTasks, writer);
            }

            log.info("Saved {} tasks to cache", wikiTasks.size());
        }
        catch (Exception e)
        {
            log.error("Failed to save cache", e);
        }
    }

    public List<RoutingAlgorithm.CombatAchievement> getEnrichedIncompleteTasks()
    {
        List<RoutingAlgorithm.CombatAchievement> cacheTasks = combatAchievementService.getIncompleteTasks();

        if (!wikiDataLoaded)
        {
            log.warn("Wiki data not loaded yet - returning cache-only tasks");
            return cacheTasks;
        }

        List<RoutingAlgorithm.CombatAchievement> enriched = new ArrayList<>();

        for (RoutingAlgorithm.CombatAchievement cacheTask : cacheTasks)
        {
            String normalizedName = normalizeTaskName(cacheTask.getName());
            RoutingAlgorithm.CombatAchievement wikiTask = wikiTasksByName.get(normalizedName);

            if (wikiTask != null)
            {
                enriched.add(new RoutingAlgorithm.CombatAchievement(
                        cacheTask.getId(),
                        wikiTask.getName(),
                        wikiTask.getMonster(),
                        cacheTask.getDifficulty(),
                        wikiTask.getType(),
                        0.0,
                        wikiTask.getDescription(),
                        cacheTask.getPrerequisiteIds()
                ));
            }
            else
            {
                enriched.add(cacheTask);
            }
        }

        return enriched;
    }

    public List<RoutingAlgorithm.CombatAchievement> getAllEnrichedTasks()
    {
        if (!combatAchievementService.isInitialized())
        {
            return Collections.emptyList();
        }

        List<RoutingAlgorithm.CombatAchievement> allTasks = new ArrayList<>();
        Collection<CombatAchievementService.CombatAchievementTask> cacheTasks =
                combatAchievementService.getAllTasks();

        for (CombatAchievementService.CombatAchievementTask cacheTask : cacheTasks)
        {
            boolean complete = combatAchievementService.isTaskComplete(cacheTask.id);
            double completionRate = complete ? 100.0 : 0.0;

            String normalizedName = normalizeTaskName(cacheTask.name);
            RoutingAlgorithm.CombatAchievement wikiTask = wikiTasksByName.get(normalizedName);

            RoutingAlgorithm.CombatAchievement enrichedTask;

            if (wikiTask != null)
            {
                enrichedTask = new RoutingAlgorithm.CombatAchievement(
                        cacheTask.id,
                        wikiTask.getName(),
                        wikiTask.getMonster(),
                        cacheTask.difficulty,
                        wikiTask.getType(),
                        completionRate,
                        wikiTask.getDescription(),
                        Collections.emptyList()
                );
            }
            else
            {
                enrichedTask = cacheTask.toRoutingTask();
                enrichedTask = new RoutingAlgorithm.CombatAchievement(
                        enrichedTask.getId(),
                        enrichedTask.getName(),
                        enrichedTask.getMonster(),
                        enrichedTask.getDifficulty(),
                        enrichedTask.getType(),
                        completionRate,
                        enrichedTask.getDescription(),
                        enrichedTask.getPrerequisiteIds()
                );
            }

            allTasks.add(enrichedTask);
        }

        return allTasks;
    }

    private String normalizeTaskName(String name)
    {
        if (name == null)
        {
            return "";
        }

        return name.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public boolean isWikiDataLoaded()
    {
        return wikiDataLoaded;
    }

    public void clearCache()
    {
        File cacheFile = new File(RuneLite.RUNELITE_DIR, CACHE_FILE);
        if (cacheFile.exists())
        {
            cacheFile.delete();
            log.info("Cache cleared");
        }
        wikiDataLoaded = false;
        wikiTasksByName.clear();
    }
}