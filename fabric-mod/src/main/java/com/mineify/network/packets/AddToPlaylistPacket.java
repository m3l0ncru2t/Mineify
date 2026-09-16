package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AddToPlaylistPacket(String videoId, String title, String duration, String thumbnail) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<AddToPlaylistPacket> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Mineify.MOD_ID, "add_to_playlist"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AddToPlaylistPacket> CODEC =
            StreamCodec.of(
                    (buf, value) -> {
                        buf.writeUtf(value.videoId);
                        buf.writeUtf(value.title);
                        buf.writeUtf(value.duration);
                        buf.writeUtf(value.thumbnail);
                    },
                    buf -> new AddToPlaylistPacket(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
