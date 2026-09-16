package com.mineify.network;

import com.mineify.Mineify;
import com.mineify.network.packets.AddToPlaylistPacket;
import com.mineify.network.packets.AudioChunkPacket;
import com.mineify.network.packets.HistorySyncPacket;
import com.mineify.network.packets.LoadMoreSearchPacket;
import com.mineify.network.packets.PlayTrackPacket;
import com.mineify.network.packets.RemoveFromHistoryPacket;
import com.mineify.network.packets.RemoveFromPlaylistPacket;
import com.mineify.network.packets.SearchRequestPacket;
import com.mineify.network.packets.SkipTrackPacket;
import com.mineify.network.packets.StopTrackPacket;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class MineifyPackets {
    public static void registerServerPackets() {
        // Register C2S (client-to-server) packet types
        PayloadTypeRegistry.serverboundPlay().register(SearchRequestPacket.ID, SearchRequestPacket.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(LoadMoreSearchPacket.ID, LoadMoreSearchPacket.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AddToPlaylistPacket.ID, AddToPlaylistPacket.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RemoveFromPlaylistPacket.ID, RemoveFromPlaylistPacket.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SkipTrackPacket.ID, SkipTrackPacket.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PlayTrackPacket.ID, PlayTrackPacket.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StopTrackPacket.ID, StopTrackPacket.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RemoveFromHistoryPacket.ID, RemoveFromHistoryPacket.CODEC);

        // Register S2C (server-to-client) packet types
        PayloadTypeRegistry.clientboundPlay().register(
                com.mineify.network.packets.SearchResultsPacket.ID,
                com.mineify.network.packets.SearchResultsPacket.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                com.mineify.network.packets.PlaylistSyncPacket.ID,
                com.mineify.network.packets.PlaylistSyncPacket.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                com.mineify.network.packets.NowPlayingPacket.ID,
                com.mineify.network.packets.NowPlayingPacket.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                AudioChunkPacket.ID,
                AudioChunkPacket.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(HistorySyncPacket.ID, HistorySyncPacket.CODEC);

        // Register server-side handlers
        ServerPlayNetworking.registerGlobalReceiver(SearchRequestPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleSearch(context.player(), payload.query());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(LoadMoreSearchPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleLoadMoreSearch(context.player());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(AddToPlaylistPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleAddToPlaylist(context.player(), payload.videoId(), payload.title(), payload.duration(), payload.thumbnail());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(RemoveFromPlaylistPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleRemoveFromPlaylist(context.player(), payload.videoId());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SkipTrackPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleSkip(context.player());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(PlayTrackPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handlePlaySpecific(context.player(), payload.videoId());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(StopTrackPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleStop(context.player());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(RemoveFromHistoryPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                var manager = Mineify.getPlaylistManager();
                if (manager != null) {
                    manager.handleRemoveFromHistory(context.player(), payload.videoId());
                }
            });
        });
    }

    public static void registerClientPackets() {
        // Client-side packet handlers are registered in MineifyClient
    }
}
