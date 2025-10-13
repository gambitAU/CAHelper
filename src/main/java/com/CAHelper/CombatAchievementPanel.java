package com.CAHelper;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.PluginErrorPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

@Slf4j
public class CombatAchievementPanel extends PluginPanel
{
    private final CAHelperPlugin plugin;
    private final WikiSyncService wikiSyncService;
    private final RoutingAlgorithm routingAlgorithm;

    private final JLabel progressLabel;
    private final JPanel tasksContainer;
    private final JButton refreshButton;
    private final PluginErrorPanel errorPanel;

    public CombatAchievementPanel(
            CAHelperPlugin plugin,
            WikiSyncService wikiSyncService,
            RoutingAlgorithm routingAlgorithm)
    {
        this.plugin = plugin;
        this.wikiSyncService = wikiSyncService;
        this.routingAlgorithm = routingAlgorithm;

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(new EmptyBorder(0, 0, 10, 0));

        progressLabel = new JLabel("Loading progress...");
        progressLabel.setFont(new Font("Dialog", Font.BOLD, 14));
        headerPanel.add(progressLabel, BorderLayout.NORTH);

        refreshButton = new JButton("Refresh Progress");
        refreshButton.addActionListener(e -> plugin.refreshProgress());
        headerPanel.add(refreshButton, BorderLayout.SOUTH);

        add(headerPanel, BorderLayout.NORTH);

        tasksContainer = new JPanel();
        tasksContainer.setLayout(new BoxLayout(tasksContainer, BoxLayout.Y_AXIS));

        JScrollPane scrollPane = new JScrollPane(tasksContainer);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scrollPane, BorderLayout.CENTER);

        errorPanel = new PluginErrorPanel();
        errorPanel.setVisible(false);
        add(errorPanel, BorderLayout.SOUTH);
    }

    public void updateProgress(WikiSyncService.PlayerProgress progress)
    {
        SwingUtilities.invokeLater(() -> {
            if (progress == null || progress.getCompletedCount() == 0)
            {
                progressLabel.setText("No progress found");
                showError("Could not load progress. Make sure you've synced on the Wiki!");
                return;
            }

            hideError();
            progressLabel.setText(String.format("Completed: %d / 500", progress.getCompletedCount()));

            tasksContainer.removeAll();

            List<RoutingAlgorithm.BossRecommendation> recommendations = routingAlgorithm.getRecommendations(progress);
            displayRecommendations(recommendations);

            tasksContainer.revalidate();
            tasksContainer.repaint();
        });
    }

    private void displayRecommendations(List<RoutingAlgorithm.BossRecommendation> recommendations)
    {
        for (RoutingAlgorithm.BossRecommendation rec : recommendations)
        {
            JPanel bossHeader = createBossHeader(rec);
            tasksContainer.add(bossHeader);
            tasksContainer.add(Box.createVerticalStrut(5));

            List<RoutingAlgorithm.CombatAchievement> topTasks = rec.getTopEasiestTasks(5);
            for (RoutingAlgorithm.CombatAchievement task : topTasks)
            {
                JPanel taskPanel = createTaskPanel(
                        task.getName(),
                        task.getDifficulty().toString(),
                        String.format("%d pts (%.1f%% complete)",
                                task.getPoints(),
                                task.getCompletionRate())
                );
                tasksContainer.add(taskPanel);
                tasksContainer.add(Box.createVerticalStrut(3));
            }

            tasksContainer.add(Box.createVerticalStrut(10));
        }
    }

    private JPanel createBossHeader(RoutingAlgorithm.BossRecommendation rec)
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 130, 180), 2),
                new EmptyBorder(8, 8, 8, 8)
        ));
        panel.setBackground(new Color(240, 248, 255));

        JLabel nameLabel = new JLabel(String.format("<html><b>%s</b></html>", rec.getBossName()));
        nameLabel.setFont(new Font("Dialog", Font.BOLD, 16));
        panel.add(nameLabel, BorderLayout.NORTH);

        JLabel statsLabel = new JLabel(String.format(
                "<html><font color='gray'>%d tasks • %.1f%% avg • %d points • Score: %.0f</font></html>",
                rec.getAvailableTasks().size(),
                rec.getAvgCompletionRate(),
                rec.getTotalPoints(),
                rec.getEfficiencyScore()
        ));
        panel.add(statsLabel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createTaskPanel(String name, String difficulty, String points)
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                new EmptyBorder(5, 5, 5, 5)
        ));

        JLabel nameLabel = new JLabel("<html><b>" + name + "</b></html>");
        panel.add(nameLabel, BorderLayout.NORTH);

        JLabel infoLabel = new JLabel(String.format(
                "<html><font color='gray'>%s • %s</font></html>", difficulty, points));
        panel.add(infoLabel, BorderLayout.SOUTH);

        return panel;
    }

    private void showError(String message)
    {
        errorPanel.setContent("Error", message);
        errorPanel.setVisible(true);
    }

    private void hideError()
    {
        errorPanel.setVisible(false);
    }
}

