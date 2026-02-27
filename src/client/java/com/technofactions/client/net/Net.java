package com.technofactions.client.net;

import com.technofactions.client.state.ClaimCache;
import com.technofactions.client.ui.ClaimMapScreen;
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

    private static final Identifier CLAIM_QUERY_ID =
            Identifier.of("technofactions", "claim_query");

    private static final Identifier CLAIM_REQUEST_ID =
            Identifier.of("technofactions", "claim_request");

    private static final Identifier UNCLAIM_REQUEST_ID =
            Identifier.of("technofactions", "unclaim_request");

    private static final Identifier CLAIM_RESULT_ID =
            Identifier.of("technofactions", "claim_result");

    private static final Identifier CLAIM_SNAPSHOT_ID =
            Identifier.of("technofactions", "claim_snapshot");

    /*
     * ===============================
     * CLAIM QUERY (C2S)
     * ===============================
     */

    public record ClaimQueryPayload(int centerX, int centerZ, int radius)
            implements CustomPayload {

        public static final Id<ClaimQueryPayload> ID =
                new Id<>(CLAIM_QUERY_ID);

        public static final PacketCodec<PacketByteBuf, ClaimQueryPayload> CODEC =
                PacketCodec.of(
                        (value, buf) -> {
                            buf.writeInt(value.centerX);
                            buf.writeInt(value.centerZ);
                            buf.writeInt(value.radius);
                        },
                        buf -> new ClaimQueryPayload(
                                buf.readInt(),
                                buf.readInt(),
                                buf.readInt()
                        )
                );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /*
     * ===============================
     * CLAIM / UNCLAIM (C2S)
     * ===============================
     */

    public record ClaimRequestPayload(int x1, int z1, int x2, int z2)
            implements CustomPayload {

        public static final Id<ClaimRequestPayload> ID =
                new Id<>(CLAIM_REQUEST_ID);

        public static final PacketCodec<PacketByteBuf, ClaimRequestPayload> CODEC =
                PacketCodec.of(
                        (value, buf) -> {
                            buf.writeInt(value.x1);
                            buf.writeInt(value.z1);
                            buf.writeInt(value.x2);
                            buf.writeInt(value.z2);
                        },
                        buf -> new ClaimRequestPayload(
                                buf.readInt(),
                                buf.readInt(),
                                buf.readInt(),
                                buf.readInt()
                        )
                );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record UnclaimRequestPayload(int x1, int z1, int x2, int z2)
            implements CustomPayload {

        public static final Id<UnclaimRequestPayload> ID =
                new Id<>(UNCLAIM_REQUEST_ID);

        public static final PacketCodec<PacketByteBuf, UnclaimRequestPayload> CODEC =
                PacketCodec.of(
                        (value, buf) -> {
                            buf.writeInt(value.x1);
                            buf.writeInt(value.z1);
                            buf.writeInt(value.x2);
                            buf.writeInt(value.z2);
                        },
                        buf -> new UnclaimRequestPayload(
                                buf.readInt(),
                                buf.readInt(),
                                buf.readInt(),
                                buf.readInt()
                        )
                );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /*
     * ===============================
     * CLAIM RESULT (S2C)
     * ===============================
     */

    public record ClaimResultPayload(boolean success, String message)
            implements CustomPayload {

        public static final Id<ClaimResultPayload> ID =
                new Id<>(CLAIM_RESULT_ID);

        public static final PacketCodec<PacketByteBuf, ClaimResultPayload> CODEC =
                PacketCodec.of(
                        (value, buf) -> {
                            buf.writeBoolean(value.success);
                            buf.writeString(value.message);
                        },
                        buf -> new ClaimResultPayload(
                                buf.readBoolean(),
                                buf.readString(256)
                        )
                );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /*
     * ===============================
     * CLAIM SNAPSHOT (S2C)
     * ===============================
     */

    public record ClaimSnapshotPayload() implements CustomPayload {

        public static final Id<ClaimSnapshotPayload> ID =
                new Id<>(CLAIM_SNAPSHOT_ID);

        public static final PacketCodec<PacketByteBuf, ClaimSnapshotPayload> CODEC =
                PacketCodec.of(
                        (value, buf) -> {},
                        buf -> {
                            ClaimCache.readSnapshot(buf);
                            return new ClaimSnapshotPayload();
                        }
                );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /*
     * ===============================
     * REGISTER
     * ===============================
     */

    public static void registerPayloads() {

        PayloadTypeRegistry.playC2S().register(ClaimQueryPayload.ID, ClaimQueryPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ClaimRequestPayload.ID, ClaimRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(UnclaimRequestPayload.ID, UnclaimRequestPayload.CODEC);

        PayloadTypeRegistry.playS2C().register(ClaimResultPayload.ID, ClaimResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ClaimSnapshotPayload.ID, ClaimSnapshotPayload.CODEC);
    }

    public static void registerClientReceivers() {

        ClientPlayNetworking.registerGlobalReceiver(
                ClaimResultPayload.ID,
                (payload, context) -> context.client().execute(() -> {

                    MinecraftClient mc = MinecraftClient.getInstance();

                    if (mc.player != null) {
                        mc.player.sendMessage(
                                Text.literal((payload.success ? "§a" : "§c") + payload.message),
                                false
                        );
                    }

                    if (payload.success) {
                        // Clear optimistic overlays and pull a fresh snapshot
                        ClaimMapScreen.clearPending();
                        ClaimMapScreen.requestFreshSnapshot();
                    }
                })
        );

        ClientPlayNetworking.registerGlobalReceiver(
                ClaimSnapshotPayload.ID,
                (payload, context) -> {
                    // ClaimCache.readSnapshot already ran in CODEC decode
                }
        );
    }

    /*
     * ===============================
     * SEND
     * ===============================
     */

    public static void requestSnapshot(int cx, int cz, int radius) {
        ClientPlayNetworking.send(new ClaimQueryPayload(cx, cz, radius));
    }

    public static void requestClaim(int x1, int z1, int x2, int z2) {
        ClientPlayNetworking.send(new ClaimRequestPayload(x1, z1, x2, z2));
    }

    public static void requestUnclaim(int x1, int z1, int x2, int z2) {
        ClientPlayNetworking.send(new UnclaimRequestPayload(x1, z1, x2, z2));
    }
}