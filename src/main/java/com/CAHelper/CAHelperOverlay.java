package com.CAHelper;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

@Slf4j
public class CAHelperOverlay extends OverlayPanel
{
    private final CAHelperPlugin plugin;
    private final CAHelperConfig config;
    private final ManualCompletionManager manualCompletionManager;

    @Inject
    public CAHelperOverlay(CAHelperPlugin plugin, CAHelperConfig config, ManualCompletionManager manualCompletionManager)
    {
        this.plugin = plugin;
        this.config = config;
        this.manualCompletionManager = manualCompletionManager;

        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showOverlay())
        {
            return null;
        }

        RoutingAlgorithm.CombatAchievement currentTask = plugin.getCurrentTask();

        if (currentTask == null)
        {
            return null;
        }

        // Title
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Combat Achievement")
                .color(Color.ORANGE)
                .build());

        // Task name
        panelComponent.getChildren().add(LineComponent.builder()
                .left(currentTask.getName())
                .leftColor(Color.WHITE)
                .build());

        // Difficulty and points
        panelComponent.getChildren().add(LineComponent.builder()
                .left(currentTask.getDifficulty().toString())
                .right(currentTask.getPoints() + " pts")
                .leftColor(getDifficultyColor(currentTask.getDifficulty()))
                .rightColor(Color.YELLOW)
                .build());

        // Description
        if (currentTask.getDescription() != null && !currentTask.getDescription().isEmpty())
        {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left(currentTask.getDescription())
                    .leftColor(Color.GRAY)
                    .build());
        }

        return super.render(graphics);
    }

    private Color getDifficultyColor(RoutingAlgorithm.Difficulty difficulty)
    {
        switch (difficulty)
        {
            case EASY: return new Color(100, 255, 100);
            case MEDIUM: return new Color(100, 150, 255);
            case HARD: return new Color(255, 150, 100);
            case ELITE: return new Color(255, 100, 100);
            case MASTER: return new Color(200, 100, 255);
            case GRANDMASTER: return new Color(255, 215, 0);
            default: return Color.GRAY;
        }
    }
}