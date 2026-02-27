package com.technofactions.client.ui;

import com.technofactions.client.state.ClaimCache;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;

public final class TerrainMinimapHud {

    // Match HUD_DRAW_SIZE=128 for 1:1 crisp rendering and less work.
    private static final int SAMPLE_SIZE = 256;

    private static final int[] FRONT = new int[SAMPLE_SIZE * SAMPLE_SIZE];
    private static final int[] BACK  = new int[SAMPLE_SIZE * SAMPLE_SIZE];

    private static boolean rebuilding = false;
    private static int buildRow = 0;

    private static int targetCenterX = Integer.MIN_VALUE;
    private static int targetCenterZ = Integer.MIN_VALUE;

    private static int blocksPerPixel = 1;
    private static int requestedBpp = 1;

    private static final int UNKNOWN_ARGB = TerrainSurfaceCache.unknownArgb();

    // Budget: rows per tick (HUD only)
    private static final int ROWS_PER_TICK = 16;
    private static final int UPLOAD_EVERY_ROWS = 8;

    private static final Identifier TEX_ID = Identifier.of("technofactions", "minimap_hud");
    private static NativeImageBackedTexture texture;
    private static NativeImage image;

    private static final TerrainSurfaceCache.Sample cacheTmp = new TerrainSurfaceCache.Sample();

    private TerrainMinimapHud() {}

    public static int sampleSize() { return SAMPLE_SIZE; }
    public static int blocksPerPixel() { return blocksPerPixel; }
    public static Identifier textureId() { ensureTexture(); return TEX_ID; }
    public static boolean isRebuilding() { return rebuilding; }

    /**
     * Compatibility method MinimapHud expects.
     * Keeps HUD centered on player.
     */
    public static void tick(int desiredBpp) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        tickAt(desiredBpp, mc.player.getBlockX(), mc.player.getBlockZ());
    }

    /**
     * Incrementally rebuild HUD minimap centered at a specific position.
     */
    public static void tickAt(int desiredBpp, int centerX, int centerZ) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null) return;

        ensureTexture();
        TerrainSurfaceCache.ensureWorld(world);

        // Clamp to sane HUD zoom
        if (desiredBpp < 1) desiredBpp = 1;
        if (desiredBpp > 4) desiredBpp = 4;
        requestedBpp = desiredBpp;

        // If zoom changes, force full rebuild
        if (!rebuilding && blocksPerPixel != requestedBpp) {
            blocksPerPixel = requestedBpp;
            startRebuild(centerX, centerZ, false);
        }

        // Movement threshold (avoid rebuilding for tiny jitter)
        int threshold = Math.max(1, blocksPerPixel);

        if (!rebuilding) {
            if (targetCenterX == Integer.MIN_VALUE) {
                startRebuild(centerX, centerZ, false);
            } else {
                int dx = Math.abs(centerX - targetCenterX);
                int dz = Math.abs(centerZ - targetCenterZ);
                if (dx >= threshold || dz >= threshold) {
                    // seed from previous frame so it looks responsive
                    startRebuild(centerX, centerZ, true);
                }
            }
        }

        if (rebuilding) stepBuild();
    }

    private static void startRebuild(int cx, int cz, boolean seedFromFront) {
        targetCenterX = cx;
        targetCenterZ = cz;
        rebuilding = true;
        buildRow = 0;

        if (seedFromFront) {
            System.arraycopy(FRONT, 0, BACK, 0, FRONT.length);
        } else {
            for (int i = 0; i < BACK.length; i++) BACK[i] = UNKNOWN_ARGB;
        }

        // write the seeded frame immediately so it doesn't "blank"
        writeWholeArrayToTexture(BACK);
        texture.upload();
    }

    private static void stepBuild() {
        int half = SAMPLE_SIZE / 2;

        int rows = 0;
        int rowsSinceUpload = 0;

        while (rows < ROWS_PER_TICK && buildRow < SAMPLE_SIZE) {
            int sy = buildRow;
            int worldZ = targetCenterZ + (sy - half) * blocksPerPixel;

            int base = sy * SAMPLE_SIZE;

            for (int sx = 0; sx < SAMPLE_SIZE; sx++) {
                int worldX = targetCenterX + (sx - half) * blocksPerPixel;

                int argb = UNKNOWN_ARGB;

                if (TerrainSurfaceCache.read(worldX, worldZ, cacheTmp)) {
                    argb = cacheTmp.argb;
                }

                // Overlay claim tint (per CHUNK, but apply to pixels in that chunk)
                int chunkX = Math.floorDiv(worldX, 16);
                int chunkZ = Math.floorDiv(worldZ, 16);
                ClaimCache.Cell cell = ClaimCache.get(chunkX, chunkZ);

                if (cell != null) {
                    if (cell.type() == 1) {
                        // green tint
                        argb = tint(argb, 0xFF33AA33, 0.35f);
                    } else if (cell.type() == 2) {
                        // red tint
                        argb = tint(argb, 0xFFAA3333, 0.35f);
                    }
                }

                BACK[base + sx] = argb;
                image.setColor(sx, sy, argbToAbgr(argb));
            }

            buildRow++;
            rows++;
            rowsSinceUpload++;

            if (rowsSinceUpload >= UPLOAD_EVERY_ROWS) {
                texture.upload();
                rowsSinceUpload = 0;
            }
        }

        if (rowsSinceUpload > 0) texture.upload();

        if (buildRow >= SAMPLE_SIZE) {
            System.arraycopy(BACK, 0, FRONT, 0, BACK.length);
            rebuilding = false;
        }
    }

    private static void writeWholeArrayToTexture(int[] srcArgb) {
        int i = 0;
        for (int y = 0; y < SAMPLE_SIZE; y++) {
            for (int x = 0; x < SAMPLE_SIZE; x++) {
                image.setColor(x, y, argbToAbgr(srcArgb[i++]));
            }
        }
    }

    private static void ensureTexture() {
        if (texture != null) return;

        image = new NativeImage(SAMPLE_SIZE, SAMPLE_SIZE, false);
        texture = new NativeImageBackedTexture(() -> TEX_ID.toString(), image);
        MinecraftClient.getInstance().getTextureManager().registerTexture(TEX_ID, texture);

        // crisp pixels
        try { texture.setFilter(false, false); } catch (Throwable ignored) {}

        for (int y = 0; y < SAMPLE_SIZE; y++) {
            for (int x = 0; x < SAMPLE_SIZE; x++) {
                image.setColor(x, y, argbToAbgr(UNKNOWN_ARGB));
            }
        }
        texture.upload();
    }

    private static int argbToAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = (argb) & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | (r);
    }

    /**
     * Blend base with overlay by alpha (0..1)
     */
    private static int tint(int baseArgb, int overlayArgb, float alpha) {
        if (alpha <= 0f) return baseArgb;
        if (alpha >= 1f) return overlayArgb;

        int br = (baseArgb >> 16) & 0xFF;
        int bg = (baseArgb >> 8) & 0xFF;
        int bb = baseArgb & 0xFF;

        int or = (overlayArgb >> 16) & 0xFF;
        int og = (overlayArgb >> 8) & 0xFF;
        int ob = overlayArgb & 0xFF;

        int r = (int)(br + (or - br) * alpha);
        int g = (int)(bg + (og - bg) * alpha);
        int b = (int)(bb + (ob - bb) * alpha);

        return (baseArgb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}