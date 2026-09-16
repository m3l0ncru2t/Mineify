package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RemoveFromHistoryPacket(String videoId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RemoveFromHistoryPacket> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Mineify.MOD_ID, "remove_from_history"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RemoveFromHistoryPacket> CODEC =
            ByteBufCodecs.STRING_UTF8.map(RemoveFromHistoryPacket::new, RemoveFromHistoryPacket::videoId)
                    .cast();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
