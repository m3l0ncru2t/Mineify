package com.mineify.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mineify.Mineify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Performs YouTube search using YouTube's internal InnerTube API.
 * Replaces the companion service's youtube-search-api npm dependency.
 */
public class YouTubeService {
    private static final String INNERTUBE_URL = "https://www.youtube.com/youtubei/v1/search";
    private static final String CLIENT_VERSION = "2.20231219.04.00";

    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public YouTubeService() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<SearchPage> search(String query) {
        JsonObject body = newRequestBody();
        body.addProperty("query", query);

        return sendRequest(body)
                .thenApply(this::parseInitialResponse)
                .exceptionally(e -> {
                    Mineify.LOGGER.error("YouTube search failed for query: {}", query, e);
                    return new SearchPage(new ArrayList<>(), null);
                });
    }

    /**
     * Fetches the next page of results for a search that already returned a
     * continuation token. YouTube's InnerTube API has no page-number or
     * offset concept - each page only exists as "whatever comes after this
     * exact opaque token", so the caller must hold onto the token from the
     * previous page rather than requesting page N directly.
     */
    public CompletableFuture<SearchPage> loadMore(String continuationToken) {
        JsonObject body = newRequestBody();
        body.addProperty("continuation", continuationToken);

        return sendRequest(body)
                .thenApply(this::parseContinuationResponse)
                .exceptionally(e -> {
                    Mineify.LOGGER.error("YouTube search continuation failed", e);
                    return new SearchPage(new ArrayList<>(), null);
                });
    }

    private JsonObject newRequestBody() {
        JsonObject context = new JsonObject();
        JsonObject client = new JsonObject();
        client.addProperty("clientName", "WEB");
        client.addProperty("clientVersion", CLIENT_VERSION);
        context.add("client", client);

        JsonObject body = new JsonObject();
        body.add("context", context);
        return body;
    }

    private CompletableFuture<String> sendRequest(JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(INNERTUBE_URL))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }

    private SearchPage parseInitialResponse(String responseBody) {
        try {
            JsonObject root = gson.fromJson(responseBody, JsonObject.class);
            JsonArray sections = root
                    .getAsJsonObject("contents")
                    .getAsJsonObject("twoColumnSearchResultsRenderer")
                    .getAsJsonObject("primaryContents")
                    .getAsJsonObject("sectionListRenderer")
                    .getAsJsonArray("contents");

            List<SearchResult> results = new ArrayList<>();
            String continuationToken = parseContentsArray(sections, results);
            return new SearchPage(results, continuationToken);
        } catch (Exception e) {
            Mineify.LOGGER.error("Failed to parse YouTube search response", e);
            return new SearchPage(new ArrayList<>(), null);
        }
    }

    /**
     * Continuation ("load more") responses come back in a different envelope
     * than the initial search - results live under
     * onResponseReceivedCommands[].appendContinuationItemsAction instead of
     * the nested twoColumnSearchResultsRenderer tree - but the actual item
     * list inside each command has the same shape as the initial page, so
     * parseContentsArray still handles it.
     */
    private SearchPage parseContinuationResponse(String responseBody) {
        try {
            JsonObject root = gson.fromJson(responseBody, JsonObject.class);
            JsonArray commands = root.getAsJsonArray("onResponseReceivedCommands");

            List<SearchResult> results = new ArrayList<>();
            String continuationToken = null;
            if (commands != null) {
                for (JsonElement cmdEl : commands) {
                    JsonObject cmd = cmdEl.getAsJsonObject();
                    if (!cmd.has("appendContinuationItemsAction")) continue;

                    JsonArray items = cmd.getAsJsonObject("appendContinuationItemsAction")
                            .getAsJsonArray("continuationItems");
                    String token = parseContentsArray(items, results);
                    if (token != null) continuationToken = token;
                }
            }
            return new SearchPage(results, continuationToken);
        } catch (Exception e) {
            Mineify.LOGGER.error("Failed to parse YouTube search continuation response", e);
            return new SearchPage(new ArrayList<>(), null);
        }
    }

    /**
     * Shared parser for the "list of sections" shape both the initial page
     * and every continuation page use: a run of itemSectionRenderer entries
     * (holding the actual videos) followed by a trailing continuationItemRenderer
     * holding the token for the *next* page, if there is one.
     */
    private String parseContentsArray(JsonArray contents, List<SearchResult> results) {
        String continuationToken = null;

        for (JsonElement section : contents) {
            JsonObject sectionObj = section.getAsJsonObject();

            if (sectionObj.has("continuationItemRenderer")) {
                continuationToken = extractContinuationToken(sectionObj.getAsJsonObject("continuationItemRenderer"));
                continue;
            }

            if (!sectionObj.has("itemSectionRenderer")) continue;

            JsonArray items = sectionObj.getAsJsonObject("itemSectionRenderer")
                    .getAsJsonArray("contents");

            for (JsonElement item : items) {
                JsonObject itemObj = item.getAsJsonObject();
                if (!itemObj.has("videoRenderer")) continue;

                JsonObject video = itemObj.getAsJsonObject("videoRenderer");
                String videoId = video.has("videoId") ? video.get("videoId").getAsString() : "";
                if (videoId.isEmpty()) continue;

                String title = extractText(video.getAsJsonObject("title"));
                String channel = video.has("ownerText")
                        ? extractText(video.getAsJsonObject("ownerText"))
                        : video.has("longBylineText")
                            ? extractText(video.getAsJsonObject("longBylineText"))
                            : "";
                String duration = video.has("lengthText")
                        ? extractText(video.getAsJsonObject("lengthText"))
                        : "";
                // Deliberately not using the thumbnail URL InnerTube returns here -
                // i.ytimg.com content-negotiates to WebP regardless of request
                // headers, which neither the client's ImageIO nor Minecraft's own
                // stb_image decoder supports. This older per-videoId endpoint has
                // always served plain JPEG, no negotiation involved.
                String thumbnail = "https://img.youtube.com/vi/" + videoId + "/mqdefault.jpg";

                results.add(new SearchResult(videoId, title, channel, duration, thumbnail));
            }
        }

        return continuationToken;
    }

    private String extractContinuationToken(JsonObject continuationItemRenderer) {
        try {
            return continuationItemRenderer
                    .getAsJsonObject("continuationEndpoint")
                    .getAsJsonObject("continuationCommand")
                    .get("token").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractText(JsonObject textObj) {
        if (textObj == null) return "";
        if (textObj.has("simpleText")) {
            return textObj.get("simpleText").getAsString();
        }
        if (textObj.has("runs")) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement run : textObj.getAsJsonArray("runs")) {
                JsonObject runObj = run.getAsJsonObject();
                if (runObj.has("text")) sb.append(runObj.get("text").getAsString());
            }
            return sb.toString();
        }
        return "";
    }

    public record SearchResult(String videoId, String title, String channel, String duration, String thumbnail) {}

    // continuationToken is null when this was the last page.
    public record SearchPage(List<SearchResult> results, String continuationToken) {}
}
