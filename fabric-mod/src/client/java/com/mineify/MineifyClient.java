package com.mineify;

import com.mineify.client.MineifyKeybinds;
import com.mineify.client.MineifyScreen;
import com.mineify.client.audio.AudioPlayer;
import com.mineify.network.packets.AudioChunkPacket;
import com.mineify.network.packets.HistorySyncPacket;
import com.mineify.network.packets.NowPlayingPacket;
import com.mineify.network.packets.PlaylistSyncPacket;
import com.mineify.network.packets.SearchResultsPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class MineifyClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mineify-client");

    // Cached playlist state (persists when screen is closed)
    private static List<MineifyScreen.PlaylistEntry> cachedPlaylist = new ArrayList<>();
    private static List<MineifyScreen.PlaylistEntry> cachedHistory = new ArrayList<>();
    private static String cachedNowPlaying = null;
    private static String cachedNowPlayingVideoId = null;
    private static String cachedNowPlayingThumbnail = null;
    private static float cachedProgress = 0f;
    private static int cachedLoadingSecondsRemaining = 0;
    private static boolean cachedIsOp = false;
    private static boolean cachedCanRemoveHistory = false;
    private static String cachedServerVersion = null;

    public static List<MineifyScreen.PlaylistEntry> getCachedPlaylist() {
        return new ArrayList<>(cachedPlaylist);
    }

    public static List<MineifyScreen.PlaylistEntry> getCachedHistory() {
        return new ArrayList<>(cachedHistory);
    }

    public static boolean getCachedIsOp() {
        return cachedIsOp;
    }

    public static boolean getCachedCanRemoveHistory() {
        return cachedCanRemoveHistory;
    }

    public static String getCachedServerVersion() {
        return cachedServerVersion;
    }

    public static String getCachedNowPlaying() {
        return cachedNowPlaying;
    }

    public static String getCachedNowPlayingVideoId() {
        return cachedNowPlayingVideoId;
    }

    public static String getCachedNowPlayingThumbnail() {
        return cachedNowPlayingThumbnail;
    }

    public static float getCachedProgress() {
        return cachedProgress;
    }

    public static int getCachedLoadingSecondsRemaining() {
        return cachedLoadingSecondsRemaining;
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Mineify Client");

        MineifyKeybinds.register();

        // Register client-side packet handlers
        ClientPlayNetworking.registerGlobalReceiver(SearchResultsPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (Minecraft.getInstance().gui.screen() instanceof MineifyScreen screen) {
                    List<MineifyScreen.SearchResult> results = new ArrayList<>();
                    for (var entry : payload.results()) {
                        results.add(new MineifyScreen.SearchResult(
                                entry.videoId(), entry.title(), entry.channel(),
                                entry.duration(), entry.thumbnail()
                        ));
                    }
                    screen.updateSearchResults(results, payload.append(), payload.hasMore());
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(PlaylistSyncPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                // Always update the cache
                List<MineifyScreen.PlaylistEntry> entries = new ArrayList<>();
                for (var entry : payload.entries()) {
                    entries.add(new MineifyScreen.PlaylistEntry(
                            entry.videoId(), entry.title(), entry.duration(), entry.addedBy(), entry.thumbnail()
                    ));
                }
                cachedPlaylist = entries;
                cachedIsOp = payload.isOp();
                cachedServerVersion = payload.serverVersion();

                // Also update screen if open
                if (Minecraft.getInstance().gui.screen() instanceof MineifyScreen screen) {
                    screen.updatePlaylist(entries, payload.isOp(), payload.serverVersion());
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(HistorySyncPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                List<MineifyScreen.PlaylistEntry> entries = new ArrayList<>();
                for (var entry : payload.entries()) {
                    entries.add(new MineifyScreen.PlaylistEntry(
                            entry.videoId(), entry.title(), entry.duration(), entry.addedBy(), entry.thumbnail()
                    ));
                }
                cachedHistory = entries;
                cachedCanRemoveHistory = payload.canRemove();

                if (Minecraft.getInstance().gui.screen() instanceof MineifyScreen screen) {
                    screen.updateHistory(entries, payload.canRemove());
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(NowPlayingPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                // Always update the cache
                cachedNowPlaying = payload.title().isEmpty() ? null : payload.title();
                cachedNowPlayingVideoId = payload.title().isEmpty() ? null : payload.videoId();
                cachedNowPlayingThumbnail = payload.title().isEmpty() ? null : payload.thumbnail();
                cachedProgress = payload.progress();
                cachedLoadingSecondsRemaining = payload.loadingSecondsRemaining();

                // Stop audio when the server signals nothing is playing
                if (payload.title().isEmpty()) {
                    AudioPlayer.getInstance().stop();
                }

                // Also update screen if open
                if (Minecraft.getInstance().gui.screen() instanceof MineifyScreen screen) {
                    screen.updateNowPlaying(payload.videoId(), payload.title(), payload.progress(), payload.loadingSecondsRemaining(), payload.thumbnail());
                }
            });
        });

        // Receive audio chunks from the server and buffer them for playback
        ClientPlayNetworking.registerGlobalReceiver(AudioChunkPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                LOGGER.debug("Received chunk {}/{} for '{}'",
                        payload.chunkIndex() + 1, payload.totalChunks(), payload.title());
                AudioPlayer.getInstance().receiveChunk(payload);
            });
        });

        // Stop audio when disconnecting from server
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Disconnected from server, stopping audio");
            AudioPlayer.getInstance().stop();
        });
    }
}
