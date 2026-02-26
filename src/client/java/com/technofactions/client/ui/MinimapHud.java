package com.technofactions.client.ui;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.RenderPipelines;

public final class MinimapHud {
    private static final int PAD = 4;
    private static final int HUD_BLOCKS_PER_PIXEL = 2;
    private static final int HUD_DRAW_SIZE = 128;

    private MinimapHud() {}

    public static void register() {
        HudRenderCallback.EVENT.register((ctx, tickDelta) -> render(ctx));
    }

    private static void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getDebugHud().shouldShowDebugHud()) return;

        if (mc.currentScreen == null && TerrainMinimapShared.isExclusive()) {
            TerrainMinimapShared.resetExclusive();
        }
        if (TerrainMinimapShared.isExclusive()) return;

        TerrainMinimapHud.tick(HUD_BLOCKS_PER_PIXEL);

        int sw = mc.getWindow().getScaledWidth();
        int x0 = sw - HUD_DRAW_SIZE - PAD;
        int y0 = PAD;

        ctx.fill(x0 - 2, y0 - 2, x0 + HUD_DRAW_SIZE + 2, y0 + HUD_DRAW_SIZE + 2, 0xAA000000);

        int s = TerrainMinimapHud.sampleSize();

        // IMPORTANT: use the overload WITH regionWidth/regionHeight to prevent tiling
        ctx.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                TerrainMinimapHud.textureId(),
                x0, y0,
                0f, 0f,
                HUD_DRAW_SIZE, HUD_DRAW_SIZE, // draw size on screen
                s, s,                         // region size in the texture (source)
                s, s                          // texture size
        );

        int cx = x0 + HUD_DRAW_SIZE / 2;
        int cy = y0 + HUD_DRAW_SIZE / 2;
        ctx.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);

        int blocksAcross = TerrainMinimapHud.sampleSize() * TerrainMinimapHud.blocksPerPixel();
        ctx.drawTextWithShadow(mc.textRenderer, "TF Map (M) ~" + blocksAcross + "m", x0 - 92, y0 + 2, 0xFFFFFFFF);

        if (TerrainMinimapHud.isRebuilding()) {
            ctx.drawTextWithShadow(mc.textRenderer, "Updating...", x0 + 4, y0 + HUD_DRAW_SIZE + 4, 0xFFFFFFFF);
        }
    }
}