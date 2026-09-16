package com.mineify.network.packets;

import com.mineify.Mineify;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Carries a chunk of raw WAV audio data from the server to clients.
 * The server splits a downloaded WAV file into fixed-size chunks and sends
 * them in order. Clients buffer all chunks and begin playback once the final
 * chunk (chunkIndex == totalChunks - 1) is received.
 */
public record AudioChunkPacket(
        String videoId,
        String title,
        int chunkIndex,
        int totalChunks,
        long startOffsetMs,
        byte[] data
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AudioChunkPacket> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Mineify.MOD_ID, "audio_chunk"));

    // Each chunk is at most 30 KB, safely under Short.MAX_VALUE (32 767).
    // Bumping this well past 32 KB was tried and made corrupted-frame
    // disconnects on the live server reliable (every reconnect) instead of
    // occasional, so something below the 1 MiB Fabric splitting threshold
    // still chokes on much bigger single payloads. Leave this alone.
    public static final int CHUNK_SIZE = 30 * 1024;

    public static final StreamCodec<RegistryFriendlyByteBuf, AudioChunkPacket> CODEC =
            StreamCodec.of(
                    (buf, value) -> {
                        buf.writeUtf(value.videoId);
                        buf.writeUtf(value.title);
                        buf.writeInt(value.chunkIndex);
                        buf.writeInt(value.totalChunks);
                        buf.writeLong(value.startOffsetMs);
                        buf.writeByteArray(value.data);
                    },
                    buf -> new AudioChunkPacket(
                            buf.readUtf(),
                            buf.readUtf(),
                            buf.readInt(),
                            buf.readInt(),
                            buf.readLong(),
                            buf.readByteArray(CHUNK_SIZE)
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
