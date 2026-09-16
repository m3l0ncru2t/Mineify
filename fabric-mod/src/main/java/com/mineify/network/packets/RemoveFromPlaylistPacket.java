package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RemoveFromPlaylistPacket(String videoId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RemoveFromPlaylistPacket> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Mineify.MOD_ID, "remove_from_playlist"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RemoveFromPlaylistPacket> CODEC =
            StreamCodec.of(
                    (buf, value) -> buf.writeUtf(value.videoId),
                    buf -> new RemoveFromPlaylistPacket(buf.readUtf())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
