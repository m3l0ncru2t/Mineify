package com.mineify.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mineify.Mineify;
import com.mineify.network.packets.AudioChunkPacket;
import com.mineify.network.packets.HistorySyncPacket;
import com.mineify.network.packets.NowPlayingPacket;
import com.mineify.network.packets.PlaylistSyncPacket;
import com.mineify.network.packets.SearchResultsPacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PlaylistManager {
    private final MinecraftServer server;
    private final YouTubeService youTubeService;
    private final AudioDownloadService audioDownloadService;
    private final List<PlaylistSyncPacket.Entry> playlist = new CopyOnWriteArrayList<>();
    private final List<PlaylistSyncPacket.Entry> history = new CopyOnWriteArrayList<>();
    private static final Path HISTORY_FILE = Path.of("config", "mineify_history.json");
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Mineify-Scheduler");
        t.setDaemon(true);
        return t;
    });

    private int currentIndex = -1;
    private boolean isPlaying = false;
    private long playbackStartTime = 0;
    private long currentTrackDurationMs = 0;
    private String currentVideoId = null;
    private String prefetchedVideoId = null;
    private ScheduledFuture<?> advanceFuture;
    private ScheduledFuture<?> progressFuture;
    private ScheduledFuture<?> opCheckFuture;
    private final Set<UUID> skipVotes = new HashSet<>();
    private final Map<UUID, Long> lastSearchTime = new HashMap<>();
    private final Map<UUID, String> searchContinuationToken = new HashMap<>();
    private final Map<UUID, Boolean> lastKnownOpStatus = new HashMap<>();
    private static final long SEARCH_COOLDOWN_MS = 2000;

    // Recent-tab removal is restricted to a single owner account, unlike every
    // other op-gated action in this file - deliberate per-request, not a general
    // op permission.
    private static final UUID HISTORY_OWNER_UUID = UUID.fromString("da6ebf64-9310-42a4-9f76-62b285cdcdca");

    public PlaylistManager(MinecraftServer server, YouTubeService youTubeService, AudioDownloadService audioDownloadService) {
        this.server = server;
        this.youTubeService = youTubeService;
        this.audioDownloadService = audioDownloadService;

        loadHistory();

        // Broadcast progress every second
        this.progressFuture = scheduler.scheduleAtFixedRate(() -> {
            if (isPlaying && currentIndex >= 0 && currentIndex < playlist.size()) {
                PlaylistSyncPacket.Entry entry = playlist.get(currentIndex);
                // Still mid-transfer (playbackStartTime is in the future) counts as 0,
                // not negative - nothing is audibly playing yet.
                long elapsed = Math.max(0, System.currentTimeMillis() - playbackStartTime);
                float progress = currentTrackDurationMs > 0 ? (float) elapsed / currentTrackDurationMs : 0f;
                server.execute(() -> broadcastNowPlaying(entry.videoId(), entry.title(), Math.min(progress, 1f), entry.thumbnail()));
            }
        }, 1, 1, TimeUnit.SECONDS);

        // Poll for op/deop so a player's UI (remove-button gating, etc.)
        // updates live instead of only refreshing on their next reconnect -
        // there's no Fabric event for an op status change, so this is the
        // simplest reliable way to catch it regardless of what changed it
        // (/op, /deop, editing ops.json directly).
        this.opCheckFuture = scheduler.scheduleAtFixedRate(() -> {
            server.execute(() -> {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (!supportsMineify(player)) continue;

                    boolean currentOp = server.getPlayerList().isOp(player.nameAndId());
                    Boolean previousOp = lastKnownOpStatus.put(player.getUUID(), currentOp);
                    if (previousOp != null && previousOp != currentOp) {
                        ServerPlayNetworking.send(player, new PlaylistSyncPacket(
                                new ArrayList<>(playlist), currentOp, Mineify.VERSION
                        ));
                    }
                }
            });
        }, 3, 3, TimeUnit.SECONDS);
    }

    public void handleSearch(ServerPlayer player, String query) {
        if (isSearchOnCooldown(player)) {
            return;
        }

        Mineify.LOGGER.info("Player {} searching for: {}", player.getName().getString(), query);

        youTubeService.search(query).thenAccept(page -> {
            server.execute(() -> {
                storeContinuationToken(player, page.continuationToken());
                ServerPlayNetworking.send(player, new SearchResultsPacket(
                        toEntries(page.results()), false, page.continuationToken() != null
                ));
            });
        });
    }

    /**
     * Fetches the next page for whatever search this player last ran. The
     * continuation token isn't sent by the client - YouTube's InnerTube API
     * requires the exact opaque token from the previous response, so the
     * server holds it per-player rather than trusting a client-supplied
     * value it could otherwise forge or replay against a different query.
     */
    public void handleLoadMoreSearch(ServerPlayer player) {
        String token = searchContinuationToken.get(player.getUUID());
        if (token == null) {
            return;
        }
        if (isSearchOnCooldown(player)) {
            return;
        }

        Mineify.LOGGER.info("Player {} loading more search results", player.getName().getString());

        youTubeService.loadMore(token).thenAccept(page -> {
            server.execute(() -> {
                storeContinuationToken(player, page.continuationToken());
                ServerPlayNetworking.send(player, new SearchResultsPacket(
                        toEntries(page.results()), true, page.continuationToken() != null
                ));
            });
        });
    }

    private boolean isSearchOnCooldown(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = lastSearchTime.get(player.getUUID());
        if (last != null && now - last < SEARCH_COOLDOWN_MS) {
            player.sendSystemMessage(Component.literal("[Mineify] ")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("Please wait a moment before searching again.")
                            .withStyle(ChatFormatting.WHITE)), false);
            return true;
        }
        lastSearchTime.put(player.getUUID(), now);
        return false;
    }

    private void storeContinuationToken(ServerPlayer player, String token) {
        if (token != null) {
            searchContinuationToken.put(player.getUUID(), token);
        } else {
            searchContinuationToken.remove(player.getUUID());
        }
    }

    private List<SearchResultsPacket.Entry> toEntries(List<YouTubeService.SearchResult> results) {
        List<SearchResultsPacket.Entry> entries = new ArrayList<>();
        for (var r : results) {
            entries.add(new SearchResultsPacket.Entry(
                    r.videoId(), r.title(), r.channel(), r.duration(), r.thumbnail()
            ));
        }
        return entries;
    }

    public void handleAddToPlaylist(ServerPlayer player, String videoId, String title, String duration, String thumbnail) {
        for (PlaylistSyncPacket.Entry existing : playlist) {
            if (existing.videoId().equals(videoId)) {
                player.sendSystemMessage(Component.literal("[Mineify] ")
                        .withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal("That song is already in the queue.")
                                .withStyle(ChatFormatting.WHITE)), false);
                return;
            }
        }

        Mineify.LOGGER.info("Player {} adding to playlist: {}", player.getName().getString(), title);

        // Warn if dependencies aren't available
        if (!audioDownloadService.isDependenciesAvailable()) {
            player.sendSystemMessage(Component.literal("[Mineify] ")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("Warning: yt-dlp or ffmpeg may not be installed on the server. Downloads may fail.")
                            .withStyle(ChatFormatting.WHITE)), false);
        }

        PlaylistSyncPacket.Entry entry = new PlaylistSyncPacket.Entry(
                videoId, title, duration, player.getName().getString(), thumbnail
        );
        playlist.add(entry);
        syncToAll();

        // If nothing is playing, start this newly-added track directly rather than
        // advancing from wherever currentIndex was left (which, once the queue has
        // played through, resets to -1 and would otherwise jump back to track 0 -
        // the oldest thing ever added - instead of the track that was just queued).
        if (!isPlaying) {
            startPlayback(playlist.size() - 1);
        } else {
            // The queue was already going - this new entry might be the track
            // right after whatever's currently playing, which prefetchNext()
            // only ever checked for once, at the moment the current track
            // started (when this entry didn't exist yet). Re-check now so it
            // doesn't miss its download window and cause a live yt-dlp wait
            // when the transition actually happens.
            prefetchNext();
        }
    }

    /**
     * Starts (or restarts, when idle) playback at a specific track chosen by a
     * player - e.g. clicking an entry in the playlist tab while nothing is playing,
     * rather than only ever being able to resume from the front of the queue.
     */
    public void handlePlaySpecific(ServerPlayer player, String videoId) {
        if (isPlaying) {
            return;
        }

        int index = -1;
        for (int i = 0; i < playlist.size(); i++) {
            if (playlist.get(i).videoId().equals(videoId)) {
                index = i;
                break;
            }
        }
        if (index == -1) {
            return;
        }

        Mineify.LOGGER.info("{} started playback of '{}'", player.getName().getString(), playlist.get(index).title());
        startPlayback(index);
    }

    public void handleRemoveFromPlaylist(ServerPlayer player, String videoId) {
        String playerName = player.getName().getString();
        boolean isOp = server.getPlayerList().isOp(player.nameAndId());

        int removeIndex = -1;
        for (int i = 0; i < playlist.size(); i++) {
            PlaylistSyncPacket.Entry entry = playlist.get(i);
            if (entry.videoId().equals(videoId) && (isOp || entry.addedBy().equals(playerName))) {
                removeIndex = i;
                break;
            }
        }

        if (removeIndex == -1) {
            Mineify.LOGGER.warn("{} tried to remove {} but doesn't own it or it doesn't exist", playerName, videoId);
            if (!isOp) {
                player.sendSystemMessage(Component.literal("[Mineify] ")
                        .withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal("You can only remove songs you added yourself, unless you're an operator.")
                                .withStyle(ChatFormatting.WHITE)), false);
            }
            return;
        }

        playlist.remove(removeIndex);
        Mineify.LOGGER.info("{} removed {} from playlist", playerName, videoId);

        audioDownloadService.delete(videoId);

        if (currentIndex >= 0) {
            if (removeIndex < currentIndex) {
                currentIndex--;
            } else if (removeIndex == currentIndex) {
                if (advanceFuture != null) {
                    advanceFuture.cancel(false);
                }
                // Tell all clients to stop playing the removed song immediately
                broadcastNowPlaying("", "", 0f, "");
                currentIndex--;
                playNext();
            }
        }

        syncToAll();

        // Removing some other queued entry can shift a different track into
        // the next-up slot (or bump the one that was already prefetched down
        // to a different index) - re-check so the right file is the one
        // sitting ready when playback actually gets there.
        if (isPlaying) {
            prefetchNext();
        }
    }

    /**
     * Ops skip immediately. Everyone else's click counts toward a majority
     * vote among currently-connected Mineify players instead of being blocked
     * outright - avoids needing an op online just to get past a bad song.
     */
    public void handleSkip(ServerPlayer player) {
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            return;
        }

        if (server.getPlayerList().isOp(player.nameAndId())) {
            Mineify.LOGGER.info("{} skipped '{}' (op)", player.getName().getString(), playlist.get(currentIndex).title());
            doSkip();
            return;
        }

        if (!skipVotes.add(player.getUUID())) {
            return;
        }

        int required = requiredSkipVotes();
        Mineify.LOGGER.info("{} voted to skip '{}' ({}/{})",
                player.getName().getString(), playlist.get(currentIndex).title(), skipVotes.size(), required);

        Component message = Component.literal("[Mineify] ")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(player.getName().getString() + " voted to skip (" + skipVotes.size() + "/" + required + ")")
                        .withStyle(ChatFormatting.WHITE));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (supportsMineify(p)) {
                p.sendSystemMessage(message, false);
            }
        }

        if (skipVotes.size() >= required) {
            doSkip();
        }
    }

    private int requiredSkipVotes() {
        int eligible = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (supportsMineify(p)) {
                eligible++;
            }
        }
        return Math.max(1, eligible / 2 + 1);
    }

    private void doSkip() {
        if (advanceFuture != null) {
            advanceFuture.cancel(false);
        }
        skipVotes.clear();
        // Tell all clients to stop playing the current song immediately
        broadcastNowPlaying("", "", 0f, "");
        playNext();
    }

    public void handleStop(ServerPlayer player) {
        if (!server.getPlayerList().isOp(player.nameAndId())) {
            player.sendSystemMessage(Component.literal("[Mineify] ")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("Only server operators can stop playback.")
                            .withStyle(ChatFormatting.WHITE)), false);
            return;
        }

        if (!isPlaying) {
            return;
        }

        Mineify.LOGGER.info("{} stopped playback", player.getName().getString());

        if (advanceFuture != null) {
            advanceFuture.cancel(false);
        }
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            audioDownloadService.delete(playlist.get(currentIndex).videoId());
        }
        skipVotes.clear();
        isPlaying = false;
        currentIndex = -1;
        currentVideoId = null;
        broadcastNowPlaying("", "", 0f, "");
    }

    /**
     * Whether this player's client has Mineify installed (registered our clientbound
     * packet types). Sending our custom payloads to a client that never registered
     * them - e.g. a vanilla or otherwise-unmodded player just joining the server -
     * crashes their connection instead of being silently ignored, so every broadcast
     * must be filtered through this first.
     */
    private boolean supportsMineify(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, PlaylistSyncPacket.ID);
    }

    public void syncToPlayer(ServerPlayer player) {
        if (!supportsMineify(player)) {
            return;
        }

        ServerPlayNetworking.send(player, new PlaylistSyncPacket(new ArrayList<>(playlist), server.getPlayerList().isOp(player.nameAndId()), Mineify.VERSION));
        ServerPlayNetworking.send(player, new HistorySyncPacket(new ArrayList<>(history), player.getUUID().equals(HISTORY_OWNER_UUID)));
        if (isPlaying && currentIndex >= 0 && currentIndex < playlist.size()) {
            PlaylistSyncPacket.Entry entry = playlist.get(currentIndex);
            long elapsed = Math.max(0, System.currentTimeMillis() - playbackStartTime);
            float progress = currentTrackDurationMs > 0 ? (float) elapsed / currentTrackDurationMs : 0f;
            int loadingSecondsRemaining = (int) Math.max(0, Math.ceil((playbackStartTime - System.currentTimeMillis()) / 1000.0));
            ServerPlayNetworking.send(player, new NowPlayingPacket(entry.videoId(), entry.title(), Math.min(progress, 1f), loadingSecondsRemaining, entry.thumbnail()));

            if (currentVideoId != null) {
                // Let them catch up on their own instead of pausing everyone else's
                // playback - send the track starting from a projected offset (where
                // it'll actually be once this specific throttled transfer finishes),
                // the same way a normal simultaneous start already accounts for its
                // own transfer time.
                final String videoId = currentVideoId;
                scheduler.submit(() -> {
                    Path filePath = audioDownloadService.getFilePath(videoId);
                    if (!Files.exists(filePath)) {
                        Mineify.LOGGER.warn("Mineify: Audio file not found for late-join catch-up ({})", videoId);
                        return;
                    }
                    try {
                        byte[] audioBytes = Files.readAllBytes(filePath);
                        server.execute(() -> {
                            if (!videoId.equals(currentVideoId)) {
                                // Track changed while the file was being read; stale.
                                return;
                            }
                            long transferTimeMs = computeTransferTimeMs(audioBytes.length);
                            long elapsedNow = Math.max(0, System.currentTimeMillis() - playbackStartTime);
                            sendAudioChunks(player, videoId, entry.title(), audioBytes, elapsedNow + transferTimeMs);
                        });
                    } catch (IOException e) {
                        Mineify.LOGGER.error("Mineify: Failed to read audio file for late-join catch-up ({})", videoId, e);
                    }
                });
            }
        }
    }

    public void handleDisconnect(ServerPlayer player) {
        skipVotes.remove(player.getUUID());
        lastSearchTime.remove(player.getUUID());
        searchContinuationToken.remove(player.getUUID());
        lastKnownOpStatus.remove(player.getUUID());
    }

    private void playNext() {
        int nextIndex = currentIndex + 1;
        if (nextIndex >= playlist.size()) {
            if (currentIndex >= 0 && currentIndex < playlist.size()) {
                audioDownloadService.delete(playlist.get(currentIndex).videoId());
            }
            isPlaying = false;
            currentIndex = -1;
            currentVideoId = null;
            broadcastNowPlaying("", "", 0f, "");
            return;
        }

        startPlayback(nextIndex);
    }

    /**
     * Downloads and broadcasts the track at the given playlist index, replacing
     * whatever is currently playing (if anything). Shared by the natural
     * end-of-track advance, a manual skip, and a player explicitly picking a
     * track to play.
     */
    private void startPlayback(int index) {
        if (currentIndex >= 0 && currentIndex < playlist.size() && currentIndex != index) {
            audioDownloadService.delete(playlist.get(currentIndex).videoId());
        }
        currentIndex = index;
        prefetchedVideoId = null;
        skipVotes.clear();

        PlaylistSyncPacket.Entry entry = playlist.get(currentIndex);
        addToHistory(entry);
        isPlaying = true;
        currentTrackDurationMs = parseDuration(entry.duration());

        Mineify.LOGGER.info("Requesting download for: {} ({})", entry.title(), entry.videoId());

        audioDownloadService.download(entry.videoId()).thenAccept(filePath -> {
            if (filePath == null) {
                Mineify.LOGGER.error("Download failed for: {}", entry.title());
                server.execute(() -> {
                    Component message = Component.literal("[Mineify] ")
                            .withStyle(ChatFormatting.RED)
                            .append(Component.literal("Failed to download: " + entry.title())
                                    .withStyle(ChatFormatting.WHITE))
                            .append(Component.literal(" - Check server logs for details")
                                    .withStyle(ChatFormatting.GRAY));
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        player.sendSystemMessage(message, false);
                    }
                    playNext();
                });
                return;
            }

            // Read audio bytes on the download thread, then send chunks on the server thread
            try {
                byte[] audioBytes = Files.readAllBytes(filePath);
                server.execute(() -> {
                    currentVideoId = entry.videoId();

                    // Chunks are sent throttled (see sendAudioChunkBatch), so clients
                    // don't actually finish receiving - and therefore start playing -
                    // until well after this point. playbackStartTime is used as the
                    // "audio is actually making sound" reference for both the progress
                    // bar and the auto-advance timer, so it needs to be pushed out by
                    // the expected transfer time rather than set to right now, or both
                    // end up measured from the wrong point: the progress bar creeps up
                    // during the silent loading phase, and the auto-advance timer fires
                    // too early and cuts the tail of the song off.
                    long transferTimeMs = computeTransferTimeMs(audioBytes.length);
                    playbackStartTime = System.currentTimeMillis() + transferTimeMs;
                    scheduleCountdown(entry.videoId(), entry.title(), transferTimeMs);

                    Mineify.LOGGER.info("Mineify: Sending audio to all players: {}", entry.title());

                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        if (supportsMineify(player)) {
                            sendAudioChunks(player, entry.videoId(), entry.title(), audioBytes, 0L);
                        }
                    }

                    broadcastNowPlaying(entry.videoId(), entry.title(), 0f, entry.thumbnail());
                    prefetchNext();

                    if (currentTrackDurationMs > 0) {
                        if (advanceFuture != null) {
                            advanceFuture.cancel(false);
                        }
                        advanceFuture = scheduler.schedule(
                                () -> server.execute(this::playNext),
                                transferTimeMs + currentTrackDurationMs + 2000,
                                TimeUnit.MILLISECONDS
                        );
                    }
                });
            } catch (IOException e) {
                Mineify.LOGGER.error("Mineify: Failed to read downloaded audio for {}", entry.videoId(), e);
                server.execute(this::playNext);
            }
        });
    }

    /**
     * Shows a "3, 2, 1" countdown on the vanilla action bar (the overlay
     * line above the hotbar, not chat) timed to land right as the track
     * actually starts sounding (playbackStartTime). Uses the same
     * sendSystemMessage(component, overlay) vanilla clients already know how
     * to render, so no client-side rendering code was needed for this -
     * gated to supportsMineify() only because it's not useful information
     * for players without the mod, not because of any technical limitation.
     */
    private void scheduleCountdown(String videoId, String title, long transferTimeMs) {
        for (int secondsLeft = 3; secondsLeft >= 1; secondsLeft--) {
            long delay = transferTimeMs - secondsLeft * 1000L;
            if (delay < 0) {
                continue;
            }
            int seconds = secondsLeft;
            scheduler.schedule(() -> server.execute(() -> {
                // The track may have been skipped/stopped/replaced while this
                // was waiting to fire - stale ticks for an abandoned track
                // should just do nothing, same guard used for late-join catch-up.
                if (!videoId.equals(currentVideoId)) {
                    return;
                }
                Component message = Component.literal(title + " starting in " + seconds + "...")
                        .withStyle(ChatFormatting.AQUA);
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (supportsMineify(player)) {
                        player.sendSystemMessage(message, true);
                    }
                }
            }), delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Kicks off the download for whatever's queued right after the track that
     * just started, so its download/convert time is hidden behind this track's
     * playback instead of creating a dead-air gap once this one ends. Runs on
     * AudioDownloadService's own background executor (same as every other
     * download), so it doesn't add load to the server thread; if the track never
     * actually gets played (removed, or skipped past via a manual pick), its
     * cached file gets cleaned up the same way any other unplayed entry does.
     */
    private void prefetchNext() {
        int nextIndex = currentIndex + 1;
        if (nextIndex >= playlist.size()) {
            return;
        }
        PlaylistSyncPacket.Entry nextEntry = playlist.get(nextIndex);
        if (nextEntry.videoId().equals(prefetchedVideoId)) {
            // Already prefetched (or in flight) for this exact next-up track.
            return;
        }
        prefetchedVideoId = nextEntry.videoId();
        Mineify.LOGGER.info("Mineify: Prefetching next track: {}", nextEntry.title());
        audioDownloadService.download(nextEntry.videoId());
    }

    // Targets roughly 300 KB/s per listener (1 chunk / 100ms) - comfortably under
    // what a normal connection can sustain. Earlier pacing (16 chunks/20ms, ~24 MB/s)
    // didn't actually throttle anything - Netty buffers writes regardless of how
    // often our code calls send(), so that just changed how the burst was shaped,
    // not its size, and still saturated connections badly enough to cause
    // keep-alive timeout kicks. This one is a real, deliberate rate limit.
    private static final int CHUNKS_PER_BATCH = 1;
    private static final long BATCH_DELAY_MS = 100;

    /**
     * How long it actually takes sendAudioChunkBatch to finish sending a file of
     * this size at the throttled rate above. The first batch sends immediately
     * (no initial delay), so the last of N batches goes out at (N-1) * delay,
     * not N * delay.
     */
    private static long computeTransferTimeMs(int audioByteLength) {
        int totalChunks = (int) Math.ceil((double) audioByteLength / AudioChunkPacket.CHUNK_SIZE);
        long totalBatches = (long) Math.ceil((double) totalChunks / CHUNKS_PER_BATCH);
        return Math.max(0, (totalBatches - 1) * BATCH_DELAY_MS);
    }

    /**
     * Splits audioBytes into CHUNK_SIZE chunks and sends each as an AudioChunkPacket,
     * throttled to CHUNKS_PER_BATCH per BATCH_DELAY_MS rather than all at once.
     */
    private void sendAudioChunks(ServerPlayer player, String videoId, String title,
                                  byte[] audioBytes, long startOffsetMs) {
        int totalChunks = (int) Math.ceil((double) audioBytes.length / AudioChunkPacket.CHUNK_SIZE);
        sendAudioChunkBatch(player, videoId, title, audioBytes, startOffsetMs, 0, totalChunks);
    }

    private void sendAudioChunkBatch(ServerPlayer player, String videoId, String title,
                                      byte[] audioBytes, long startOffsetMs, int startIndex, int totalChunks) {
        int chunkSize = AudioChunkPacket.CHUNK_SIZE;
        int endIndex = Math.min(startIndex + CHUNKS_PER_BATCH, totalChunks);

        for (int i = startIndex; i < endIndex; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, audioBytes.length);
            byte[] chunk = Arrays.copyOfRange(audioBytes, start, end);
            ServerPlayNetworking.send(player, new AudioChunkPacket(videoId, title, i, totalChunks, startOffsetMs, chunk));
        }

        if (endIndex < totalChunks) {
            scheduler.schedule(
                    () -> server.execute(() -> sendAudioChunkBatch(player, videoId, title, audioBytes, startOffsetMs, endIndex, totalChunks)),
                    BATCH_DELAY_MS, TimeUnit.MILLISECONDS
            );
        } else {
            Mineify.LOGGER.info("Mineify: Sent {} chunks ({} KB) for '{}' to {}",
                    totalChunks, audioBytes.length / 1024, title, player.getName().getString());
        }
    }

    private void loadHistory() {
        if (!Files.exists(HISTORY_FILE)) {
            return;
        }
        try {
            String json = Files.readString(HISTORY_FILE);
            JsonArray array = new Gson().fromJson(json, JsonArray.class);
            for (var element : array) {
                JsonObject obj = element.getAsJsonObject();
                history.add(new PlaylistSyncPacket.Entry(
                        obj.get("videoId").getAsString(),
                        obj.get("title").getAsString(),
                        obj.get("duration").getAsString(),
                        obj.get("addedBy").getAsString(),
                        // Older history files predate this field.
                        obj.has("thumbnail") ? obj.get("thumbnail").getAsString() : ""
                ));
            }
        } catch (IOException | RuntimeException e) {
            Mineify.LOGGER.warn("Mineify: Failed to load history", e);
        }
    }

    private void saveHistory() {
        JsonArray array = new JsonArray();
        for (PlaylistSyncPacket.Entry entry : history) {
            JsonObject obj = new JsonObject();
            obj.addProperty("videoId", entry.videoId());
            obj.addProperty("title", entry.title());
            obj.addProperty("duration", entry.duration());
            obj.addProperty("addedBy", entry.addedBy());
            obj.addProperty("thumbnail", entry.thumbnail());
            array.add(obj);
        }
        try {
            Files.createDirectories(HISTORY_FILE.getParent());
            Files.writeString(HISTORY_FILE, new Gson().toJson(array));
        } catch (IOException e) {
            Mineify.LOGGER.warn("Mineify: Failed to save history", e);
        }
    }

    private void addToHistory(PlaylistSyncPacket.Entry entry) {
        // Entry is a record, so a plain remove(entry) only matches when every
        // field is identical - replaying a song someone else added produces
        // a new entry with a different addedBy, which wouldn't match the old
        // one and left both sitting in history as separate listings for the
        // same video. Dedupe on videoId alone instead: whoever queued it most
        // recently is the one shown.
        history.removeIf(e -> e.videoId().equals(entry.videoId()));
        history.add(0, entry);
        saveHistory();

        List<PlaylistSyncPacket.Entry> snapshot = new ArrayList<>(history);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (supportsMineify(player)) {
                ServerPlayNetworking.send(player, new HistorySyncPacket(snapshot, player.getUUID().equals(HISTORY_OWNER_UUID)));
            }
        }
    }

    public void handleRemoveFromHistory(ServerPlayer player, String videoId) {
        if (!player.getUUID().equals(HISTORY_OWNER_UUID)) {
            return;
        }

        if (!history.removeIf(entry -> entry.videoId().equals(videoId))) {
            return;
        }

        Mineify.LOGGER.info("{} removed {} from history", player.getName().getString(), videoId);
        saveHistory();

        List<PlaylistSyncPacket.Entry> snapshot = new ArrayList<>(history);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (supportsMineify(p)) {
                ServerPlayNetworking.send(p, new HistorySyncPacket(snapshot, p.getUUID().equals(HISTORY_OWNER_UUID)));
            }
        }
    }

    private void syncToAll() {
        List<PlaylistSyncPacket.Entry> entries = new ArrayList<>(playlist);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (supportsMineify(player)) {
                ServerPlayNetworking.send(player, new PlaylistSyncPacket(entries, server.getPlayerList().isOp(player.nameAndId()), Mineify.VERSION));
            }
        }
    }

    private void broadcastNowPlaying(String videoId, String title, float progress, String thumbnail) {
        int loadingSecondsRemaining = title.isEmpty()
                ? 0
                : (int) Math.max(0, Math.ceil((playbackStartTime - System.currentTimeMillis()) / 1000.0));
        NowPlayingPacket packet = new NowPlayingPacket(videoId, title, progress, loadingSecondsRemaining, thumbnail);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (supportsMineify(player)) {
                ServerPlayNetworking.send(player, packet);
            }
        }
    }

    /**
     * Parse duration string like "3:45" or "1:02:30" to milliseconds.
     */
    private long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return 3 * 60 * 1000;
        }
        try {
            String[] parts = duration.split(":");
            long seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Long.parseLong(part.trim());
            }
            return seconds * 1000;
        } catch (NumberFormatException e) {
            return 3 * 60 * 1000;
        }
    }

    public void shutdown() {
        if (advanceFuture != null) advanceFuture.cancel(true);
        if (progressFuture != null) progressFuture.cancel(true);
        if (opCheckFuture != null) opCheckFuture.cancel(true);
        scheduler.shutdownNow();
        playlist.clear();
        Mineify.LOGGER.info("Mineify: Playlist manager shut down");
    }
}
