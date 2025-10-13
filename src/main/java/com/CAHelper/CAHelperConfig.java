package com.CAHelper;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("CAHelper")
public interface CAHelperConfig extends Config
{
    @ConfigItem(
            keyName = "useWikiSync",
            name = "Use Wiki Sync",
            description = "Fetch progress from OSRS Wiki (requires you to sync in-game)",
            position = 1
    )
    default boolean useWikiSync()
    {
        return true;
    }

    @ConfigItem(
            keyName = "routingPriority",
            name = "Routing Priority",
            description = "How to prioritize suggested tasks",
            position = 2
    )
    default RoutingPriority routingPriority()
    {
        return RoutingPriority.LOCATION_CLUSTER;
    }

    @ConfigItem(
            keyName = "minDifficulty",
            name = "Minimum Difficulty",
            description = "Only show tasks at or above this difficulty",
            position = 3
    )
    default Difficulty minDifficulty()
    {
        return Difficulty.EASY;
    }

    @ConfigItem(
            keyName = "maxDifficulty",
            name = "Maximum Difficulty",
            description = "Only show tasks at or below this difficulty",
            position = 4
    )
    default Difficulty maxDifficulty()
    {
        return Difficulty.GRANDMASTER;
    }

    @ConfigItem(
            keyName = "autoRefresh",
            name = "Auto Refresh Minutes",
            description = "Automatically refresh progress every X minutes (0 = disabled)",
            position = 5
    )
    default int autoRefreshMinutes()
    {
        return 10;
    }

    enum RoutingPriority
    {
        LOCATION_CLUSTER,  // Group by boss/location
        DIFFICULTY,        // Easiest first
        POINTS,           // Highest point value first
        GEAR_SIMILARITY   // Similar gear requirements
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