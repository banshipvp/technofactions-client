package com.technofactions.client.ui;

import com.technofactions.client.net.Net;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

public final class MinimapHud {

    private static final int RADIUS = 6;
    private static int lastChunkX = Integer.MIN_VALUE;
    private static int lastChunkZ = Integer.MIN_VALUE;

    private MinimapHud() {}

    public static void register() {
        HudRenderCallback.EVENT.register((DrawContext ctx, float tickDelta) -> render(ctx));
    }

    private static void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getDebugHud().shouldShowDebugHud()) return;

        int cx = mc.player.getChunkPos().x;
        int cz = mc.player.getChunkPos().z;

        if (cx != lastChunkX || cz != lastChunkZ) {
            lastChunkX = cx;
            lastChunkZ = cz;
            Net.requestSnapshot(cx, cz, RADIUS);
        }

        // Minimal placeholder HUD indicator (top right)
        int x = mc.getWindow().getScaledWidth() - 80;
        int y = 10;

        ctx.drawText(mc.textRenderer, "TF Map: M", x, y, 0xFFFFFF, true);
    }
}
