package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record NowPlayingPacket(String videoId, String title, float progress, int loadingSecondsRemaining, String thumbnail) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<NowPlayingPacket> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Mineify.MOD_ID, "now_playing"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NowPlayingPacket> CODEC =
            StreamCodec.of(
                    (buf, value) -> {
                        buf.writeUtf(value.videoId);
                        buf.writeUtf(value.title);
                        buf.writeFloat(value.progress);
                        buf.writeVarInt(value.loadingSecondsRemaining);
                        buf.writeUtf(value.thumbnail);
                    },
                    buf -> new NowPlayingPacket(buf.readUtf(), buf.readUtf(), buf.readFloat(), buf.readVarInt(), buf.readUtf())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
