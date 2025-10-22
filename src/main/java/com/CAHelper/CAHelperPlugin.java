package com.CAHelper;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;

@Slf4j
@PluginDescriptor(
        name = "CA Helper",
        description = "Routes combat achievements efficiently based on your progress",
        tags = {"combat", "achievements", "pvm", "routing", "CA","Combat Achievement"}
)
public class CAHelperPlugin extends Plugin
{
    @Inject
    private ClientThread clientThread;

    @Inject
    private Client client;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private CAHelperConfig config;

    @Inject
    private RoutingAlgorithm routingAlgorithm;

    @Inject
    private CombatAchievementService combatAchievementService;

    @Inject
    private CombatAchievementEnrichmentService enrichmentService;

    private CombatAchievementPanel panel;
    private NavigationButton navButton;
    private boolean hasLoadedTasks = false;

    @Override
    protected void startUp() throws Exception
    {
        log.info("CA Helper started!");

        // Setup panel ONLY - do NOT initialize service here
        panel = new CombatAchievementPanel(this, combatAchievementService, routingAlgorithm, clientThread);

        // Set panel refresh callback so wiki data can auto-refresh it
        enrichmentService.setPanelRefreshCallback(() -> {
            log.info("Panel refresh callback triggered!");
            panel.loadRecommendations();
        });

        // Set config on routing algorithm for filtering
        routingAlgorithm.setConfig(config);

        navButton = NavigationButton.builder()
                .tooltip("CA Helper")
                .icon(getIcon())
                .panel(panel)
                .build();
        clientToolbar.addNavigation(navButton);

        // DO NOT initialize service here - it will happen in onGameTick
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            // Reset flag so we reinitialize on login
            hasLoadedTasks = false;
        }
        else if (event.getGameState() == GameState.HOPPING ||
                event.getGameState() == GameState.LOGGING_IN)
        {
            hasLoadedTasks = false;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        // Load tasks ONLY after login on the first game tick
        // This ensures varps are transmitted and we're on client thread
        if (client.getGameState() == GameState.LOGGED_IN && !hasLoadedTasks)
        {
            try
            {
                log.info("Loading Combat Achievement tasks from cache...");
                combatAchievementService.initialize();

                int total = combatAchievementService.getTotalTaskCount();
                int completed = combatAchievementService.getCompletedTaskCount();
                log.info("Loaded {} tasks, {} completed", total, completed);

                // Load wiki data in background (will auto-refresh panel when done)
                log.info("Loading wiki data in background...");
                enrichmentService.loadWikiData();

                log.info("Panel will auto-refresh when wiki data finishes loading (~3 seconds)");

                hasLoadedTasks = true;
            }
            catch (Exception e)
            {
                log.error("Failed to load CA tasks", e);
            }
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        // Only process if initialized
        if (!combatAchievementService.isInitialized())
        {
            return;
        }

        // Check if it's one of the CA completion varps
        int changedVarp = event.getVarpId();

        // CA varps (from VARP_IDS array in CombatAchievementService)
        int[] CA_VARPS = {3116, 3117, 3118, 3119, 3120, 3121, 3122, 3123, 3124, 3125,
                3126, 3127, 3128, 3387, 3718, 3773, 3774, 4204, 4496, 4721};

        for (int varpId : CA_VARPS)
        {
            if (changedVarp == varpId)
            {
                log.info("Combat Achievement varp {} changed - refreshing panel", changedVarp);

                // Refresh panel on client thread after a short delay
                // (gives time for all related varps to update)
                clientThread.invokeLater(() -> {
                    if (panel != null)
                    {
                        panel.loadRecommendations();
                    }
                });

                break;
            }
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!event.getGroup().equals("CAHelper"))
        {
            return;
        }

        // Config changed - refresh routing algorithm and panel
        log.info("Config changed: {} = {}", event.getKey(), event.getNewValue());

        clientThread.invokeLater(() -> {
            // Update routing algorithm with new config
            routingAlgorithm.setConfig(config);

            // Refresh panel
            if (panel != null)
            {
                log.info("Refreshing panel due to config change");
                panel.loadRecommendations();
            }
        });
    }

    @Subscribe
    public void onCommandExecuted(CommandExecuted commandExecuted)
    {
        String command = commandExecuted.getCommand();

        if (command.equals("cadebug"))
        {
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                log.warn("Must be logged in to debug");
                return;
            }

            log.info("=== COMBAT ACHIEVEMENT DEBUG ===");
            log.info("Total tasks: {}", combatAchievementService.getTotalTaskCount());
            log.info("Completed tasks: {}", combatAchievementService.getCompletedTaskCount());
            log.info("Incomplete tasks: {}", combatAchievementService.getIncompleteTasks().size());
            log.info("Current tier: {}", combatAchievementService.getCurrentTier());
            log.info("Current points: {}", combatAchievementService.getCurrentTierPoints());
            log.info("Next tier: {}", combatAchievementService.getNextTierName());
            log.info("Points to next tier: {}", combatAchievementService.getPointsToNextTier());
            log.info("Wiki data loaded: {}", enrichmentService.isWikiDataLoaded());

            // Debug config
            log.info("=== Config Settings ===");
            log.info("Min Difficulty: {}", config.minDifficulty());
            log.info("Max Difficulty: {}", config.maxDifficulty());
            log.info("Solo Content Only: {}", config.soloContentOnly());

            // Debug points breakdown by difficulty
            log.info("=== Points Breakdown by Difficulty ===");
            var breakdown = combatAchievementService.getPointsBreakdown();
            breakdown.forEach((diff, pts) -> log.info("  {}: {} points", diff, pts));

            // Debug what panel is actually showing
            log.info("=== Testing Panel Data ===");
            var recs = routingAlgorithm.getRecommendations(10);
            log.info("Panel would show {} bosses:", recs.size());
            recs.stream().limit(10).forEach(rec ->
                    log.info("  Boss: '{}' ({} tasks, {:.1f}% avg completion)",
                            rec.getBossName(), rec.getTotalCount(), rec.getCompletionPercentage())
            );

            log.info("=== END DEBUG ===");
        }
        else if (command.equals("careload"))
        {
            log.info("Reloading Combat Achievement service...");
            combatAchievementService.reset();
            combatAchievementService.initialize();
            enrichmentService.loadWikiData();

            // Wait a bit for wiki to load, then refresh panel
            new Thread(() -> {
                try
                {
                    Thread.sleep(3000); // Wait 3 seconds
                    log.info("Refreshing panel after wiki load...");
                    clientThread.invokeLater(() -> panel.loadRecommendations());
                }
                catch (InterruptedException e)
                {
                    log.error("Reload interrupted", e);
                }
            }).start();

            log.info("Reload in progress (will refresh panel in 3 seconds)...");
        }
        else if (command.equals("cawikimatch"))
        {
            System.out.println("\n=== WIKI MATCHING DEBUG ===");
            System.out.println("Wiki loaded: " + enrichmentService.isWikiDataLoaded());

            // Test with first 10 incomplete tasks
            var cacheTasks = combatAchievementService.getIncompleteTasks();
            System.out.println("Testing " + cacheTasks.size() + " cache tasks\n");

            System.out.println("BEFORE ENRICHMENT (cache only):");
            cacheTasks.stream().limit(10).forEach(task -> {
                System.out.println("  Task: '" + task.getName() + "' -> Monster: '" + task.getMonster() + "'");
            });

            // Now test enriched
            System.out.println("\nAFTER ENRICHMENT (with wiki):");
            var enriched = enrichmentService.getEnrichedIncompleteTasks();
            enriched.stream().limit(10).forEach(task -> {
                System.out.println("  Task: '" + task.getName() + "' -> Monster: '" + task.getMonster() + "'");
            });

            // Show what panel will display
            System.out.println("\nPANEL WILL SHOW THESE BOSSES:");
            var recs = routingAlgorithm.getRecommendations(15);
            recs.stream().limit(15).forEach(rec -> {
                System.out.println("  Boss: '" + rec.getBossName() + "' (" + rec.getTotalCount() + " tasks, "
                        + String.format("%.1f", rec.getCompletionPercentage()) + "% avg)");
            });

            System.out.println("=== END WIKI MATCH DEBUG ===\n");
        }
        else if (command.equals("cadoom"))
        {
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                log.warn("Must be logged in to debug");
                return;
            }

            log.info("=== CADOOM DEBUG START ===");
            clientThread.invokeLater(() -> {
                combatAchievementService.debugBossTasks("mokhaiotl");
                combatAchievementService.debugBossTasks("doom");
            });
            log.info("=== CADOOM DEBUG END (results will appear above if applicable) ===");
        }
        else if (command.equals("casolo"))
        {
            log.info("=== TESTING SOLO FILTER ===");

            // Get all tasks
            var allTasks = enrichmentService.getAllEnrichedTasks();
            log.info("Total tasks: {}", allTasks.size());

            // Test with solo filter OFF
            routingAlgorithm.setConfig(config);
            var allRecs = routingAlgorithm.getRecommendations(Integer.MAX_VALUE);
            log.info("With solo filter OFF: {} bosses", allRecs.size());

            // Show sample group tasks that would be filtered
            log.info("\n=== Sample Group Content Tasks ===");
            allTasks.stream()
                    .filter(t -> t.getName().toLowerCase().contains("duo")
                            || t.getName().toLowerCase().contains("trio")
                            || t.getName().toLowerCase().contains("scale")
                            || (t.getDescription() != null && t.getDescription().toLowerCase().contains("group")))
                    .limit(10)
                    .forEach(t -> log.info("  '{}' - {}", t.getName(), t.getMonster()));

            log.info("=== END SOLO FILTER TEST ===");
        }
    }

    public void debugDoom()
    {
        clientThread.invokeLater(() -> {
            combatAchievementService.debugBossTasks("mokhaiotl");
            combatAchievementService.debugBossTasks("doom");
        });
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("CA Helper stopped!");

        if (combatAchievementService != null)
        {
            combatAchievementService.reset();
        }

        clientToolbar.removeNavigation(navButton);
        hasLoadedTasks = false;
    }

    private BufferedImage getIcon()
    {
        return ImageUtil.loadImageResource(getClass(), "/icon.png");
    }

    @Provides
    CAHelperConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(CAHelperConfig.class);
    }
}