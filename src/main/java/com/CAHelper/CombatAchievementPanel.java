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
            // Parent panel for this boss
            JPanel bossPanel = new JPanel();
            bossPanel.setLayout(new BoxLayout(bossPanel, BoxLayout.Y_AXIS));
            bossPanel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color.LIGHT_GRAY),
                    new EmptyBorder(5, 5, 5, 5)
            ));

            // Header
            JPanel headerPanel = createBossHeaderWithToggle(rec, bossPanel);
            bossPanel.add(headerPanel);

            // Task list
            JPanel taskListPanel = new JPanel();
            taskListPanel.setLayout(new BoxLayout(taskListPanel, BoxLayout.Y_AXIS));
            taskListPanel.setBorder(new EmptyBorder(5, 20, 5, 5));

            List<RoutingAlgorithm.CombatAchievement> topTasks = rec.getTopEasiestTasks(5);
            for (RoutingAlgorithm.CombatAchievement task : topTasks)
            {
                JLabel taskLabel = new JLabel(String.format(
                        "• %s (%s • %d pts)",
                        task.getName(),
                        task.getDifficulty(),
                        task.getPoints()
                ));
                taskListPanel.add(taskLabel);
            }

            // Store for toggle access
            taskListPanel.setName("taskListPanel");
            bossPanel.putClientProperty("taskListPanel", taskListPanel);

            bossPanel.add(taskListPanel);
            tasksContainer.add(bossPanel);
            tasksContainer.add(Box.createVerticalStrut(8));
        }
    }

    private JPanel createBossHeaderWithToggle(RoutingAlgorithm.BossRecommendation rec, JPanel parentBossPanel)
    {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(245, 245, 245));
        headerPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        // Toggle button
        JToggleButton toggleButton = new JToggleButton("▼");
        toggleButton.setBorderPainted(false);
        toggleButton.setContentAreaFilled(false);
        toggleButton.setFocusPainted(false);
        toggleButton.setPreferredSize(new Dimension(30, 20));

        // Boss name
        JLabel nameLabel = new JLabel(rec.getBossName());
        nameLabel.setFont(new Font("Dialog", Font.BOLD, 14));

        // Stats
        String statsText = String.format("[%d tasks • %d pts]",
                rec.getAvailableTasks().size(),
                rec.getTotalPoints());

        JLabel statsLabel = new JLabel(statsText);
        statsLabel.setFont(new Font("Dialog", Font.PLAIN, 12));
        statsLabel.setForeground(Color.GRAY);

        headerPanel.add(toggleButton, BorderLayout.WEST);
        headerPanel.add(nameLabel, BorderLayout.CENTER);
        headerPanel.add(statsLabel, BorderLayout.EAST);

        // Toggle action
        toggleButton.setSelected(true);
        toggleButton.addActionListener(e -> {
            JPanel taskListPanel = (JPanel) parentBossPanel.getClientProperty("taskListPanel");
            boolean expanded = toggleButton.isSelected();
            toggleButton.setText(expanded ? "▼" : "►");

            if (taskListPanel != null)
            {
                taskListPanel.setVisible(expanded);
                taskListPanel.revalidate();
                taskListPanel.repaint();
            }
        });

        return headerPanel;
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
