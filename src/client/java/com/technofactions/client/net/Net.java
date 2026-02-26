package com.technofactions.client.net;

import com.technofactions.client.state.ClaimCache;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class Net {

    private Net() {}

    /*
     * ================================
     * SNAPSHOT QUERY (C2S)
     * ================================
     */

    public record ClaimQueryPayload(int centerX, int centerZ, int radius) implements CustomPayload {

        public static final Id<ClaimQueryPayload> ID =
                new Id<>(Identifier.of("technofactions", "claim_query"));

        public static final PacketCodec<PacketByteBuf, ClaimQueryPayload> CODEC =
                PacketCodec.of(
                        (value, buf) -> {
                            buf.writeInt(value.centerX());
                            buf.writeInt(value.centerZ());
                            buf.writeInt(value.radius());
                        },
                        buf -> new ClaimQueryPayload(
                                buf.readInt(),
                                buf.readInt(),
                                buf.readInt()
                        )
                );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /*
     * ================================
     * CLAIM REQUEST (C2S)
     * ================================
     */

    public record ClaimRequestPayload(int x1, int z1, int x2, int z2) implements CustomPayload {

        public static final Id<ClaimRequestPayload> ID =
                new Id<>(Identifier.of("technofactions", "claim_request"));

        public static final PacketCodec<PacketByteBuf, ClaimRequestPayload> CODEC =
                PacketCodec.of(
                        (value, buf) -> {
                            buf.writeInt(value.x1());
                            buf.writeInt(value.z1());
                            buf.writeInt(value.x2());
                            buf.writeInt(value.z2());
                        },
                        buf -> new ClaimRequestPayload(
                                buf.readInt(),
                                buf.readInt(),
                                buf.readInt(),
                                buf.readInt()
                        )
                );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /*
     * ================================
     * SNAPSHOT RESPONSE (S2C)
     * ================================
     */

    public record ClaimSnapshotPayload(PacketByteBuf raw) implements CustomPayload {

        public static final Id<ClaimSnapshotPayload> ID =
                new Id<>(Identifier.of("technofactions", "claim_snapshot"));

        public static final PacketCodec<PacketByteBuf, ClaimSnapshotPayload> CODEC =
                PacketCodec.of(
                        (value, buf) -> buf.writeBytes(value.raw()),
                        buf -> new ClaimSnapshotPayload(buf)
                );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /*
     * ================================
     * CLAIM RESULT (S2C)
     * ================================
     */

    public record ClaimResultPayload(boolean success, String message) implements CustomPayload {

        public static final Id<ClaimResultPayload> ID =
                new Id<>(Identifier.of("technofactions", "claim_result"));

        public static final PacketCodec<PacketByteBuf, ClaimResultPayload> CODEC =
                PacketCodec.of(
                        (value, buf) -> {
                            buf.writeBoolean(value.success());
                            buf.writeString(value.message());
                        },
                        buf -> new ClaimResultPayload(
                                buf.readBoolean(),
                                buf.readString(256)
                        )
                );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /*
     * ================================
     * REGISTRATION
     * ================================
     */

    public static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(ClaimQueryPayload.ID, ClaimQueryPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ClaimRequestPayload.ID, ClaimRequestPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(ClaimSnapshotPayload.ID, ClaimSnapshotPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ClaimResultPayload.ID, ClaimResultPayload.CODEC);
    }

    public static void registerClientReceivers() {

        ClientPlayNetworking.registerGlobalReceiver(ClaimSnapshotPayload.ID,
                (payload, context) -> {

                    ClaimCache.readSnapshot(payload.raw());
                });

        ClientPlayNetworking.registerGlobalReceiver(ClaimResultPayload.ID,
                (payload, context) -> {

                    MinecraftClient client = MinecraftClient.getInstance();
                    client.execute(() -> {
                        if (client.player != null) {
                            client.player.sendMessage(
                                    Text.literal((payload.success() ? "§a" : "§c") + payload.message()),
                                    false
                            );
                        }
                    });
                });
    }

    /*
     * ================================
     * SEND METHODS
     * ================================
     */

    public static void requestSnapshot(int centerX, int centerZ, int radius) {
        ClientPlayNetworking.send(new ClaimQueryPayload(centerX, centerZ, radius));
    }
public static void sendClaimChunk(int chunkX, int chunkZ) {
    // send packet to server
}
    public static void requestClaim(int x1, int z1, int x2, int z2) {
        ClientPlayNetworking.send(new ClaimRequestPayload(x1, z1, x2, z2));
    }
}