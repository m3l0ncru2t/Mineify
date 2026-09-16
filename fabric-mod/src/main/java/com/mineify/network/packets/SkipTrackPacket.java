package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SkipTrackPacket() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SkipTrackPacket> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Mineify.MOD_ID, "skip_track"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SkipTrackPacket> CODEC =
            StreamCodec.unit(new SkipTrackPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
