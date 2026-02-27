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

    private static final int SNAPSHOT_MARGIN_CHUNKS = 6;
    private static final int MAX_CHUNKS_ACROSS = 121;

    private static final LongSet PENDING_CLAIM = new LongOpenHashSet();
    private static final LongSet PENDING_UNCLAIM = new LongOpenHashSet();

    private static boolean HAS_LAST_VIEW = false;
    private static int LAST_ZOOM_INDEX = 2;
    private static int LAST_VIEW_CENTER_X = 0;
    private static int LAST_VIEW_CENTER_Z = 0;
    private static boolean LAST_FOLLOW = true;

    private static ClaimMapScreen ACTIVE = null;


private static final int[] ZOOMS = {512, 1024, 2048};
    private int zoomIndex = 2;

    private int viewCenterX;
    private int viewCenterZ;
    private boolean followPlayer = true;

    private int centerChunkX;
    private int centerChunkZ;

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

    private boolean panning = false;
    private boolean prevMiddleDown = false;
    private double panStartMouseX, panStartMouseY;
    private int panStartCenterX, panStartCenterZ;

    private int lastSnapshotCenterCx = Integer.MIN_VALUE;
    private int lastSnapshotCenterCz = Integer.MIN_VALUE;
    private int lastSnapshotRadius = -1;
    private int snapshotCooldownTicks = 0;

    public ClaimMapScreen() {
        super(Text.literal("TechnoFactions Claim Map"));
    }

    private static long key(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static int chunkXFromBlock(double worldX) {
        return Math.floorDiv((int) Math.floor(worldX), 16);
    }

    private static int chunkZFromBlock(double worldZ) {
        return Math.floorDiv((int) Math.floor(worldZ), 16);
    }

    public static void clearPending() {
        PENDING_CLAIM.clear();
        PENDING_UNCLAIM.clear();
    }

    public static void requestFreshSnapshot() {
        if (ACTIVE == null) return;
        ACTIVE.forceSnapshotSoon();
    }

    private void forceSnapshotSoon() {
        this.lastSnapshotCenterCx = Integer.MIN_VALUE;
        this.lastSnapshotCenterCz = Integer.MIN_VALUE;
        this.lastSnapshotRadius = -1;
        this.snapshotCooldownTicks = 0;
    }

    @Override
    protected void init() {
        super.init();

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        ACTIVE = this;
        TerrainMinimapShared.beginExclusive();

        PENDING_CLAIM.clear();
        PENDING_UNCLAIM.clear();

        ScreenMouseEvents.allowMouseScroll(this).register((screen, mouseX, mouseY, horizontalAmount, verticalAmount) -> {
            if (verticalAmount > 0) {
                if (zoomIndex > 0) zoomIndex--;
            } else if (verticalAmount < 0) {
                if (zoomIndex < ZOOMS.length - 1) zoomIndex++;
            }
            return true;
        });

        if (HAS_LAST_VIEW) {
            zoomIndex = Math.max(0, Math.min(ZOOMS.length - 1, LAST_ZOOM_INDEX));
            viewCenterX = LAST_VIEW_CENTER_X;
            viewCenterZ = LAST_VIEW_CENTER_Z;
            followPlayer = LAST_FOLLOW;
        } else {
            zoomIndex = 2;
            followPlayer = true;
            viewCenterX = mc.player.getBlockX();
            viewCenterZ = mc.player.getBlockZ();
        }

        centerChunkX = Math.floorDiv(viewCenterX, 16);
        centerChunkZ = Math.floorDiv(viewCenterZ, 16);

        forceSnapshotSoon();
    }

    @Override
    public void close() {
        persistView();
        ACTIVE = null;

        PENDING_CLAIM.clear();
        PENDING_UNCLAIM.clear();

        TerrainMinimapShared.endExclusive();
        super.close();
    }

    @Override
    public void removed() {
        persistView();
        ACTIVE = null;

        PENDING_CLAIM.clear();
        PENDING_UNCLAIM.clear();

        TerrainMinimapShared.endExclusive();
        super.removed();
    }

    private void persistView() {
        HAS_LAST_VIEW = true;
        LAST_ZOOM_INDEX = zoomIndex;
        LAST_VIEW_CENTER_X = viewCenterX;
        LAST_VIEW_CENTER_Z = viewCenterZ;
        LAST_FOLLOW = followPlayer;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (snapshotCooldownTicks > 0) snapshotCooldownTicks--;

        pollKeys();
        pollMouse(mouseX, mouseY);

        // ✅ Tell terrain builder to go fast while panning
        TerrainMinimapFull.setBoost(panning);

        if (followPlayer && mc.player != null) {
            viewCenterX = mc.player.getBlockX();
            viewCenterZ = mc.player.getBlockZ();
        }

        centerChunkX = Math.floorDiv(viewCenterX, 16);
        centerChunkZ = Math.floorDiv(viewCenterZ, 16);

    int sample = TerrainMinimapFull.sampleSize();
int blocksAcross = ZOOMS[zoomIndex];

// convert to terrain sampling rate
int bpp = Math.max(1, blocksAcross / sample);

        int mapSize = Math.min(this.width, this.height) - 40;
        mapSize = Math.max(200, mapSize);
        int mapX = (this.width - mapSize) / 2;
        int mapY = (this.height - mapSize) / 2;

        drawTerrainSquare(ctx, mapX, mapY, mapSize, mapSize);
        drawCompassOutside(ctx, mapX, mapY, mapSize, mapSize);

        ctx.drawTextWithShadow(
                textRenderer,
                "Zoom: " + bpp + " blocks/pixel (~" + blocksAcross + "m)  Scroll zoom  F follow: " + (followPlayer ? "ON" : "OFF") + " | MMB hold: pan",
                10, 10, 0xFFFFFFFF
        );

double halfBlocks = blocksAcross / 2.0;
double worldLeftX = (viewCenterX + 0.5) - halfBlocks;
double worldTopZ = (viewCenterZ + 0.5) - halfBlocks;

double pxPerTexel = mapSize / (double) sample;
double pxPerBlock = pxPerTexel / (double) bpp;
double pxPerChunk = 16.0 * pxPerBlock;

drawChunkGrid(ctx, mapX, mapY, mapSize, worldLeftX, worldTopZ, pxPerBlock);

        int chunksAcross = (int) Math.ceil(mapSize / Math.max(2.0, pxPerChunk));
        if ((chunksAcross & 1) == 0) chunksAcross--;
        if (chunksAcross < 3) chunksAcross = 3;
        if (chunksAcross > MAX_CHUNKS_ACROSS) chunksAcross = MAX_CHUNKS_ACROSS;

        maybeRequestSnapshotForView(chunksAcross);

        Hover hover = getHover(mouseX, mouseY, mapX, mapY, mapSize, worldLeftX, worldTopZ, pxPerBlock);
        if (hover != null) {
            ctx.drawTextWithShadow(textRenderer, "Hover chunk: " + hover.chunkX + ", " + hover.chunkZ, 10, 24, 0xFFFFFFFF);
        } else {
            ctx.drawTextWithShadow(textRenderer, "View center: X=" + viewCenterX + " Z=" + viewCenterZ + " | RMB drag = unclaim", 10, 24, 0xFFFFFFFF);
        }

        int centerCx = centerChunkX;
        int centerCz = centerChunkZ;
        int radius = chunksAcross / 2;
        int minCx = centerCx - radius;
        int maxCx = centerCx + radius;
        int minCz = centerCz - radius;
        int maxCz = centerCz + radius;

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

                boolean pendingClaim = PENDING_CLAIM.contains(key(cx, cz));
                boolean pendingUnclaim = PENDING_UNCLAIM.contains(key(cx, cz));

                boolean selected = dragging && cx >= selMinX && cx <= selMaxX && cz >= selMinZ && cz <= selMaxZ;
                boolean erasedArea = erasing && cx >= erMinX && cx <= erMaxX && cz >= erMinZ && cz <= erMaxZ;

                if (pendingUnclaim) cell = null;

                if (cell == null && !pendingClaim && !selected && !erasedArea) continue;

                Rect r = chunkRectOnScreen(cx, cz, mapX, mapY, mapSize, worldLeftX, worldTopZ, pxPerBlock);
                if (r == null) continue;
                if (r.w < 1 || r.h < 1) continue;

                int color = 0x00000000;

                if (cell != null) {
    if (cell.type() == 1) color = 0x7733AA33;      // claimed
    else if (cell.type() == 2) color = 0x77AA3333; // enemy / other
    else color = 0x00000000; // no overlay for unclaimed
}
if (color == 0x00000000 && !pendingClaim && !selected && !erasedArea) {
    continue;
}
                if (pendingClaim) color = 0xCC33CC33;
                if (selected) color = 0xCCFFFF00;
                if (erasedArea) color = 0x66FFFFFF;

                // solid fill
                ctx.fill(r.x, r.y, r.x + r.w, r.y + r.h, color);

                if (hover != null && hover.chunkX == cx && hover.chunkZ == cz) {
                    drawRectOutline(ctx, r.x, r.y, r.w, r.h, 0xFFFFFFFF);
                }
            }
        }

        drawPlayerTriangleProjected(ctx, mapX, mapY, mapSize, mc, worldLeftX, worldTopZ, pxPerBlock);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void maybeRequestSnapshotForView(int chunksAcross) {
        int neededRadius = (chunksAcross / 2) + SNAPSHOT_MARGIN_CHUNKS;
        if (neededRadius < 2) neededRadius = 2;

        boolean centerChanged = (centerChunkX != lastSnapshotCenterCx) || (centerChunkZ != lastSnapshotCenterCz);
        boolean radiusGrew = neededRadius > lastSnapshotRadius;

        // faster while panning
        int cooldown = panning ? 0 : 2;
        boolean canSendNow = snapshotCooldownTicks == 0 || (panning && centerChanged);

        if (canSendNow && (centerChanged || radiusGrew)) {
            lastSnapshotCenterCx = centerChunkX;
            lastSnapshotCenterCz = centerChunkZ;
            lastSnapshotRadius = neededRadius;

            Net.requestSnapshot(centerChunkX, centerChunkZ, neededRadius);

            int keep = neededRadius + 24;
            ClaimCache.pruneOutside(centerChunkX, centerChunkZ, keep);

            snapshotCooldownTicks = cooldown;
        }
    }

    private Rect chunkRectOnScreen(int chunkX, int chunkZ, int mapX, int mapY, int mapSize,
                                   double worldLeftX, double worldTopZ, double pxPerBlock) {

        double chunkLeftWorldX = chunkX * 16.0;
        double chunkTopWorldZ = chunkZ * 16.0;

        double x0d = mapX + (chunkLeftWorldX - worldLeftX) * pxPerBlock;
        double y0d = mapY + (chunkTopWorldZ - worldTopZ) * pxPerBlock;
        double x1d = mapX + ((chunkLeftWorldX + 16.0) - worldLeftX) * pxPerBlock;
        double y1d = mapY + ((chunkTopWorldZ + 16.0) - worldTopZ) * pxPerBlock;

        int x0 = (int) Math.floor(x0d);
        int y0 = (int) Math.floor(y0d);
        int x1 = (int) Math.ceil(x1d);
        int y1 = (int) Math.ceil(y1d);

        if (x1 <= mapX || y1 <= mapY || x0 >= mapX + mapSize || y0 >= mapY + mapSize) return null;

        int cx0 = Math.max(x0, mapX);
        int cy0 = Math.max(y0, mapY);
        int cx1 = Math.min(x1, mapX + mapSize);
        int cy1 = Math.min(y1, mapY + mapSize);

        return new Rect(cx0, cy0, cx1 - cx0, cy1 - cy0);
    }

    private Hover getHover(int mouseX, int mouseY, int mapX, int mapY, int mapSize,
                           double worldLeftX, double worldTopZ, double pxPerBlock) {

        if (mouseX < mapX || mouseY < mapY || mouseX >= mapX + mapSize || mouseY >= mapY + mapSize) return null;

        double worldX = worldLeftX + (mouseX - mapX) / pxPerBlock;
        double worldZ = worldTopZ + (mouseY - mapY) / pxPerBlock;

        int chunkX = chunkXFromBlock(worldX);
        int chunkZ = chunkZFromBlock(worldZ);

        return new Hover(chunkX, chunkZ);
    }

    private void drawPlayerTriangleProjected(DrawContext ctx, int mapX, int mapY, int mapSize,
                                         MinecraftClient mc, double worldLeftX, double worldTopZ, double pxPerBlock) {

    if (mc.player == null) return;

    double playerX = mc.player.getX();
    double playerZ = mc.player.getZ();

    double sx = mapX + (playerX - worldLeftX) * pxPerBlock;
    double sy = mapY + (playerZ - worldTopZ) * pxPerBlock;

    int cx = (int) Math.round(sx);
    int cy = (int) Math.round(sy);

    double yaw = Math.toRadians(mc.player.getYaw());

    double fx = -Math.sin(yaw);
    double fy =  Math.cos(yaw);
    double px = -fy;
    double py =  fx;

    // smaller, cleaner arrow
    int tipLen = 9;
    int width  = 3;
    int back   = 3;

    int tipX = cx + (int)(fx * tipLen);
    int tipY = cy + (int)(fy * tipLen);

    int leftX  = cx + (int)(px * width) - (int)(fx * back);
    int leftY  = cy + (int)(py * width) - (int)(fy * back);

    int rightX = cx - (int)(px * width) - (int)(fx * back);
    int rightY = cy - (int)(py * width) - (int)(fy * back);

    fillTriangle(ctx, tipX, tipY, leftX, leftY, rightX, rightY, 0xFFFFFFFF);
}


    private void fillTriangle(DrawContext ctx, int x1, int y1, int x2, int y2, int x3, int y3, int color) {
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
private void drawChunkGrid(DrawContext ctx,
                           int mapX, int mapY, int mapSize,
                           double worldLeftX, double worldTopZ,
                           double pxPerBlock) {

    double pxPerChunk = 16.0 * pxPerBlock;

    if (pxPerChunk < 6) return; // don't draw if too zoomed out

    int chunksAcross = (int) Math.ceil(mapSize / pxPerChunk) + 2;

    int startChunkX = (int) Math.floor(worldLeftX / 16.0);
    int startChunkZ = (int) Math.floor(worldTopZ / 16.0);

    for (int i = 0; i < chunksAcross; i++) {
        int cx = startChunkX + i;
        double worldX = cx * 16.0;
        int sx = (int) (mapX + (worldX - worldLeftX) * pxPerBlock);

        ctx.fill(sx, mapY, sx + 1, mapY + mapSize, 0x33000000);
    }

    for (int i = 0; i < chunksAcross; i++) {
        int cz = startChunkZ + i;
        double worldZ = cz * 16.0;
        int sy = (int) (mapY + (worldZ - worldTopZ) * pxPerBlock);

        ctx.fill(mapX, sy, mapX + mapSize, sy + 1, 0x33000000);
    }
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

    // 🔥 CLIP RENDER AREA
    ctx.enableScissor(x0, y0, x0 + w, y0 + h);

    ctx.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            TerrainMinimapFull.textureId(),
            x0, y0,
            0f, 0f,
            w, h,
            s, s,
            s, s
    );

    ctx.disableScissor();

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

        boolean zoomIn = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_BRACKET) == GLFW.GLFW_PRESS;
        boolean zoomOut = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_BRACKET) == GLFW.GLFW_PRESS;
        boolean follow = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F) == GLFW.GLFW_PRESS;

        if (zoomIn && !prevZoomIn) {
            if (zoomIndex > 0) zoomIndex--;
        }
        if (zoomOut && !prevZoomOut) {
            if (zoomIndex < ZOOMS.length - 1) zoomIndex++;
        }
        if (follow && !prevFollow) {
            followPlayer = !followPlayer;
            if (followPlayer) panning = false;
        }

        prevZoomIn = zoomIn;
        prevZoomOut = zoomOut;
        prevFollow = follow;
    }

    private void pollMouse(int mouseX, int mouseY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        long handle = mc.getWindow().getHandle();

        boolean leftDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS;
        boolean rightDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_2) == GLFW.GLFW_PRESS;
        boolean middleDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_3) == GLFW.GLFW_PRESS;

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

        // MMB pan
        if (middleDown && !prevMiddleDown) {
            if (mouseX >= mapX && mouseX < mapX + mapSize && mouseY >= mapY && mouseY < mapY + mapSize) {
                panning = true;
                followPlayer = false;
                panStartMouseX = mouseX;
                panStartMouseY = mouseY;
                panStartCenterX = viewCenterX;
                panStartCenterZ = viewCenterZ;
            }
        }

        if (middleDown && panning) {
            double dxPx = (mouseX - panStartMouseX);
            double dyPx = (mouseY - panStartMouseY);
            double speed = 1.4; // increase pan speed
int dxBlocks = (int) Math.round((dxPx * speed) / pxPerBlock);
int dzBlocks = (int) Math.round((dyPx * speed) / pxPerBlock);

            viewCenterX = panStartCenterX - dxBlocks;
            viewCenterZ = panStartCenterZ - dzBlocks;
        }

        if (!middleDown && prevMiddleDown) {
            panning = false;
        }

        if (panning) {
            prevLeftDown = leftDown;
            prevRightDown = rightDown;
            prevMiddleDown = middleDown;
            return;
        }

        // LEFT drag claim
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
                        long k = key(cx, cz);
                        PENDING_UNCLAIM.remove(k);
                        PENDING_CLAIM.add(k);
                    }
                }

                Net.requestClaim(minX, minZ, maxX, maxZ);
            }
        }

        // RIGHT drag unclaim
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
                        long k = key(cx, cz);
                        PENDING_CLAIM.remove(k);
                        PENDING_UNCLAIM.add(k);
                        ClaimCache.remove(cx, cz);
                    }
                }

                Net.requestUnclaim(minX, minZ, maxX, maxZ);
            }
        }

        prevLeftDown = leftDown;
        prevRightDown = rightDown;
        prevMiddleDown = middleDown;
    }

    private static void drawRectOutline(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static final class Rect {
        final int x, y, w, h;
        Rect(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }
    }

    private static final class Hover {
        final int chunkX, chunkZ;
        Hover(int chunkX, int chunkZ) { this.chunkX = chunkX; this.chunkZ = chunkZ; }
    }
}