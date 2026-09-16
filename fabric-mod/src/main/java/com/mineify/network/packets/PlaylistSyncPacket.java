package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record PlaylistSyncPacket(List<Entry> entries, boolean isOp, String serverVersion) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PlaylistSyncPacket> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Mineify.MOD_ID, "playlist_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlaylistSyncPacket> CODEC =
            StreamCodec.of(
                    (buf, value) -> {
                        buf.writeVarInt(value.entries.size());
                        for (Entry e : value.entries) {
                            buf.writeUtf(e.videoId);
                            buf.writeUtf(e.title);
                            buf.writeUtf(e.duration);
                            buf.writeUtf(e.addedBy);
                            buf.writeUtf(e.thumbnail);
                        }
                        buf.writeBoolean(value.isOp);
                        buf.writeUtf(value.serverVersion);
                    },
                    buf -> {
                        int size = buf.readVarInt();
                        List<Entry> entries = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            entries.add(new Entry(
                                    buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf()
                            ));
                        }
                        boolean isOp = buf.readBoolean();
                        String serverVersion = buf.readUtf();
                        return new PlaylistSyncPacket(entries, isOp, serverVersion);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public record Entry(String videoId, String title, String duration, String addedBy, String thumbnail) {}
}
