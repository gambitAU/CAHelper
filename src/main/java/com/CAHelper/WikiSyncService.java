package com.CAHelper;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

@Slf4j
@Singleton
public class WikiSyncService
{
    private static final String WIKI_API_BASE = "https://sync.runescape.wiki/runelite/player";

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private Gson gson;

    public void fetchPlayerProgress(String username, Consumer<PlayerProgress> callback)
    {
        HttpUrl url = HttpUrl.parse(WIKI_API_BASE)
                .newBuilder()
                .addPathSegment(username)
                .addPathSegment("combat-achievements")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.error("Failed to fetch Wiki Sync data for {}", username, e);
                SwingUtilities.invokeLater(() -> callback.accept(new PlayerProgress(new ArrayList<>())));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                if (!response.isSuccessful())
                {
                    log.warn("Wiki Sync returned non-successful response: {}", response.code());
                    response.close();
                    SwingUtilities.invokeLater(() -> callback.accept(new PlayerProgress(new ArrayList<>())));
                    return;
                }

                String body = response.body().string();
                response.close();

                try
                {
                    WikiSyncResponse syncResponse = gson.fromJson(body, WikiSyncResponse.class);
                    if (syncResponse != null && syncResponse.completed != null)
                    {
                        SwingUtilities.invokeLater(() -> callback.accept(new PlayerProgress(syncResponse.completed)));
                    }
                    else
                    {
                        SwingUtilities.invokeLater(() -> callback.accept(new PlayerProgress(new ArrayList<>())));
                    }
                }
                catch (Exception e)
                {
                    log.error("Error parsing Wiki Sync response", e);
                    SwingUtilities.invokeLater(() -> callback.accept(new PlayerProgress(new ArrayList<>())));
                }
            }
        });
    }

    private static class WikiSyncResponse
    {
        List<Integer> completed;
    }

    public static class PlayerProgress
    {
        private final List<Integer> completedIds;

        public PlayerProgress(List<Integer> completedIds)
        {
            this.completedIds = completedIds;
        }

        public List<Integer> getCompletedIds()
        {
            return completedIds;
        }

        public int getCompletedCount()
        {
            return completedIds.size();
        }

        public boolean isCompleted(int id)
        {
            return completedIds.contains(id);
        }
    }
}
