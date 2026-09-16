package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SearchRequestPacket(String query) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SearchRequestPacket> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Mineify.MOD_ID, "search_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SearchRequestPacket> CODEC =
            ByteBufCodecs.STRING_UTF8.map(SearchRequestPacket::new, SearchRequestPacket::query)
                    .cast();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
