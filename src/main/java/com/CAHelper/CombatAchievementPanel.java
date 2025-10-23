package com.CAHelper;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Slf4j
public class CombatAchievementPanel extends PluginPanel
{
    private final CAHelperPlugin plugin;
    private final CombatAchievementService combatAchievementService;
    private final RoutingAlgorithm routingAlgorithm;
    private final ClientThread clientThread;

    private final JLabel progressLabel;
    private final JProgressBar progressBar;
    private final CardLayout cardLayout;
    private final JPanel cardPanel;
    private final JPanel bossList;
    private final JPanel bossDetail;
    private RoutingAlgorithm.BossRecommendation selectedBoss;
    private final Map<String, Boolean> taskExpandedState = new HashMap<>();

    public CombatAchievementPanel(
            CAHelperPlugin plugin,
            CombatAchievementService combatAchievementService,
            RoutingAlgorithm routingAlgorithm,
            ClientThread clientThread)
    {
        this.plugin = plugin;
        this.combatAchievementService = combatAchievementService;
        this.routingAlgorithm = routingAlgorithm;
        this.clientThread = clientThread;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Header with progress in a box
        JPanel topContainer = new JPanel();
        topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
        topContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Social buttons
        JPanel socialPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));
        socialPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        socialPanel.setBorder(new EmptyBorder(5, 5, 0, 5));

        JButton discordBtn = createSocialButton("Discord", "https://discord.gg/cvD4er93f7");
        JButton githubBtn = createSocialButton("GitHub", "https://github.com/gambitAU/CAHelper");
        JButton kofiBtn = createSocialButton("Ko-fi", "https://ko-fi.com/gambitau");

        socialPanel.add(discordBtn);
        socialPanel.add(githubBtn);
        socialPanel.add(kofiBtn);

        topContainer.add(socialPanel);

        JPanel headerPanel = new JPanel();
        headerPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        headerPanel.setLayout(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Progress box containing label and bar
        JPanel progressBox = new JPanel();
        progressBox.setLayout(new BoxLayout(progressBox, BoxLayout.Y_AXIS));
        progressBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        progressBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                new EmptyBorder(8, 8, 8, 8)
        ));

        progressLabel = new JLabel("Loading...");
        progressLabel.setFont(FontManager.getRunescapeBoldFont());
        progressLabel.setForeground(Color.WHITE);
        progressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        progressBox.add(progressLabel);

        progressBox.add(Box.createVerticalStrut(5));

        // Progress bar
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setFont(FontManager.getRunescapeSmallFont());
        progressBar.setForeground(ColorScheme.BRAND_ORANGE);
        progressBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        progressBox.add(progressBar);

        headerPanel.add(progressBox, BorderLayout.CENTER);
        topContainer.add(headerPanel);
        add(topContainer, BorderLayout.NORTH);

        // CardLayout for switching between list and detail
        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Boss list view
        bossList = createBossListView();
        cardPanel.add(bossList, "list");

        // Boss detail view
        bossDetail = new JPanel();
        bossDetail.setLayout(new BorderLayout());
        bossDetail.setBackground(ColorScheme.DARK_GRAY_COLOR);
        cardPanel.add(bossDetail, "detail");

        add(cardPanel, BorderLayout.CENTER);

        // Show list by default
        cardLayout.show(cardPanel, "list");
    }

    private JPanel createBossListView()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setBorder(new EmptyBorder(5, 0, 0, 0));
        return panel;
    }

    public void loadRecommendations()
    {
        bossList.removeAll();

        try
        {
            List<RoutingAlgorithm.BossRecommendation> allRecommendations = routingAlgorithm.getRecommendations(Integer.MAX_VALUE);

            // Show all Bosses at this point, mostly for debug but also dopamine of seeing boss greenlogged
            List<RoutingAlgorithm.BossRecommendation> recommendations = allRecommendations;

            if (recommendations.isEmpty())
            {
                progressLabel.setText("No tasks found");
                progressLabel.setForeground(Color.GRAY);
                progressBar.setValue(0);
                progressBar.setString("0%");
                bossList.revalidate();
                return;
            }

            int pointsToNext = combatAchievementService.getPointsToNextTier();
            String nextTier = combatAchievementService.getNextTierName();

            if (pointsToNext <= 0)
            {
                progressLabel.setText("You have reached GRANDMASTER!");
                progressBar.setValue(100);
                progressBar.setString("Complete!");
            }
            else
            {
                progressLabel.setText(String.format("%d points to %s", pointsToNext, nextTier));

                // Calculate progress percentage
                int currentPoints = combatAchievementService.getCurrentTierPoints();
                int currentTierThreshold = combatAchievementService.getCurrentTierThreshold();

                // How many points we've earned in the current tier
                int progressInTier = currentPoints - currentTierThreshold;

                // Total points needed for the entire tier
                int totalNeededForTier = progressInTier + pointsToNext;

                int percentage = totalNeededForTier > 0 ? (progressInTier * 100) / totalNeededForTier : 0;

                log.debug("Progress calculation: currentPoints={}, currentTierThreshold={}, progressInTier={}, pointsToNext={}, totalNeededForTier={}, percentage={}",
                        currentPoints, currentTierThreshold, progressInTier, pointsToNext, totalNeededForTier, percentage);

                // Clamp between 0-100
                percentage = Math.max(0, Math.min(100, percentage));

                progressBar.setValue(percentage);
                progressBar.setString(percentage + "%");
            }
            progressLabel.setForeground(Color.WHITE);

            for (RoutingAlgorithm.BossRecommendation rec : recommendations)
            {
                JPanel bossRow = createBossListRow(rec);
                bossList.add(bossRow);
            }

            bossList.add(Box.createVerticalGlue());
            bossList.revalidate();
        }
        catch (Exception e)
        {
            log.error("Failed to load recommendations", e);
            progressLabel.setText("Error loading tasks");
            progressLabel.setForeground(Color.RED);
        }
    }

    private JPanel createBossListRow(RoutingAlgorithm.BossRecommendation rec)
    {
        int completionPercentage = calculateBossCompletionPercentage(rec);

        // Color based on completion: green if 100%, orange if partial, gray if 0%
        Color textColor;
        if (completionPercentage >= 100)
        {
            textColor = new Color(0, 200, 0); // Green for complete
        }
        else if (completionPercentage > 0)
        {
            textColor = ColorScheme.BRAND_ORANGE; // Orange for partial
        }
        else
        {
            textColor = Color.GRAY; // Gray for not started
        }

        JPanel row = new JPanel();
        row.setLayout(new BorderLayout(5, 0));
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setBorder(new EmptyBorder(0, 7, 5, 7));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        row.setCursor(new Cursor(Cursor.HAND_CURSOR));

        // Left: Boss name in box with wrapping
        JPanel nameBox = new JPanel();
        nameBox.setLayout(new BorderLayout());
        nameBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        nameBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                new EmptyBorder(8, 10, 8, 10)
        ));

        JLabel bossLabel = new JLabel("<html>" + rec.getBossName() + "</html>");
        bossLabel.setFont(FontManager.getRunescapeBoldFont());
        bossLabel.setForeground(textColor);
        nameBox.add(bossLabel, BorderLayout.CENTER);

        // Right: Arrow in box
        JPanel arrowBox = new JPanel();
        arrowBox.setLayout(new BorderLayout());
        arrowBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        arrowBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                new EmptyBorder(8, 10, 8, 10)
        ));

        JLabel arrowLabel = new JLabel(">");
        arrowLabel.setFont(FontManager.getRunescapeBoldFont());
        arrowLabel.setForeground(Color.GRAY);
        arrowBox.add(arrowLabel, BorderLayout.CENTER);

        // Add hover effect to arrow
        arrowBox.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e)
            {
                arrowBox.setBackground(ColorScheme.DARKER_GRAY_COLOR.brighter());
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e)
            {
                arrowBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            }

            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                selectedBoss = rec;
                showBossDetail();
            }
        });

        row.add(nameBox, BorderLayout.CENTER);
        row.add(arrowBox, BorderLayout.EAST);

        row.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                selectedBoss = rec;
                showBossDetail();
            }
        });

        return row;
    }

    private void showBossDetail()
    {
        if (selectedBoss == null)
        {
            return;
        }

        bossDetail.removeAll();
        taskExpandedState.clear();

        // Boss title
        JPanel titlePanel = new JPanel();
        titlePanel.setLayout(new BorderLayout());
        titlePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        titlePanel.setBorder(new EmptyBorder(7, 7, 5, 7));

        JLabel bossTitle = new JLabel("<html><div style='text-align: center;'>" + selectedBoss.getBossName() + "</div></html>");
        bossTitle.setFont(FontManager.getRunescapeBoldFont());
        bossTitle.setForeground(Color.WHITE);
        bossTitle.setHorizontalAlignment(SwingConstants.CENTER);
        titlePanel.add(bossTitle, BorderLayout.CENTER);

        bossDetail.add(titlePanel, BorderLayout.NORTH);

        // Content panel with BorderLayout
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BorderLayout());
        contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // WRAPPER PANEL - contains back button + tasks
        JPanel wrapperPanel = new JPanel();
        wrapperPanel.setLayout(new BoxLayout(wrapperPanel, BoxLayout.Y_AXIS));
        wrapperPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrapperPanel.setBorder(new EmptyBorder(0, 7, 5, 7));

        // Back button and counter
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
        controlPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        controlPanel.setBorder(new EmptyBorder(2, 0, 7, 0));

        JButton backBtn = new JButton("<");
        backBtn.setFont(FontManager.getRunescapeSmallFont());
        backBtn.setForeground(Color.WHITE);
        backBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
        backBtn.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
        backBtn.setPreferredSize(new Dimension(30, 25));
        backBtn.setFocusPainted(false);
        backBtn.addActionListener(e -> {
            selectedBoss = null;
            cardLayout.show(cardPanel, "list");
        });

        controlPanel.add(backBtn);

        int totalTasks = selectedBoss.getAvailableTasks().size();
        long completedTasks = selectedBoss.getAvailableTasks().stream()
                .filter(task -> task.getCompletionRate() >= 100)
                .count();

        JLabel counterLabel = new JLabel("  " + completedTasks + "/" + totalTasks + " completed");
        counterLabel.setFont(FontManager.getRunescapeBoldFont());

        if (completedTasks == 0)
        {
            counterLabel.setForeground(Color.GRAY);
        }
        else if (completedTasks == totalTasks)
        {
            counterLabel.setForeground(new Color(0, 200, 0));
        }
        else
        {
            counterLabel.setForeground(ColorScheme.BRAND_ORANGE);
        }

        controlPanel.add(counterLabel);
        wrapperPanel.add(controlPanel);

        // Sort tasks with incomplete tasks first then complete tasks at the bottom
        List<RoutingAlgorithm.CombatAchievement> incompleteTasks = new ArrayList<>();
        List<RoutingAlgorithm.CombatAchievement> completeTasks = new ArrayList<>();

        for (RoutingAlgorithm.CombatAchievement task : selectedBoss.getAvailableTasks())
        {
            if (task.getCompletionRate() >= 100)
            {
                completeTasks.add(task);
            }
            else
            {
                incompleteTasks.add(task);
            }
        }

        incompleteTasks.sort(Comparator.comparing(RoutingAlgorithm.CombatAchievement::getName));
        completeTasks.sort(Comparator.comparing(RoutingAlgorithm.CombatAchievement::getName));

        // Add incomplete tasks
        for (int i = 0; i < incompleteTasks.size(); i++)
        {
            RoutingAlgorithm.CombatAchievement task = incompleteTasks.get(i);
            JPanel taskPanel = createTaskDetailPanel(task, false);
            wrapperPanel.add(taskPanel);

            if (i < incompleteTasks.size() - 1 || !completeTasks.isEmpty())
            {
                wrapperPanel.add(Box.createVerticalStrut(5));
            }
        }

        // Add completed tasks
        for (int i = 0; i < completeTasks.size(); i++)
        {
            RoutingAlgorithm.CombatAchievement task = completeTasks.get(i);
            JPanel taskPanel = createTaskDetailPanel(task, true);
            wrapperPanel.add(taskPanel);

            if (i < completeTasks.size() - 1)
            {
                wrapperPanel.add(Box.createVerticalStrut(5));
            }
        }


        contentPanel.add(wrapperPanel, BorderLayout.NORTH);

        bossDetail.add(contentPanel, BorderLayout.CENTER);

        cardLayout.show(cardPanel, "detail");
        bossDetail.revalidate();
    }

    private JPanel createTaskDetailPanel(RoutingAlgorithm.CombatAchievement task, boolean isCompleted)
    {
        if (task == null || task.getDifficulty() == null || task.getName() == null)
        {
            return new JPanel();
        }

        String taskKey = task.getName();
        // Completed tasks default to collapsed, incomplete default to expanded
        taskExpandedState.putIfAbsent(taskKey, !isCompleted);

        JPanel mainPanel = new JPanel()
        {
            @Override
            public Dimension getMaximumSize()
            {
                Dimension pref = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, pref.height);
            }
        };
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

        // Green background for completed tasks
        Color bgColor = isCompleted ? new Color(40, 60, 40) : ColorScheme.DARKER_GRAY_COLOR;
        mainPanel.setBackground(bgColor);

        mainPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(isCompleted ? new Color(0, 150, 0) : Color.GRAY, 1),
                new EmptyBorder(6, 6, 6, 6)
        ));

        // Clickable Header panel - task name and arrow
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BorderLayout(5, 0));
        headerPanel.setBackground(bgColor);
        headerPanel.setCursor(new Cursor(Cursor.HAND_CURSOR));
        if (!isCompleted)
        {
            JCheckBox skipCheckbox = new JCheckBox();
            skipCheckbox.setSelected(plugin.getManualCompletionManager().isManuallyCompleted(task.getId()));
            skipCheckbox.setBackground(bgColor);
            skipCheckbox.setToolTipText("Skip this task");
            skipCheckbox.setFocusPainted(false);
            skipCheckbox.setPreferredSize(new Dimension(20, 20)); // Fixed size

            skipCheckbox.addActionListener(e -> {
                plugin.getManualCompletionManager().toggleManualCompletion(task.getId());
                plugin.setCurrentTask(selectedBoss);
                showBossDetail();
            });

            headerPanel.add(skipCheckbox, BorderLayout.WEST);
        }
        // Task name with HTML wrapping
        JLabel nameLabel = new JLabel("<html>" + task.getName() + "</html>");
        nameLabel.setFont(FontManager.getRunescapeBoldFont());
        nameLabel.setForeground(isCompleted ? new Color(150, 255, 150) : Color.WHITE);
        headerPanel.add(nameLabel, BorderLayout.CENTER);

        // Arrow indicator
        boolean isExpanded = taskExpandedState.get(taskKey);
        JLabel arrowLabel = new JLabel(isExpanded ? "▼" : "▶");
        arrowLabel.setFont(FontManager.getRunescapeSmallFont());
        arrowLabel.setForeground(Color.GRAY);
        headerPanel.add(arrowLabel, BorderLayout.EAST);

        mainPanel.add(headerPanel);

        // Details panel (collapsible)
        JPanel detailsPanel = new JPanel();
        detailsPanel.setLayout(new BorderLayout());
        detailsPanel.setBackground(bgColor);
        detailsPanel.setVisible(isExpanded);

        // Inner container
        JPanel innerPanel = new JPanel();
        innerPanel.setLayout(new BoxLayout(innerPanel, BoxLayout.Y_AXIS));
        innerPanel.setBackground(bgColor);

        // Spacing
        innerPanel.add(Box.createVerticalStrut(6));

        // Difficulty and points
        String pointsText = task.getPoints() == 1 ? "pt" : "pts";
        String difficultyText = task.getDifficulty().toString();

        JLabel metaLabel = new JLabel(difficultyText + " " + task.getPoints() + pointsText);
        metaLabel.setFont(FontManager.getRunescapeSmallFont());
        metaLabel.setForeground(getDifficultyColor(task.getDifficulty()));
        innerPanel.add(metaLabel);

        // Description
        if (task.getDescription() != null && !task.getDescription().isEmpty())
        {
            innerPanel.add(Box.createVerticalStrut(4));

            JLabel descLabel = new JLabel("<html><font color='gray'>" + task.getDescription() + "</font></html>");
            descLabel.setFont(FontManager.getRunescapeSmallFont());
            innerPanel.add(descLabel);
        }

        detailsPanel.add(innerPanel, BorderLayout.CENTER);

        mainPanel.add(detailsPanel);

        // Add click listener to header
        headerPanel.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                boolean expanded = taskExpandedState.get(taskKey);
                taskExpandedState.put(taskKey, !expanded);

                detailsPanel.setVisible(!expanded);
                arrowLabel.setText(!expanded ? "▼" : "▶");

                mainPanel.revalidate();
                mainPanel.repaint();
            }
        });

        return mainPanel;
    }
    private JButton createSocialButton(String text, String url)
    {
        JButton button = new JButton(text);
        button.setFont(FontManager.getRunescapeSmallFont());
        button.setForeground(Color.WHITE);
        button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                new EmptyBorder(3, 8, 3, 8)
        ));
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        button.addActionListener(e -> {
            try {
                LinkBrowser.browse(url);
            } catch (Exception ex) {
                log.error("Failed to open URL: " + url, ex);
            }
        });

        // Hover effect
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                button.setBackground(ColorScheme.DARKER_GRAY_COLOR.brighter());
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            }
        });

        return button;
    }

    private int calculateBossCompletionPercentage(RoutingAlgorithm.BossRecommendation boss)
    {
        List<RoutingAlgorithm.CombatAchievement> tasks = boss.getAvailableTasks();
        if (tasks.isEmpty())
        {
            return 0;
        }

        double totalCompletion = tasks.stream()
                .mapToDouble(RoutingAlgorithm.CombatAchievement::getCompletionRate)
                .sum();

        return (int) (totalCompletion / tasks.size());
    }

    Color getDifficultyColor(RoutingAlgorithm.Difficulty difficulty)
    {
        switch (difficulty)
        {
            case EASY:
                return new Color(100, 120, 100);
            case MEDIUM:
                return new Color(80, 100, 120);
            case HARD:
                return new Color(120, 100, 80);
            case ELITE:
                return new Color(120, 80, 80);
            case MASTER:
                return new Color(100, 80, 120);
            case GRANDMASTER:
                return new Color(120, 110, 80);
            default:
                return new Color(90, 90, 90);
        }
    }
}