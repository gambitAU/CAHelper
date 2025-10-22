package com.CAHelper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Singleton
public class WikiDataLoader
{
    private static final String WIKI_API_URL = "https://oldschool.runescape.wiki/api.php";
    private static final String PAGE_TITLE = "Combat Achievements/All tasks";

    private static final Pattern COMPLETION_PATTERN = Pattern.compile("([0-9.]+)%");

    @Inject
    private Gson gson;

    @Inject
    private okhttp3.OkHttpClient okHttpClient;

    public List<RoutingAlgorithm.CombatAchievement> loadAllAchievements() //
    {
        try
        {
            log.info("Fetching combat achievements from Wiki API...");

            String html = fetchRenderedHtml(PAGE_TITLE);
            if (html == null || html.isEmpty())
            {
                log.error("Failed to fetch rendered HTML");
                return Collections.emptyList();
            }

            return parseRenderedHtml(html);
        }
        catch (Exception e)
        {
            log.error("Error loading achievements from Wiki", e);
            return Collections.emptyList();
        }
    }

    private String fetchRenderedHtml(String pageTitle) throws IOException
    {
        HttpUrl url = HttpUrl.parse(WIKI_API_URL).newBuilder()
                .addQueryParameter("action", "parse")
                .addQueryParameter("page", pageTitle)
                .addQueryParameter("prop", "text")
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
                .build();

        log.info("Fetching URL: {}", url);

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "RuneLite-CAHelper/1.0")
                .build();

        try (Response response = okHttpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                log.error("Failed to fetch Wiki page: {}", response.code());
                return null;
            }

            String json = response.body().string();
            log.debug("API Response length: {} characters", json.length());

            JsonObject root = gson.fromJson(json, JsonObject.class);

            if (root.has("parse") && root.getAsJsonObject("parse").has("text"))
            {
                return root.getAsJsonObject("parse").get("text").getAsString();
            }

            log.error("Unexpected API response structure");
            log.debug("Response JSON: {}", json.substring(0, Math.min(500, json.length())));
            return null;
        }
    }

    private List<RoutingAlgorithm.CombatAchievement> parseRenderedHtml(String html)
    {
        List<RoutingAlgorithm.CombatAchievement> achievements = new ArrayList<>();

        if (html == null || html.isEmpty())
        {
            log.error("HTML content is empty");
            return achievements;
        }

        Document doc = Jsoup.parse(html);
        Element table = doc.selectFirst("table.wikitable.ca-tasks");

        if (table == null)
        {
            log.warn("TABLE NOT FOUND with selector 'table.wikitable.ca-tasks'");
            log.warn("Searching for alternative table selectors...");

            Elements allTables = doc.select("table");
            log.warn("Total tables on page: {}", allTables.size());

            if (!allTables.isEmpty())
            {
                log.warn("First table classes: {}", allTables.get(0).className());
                log.warn("First table id: {}", allTables.get(0).id());
            }

            // Try alternative selector
            Element wikiTable = doc.selectFirst("table.wikitable");
            if (wikiTable != null)
            {
                log.warn("Found 'table.wikitable' - using this instead");
                table = wikiTable;
            }
        }
        else
        {
            log.info("Table found successfully with 'table.wikitable.ca-tasks'");
        }

        if (table == null)
        {
            log.error("Could not find any suitable table in rendered HTML");
            return achievements;
        }

        Elements rows = table.select("tbody > tr");
        log.info("Found {} rows in table", rows.size());

        if (rows.isEmpty())
        {
            // Try without tbody
            rows = table.select("tr");
            log.info("No tbody found, trying direct tr selector: {} rows", rows.size());
        }

        int id = 0;

        for (Element row : rows)
        {
            String taskId = row.attr("data-ca-task-id");
            if (taskId == null || taskId.isEmpty())
            {
                // Skip rows without task ID (e.g. header rows)
                continue;
            }

            Elements cells = row.select("td");
            if (cells.size() < 6)
            {
                log.debug("Skipping incomplete row with {} cells", cells.size());
                continue;
            }

            try
            {
                // Parse 6-column structure:
                // 0: Monster/Boss
                // 1: Task Name
                // 2: Description
                // 3: Type (Kill Count, Speed, Mechanical, Perfection, Restriction, Stamina, Group Size)
                // 4: Difficulty + Points combined (e.g., "Medium (2 pts)")
                // 5: Completion %

                String monster = cells.get(0).text().trim();
                String name = cells.get(1).text().trim();
                String description = cells.get(2).text().trim();
                String typeText = cells.get(3).text().trim();
                String difficultyAndPoints = cells.get(4).text().trim();
                String compRateText = cells.get(5).text().trim();

                if (name.isEmpty() || difficultyAndPoints.isEmpty())
                {
                    log.debug("Skipping row with empty name or difficulty");
                    continue;
                }

                // Parse difficulty and points from combined field
                RoutingAlgorithm.Difficulty difficulty = parseDifficulty(difficultyAndPoints);
                double completionRate = parseCompletionRate(compRateText);

                // Parse task type from the type field
                RoutingAlgorithm.TaskType taskType = parseTaskType(typeText);

                // NEW: Match constructor order (id, name, monster, difficulty, type, completionRate, description, prerequisites)
                RoutingAlgorithm.CombatAchievement ca = new RoutingAlgorithm.CombatAchievement(
                        id++,                      // id
                        name,                      // name
                        monster,                   // monster (boss name)
                        difficulty,                // difficulty
                        taskType,                  // type
                        completionRate,            // completionRate
                        description,               // description
                        Collections.emptyList()    // prerequisiteIds
                );

                achievements.add(ca);
                log.debug("Added achievement: {} - {} [{}] ({} pts)", monster, name, taskType, difficulty.getPoints());
            }
            catch (Exception e)
            {
                log.warn("Failed to parse row: {}", e.getMessage(), e);
            }
        }

        log.info("Parsed {} achievements from rendered HTML", achievements.size());

        // ===== DEBUG LOGGING =====
        Map<String, List<RoutingAlgorithm.CombatAchievement>> tasksByBoss = achievements.stream()
                .collect(Collectors.groupingBy(RoutingAlgorithm.CombatAchievement::getMonster));

        log.info("========================================");
        log.info("=== LOADED TASKS BY BOSS (Top 20) ===");
        log.info("========================================");
        tasksByBoss.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(20)
                .forEach(entry -> {
                    String boss = entry.getKey();
                    List<RoutingAlgorithm.CombatAchievement> tasks = entry.getValue();
                    log.info("  {} ({} tasks)", boss, tasks.size());
                });

        log.info("Total unique bosses: {}", tasksByBoss.size());
        log.info("Total tasks loaded: {}", achievements.size());
        log.info("========================================");

        return achievements;
    }

    private RoutingAlgorithm.Difficulty parseDifficulty(String tierText)
    {
        if (tierText == null) return RoutingAlgorithm.Difficulty.EASY;

        String tier = tierText.toLowerCase().trim();

        if (tier.contains("grandmaster"))
        {
            return RoutingAlgorithm.Difficulty.GRANDMASTER;
        }
        else if (tier.contains("master"))
        {
            return RoutingAlgorithm.Difficulty.MASTER;
        }
        else if (tier.contains("elite"))
        {
            return RoutingAlgorithm.Difficulty.ELITE;
        }
        else if (tier.contains("hard"))
        {
            return RoutingAlgorithm.Difficulty.HARD;
        }
        else if (tier.contains("medium"))
        {
            return RoutingAlgorithm.Difficulty.MEDIUM;
        }
        else if (tier.contains("easy"))
        {
            return RoutingAlgorithm.Difficulty.EASY;
        }

        log.warn("Unknown difficulty tier: {}", tierText);
        return RoutingAlgorithm.Difficulty.EASY;
    }

    /**
     * Parse task type from wiki type field
     * Matches the type string against TaskType enum values
     */
    private RoutingAlgorithm.TaskType parseTaskType(String typeText)
    {
        if (typeText == null || typeText.isEmpty())
        {
            log.warn("Empty task type text");
            return RoutingAlgorithm.TaskType.KILLCOUNT; // Default
        }

        String normalized = typeText.toLowerCase().trim();

        // Try to match against all TaskType enum values
        for (RoutingAlgorithm.TaskType type : RoutingAlgorithm.TaskType.values())
        {
            String wikiName = type.getWikiName().toLowerCase();
            if (normalized.equalsIgnoreCase(wikiName) || normalized.contains(wikiName))
            {
                log.debug("Parsed task type: '{}' -> {}", typeText, type);
                return type;
            }
        }

        log.warn("Could not parse task type: '{}', using default KILLCOUNT", typeText);
        return RoutingAlgorithm.TaskType.KILLCOUNT;
    }

    private double parseCompletionRate(String compRateText)
    {
        if (compRateText == null || compRateText.isEmpty())
        {
            return 0.0;
        }

        Matcher matcher = COMPLETION_PATTERN.matcher(compRateText);
        if (matcher.find())
        {
            try
            {
                return Double.parseDouble(matcher.group(1));
            }
            catch (NumberFormatException e)
            {
                log.warn("Failed to parse completion rate: {}", compRateText);
            }
        }
        return 0.0;
    }
}