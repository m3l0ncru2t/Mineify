package com.mineify;

import com.mineify.network.MineifyPackets;
import com.mineify.server.AudioDownloadService;
import com.mineify.server.PlaylistManager;
import com.mineify.server.YouTubeService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Main entry point for the Mineify mod.
 * Handles server-side initialization and lifecycle events.
 */
public class Mineify implements ModInitializer {
    public static final String MOD_ID = "mineify";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Read once from fabric.mod.json rather than hardcoded, so it can't drift
    // out of sync with the jar's actual declared version.
    public static final String VERSION = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");

    private static PlaylistManager playlistManager;
    private static YouTubeService youTubeService;
    private static AudioDownloadService audioDownloadService;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Mineify - Server-Wide Music Player");

        MineifyPackets.registerServerPackets();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            LOGGER.info("Mineify: Server starting, initializing components...");

            youTubeService = new YouTubeService();

            try {
                audioDownloadService = new AudioDownloadService(
                        Path.of(MineifyConfig.getDownloadDir())
                );
            } catch (IOException e) {
                LOGGER.error("Mineify: Failed to initialise audio download service.", e);
                return;
            }

            playlistManager = new PlaylistManager(server, youTubeService, audioDownloadService);

            LOGGER.info("Mineify: Components initialized successfully");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Mineify: Server stopping, cleaning up...");

            if (playlistManager != null) {
                playlistManager.shutdown();
            }
            if (audioDownloadService != null) {
                audioDownloadService.shutdown();
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            LOGGER.info("Mineify: Player {} joined, syncing playlist state",
                    handler.player.getName().getString());

            if (playlistManager != null) {
                playlistManager.syncToPlayer(handler.player);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            LOGGER.info("Mineify: Player {} disconnected",
                    handler.player.getName().getString());
            if (playlistManager != null) {
                playlistManager.handleDisconnect(handler.player);
            }
        });

        LOGGER.info("Mineify initialized successfully!");
    }

    public static PlaylistManager getPlaylistManager() {
        return playlistManager;
    }
}
