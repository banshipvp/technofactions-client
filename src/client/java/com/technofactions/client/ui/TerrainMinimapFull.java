package com.technofactions.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;

public final class TerrainMinimapFull {

    // 🔥 Increased resolution for sharp map
    private static final int SAMPLE_SIZE = 512;

    private static final int[] FRONT   = new int[SAMPLE_SIZE * SAMPLE_SIZE];
    private static final int[] FRONT_H = new int[SAMPLE_SIZE * SAMPLE_SIZE];

    private static final int[] BACK   = new int[SAMPLE_SIZE * SAMPLE_SIZE];
    private static final int[] BACK_H = new int[SAMPLE_SIZE * SAMPLE_SIZE];

    private static boolean rebuilding = false;
    private static int buildRow = 0;

    private static int targetCenterX = Integer.MIN_VALUE;
    private static int targetCenterZ = Integer.MIN_VALUE;

    private static int blocksPerPixel = 4;
    private static int requestedBlocksPerPixel = 4;

    private static final int UNKNOWN_ARGB = 0x00000000;

    private static final int MIN_BLOCKS_ACROSS = 512;
private static final int MAX_BLOCKS_ACROSS = 2048;

    private static final int SUPERSAMPLE = 1;

    // Upload cadence
    private static final int UPLOAD_EVERY_ROWS_NORMAL = 8;
    private static final int UPLOAD_EVERY_ROWS_BOOST  = 4; // 🔥 reduced GPU spam

    private static int rowsPerTick = 32;
    private static final int ROWS_MIN = 16;
    private static final int ROWS_MAX = 224;

    private static int stableTicks = 0;

    private static boolean boost = false;

    private static final Identifier TEX_ID = Identifier.of("technofactions", "minimap_full");
    private static NativeImageBackedTexture texture;
    private static NativeImage image;

    private static String lastSessionKey = null;

    private TerrainMinimapFull() {}

    public static int sampleSize() { return SAMPLE_SIZE; }
    public static int blocksPerPixel() { return blocksPerPixel; }
    public static boolean isRebuilding() { return rebuilding; }
    public static Identifier textureId() { ensureTexture(); return TEX_ID; }

    public static void setBoost(boolean on) {
        boost = on;
    }

    public static void tickAt(int blocksAcross, int centerX, int centerZ) {

    if (blocksAcross < MIN_BLOCKS_ACROSS)
        blocksAcross = MIN_BLOCKS_ACROSS;

    if (blocksAcross > MAX_BLOCKS_ACROSS)
        blocksAcross = MAX_BLOCKS_ACROSS;

    requestedBlocksPerPixel = Math.max(1, blocksAcross / SAMPLE_SIZE);

    MinecraftClient mc = MinecraftClient.getInstance();
    ClientWorld world = mc.world;
    if (world == null) return;

    ensureTexture();
    TerrainSurfaceCache.ensureWorld(world);

    String sessionKey = computeSessionKey(world);
    if (!sessionKey.equals(lastSessionKey)) {
        lastSessionKey = sessionKey;
        hardResetFrames();
    }

    if (blocksPerPixel != requestedBlocksPerPixel) {
        blocksPerPixel = requestedBlocksPerPixel;
        startRebuild(centerX, centerZ, false);
    }

    int threshold = Math.max(1, blocksPerPixel);

    if (!boost && rebuilding && targetCenterX != Integer.MIN_VALUE) {
        int dx = Math.abs(centerX - targetCenterX);
        int dz = Math.abs(centerZ - targetCenterZ);

        if (dx >= threshold || dz >= threshold) {
            startRebuild(centerX, centerZ, true);
        }
    }

    if (!rebuilding) {
        if (targetCenterX == Integer.MIN_VALUE) {
            startRebuild(centerX, centerZ, false);
        } else {
            int dx = Math.abs(centerX - targetCenterX);
            int dz = Math.abs(centerZ - targetCenterZ);
            if (dx >= threshold || dz >= threshold) {
                startRebuild(centerX, centerZ, true);
            }
        }
    }

    if (boost) {
        rowsPerTick = ROWS_MAX;
        stableTicks = 0;
    }

    if (rebuilding) {
        stepBuild();
    }
}
    private static void hardResetFrames() {
        for (int i = 0; i < FRONT.length; i++) {
            FRONT[i] = UNKNOWN_ARGB;
            BACK[i] = UNKNOWN_ARGB;
            FRONT_H[i] = Integer.MIN_VALUE;
            BACK_H[i] = Integer.MIN_VALUE;
        }

        writeWholeArrayToTexture(FRONT);
        texture.upload();

        targetCenterX = Integer.MIN_VALUE;
        targetCenterZ = Integer.MIN_VALUE;
        rebuilding = false;
        buildRow = 0;
    }

    private static void ensureTexture() {
        if (texture != null) return;

        image = new NativeImage(SAMPLE_SIZE, SAMPLE_SIZE, false);
        texture = new NativeImageBackedTexture(() -> TEX_ID.toString(), image);

        MinecraftClient.getInstance().getTextureManager().registerTexture(TEX_ID, texture);

        // 🔥 Enable linear filtering for smoother scaling
        texture.setFilter(true, false);

        for (int y = 0; y < SAMPLE_SIZE; y++) {
            for (int x = 0; x < SAMPLE_SIZE; x++) {
                image.setColor(x, y, argbToAbgr(UNKNOWN_ARGB));
            }
        }
        texture.upload();
    }

    private static void startRebuild(int cx, int cz, boolean seedFromFront) {
        targetCenterX = cx;
        targetCenterZ = cz;
        rebuilding = true;
        buildRow = 0;

        rowsPerTick = boost ? ROWS_MAX : 48;

        if (seedFromFront) {
            System.arraycopy(FRONT, 0, BACK, 0, FRONT.length);
            System.arraycopy(FRONT_H, 0, BACK_H, 0, FRONT_H.length);
        } else {
            for (int i = 0; i < BACK.length; i++) {
                BACK[i] = UNKNOWN_ARGB;
                BACK_H[i] = Integer.MIN_VALUE;
            }
        }

        // 🔥 Removed full texture wipe here (prevents flash)
    }

    private static final class Sample {
        boolean valid;
        int argb;
        int topY;
    }

    private static final Sample tmp = new Sample();
    private static final TerrainSurfaceCache.Sample cacheTmp = new TerrainSurfaceCache.Sample();

    private static void stepBuild() {
        int half = SAMPLE_SIZE / 2;
        int uploadEvery = boost ? UPLOAD_EVERY_ROWS_BOOST : UPLOAD_EVERY_ROWS_NORMAL;

        int rows = 0;
        int rowsSinceUpload = 0;

        while (rows < rowsPerTick && buildRow < SAMPLE_SIZE) {

            int sy = buildRow;
            int baseZ = targetCenterZ + (sy - half) * blocksPerPixel;
            int rowBase = sy * SAMPLE_SIZE;

            for (int sx = 0; sx < SAMPLE_SIZE; sx++) {
                int baseX = targetCenterX + (sx - half) * blocksPerPixel;
                int idx = rowBase + sx;

                if (TerrainSurfaceCache.read(baseX, baseZ, cacheTmp)) {
                    BACK[idx] = cacheTmp.argb;
                    BACK_H[idx] = cacheTmp.topY;
                } else {
                    BACK[idx] = UNKNOWN_ARGB;
                    BACK_H[idx] = Integer.MIN_VALUE;
                }
            }

            applySlopeShadingForRow(sy);
            writeRowToTexture(sy);

            buildRow++;
            rows++;
            rowsSinceUpload++;

            if (rowsSinceUpload >= uploadEvery) {
                texture.upload();
                rowsSinceUpload = 0;
            }
        }

        if (rowsSinceUpload > 0) texture.upload();

        if (buildRow >= SAMPLE_SIZE) {
            System.arraycopy(BACK, 0, FRONT, 0, BACK.length);
            System.arraycopy(BACK_H, 0, FRONT_H, 0, BACK_H.length);
            rebuilding = false;
        }
    }

    private static void writeWholeArrayToTexture(int[] src) {
        int i = 0;
        for (int y = 0; y < SAMPLE_SIZE; y++) {
            for (int x = 0; x < SAMPLE_SIZE; x++) {
                image.setColor(x, y, argbToAbgr(src[i++]));
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
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static void applySlopeShadingForRow(int sy) {
        int rowBase = sy * SAMPLE_SIZE;
        for (int sx = 0; sx < SAMPLE_SIZE; sx++) {
            int idx = rowBase + sx;
            int h = BACK_H[idx];
            if (h == Integer.MIN_VALUE) continue;

            int hW = (sx > 0) ? BACK_H[idx - 1] : h;
            int hN = (sy > 0) ? BACK_H[idx - SAMPLE_SIZE] : h;

            int dh = (hW - h) + (hN - h);
            dh = Math.max(-6, Math.min(6, dh));

            float shade = -dh * 0.03f;
            int rgb = BACK[idx] & 0x00FFFFFF;
            rgb = add(rgb, shade);
            BACK[idx] = 0xFF000000 | rgb;
        }
    }

    private static int add(int rgb, float amount) {
        int delta = (int)(amount * 255f);
        int r = clamp((rgb >> 16) & 0xFF + delta);
        int g = clamp((rgb >> 8) & 0xFF + delta);
        int b = clamp((rgb & 0xFF) + delta);
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    private static String computeSessionKey(ClientWorld world) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String server = mc.getCurrentServerEntry() != null ?
                mc.getCurrentServerEntry().address : "sp";
        return server + "|" + world.getRegistryKey().getValue();
    }
}