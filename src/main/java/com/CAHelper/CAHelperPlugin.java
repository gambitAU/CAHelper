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
import net.runelite.client.ui.overlay.OverlayManager;
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
    @Inject
    private ManualCompletionManager manualCompletionManager;

    @Inject
    private OverlayManager overlayManager;

    private CAHelperOverlay overlay;
    private RoutingAlgorithm.CombatAchievement currentTask = null;
    private CombatAchievementPanel panel;
    private NavigationButton navButton;
    private boolean hasLoadedTasks = false;
    private long lastPanelRefresh = 0;
    private static final long PANEL_REFRESH_COOLDOWN_MS = 500; // Max 1 refresh per 500ms
    private int gameTicksSinceLogin = 0;

    @Override
    protected void startUp() throws Exception
    {
        log.info("=== CA Helper startUp() called ===");
        log.info("hasLoadedTasks = {}", hasLoadedTasks);
        manualCompletionManager.initialize();
        overlay = new CAHelperOverlay(this, config, manualCompletionManager);
        overlayManager.add(overlay);


        panel = new CombatAchievementPanel(this, combatAchievementService, routingAlgorithm, clientThread);

        enrichmentService.setPanelRefreshCallback(() -> {
            log.info("Panel refresh callback triggered!");
            panel.loadRecommendations();
        });

        routingAlgorithm.setConfig(config);

        navButton = NavigationButton.builder()
                .tooltip("CA Helper")
                .icon(getIcon())
                .panel(panel)
                .build();
        clientToolbar.addNavigation(navButton);

        log.info("=== CA Helper startUp() complete ===");
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        log.info("=== GameStateChanged: {} → hasLoadedTasks={} ===", event.getGameState(), hasLoadedTasks);

        if (event.getGameState() == GameState.LOGGED_IN)
        {
            gameTicksSinceLogin = 0; // reset counter

            if (hasLoadedTasks)
            {
                log.info("Already loaded - waiting for varps to transmit before refresh");
                // waiting for game ticks to refresh
            }
        }
    }


    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (client.getGameState() == GameState.LOGGED_IN && !hasLoadedTasks)
        {
            // check service has data
            if (combatAchievementService.isInitialized() && combatAchievementService.getTotalTaskCount() > 0)
            {
                log.info("Service already initialized with {} tasks - skipping reload",
                        combatAchievementService.getTotalTaskCount());
                hasLoadedTasks = true;
                return; // wait for varps before refresh
            }

            log.info("=== TRIGGERING FULL RELOAD (service not initialized) ===");

            try
            {
                log.info("Loading Combat Achievement tasks from cache...");
                combatAchievementService.initialize();

                int total = combatAchievementService.getTotalTaskCount();
                int completed = combatAchievementService.getCompletedTaskCount();
                log.info("Loaded {} tasks, {} completed", total, completed);

                log.info("Loading wiki data in background...");
                enrichmentService.loadWikiData();

                log.info("Panel will auto-refresh when wiki data finishes loading");

                hasLoadedTasks = true;
            }
            catch (Exception e)
            {
                log.error("Failed to load CA tasks", e);
            }
        }

        // on subsequent logins, wait 3 ticks for varps to transmit, then refresh
        if (hasLoadedTasks && gameTicksSinceLogin > 0 && gameTicksSinceLogin <= 3)
        {
            gameTicksSinceLogin++;

            if (gameTicksSinceLogin == 3)
            {
                log.info("5 ticks passed - varps should be loaded, refreshing panel");
                refreshPanelThrottled();
            }
        }
        else if (hasLoadedTasks && gameTicksSinceLogin == 0)
        {
            gameTicksSinceLogin = 1; //
        }
    }


    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        if (!combatAchievementService.isInitialized() || !hasLoadedTasks)
        {
            return;
        }

        // skip varp processing during first 3 ticks
        if (gameTicksSinceLogin > 0 && gameTicksSinceLogin < 3)
        {
            return;
        }

        int changedVarp = event.getVarpId();

        int[] CA_VARPS = {3116, 3117, 3118, 3119, 3120, 3121, 3122, 3123, 3124, 3125,
                3126, 3127, 3128, 3387, 3718, 3773, 3774, 4204, 4496, 4721};

        for (int varpId : CA_VARPS)
        {
            if (changedVarp == varpId)
            {
                refreshPanelThrottled();
                break;
            }
        }
    }

    private void refreshPanelThrottled()
    {
        long now = System.currentTimeMillis();
        long timeSinceLastRefresh = now - lastPanelRefresh;

        if (timeSinceLastRefresh < PANEL_REFRESH_COOLDOWN_MS)
        {
            return;
        }

        lastPanelRefresh = now;
        log.info("Refreshing panel (throttled)");

        clientThread.invokeLater(() -> {
            if (panel != null)
            {
                panel.loadRecommendations();
            }
        });
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!event.getGroup().equals("CAHelper"))
        {
            return;
        }

        log.info("Config changed: {} = {}", event.getKey(), event.getNewValue());

        clientThread.invokeLater(() -> {
            routingAlgorithm.setConfig(config);

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
            log.info("hasLoadedTasks: {}", hasLoadedTasks);
            log.info("Total tasks: {}", combatAchievementService.getTotalTaskCount());
            log.info("Completed tasks: {}", combatAchievementService.getCompletedTaskCount());
            log.info("Incomplete tasks: {}", combatAchievementService.getIncompleteTasks().size());
            log.info("Current tier: {}", combatAchievementService.getCurrentTier());
            log.info("Current points: {}", combatAchievementService.getCurrentTierPoints());
            log.info("Next tier: {}", combatAchievementService.getNextTierName());
            log.info("Points to next tier: {}", combatAchievementService.getPointsToNextTier());
            log.info("Wiki data loaded: {}", enrichmentService.isWikiDataLoaded());

            log.info("=== Config Settings ===");
            log.info("Min Difficulty: {}", config.minDifficulty());
            log.info("Max Difficulty: {}", config.maxDifficulty());
            log.info("Solo Content Only: {}", config.soloContentOnly());

            log.info("=== Points Breakdown by Difficulty ===");
            var breakdown = combatAchievementService.getPointsBreakdown();
            breakdown.forEach((diff, pts) -> log.info("  {}: {} points", diff, pts));

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
            log.info("=== Manual reload requested ===");
            log.info("Resetting hasLoadedTasks to false");
            hasLoadedTasks = false;

            combatAchievementService.reset();
            combatAchievementService.initialize();
            enrichmentService.clearCache();
            enrichmentService.loadWikiData();

            new Thread(() -> {
                try
                {
                    Thread.sleep(3000);
                    log.info("Refreshing panel after wiki load...");
                    clientThread.invokeLater(() -> panel.loadRecommendations());
                }
                catch (InterruptedException e)
                {
                    log.error("Reload interrupted", e);
                }
            }).start();

            log.info("Reload in progress (will refresh panel in a moment)...");
        }
        else if (command.equals("cawikimatch"))
        {
            System.out.println("\n=== WIKI MATCHING DEBUG ===");
            System.out.println("Wiki loaded: " + enrichmentService.isWikiDataLoaded());

            var cacheTasks = combatAchievementService.getIncompleteTasks();
            System.out.println("Testing " + cacheTasks.size() + " cache tasks\n");

            System.out.println("BEFORE ENRICHMENT (cache only):");
            cacheTasks.stream().limit(10).forEach(task -> {
                System.out.println("  Task: '" + task.getName() + "' -> Monster: '" + task.getMonster() + "'");
            });

            System.out.println("\nAFTER ENRICHMENT (with wiki):");
            var enriched = enrichmentService.getEnrichedIncompleteTasks();
            enriched.stream().limit(10).forEach(task -> {
                System.out.println("  Task: '" + task.getName() + "' -> Monster: '" + task.getMonster() + "'");
            });

            System.out.println("\nPANEL WILL SHOW THESE BOSSES:");
            var recs = routingAlgorithm.getRecommendations(15);
            recs.stream().limit(15).forEach(rec -> {
                System.out.println("  Boss: '" + rec.getBossName() + "' (" + rec.getTotalCount() + " tasks, "
                        + String.format("%.1f", rec.getCompletionPercentage()) + "% avg)");
            });

            System.out.println("=== END WIKI MATCH DEBUG ===\n");
        }
        else if (command.equals("casolo"))
        {
            log.info("=== TESTING SOLO FILTER ===");

            var allTasks = enrichmentService.getAllEnrichedTasks();
            log.info("Total tasks: {}", allTasks.size());

            routingAlgorithm.setConfig(config);
            var allRecs = routingAlgorithm.getRecommendations(Integer.MAX_VALUE);
            log.info("With solo filter OFF: {} bosses", allRecs.size());

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

    @Override
    protected void shutDown() throws Exception
    {
        log.info("=== CA Helper shutDown() called ===");

        if (combatAchievementService != null)
        {
            combatAchievementService.reset();
        }

        clientToolbar.removeNavigation(navButton);
        hasLoadedTasks = false; // Reset for next startup
        overlayManager.remove(overlay);
        log.info("=== CA Helper shutdown complete ===");
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
    public RoutingAlgorithm.CombatAchievement getCurrentTask()
    {
        return currentTask;
    }

    // Method to set current task (called when boss detail opens)
    public void setCurrentTask(RoutingAlgorithm.BossRecommendation boss)
    {
        if (boss == null)
        {
            currentTask = null;
            return;
        }

        currentTask = boss.getAvailableTasks().stream()
                .filter(task -> task.getCompletionRate() < 100)
                .filter(task -> !manualCompletionManager.isManuallyCompleted(task.getId()))
                .findFirst()
                .orElse(null);

        log.info("Set current task: {}", currentTask != null ? currentTask.getName() : "none");
    }
    public ManualCompletionManager getManualCompletionManager()
    {
        return manualCompletionManager;
    }
}
