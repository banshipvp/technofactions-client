package com.technofactions.client.ui;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

public final class TerrainMinimapHud {
    private static final int SAMPLE_SIZE = 64;

    private static final int[] FRONT = new int[SAMPLE_SIZE * SAMPLE_SIZE];
    private static final int[] BACK  = new int[SAMPLE_SIZE * SAMPLE_SIZE];
    private static final int[] BACK_H = new int[SAMPLE_SIZE * SAMPLE_SIZE];

    private static boolean rebuilding = false;
    private static int buildRow = 0;

    private static int targetCenterX = Integer.MIN_VALUE;
    private static int targetCenterZ = Integer.MIN_VALUE;

    private static int blocksPerPixel = 2;
    private static int requestedBlocksPerPixel = 2;

    private static final int ROWS_PER_TICK = 24;
    private static final float BRIGHTNESS = 1.20f;
    private static final boolean WATER_TINT = true;

    private static final Identifier TEX_ID = Identifier.of("technofactions", "minimap_hud");
    private static NativeImageBackedTexture texture;
    private static NativeImage image;

    private TerrainMinimapHud() {}

    public static int sampleSize() { return SAMPLE_SIZE; }
    public static int blocksPerPixel() { return blocksPerPixel; }
    public static boolean isRebuilding() { return rebuilding; }
    public static Identifier textureId() { ensureTexture(); return TEX_ID; }

    public static void tick(int desiredBlocksPerPixel) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (TerrainMinimapShared.isExclusive()) return;
        tickAt(desiredBlocksPerPixel, mc.player.getBlockX(), mc.player.getBlockZ());
    }

    public static void tickAt(int desiredBlocksPerPixel, int centerX, int centerZ) {
        if (desiredBlocksPerPixel < 1) desiredBlocksPerPixel = 1;
        requestedBlocksPerPixel = desiredBlocksPerPixel;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null) return;

        ensureTexture();

        if (!rebuilding && blocksPerPixel != requestedBlocksPerPixel) {
            blocksPerPixel = requestedBlocksPerPixel;
            startRebuild(centerX, centerZ);
        }

        int threshold = Math.max(2, blocksPerPixel);

        if (!rebuilding) {
            if (targetCenterX == Integer.MIN_VALUE) {
                startRebuild(centerX, centerZ);
            } else {
                int dx = Math.abs(centerX - targetCenterX);
                int dz = Math.abs(centerZ - targetCenterZ);
                if (dx >= threshold || dz >= threshold) startRebuild(centerX, centerZ);
            }
        }

        if (rebuilding) stepBuild(world);
    }

    private static void ensureTexture() {
        if (texture != null) return;

        image = new NativeImage(SAMPLE_SIZE, SAMPLE_SIZE, false);
        texture = new NativeImageBackedTexture(() -> TEX_ID.toString(), image);

        MinecraftClient mc = MinecraftClient.getInstance();
        mc.getTextureManager().registerTexture(TEX_ID, texture);

        try {
            texture.setFilter(false, false); // sharp
        } catch (Throwable ignored) {}

        for (int y = 0; y < SAMPLE_SIZE; y++) {
            for (int x = 0; x < SAMPLE_SIZE; x++) {
                image.setColor(x, y, argbToAbgr(0xFF000000));
            }
        }
        texture.upload();
    }

    private static void startRebuild(int cx, int cz) {
        targetCenterX = cx;
        targetCenterZ = cz;
        rebuilding = true;
        buildRow = 0;

        System.arraycopy(FRONT, 0, BACK, 0, FRONT.length);
        writeWholeArrayToTexture(BACK);
        texture.upload();
    }

    private static void stepBuild(ClientWorld world) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int half = SAMPLE_SIZE / 2;

        int rows = 0;
        int lastRow = -1;

        while (rows < ROWS_PER_TICK && buildRow < SAMPLE_SIZE) {
            int sy = buildRow;
            int z = targetCenterZ + (sy - half) * blocksPerPixel;

            for (int sx = 0; sx < SAMPLE_SIZE; sx++) {
                int x = targetCenterX + (sx - half) * blocksPerPixel;
                int idx = sy * SAMPLE_SIZE + sx;

                if (!isChunkLikelyLoaded(world, x, z)) {
                    continue;
                }

                int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                int y = Math.max(world.getBottomY(), topY - 1);

                pos.set(x, y, z);
                BlockState state = world.getBlockState(pos);
                MapColor mapColor = state.getMapColor(world, pos);

                int rgb = mapColor.color;
                BACK_H[idx] = topY;

                rgb = shadeByHeight(rgb, topY);

                if (WATER_TINT && mapColor == MapColor.WATER_BLUE) {
                    rgb = mix(rgb, 0x2A4DFF, 0.35f);
                }

                rgb = brighten(rgb, BRIGHTNESS);
                BACK[idx] = 0xFF000000 | (rgb & 0x00FFFFFF);
            }

            applySlopeShadingForRow(sy);
            writeRowToTexture(sy);
            lastRow = sy;

            buildRow++;
            rows++;
        }

        if (lastRow >= 0) texture.upload();

        if (buildRow >= SAMPLE_SIZE) {
            System.arraycopy(BACK, 0, FRONT, 0, BACK.length);
            rebuilding = false;
        }
    }

    private static boolean isChunkLikelyLoaded(ClientWorld world, int blockX, int blockZ) {
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        try {
            return world.getChunkManager().isChunkLoaded(cx, cz);
        } catch (Throwable ignored) {
            try {
                return world.getChunkManager().getChunk(cx, cz) != null;
            } catch (Throwable ignored2) {
                return true;
            }
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

    private static void writeRowToTexture(int sy) {
        int base = sy * SAMPLE_SIZE;
        for (int sx = 0; sx < SAMPLE_SIZE; sx++) {
            image.setColor(sx, sy, argbToAbgr(BACK[base + sx]));
        }
    }

    private static int argbToAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = (argb) & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | (r);
    }

    private static void applySlopeShadingForRow(int sy) {
        for (int sx = 0; sx < SAMPLE_SIZE; sx++) {
            int idx = sy * SAMPLE_SIZE + sx;

            int h = BACK_H[idx];
            int hW = (sx > 0) ? BACK_H[idx - 1] : h;
            int hN = (sy > 0) ? BACK_H[idx - SAMPLE_SIZE] : h;

            int dh = (hW - h) + (hN - h);
            dh = Math.max(-6, Math.min(6, dh));

            float shade = -dh * 0.035f;

            int rgb = BACK[idx] & 0x00FFFFFF;
            rgb = add(rgb, shade);
            BACK[idx] = 0xFF000000 | (rgb & 0x00FFFFFF);
        }
    }

    private static int shadeByHeight(int rgb, int topY) {
        int shade = ((topY & 31) - 16);
        return add(rgb, shade * 0.010f);
    }

    private static int brighten(int rgb, float mult) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = clamp255((int)(r * mult));
        g = clamp255((int)(g * mult));
        b = clamp255((int)(b * mult));
        return (r << 16) | (g << 8) | b;
    }

    private static int add(int rgb, float amount) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int delta = (int)(amount * 255f);
        r = clamp255(r + delta);
        g = clamp255(g + delta);
        b = clamp255(b + delta);
        return (r << 16) | (g << 8) | b;
    }

    private static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int)(ar + (br - ar) * t);
        int g = (int)(ag + (bg - ag) * t);
        int bl = (int)(ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }
}