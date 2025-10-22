package com.CAHelper;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("CAHelper")
public interface CAHelperConfig extends Config
{
    @ConfigItem(
            keyName = "useSmartRouting",
            name = "Smart Routing",
            description = "Routes bosses by easiest incomplete tasks. Disable to sort by difficulty only.",
            position = 1
    )
    default boolean useSmartRouting()
    {
        return true;
    }

    @ConfigItem(
            keyName = "minDifficulty",
            name = "Minimum Difficulty",
            description = "Only show tasks at or above this difficulty",
            position = 2
    )
    default Difficulty minDifficulty()
    {
        return Difficulty.EASY;
    }

    @ConfigItem(
            keyName = "maxDifficulty",
            name = "Maximum Difficulty",
            description = "Only show tasks at or below this difficulty",
            position = 3
    )
    default Difficulty maxDifficulty()
    {
        return Difficulty.GRANDMASTER;
    }

    @ConfigItem(
            keyName = "soloContentOnly",
            name = "Solo Content Only",
            description = "Only show tasks that can be completed solo (filters out duo, trio, group tasks)",
            position = 4
    )
    default boolean soloContentOnly()
    {
        return false;
    }

    @ConfigItem(
            keyName = "hideWildernessContent",
            name = "Hide Wilderness Tasks",
            description = "Filter out all wilderness-related tasks (recommended for Hardcore Ironmen)",
            position = 5
    )
    default boolean hideWildernessContent()
    {
        return false;
    }

    enum Difficulty
    {
        EASY,
        MEDIUM,
        HARD,
        ELITE,
        MASTER,
        GRANDMASTER
    }
}