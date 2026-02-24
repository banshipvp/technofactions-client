package com.technofactions.client.ui;

import com.technofactions.client.net.Net;
import com.technofactions.client.state.ClaimCache;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public final class ClaimMapScreen extends Screen {

    private static final int RADIUS = 10; // 21x21

    private int centerChunkX;
    private int centerChunkZ;

    private boolean dragging = false;
    private int dragStartDX, dragStartDZ;
    private int dragEndDX, dragEndDZ;

    public ClaimMapScreen() {
        super(Text.literal("TechnoFactions Claim Map"));
    }

    @Override
    protected void init() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        centerChunkX = mc.player.getChunkPos().x;
        centerChunkZ = mc.player.getChunkPos().z;

        Net.requestSnapshot(centerChunkX, centerChunkZ, RADIUS);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);

        int w = this.width;
        int h = this.height;

        int mapSizePx = Math.min(w, h) - 60;
        int cellSize = Math.max(6, mapSizePx / (RADIUS * 2 + 1));

        int gridSize = cellSize * (RADIUS * 2 + 1);
        int mapLeft = (w - gridSize) / 2;
        int mapTop  = (h - gridSize) / 2;

        ctx.drawText(textRenderer, "Drag to select chunks, release to claim", 10, 10, 0xFFFFFF, true);

        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {

                int cx = centerChunkX + dx;
                int cz = centerChunkZ + dz;

                int x = mapLeft + (dx + RADIUS) * cellSize;
                int y = mapTop  + (dz + RADIUS) * cellSize;

                int color = 0xFF444444; // wilderness

                ClaimCache.Cell cell = ClaimCache.get(cx, cz);
                if (cell != null) {
                    if (cell.type() == 1) color = 0xFF33AA33;      // own
                    else if (cell.type() == 2) color = 0xFFAA3333; // other
                }

                if (dx == 0 && dz == 0) color = 0xFF33DDDD; // current chunk

                ctx.fill(x, y, x + cellSize - 1, y + cellSize - 1, color);
            }
        }

        if (dragging) {
            int minDX = Math.min(dragStartDX, dragEndDX);
            int maxDX = Math.max(dragStartDX, dragEndDX);
            int minDZ = Math.min(dragStartDZ, dragEndDZ);
            int maxDZ = Math.max(dragStartDZ, dragEndDZ);

            int sx = mapLeft + (minDX + RADIUS) * cellSize;
            int sz = mapTop  + (minDZ + RADIUS) * cellSize;
            int ex = mapLeft + (maxDX + RADIUS + 1) * cellSize;
            int ez = mapTop  + (maxDZ + RADIUS + 1) * cellSize;

            ctx.fill(sx, sz, ex, sz + 2, 0xFFFFFFFF);
            ctx.fill(sx, ez - 2, ex, ez, 0xFFFFFFFF);
            ctx.fill(sx, sz, sx + 2, ez, 0xFFFFFFFF);
            ctx.fill(ex - 2, sz, ex, ez, 0xFFFFFFFF);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int packed = getCellAt(mouseX, mouseY);
        if (packed == Integer.MIN_VALUE) return super.mouseClicked(mouseX, mouseY, button);

        dragging = true;
        int dx = (packed >> 16);
        int dz = (short) (packed & 0xFFFF);

        dragStartDX = dx;
        dragStartDZ = dz;
        dragEndDX = dx;
        dragEndDZ = dz;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!dragging) return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);

        int packed = getCellAt(mouseX, mouseY);
        if (packed == Integer.MIN_VALUE) return true;

        int dx = (packed >> 16);
        int dz = (short) (packed & 0xFFFF);

        dragEndDX = dx;
        dragEndDZ = dz;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != 0 || !dragging) return super.mouseReleased(mouseX, mouseY, button);

        dragging = false;

        int x1 = centerChunkX + dragStartDX;
        int z1 = centerChunkZ + dragStartDZ;
        int x2 = centerChunkX + dragEndDX;
        int z2 = centerChunkZ + dragEndDZ;

        Net.requestClaim(x1, z1, x2, z2);
        return true;
    }

    private int getCellAt(double mouseX, double mouseY) {
        int w = this.width;
        int h = this.height;

        int mapSizePx = Math.min(w, h) - 60;
        int cellSize = Math.max(6, mapSizePx / (RADIUS * 2 + 1));
        int gridSize = cellSize * (RADIUS * 2 + 1);

        int mapLeft = (w - gridSize) / 2;
        int mapTop  = (h - gridSize) / 2;

        int col = (int) ((mouseX - mapLeft) / cellSize);
        int row = (int) ((mouseY - mapTop) / cellSize);

        if (col < 0 || row < 0 || col >= (RADIUS * 2 + 1) || row >= (RADIUS * 2 + 1)) {
            return Integer.MIN_VALUE;
        }

        int dx = col - RADIUS;
        int dz = row - RADIUS;

        return (dx << 16) | (dz & 0xFFFF);
    }
}
