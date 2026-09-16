package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record HistorySyncPacket(List<PlaylistSyncPacket.Entry> entries, boolean canRemove) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<HistorySyncPacket> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Mineify.MOD_ID, "history_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HistorySyncPacket> CODEC =
            StreamCodec.of(
                    (buf, value) -> {
                        buf.writeVarInt(value.entries.size());
                        for (PlaylistSyncPacket.Entry e : value.entries) {
                            buf.writeUtf(e.videoId());
                            buf.writeUtf(e.title());
                            buf.writeUtf(e.duration());
                            buf.writeUtf(e.addedBy());
                            buf.writeUtf(e.thumbnail());
                        }
                        buf.writeBoolean(value.canRemove);
                    },
                    buf -> {
                        int size = buf.readVarInt();
                        List<PlaylistSyncPacket.Entry> entries = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            entries.add(new PlaylistSyncPacket.Entry(
                                    buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf()
                            ));
                        }
                        boolean canRemove = buf.readBoolean();
                        return new HistorySyncPacket(entries, canRemove);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
