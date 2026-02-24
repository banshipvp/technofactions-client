package com.technofactions.client.net;

import com.technofactions.client.state.ClaimCache;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

public final class Net {

    public static final Identifier CH_QUERY    = Identifier.of("technofactions", "claim_query");
    public static final Identifier CH_SNAPSHOT = Identifier.of("technofactions", "claim_snapshot");
    public static final Identifier CH_REQ      = Identifier.of("technofactions", "claim_request");
    public static final Identifier CH_RES      = Identifier.of("technofactions", "claim_result");

    private Net() {}

    public static void initClientReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(CH_SNAPSHOT, (client, handler, buf, responseSender) -> {
            ClaimCache.readSnapshot(buf);
        });

        ClientPlayNetworking.registerGlobalReceiver(CH_RES, (client, handler, buf, responseSender) -> {
            boolean ok = buf.readBoolean();
            String msg = buf.readString(256);

            client.execute(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player != null) {
                    mc.player.sendMessage(net.minecraft.text.Text.literal((ok ? "§a" : "§c") + msg), false);
                }
            });
        });
    }

    public static void requestSnapshot(int centerChunkX, int centerChunkZ, int radius) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(centerChunkX);
        buf.writeInt(centerChunkZ);
        buf.writeInt(radius);
        ClientPlayNetworking.send(CH_QUERY, buf);
    }

    public static void requestClaim(int x1, int z1, int x2, int z2) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(x1);
        buf.writeInt(z1);
        buf.writeInt(x2);
        buf.writeInt(z2);
        ClientPlayNetworking.send(CH_REQ, buf);
    }
}
