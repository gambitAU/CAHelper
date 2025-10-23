package com.CAHelper;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class ManualCompletionManager
{
    private static final String CONFIG_GROUP = "CAHelper";
    private static final String MANUAL_COMPLETIONS_KEY = "manualCompletions";

    @Inject
    private ConfigManager configManager;

    private Set<Integer> manuallyCompletedTaskIds = new HashSet<>();

    public void initialize()
    {
        // Load from config
        String saved = configManager.getConfiguration(CONFIG_GROUP, MANUAL_COMPLETIONS_KEY);
        if (saved != null && !saved.isEmpty())
        {
            manuallyCompletedTaskIds = Arrays.stream(saved.split(","))
                    .map(Integer::parseInt)
                    .collect(Collectors.toSet());

            log.info("Loaded {} manually completed tasks", manuallyCompletedTaskIds.size());
        }
    }

    public boolean isManuallyCompleted(int taskId)
    {
        return manuallyCompletedTaskIds.contains(taskId);
    }

    public void toggleManualCompletion(int taskId)
    {
        if (manuallyCompletedTaskIds.contains(taskId))
        {
            manuallyCompletedTaskIds.remove(taskId);
            log.info("Unmarked task {} as manually complete", taskId);
        }
        else
        {
            manuallyCompletedTaskIds.add(taskId);
            log.info("Marked task {} as manually complete", taskId);
        }

        save();
    }

    private void save()
    {
        String toSave = manuallyCompletedTaskIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        configManager.setConfiguration(CONFIG_GROUP, MANUAL_COMPLETIONS_KEY, toSave);
    }

    public void reset()
    {
        manuallyCompletedTaskIds.clear();
        save();
        log.info("Cleared all manual completions");
    }
}