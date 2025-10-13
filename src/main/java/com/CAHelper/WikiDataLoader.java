package com.CAHelper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Singleton
public class WikiDataLoader
{
    private static final String WIKI_API_URL = "https://oldschool.runescape.wiki/api.php";
    private static final String PAGE_TITLE = "Combat Achievements/All tasks";

    // Patterns for parsing wikitext
    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile(
            "\\|\\s*\\[\\[([^\\]]+)\\]\\]\\s*\\|\\|\\s*([^|]+?)\\s*\\|\\|\\s*([^|]+?)\\s*\\|\\|\\s*([^|]+?)\\s*\\|\\|\\s*([^|]+?)\\s*\\|\\|\\s*([^|\\n]+)",
            Pattern.MULTILINE
    );
    private static final Pattern COMPLETION_PATTERN = Pattern.compile("([0-9.]+)%");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[\\[([^|\\]]+)(?:\\|[^\\]]+)?\\]\\]");

    @Inject
    private Gson gson;

    @Inject
    private okhttp3.OkHttpClient okHttpClient;

    public List<RoutingAlgorithm.CombatAchievement> loadAllAchievements()
    {
        try
        {
            log.info("Fetching combat achievements from Wiki API...");

            String wikitext = fetchWikitext(PAGE_TITLE);
            if (wikitext == null || wikitext.isEmpty())
            {
                log.error("Failed to fetch wikitext");
                return Collections.emptyList();
            }

            return parseWikitext(wikitext);
        }
        catch (Exception e)
        {
            log.error("Error loading achievements from Wiki", e);
            return Collections.emptyList();
        }
    }

    private String fetchWikitext(String pageTitle) throws IOException
    {
        HttpUrl url = HttpUrl.parse(WIKI_API_URL).newBuilder()
                .addQueryParameter("action", "parse")
                .addQueryParameter("page", pageTitle)
                .addQueryParameter("prop", "wikitext")
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
                .build();

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
            JsonObject root = gson.fromJson(json, JsonObject.class);

            if (root.has("parse") && root.getAsJsonObject("parse").has("wikitext"))
            {
                return root.getAsJsonObject("parse").get("wikitext").getAsString();
            }

            log.error("Unexpected API response structure");
            return null;
        }
    }

    private List<RoutingAlgorithm.CombatAchievement> parseWikitext(String wikitext)
    {
        List<RoutingAlgorithm.CombatAchievement> achievements = new ArrayList<>();

        try
        {
            // Find the main table section
            int tableStart = wikitext.indexOf("{| class=\"wikitable");
            if (tableStart == -1)
            {
                log.error("Could not find wikitable in wikitext");
                return achievements;
            }

            // Split into lines and process row by row
            String[] lines = wikitext.substring(tableStart).split("\n");

            int id = 0;
            String currentMonster = "";
            String currentName = "";
            String currentDesc = "";
            String currentType = "";
            String currentTier = "";
            String currentCompletion = "";
            int columnIndex = 0;

            for (String line : lines)
            {
                line = line.trim();

                // End of table
                if (line.startsWith("|}"))
                {
                    break;
                }

                // New row
                if (line.startsWith("|-"))
                {
                    // Process previous row if we have data
                    if (!currentName.isEmpty())
                    {
                        try
                        {
                            RoutingAlgorithm.CombatAchievement ca = createAchievement(
                                    id++, currentMonster, currentName, currentDesc,
                                    currentType, currentTier, currentCompletion
                            );
                            if (ca != null)
                            {
                                achievements.add(ca);
                            }
                        }
                        catch (Exception e)
                        {
                            log.warn("Failed to create achievement: {}", e.getMessage());
                        }
                    }

                    // Reset for next row
                    currentMonster = "";
                    currentName = "";
                    currentDesc = "";
                    currentType = "";
                    currentTier = "";
                    currentCompletion = "";
                    columnIndex = 0;
                    continue;
                }

                // Parse cell data
                if (line.startsWith("|") && !line.startsWith("|-"))
                {
                    String cellContent = line.substring(1).trim();

                    // Handle || separator for multiple cells in one line
                    if (line.contains("||"))
                    {
                        String[] cells = line.substring(1).split("\\|\\|");
                        for (String cell : cells)
                        {
                            assignCell(cell.trim(), columnIndex++);
                        }
                    }
                    else
                    {
                        assignCell(cellContent, columnIndex++);
                    }
                }
            }

            // Process last row
            if (!currentName.isEmpty())
            {
                try
                {
                    RoutingAlgorithm.CombatAchievement ca = createAchievement(
                            id++, currentMonster, currentName, currentDesc,
                            currentType, currentTier, currentCompletion
                    );
                    if (ca != null)
                    {
                        achievements.add(ca);
                    }
                }
                catch (Exception e)
                {
                    log.warn("Failed to create achievement: {}", e.getMessage());
                }
            }

            log.info("Loaded {} achievements from Wiki", achievements.size());
        }
        catch (Exception e)
        {
            log.error("Error parsing wikitext", e);
        }

        return achievements;
    }

    // Helper method to track cell assignment
    private String currentMonster = "";
    private String currentName = "";
    private String currentDesc = "";
    private String currentType = "";
    private String currentTier = "";
    private String currentCompletion = "";

    private void assignCell(String content, int index)
    {
        String cleaned = cleanWikitext(content);

        switch (index)
        {
            case 0: currentMonster = cleaned; break;
            case 1: currentName = cleaned; break;
            case 2: currentDesc = cleaned; break;
            case 3: currentType = cleaned; break;
            case 4: currentTier = cleaned; break;
            case 5: currentCompletion = cleaned; break;
        }
    }

    private RoutingAlgorithm.CombatAchievement createAchievement(
            int id, String monster, String name, String description,
            String type, String tierText, String compRateText)
    {
        if (name.isEmpty() || tierText.isEmpty())
        {
            return null;
        }

        RoutingAlgorithm.Difficulty difficulty = parseDifficulty(tierText);
        double completionRate = parseCompletionRate(compRateText);

        return new RoutingAlgorithm.CombatAchievement(
                id,
                name,
                description,
                difficulty,
                completionRate,
                monster,
                Collections.emptyList()
        );
    }

    private String cleanWikitext(String text)
    {
        if (text == null)
        {
            return "";
        }

        // Remove wiki links but keep the display text
        Matcher linkMatcher = LINK_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (linkMatcher.find())
        {
            String linkText = linkMatcher.group(1);
            // If there's a pipe, the text after pipe is display text
            if (linkText.contains("|"))
            {
                linkText = linkText.substring(linkText.indexOf("|") + 1);
            }
            linkMatcher.appendReplacement(sb, Matcher.quoteReplacement(linkText));
        }
        linkMatcher.appendTail(sb);

        // Remove other wiki formatting
        return sb.toString()
                .replaceAll("'''", "")  // Bold
                .replaceAll("''", "")   // Italic
                .replaceAll("<br\\s*/?>", " ")  // Line breaks
                .replaceAll("\\{\\{[^}]+\\}\\}", "")  // Templates
                .trim();
    }

    private RoutingAlgorithm.Difficulty parseDifficulty(String tierText)
    {
        String tier = tierText.toLowerCase().trim();

        if (tier.contains("easy"))
        {
            return RoutingAlgorithm.Difficulty.EASY;
        }
        else if (tier.contains("medium"))
        {
            return RoutingAlgorithm.Difficulty.MEDIUM;
        }
        else if (tier.contains("hard"))
        {
            return RoutingAlgorithm.Difficulty.HARD;
        }
        else if (tier.contains("elite"))
        {
            return RoutingAlgorithm.Difficulty.ELITE;
        }
        else if (tier.contains("master"))
        {
            return RoutingAlgorithm.Difficulty.MASTER;
        }
        else if (tier.contains("grandmaster"))
        {
            return RoutingAlgorithm.Difficulty.GRANDMASTER;
        }

        log.warn("Unknown difficulty tier: {}", tierText);
        return RoutingAlgorithm.Difficulty.EASY;
    }

    private double parseCompletionRate(String compRateText)
    {
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