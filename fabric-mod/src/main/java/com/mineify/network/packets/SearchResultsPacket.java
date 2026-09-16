package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record SearchResultsPacket(List<Entry> results, boolean append, boolean hasMore) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SearchResultsPacket> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Mineify.MOD_ID, "search_results"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SearchResultsPacket> CODEC =
            StreamCodec.of(
                    (buf, value) -> {
                        buf.writeVarInt(value.results.size());
                        for (Entry e : value.results) {
                            buf.writeUtf(e.videoId);
                            buf.writeUtf(e.title);
                            buf.writeUtf(e.channel);
                            buf.writeUtf(e.duration);
                            buf.writeUtf(e.thumbnail);
                        }
                        buf.writeBoolean(value.append);
                        buf.writeBoolean(value.hasMore);
                    },
                    buf -> {
                        int size = buf.readVarInt();
                        List<Entry> results = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            results.add(new Entry(
                                    buf.readUtf(), buf.readUtf(), buf.readUtf(),
                                    buf.readUtf(), buf.readUtf()
                            ));
                        }
                        boolean append = buf.readBoolean();
                        boolean hasMore = buf.readBoolean();
                        return new SearchResultsPacket(results, append, hasMore);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public record Entry(String videoId, String title, String channel, String duration, String thumbnail) {}
}
