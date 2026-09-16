package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record LoadMoreSearchPacket() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<LoadMoreSearchPacket> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Mineify.MOD_ID, "load_more_search"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LoadMoreSearchPacket> CODEC =
            StreamCodec.unit(new LoadMoreSearchPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
