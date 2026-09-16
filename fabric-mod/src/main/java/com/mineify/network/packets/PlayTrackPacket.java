package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PlayTrackPacket(String videoId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PlayTrackPacket> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Mineify.MOD_ID, "play_track"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayTrackPacket> CODEC =
            ByteBufCodecs.STRING_UTF8.map(PlayTrackPacket::new, PlayTrackPacket::videoId)
                    .cast();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
