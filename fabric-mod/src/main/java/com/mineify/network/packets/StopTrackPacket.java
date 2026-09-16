package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record StopTrackPacket() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StopTrackPacket> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Mineify.MOD_ID, "stop_track"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StopTrackPacket> CODEC =
            StreamCodec.unit(new StopTrackPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
