package com.CAHelper;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;

@Slf4j
@PluginDescriptor(
        name = "CA Helper",
        description = "Routes combat achievements efficiently based on your progress",
        tags = {"combat", "achievements", "pvm", "routing", "CA","Combat Achievement"}
)
public class CAHelperPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private CAHelperConfig config;

    @Inject
    private WikiSyncService wikiSyncService;

    @Inject
    private RoutingAlgorithm routingAlgorithm;

    @Inject
    private WikiDataLoader wikiDataLoader;

    private CombatAchievementPanel panel;
    private NavigationButton navButton;

    @Override
    protected void startUp() throws Exception
    {
        log.info("Combat Achievement Router started!");

        panel = new CombatAchievementPanel(this, wikiSyncService, routingAlgorithm);

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/icon.png");

        navButton = NavigationButton.builder()
                .tooltip("CAHelper")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);

        loadPlayerProgress();
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("CAHelper stopped!");
        clientToolbar.removeNavigation(navButton);
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        // nothing extra
    }

    private void loadPlayerProgress()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        String username = client.getLocalPlayer().getName();
        if (username != null && config.useWikiSync())
        {
            log.info("Loading progress for: {}", username);
            wikiSyncService.fetchPlayerProgress(username, progress -> {
                // UI update must happen on EDT
                SwingUtilities.invokeLater(() -> panel.updateProgress(progress));
            });
        }
    }

    @Provides
    CAHelperConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(CAHelperConfig.class);
    }

    public void refreshProgress()
    {
        loadPlayerProgress();
    }
}
