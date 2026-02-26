package com.technofactions.client.ui;

import com.technofactions.client.net.Net;
import com.technofactions.client.state.ClaimCache;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class ClaimMapScreen extends Screen {
    private static final int SNAPSHOT_RADIUS = 10;
    private static final int MAX_CHUNKS_ACROSS = 121; // perf cap (odd recommended)
    private static final LongSet PENDING = new LongOpenHashSet();

    // blocks-per-pixel choices
    private static final int[] ZOOMS = {1, 2, 4, 8, 16};
    private int zoomIndex = 2; // default bpp=4

    private int viewCenterX;
    private int viewCenterZ;

    private boolean followPlayer = true; // default ON

    // derived each frame
    private int centerChunkX;
    private int centerChunkZ;

    // selection/erase in ABSOLUTE chunk coords (fixes drift + half-cell issues)
    private boolean dragging = false;
    private int dragStartChunkX, dragStartChunkZ;
    private int dragEndChunkX, dragEndChunkZ;

    private boolean erasing = false;
    private int eraseStartChunkX, eraseStartChunkZ;
    private int eraseEndChunkX, eraseEndChunkZ;

    private boolean prevLeftDown = false;
    private boolean prevRightDown = false;

    private boolean prevZoomIn = false;
    private boolean prevZoomOut = false;
    private boolean prevFollow = false;

    public ClaimMapScreen() {
        super(Text.literal("TechnoFactions Claim Map"));
    }

    private static long key(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static int chunkBoundaryFloor(int blockX) {
        return Math.floorDiv(blockX, 16) * 16;
    }

    private static int chunkXFromBlock(double worldX) {
        return Math.floorDiv((int) Math.floor(worldX), 16);
    }

    private static int chunkZFromBlock(double worldZ) {
        return Math.floorDiv((int) Math.floor(worldZ), 16);
    }

    @Override
    protected void init() {
        super.init();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        TerrainMinimapShared.beginExclusive();

        ScreenMouseEvents.allowMouseScroll(this).register((screen, mouseX, mouseY, horizontalAmount, verticalAmount) -> {
            if (verticalAmount > 0) {
                if (zoomIndex > 0) zoomIndex--;
            } else if (verticalAmount < 0) {
                if (zoomIndex < ZOOMS.length - 1) zoomIndex++;
            }
            return true;
        });

        viewCenterX = mc.player.getBlockX();
        viewCenterZ = mc.player.getBlockZ();

        centerChunkX = Math.floorDiv(viewCenterX, 16);
        centerChunkZ = Math.floorDiv(viewCenterZ, 16);

        Net.requestSnapshot(centerChunkX, centerChunkZ, SNAPSHOT_RADIUS);
    }

    @Override
    public void close() {
        TerrainMinimapShared.endExclusive();
        super.close();
    }

    @Override
    public void removed() {
        TerrainMinimapShared.endExclusive();
        super.removed();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();

        pollKeys();
        pollMouse(mouseX, mouseY);

        if (followPlayer && mc.player != null) {
            viewCenterX = mc.player.getBlockX();
            viewCenterZ = mc.player.getBlockZ();
        }

        centerChunkX = Math.floorDiv(viewCenterX, 16);
        centerChunkZ = Math.floorDiv(viewCenterZ, 16);

        int sample = TerrainMinimapFull.sampleSize();
        int bpp = ZOOMS[zoomIndex];

        int mapSize = Math.min(this.width, this.height) - 40;
        mapSize = Math.max(200, mapSize);

        int mapX = (this.width - mapSize) / 2;
        int mapY = (this.height - mapSize) / 2;

        // dim background
        ctx.fill(0, 0, this.width, this.height, 0x33000000);

        // terrain
        drawTerrainSquare(ctx, mapX, mapY, mapSize, mapSize);
        drawCompassOutside(ctx, mapX, mapY, mapSize, mapSize);

        int blocksAcross = sample * bpp;
        ctx.drawTextWithShadow(textRenderer,
                "Zoom: " + bpp + " blocks/pixel (~" + blocksAcross + "m)   Scroll zoom   F follow: " + (followPlayer ? "ON" : "OFF"),
                10, 10, 0xFFFFFFFF);

        // World window (ASSUMES TerrainMinimapFull renders a sample x sample texture covering sample*bpp blocks)
        // Half-block center fix included.
        double halfBlocks = (sample * (double) bpp) / 2.0;
        double worldLeftX = (viewCenterX + 0.5) - halfBlocks;
        double worldTopZ  = (viewCenterZ + 0.5) - halfBlocks;
        double worldRightX = worldLeftX + sample * (double) bpp;
        double worldBottomZ = worldTopZ + sample * (double) bpp;

        // pixel scale
        double pxPerTexel = mapSize / (double) sample;
        double pxPerBlock = pxPerTexel / (double) bpp;
        double pxPerChunk = 16.0 * pxPerBlock;

        // limit visible chunk count for safety
        int chunksAcross = (int) Math.ceil(mapSize / Math.max(2.0, pxPerChunk));
        if ((chunksAcross & 1) == 0) chunksAcross--;
        if (chunksAcross < 3) chunksAcross = 3;
        if (chunksAcross > MAX_CHUNKS_ACROSS) chunksAcross = MAX_CHUNKS_ACROSS;

        // hover chunk (absolute)
        Hover hover = getHover(mouseX, mouseY, mapX, mapY, mapSize, worldLeftX, worldTopZ, pxPerBlock);

        if (hover != null) {
            ctx.drawTextWithShadow(textRenderer,
                    "Hover chunk: " + hover.chunkX + ", " + hover.chunkZ,
                    10, 24, 0xFFFFFFFF);
        } else {
            ctx.drawTextWithShadow(textRenderer,
                    "View center: X=" + viewCenterX + " Z=" + viewCenterZ + "   |   RMB drag = erase pending",
                    10, 24, 0xFFFFFFFF);
        }

        // --- draw chunk border grid aligned to real chunk boundaries ---
        int lineColor = 0x33555555;

        int firstBorderX = chunkBoundaryFloor((int) Math.floor(worldLeftX));
        int firstBorderZ = chunkBoundaryFloor((int) Math.floor(worldTopZ));

        // vertical borders
        for (int bx = firstBorderX; bx <= (int) Math.ceil(worldRightX) + 16; bx += 16) {
            double screenX = mapX + (bx - worldLeftX) * pxPerBlock;
            int sx = (int) Math.round(screenX);
            if (sx < mapX) continue;
            if (sx > mapX + mapSize) break;
            ctx.fill(sx, mapY, sx + 1, mapY + mapSize, lineColor);
        }

        // horizontal borders
        for (int bz = firstBorderZ; bz <= (int) Math.ceil(worldBottomZ) + 16; bz += 16) {
            double screenY = mapY + (bz - worldTopZ) * pxPerBlock;
            int sy = (int) Math.round(screenY);
            if (sy < mapY) continue;
            if (sy > mapY + mapSize) break;
            ctx.fill(mapX, sy, mapX + mapSize, sy + 1, lineColor);
        }

        // --- fill interesting chunks only ---
        // compute visible chunk range (clamped by chunksAcross cap)
        int centerCx = centerChunkX;
        int centerCz = centerChunkZ;
        int radius = chunksAcross / 2;

        int minCx = centerCx - radius;
        int maxCx = centerCx + radius;
        int minCz = centerCz - radius;
        int maxCz = centerCz + radius;

        // selection area (absolute coords)
        int selMinX = Math.min(dragStartChunkX, dragEndChunkX);
        int selMaxX = Math.max(dragStartChunkX, dragEndChunkX);
        int selMinZ = Math.min(dragStartChunkZ, dragEndChunkZ);
        int selMaxZ = Math.max(dragStartChunkZ, dragEndChunkZ);

        int erMinX = Math.min(eraseStartChunkX, eraseEndChunkX);
        int erMaxX = Math.max(eraseStartChunkX, eraseEndChunkX);
        int erMinZ = Math.min(eraseStartChunkZ, eraseEndChunkZ);
        int erMaxZ = Math.max(eraseStartChunkZ, eraseEndChunkZ);

        for (int cz = minCz; cz <= maxCz; cz++) {
            for (int cx = minCx; cx <= maxCx; cx++) {
                ClaimCache.Cell cell = ClaimCache.get(cx, cz);
                boolean pending = PENDING.contains(key(cx, cz));
                boolean selected = dragging && cx >= selMinX && cx <= selMaxX && cz >= selMinZ && cz <= selMaxZ;
                boolean erasedArea = erasing && cx >= erMinX && cx <= erMaxX && cz >= erMinZ && cz <= erMaxZ;

                if (cell == null && !pending && !selected && !erasedArea) continue;

                Rect r = chunkRectOnScreen(cx, cz, mapX, mapY, mapSize, worldLeftX, worldTopZ, pxPerBlock);
                if (r == null) continue;
                if (r.w < 2 || r.h < 2) continue;

                int color = 0x00000000;
                if (cell != null) {
                    if (cell.type() == 1) color = 0x7733AA33;
                    else if (cell.type() == 2) color = 0x77AA3333;
                    else color = 0x77444444;
                }
                if (pending) color = 0xCCAA66FF;
                if (selected) color = 0xCCFFFF00;
                if (erasedArea) color = 0x66FFFFFF;

                ctx.fill(r.x + 1, r.y + 1, r.x + r.w - 1, r.y + r.h - 1, color);

                if (hover != null && hover.chunkX == cx && hover.chunkZ == cz) {
                    drawRectOutline(ctx, r.x, r.y, r.w, r.h, 0xFFFFFFFF);
                }
            }
        }

        // player marker: clean filled triangle (points where the player is facing)
        drawPlayerTriangle(ctx, mapX, mapY, mapSize, mc);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private Rect chunkRectOnScreen(int chunkX, int chunkZ,
                                  int mapX, int mapY, int mapSize,
                                  double worldLeftX, double worldTopZ,
                                  double pxPerBlock) {
        double chunkLeftWorldX = chunkX * 16.0;
        double chunkTopWorldZ  = chunkZ * 16.0;

        double x0d = mapX + (chunkLeftWorldX - worldLeftX) * pxPerBlock;
        double y0d = mapY + (chunkTopWorldZ  - worldTopZ)  * pxPerBlock;
        double x1d = mapX + ((chunkLeftWorldX + 16.0) - worldLeftX) * pxPerBlock;
        double y1d = mapY + ((chunkTopWorldZ  + 16.0) - worldTopZ)  * pxPerBlock;

        int x0 = (int) Math.round(x0d);
        int y0 = (int) Math.round(y0d);
        int x1 = (int) Math.round(x1d);
        int y1 = (int) Math.round(y1d);

        // clip
        if (x1 <= mapX || y1 <= mapY || x0 >= mapX + mapSize || y0 >= mapY + mapSize) return null;

        int cx0 = Math.max(x0, mapX);
        int cy0 = Math.max(y0, mapY);
        int cx1 = Math.min(x1, mapX + mapSize);
        int cy1 = Math.min(y1, mapY + mapSize);

        return new Rect(cx0, cy0, cx1 - cx0, cy1 - cy0);
    }

    private Hover getHover(int mouseX, int mouseY,
                           int mapX, int mapY, int mapSize,
                           double worldLeftX, double worldTopZ,
                           double pxPerBlock) {
        if (mouseX < mapX || mouseY < mapY || mouseX >= mapX + mapSize || mouseY >= mapY + mapSize) return null;

        double worldX = worldLeftX + (mouseX - mapX) / pxPerBlock;
        double worldZ = worldTopZ  + (mouseY - mapY) / pxPerBlock;

        int chunkX = chunkXFromBlock(worldX);
        int chunkZ = chunkZFromBlock(worldZ);

        return new Hover(chunkX, chunkZ);
    }

    private void drawPlayerTriangle(DrawContext ctx, int mapX, int mapY, int mapSize, MinecraftClient mc) {
        if (mc.player == null) return;

        // marker at center of map (because viewCenter is either player or panned center)
        int cx = mapX + mapSize / 2;
        int cy = mapY + mapSize / 2;

        // Minecraft yaw: 0=south, 180/-180=north. Screen Y increases downward.
        double yaw = Math.toRadians(mc.player.getYaw());
        double fx = -Math.sin(yaw);
        double fy =  Math.cos(yaw);

        int tipLen = 10;
        int baseLen = 6;

        int tipX = cx + (int) Math.round(fx * tipLen);
        int tipY = cy + (int) Math.round(fy * tipLen);

        // perpendicular
        double px = -fy;
        double py =  fx;

        int leftX  = cx + (int) Math.round(px * baseLen);
        int leftY  = cy + (int) Math.round(py * baseLen);
        int rightX = cx - (int) Math.round(px * baseLen);
        int rightY = cy - (int) Math.round(py * baseLen);

        fillTriangle(ctx, tipX, tipY, leftX, leftY, rightX, rightY, 0xFFFFFFFF);

        // small outline for visibility
        drawLinePixels(ctx, tipX, tipY, leftX, leftY, 0xFF000000);
        drawLinePixels(ctx, leftX, leftY, rightX, rightY, 0xFF000000);
        drawLinePixels(ctx, rightX, rightY, tipX, tipY, 0xFF000000);
    }

    private void fillTriangle(DrawContext ctx,
                              int x1, int y1, int x2, int y2, int x3, int y3,
                              int color) {
        int minY = Math.min(y1, Math.min(y2, y3));
        int maxY = Math.max(y1, Math.max(y2, y3));

        for (int y = minY; y <= maxY; y++) {
            int[] xs = new int[3];
            int n = 0;

            n = addIntersect(xs, n, x1, y1, x2, y2, y);
            n = addIntersect(xs, n, x2, y2, x3, y3, y);
            n = addIntersect(xs, n, x3, y3, x1, y1, y);

            if (n < 2) continue;

            int a = xs[0], b = xs[1];
            if (a > b) { int t = a; a = b; b = t; }

            ctx.fill(a, y, b + 1, y + 1, color);
        }
    }

    private int addIntersect(int[] xs, int n, int x1, int y1, int x2, int y2, int y) {
        if (y1 == y2) return n;
        if (y < Math.min(y1, y2) || y > Math.max(y1, y2)) return n;

        double t = (y - y1) / (double) (y2 - y1);
        int x = (int) Math.round(x1 + t * (x2 - x1));
        xs[n++] = x;
        return n;
    }

    private void drawLinePixels(DrawContext ctx, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        int x = x0;
        int y = y0;

        while (true) {
            ctx.fill(x, y, x + 1, y + 1, color);
            if (x == x1 && y == y1) break;
            int e2 = err << 1;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 <  dx) { err += dx; y += sy; }
        }
    }

    private void drawTerrainSquare(DrawContext ctx, int x0, int y0, int w, int h) {
        int bpp = ZOOMS[zoomIndex];
        TerrainMinimapFull.tickAt(bpp, viewCenterX, viewCenterZ);

        int s = TerrainMinimapFull.sampleSize();

        ctx.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                TerrainMinimapFull.textureId(),
                x0, y0,
                0f, 0f,
                w, h,
                s, s,
                s, s
        );

        drawRectOutline(ctx, x0, y0, w, h, 0xAA000000);

        if (TerrainMinimapFull.isRebuilding()) {
            ctx.drawTextWithShadow(textRenderer, "Updating...", x0 + 6, y0 + 6, 0xFFFFFFFF);
        }
    }

    private void drawCompassOutside(DrawContext ctx, int mapX, int mapY, int mapW, int mapH) {
        ctx.drawTextWithShadow(textRenderer, "N", mapX + mapW / 2 - 3, mapY - 18, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, "S", mapX + mapW / 2 - 3, mapY + mapH + 6, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, "W", mapX - 14, mapY + mapH / 2 - 4, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, "E", mapX + mapW + 6, mapY + mapH / 2 - 4, 0xFFFFFFFF);
    }

    private void pollKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        long handle = mc.getWindow().getHandle();

        boolean zoomIn  = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_BRACKET) == GLFW.GLFW_PRESS;
        boolean zoomOut = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_BRACKET) == GLFW.GLFW_PRESS;
        boolean follow  = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F) == GLFW.GLFW_PRESS;

        if (zoomIn && !prevZoomIn) {
            if (zoomIndex > 0) zoomIndex--;
        }
        if (zoomOut && !prevZoomOut) {
            if (zoomIndex < ZOOMS.length - 1) zoomIndex++;
        }
        if (follow && !prevFollow) {
            followPlayer = !followPlayer;
        }

        prevZoomIn = zoomIn;
        prevZoomOut = zoomOut;
        prevFollow = follow;
    }

    private void pollMouse(int mouseX, int mouseY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        long handle = mc.getWindow().getHandle();

        boolean leftDown  = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS;
        boolean rightDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_2) == GLFW.GLFW_PRESS;

        int sample = TerrainMinimapFull.sampleSize();
        int bpp = ZOOMS[zoomIndex];

        int mapSize = Math.min(this.width, this.height) - 40;
        mapSize = Math.max(200, mapSize);

        int mapX = (this.width - mapSize) / 2;
        int mapY = (this.height - mapSize) / 2;

        double halfBlocks = (sample * (double) bpp) / 2.0;
        double worldLeftX = (viewCenterX + 0.5) - halfBlocks;
        double worldTopZ  = (viewCenterZ + 0.5) - halfBlocks;

        double pxPerTexel = mapSize / (double) sample;
        double pxPerBlock = pxPerTexel / (double) bpp;

        Hover h = getHover(mouseX, mouseY, mapX, mapY, mapSize, worldLeftX, worldTopZ, pxPerBlock);

        // Left select
        if (leftDown && !prevLeftDown) {
            if (h != null) {
                dragging = true;
                dragStartChunkX = dragEndChunkX = h.chunkX;
                dragStartChunkZ = dragEndChunkZ = h.chunkZ;
            }
        }
        if (leftDown && dragging) {
            if (h != null) {
                dragEndChunkX = h.chunkX;
                dragEndChunkZ = h.chunkZ;
            }
        }
        if (!leftDown && prevLeftDown) {
            if (dragging) {
                dragging = false;

                int minX = Math.min(dragStartChunkX, dragEndChunkX);
                int maxX = Math.max(dragStartChunkX, dragEndChunkX);
                int minZ = Math.min(dragStartChunkZ, dragEndChunkZ);
                int maxZ = Math.max(dragStartChunkZ, dragEndChunkZ);

                for (int cx = minX; cx <= maxX; cx++) {
                    for (int cz = minZ; cz <= maxZ; cz++) {
                        PENDING.add(key(cx, cz));
                        Net.sendClaimChunk(cx, cz);
                    }
                }
            }
        }

        // Right erase pending
        if (rightDown && !prevRightDown) {
            if (h != null) {
                erasing = true;
                eraseStartChunkX = eraseEndChunkX = h.chunkX;
                eraseStartChunkZ = eraseEndChunkZ = h.chunkZ;
            }
        }
        if (rightDown && erasing) {
            if (h != null) {
                eraseEndChunkX = h.chunkX;
                eraseEndChunkZ = h.chunkZ;
            }
        }
        if (!rightDown && prevRightDown) {
            if (erasing) {
                erasing = false;

                int minX = Math.min(eraseStartChunkX, eraseEndChunkX);
                int maxX = Math.max(eraseStartChunkX, eraseEndChunkX);
                int minZ = Math.min(eraseStartChunkZ, eraseEndChunkZ);
                int maxZ = Math.max(eraseStartChunkZ, eraseEndChunkZ);

                for (int cx = minX; cx <= maxX; cx++) {
                    for (int cz = minZ; cz <= maxZ; cz++) {
                        PENDING.remove(key(cx, cz));
                    }
                }
            }
        }

        prevLeftDown = leftDown;
        prevRightDown = rightDown;
    }

    private static void drawRectOutline(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    // --- small structs ---
    private static final class Rect {
        final int x, y, w, h;
        Rect(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }
    }

    private static final class Hover {
        final int chunkX, chunkZ;
        Hover(int chunkX, int chunkZ) { this.chunkX = chunkX; this.chunkZ = chunkZ; }
    }
}