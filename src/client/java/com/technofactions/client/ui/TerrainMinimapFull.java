package com.technofactions.client.ui;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class TerrainMinimapFull {
    private static final int SAMPLE_SIZE = 256;

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

    private static final int ROWS_PER_TICK = 24;

    private static final float BRIGHTNESS = 1.08f;
    private static final boolean WATER_TINT = true;

    // Unknown / not yet cached
    private static final int UNKNOWN_ARGB = 0xFF0C0F14;

    // ---- Zoom limits ----
    private static final int MIN_BPP = 1;
    private static final int MAX_BPP = 8; // lower this to 4 if you want even less zoom-out

    private static final Identifier TEX_ID = Identifier.of("technofactions", "minimap_full");
    private static NativeImageBackedTexture texture;
    private static NativeImage image;

    // ---- Persistent cache (per dimension) ----
    private static String lastWorldKey = null;

    private static final class ChunkCache {
        final int[] color = new int[16 * 16];     // ARGB already shaded/tinted/brightened
        final short[] height = new short[16 * 16]; // topY (optional but used for slope shading)

        ChunkCache() {
            for (int i = 0; i < color.length; i++) color[i] = UNKNOWN_ARGB;
            for (int i = 0; i < height.length; i++) height[i] = Short.MIN_VALUE;
        }
    }

    private static final Map<Long, ChunkCache> chunkCache = new HashMap<>(4096);

    private static boolean cacheDirty = false;
    private static int saveTickCounter = 0;
    private static final int SAVE_EVERY_TICKS = 200; // ~10 seconds at 20 TPS

    // file format
    private static final int CACHE_MAGIC = 0x4D4D4631; // "MMF1"
    private static final int CACHE_VERSION = 1;

    private TerrainMinimapFull() {}

    public static int sampleSize() { return SAMPLE_SIZE; }
    public static int blocksPerPixel() { return blocksPerPixel; }
    public static boolean isRebuilding() { return rebuilding; }
    public static Identifier textureId() { ensureTexture(); return TEX_ID; }

    public static void tickAt(int desiredBlocksPerPixel, int centerX, int centerZ) {
        // cap zoom
        if (desiredBlocksPerPixel < MIN_BPP) desiredBlocksPerPixel = MIN_BPP;
        if (desiredBlocksPerPixel > MAX_BPP) desiredBlocksPerPixel = MAX_BPP;
        requestedBlocksPerPixel = desiredBlocksPerPixel;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null) return;

        ensureTexture();
        ensureCacheWorld(world);

        // periodic save
        saveTickCounter++;
        if (cacheDirty && saveTickCounter >= SAVE_EVERY_TICKS) {
            saveTickCounter = 0;
            saveCacheToDisk();
        }

        // If zoom changed: rebuild WITHOUT seeding (prevents outer stale ring)
        if (!rebuilding && blocksPerPixel != requestedBlocksPerPixel) {
            blocksPerPixel = requestedBlocksPerPixel;
            startRebuild(centerX, centerZ, false); // <- key change
        }

        int threshold = Math.max(2, blocksPerPixel);

        if (!rebuilding) {
            if (targetCenterX == Integer.MIN_VALUE) {
                startRebuild(centerX, centerZ, true);
            } else {
                int dx = Math.abs(centerX - targetCenterX);
                int dz = Math.abs(centerZ - targetCenterZ);
                if (dx >= threshold || dz >= threshold) {
                    // movement can seed for smoothness
                    startRebuild(centerX, centerZ, true);
                }
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

        try { texture.setFilter(false, false); } catch (Throwable ignored) {}

        for (int y = 0; y < SAMPLE_SIZE; y++) {
            for (int x = 0; x < SAMPLE_SIZE; x++) {
                image.setColor(x, y, argbToAbgr(UNKNOWN_ARGB));
            }
        }
        texture.upload();

        for (int i = 0; i < FRONT_H.length; i++) FRONT_H[i] = Integer.MIN_VALUE;
        for (int i = 0; i < BACK_H.length; i++) BACK_H[i] = Integer.MIN_VALUE;
        for (int i = 0; i < FRONT.length; i++) FRONT[i] = UNKNOWN_ARGB;
        for (int i = 0; i < BACK.length; i++) BACK[i] = UNKNOWN_ARGB;
    }

    private static void ensureCacheWorld(ClientWorld world) {
        String key = worldKeyString(world);
        if (lastWorldKey == null || !lastWorldKey.equals(key)) {
            // save previous world cache before switching
            if (cacheDirty) saveCacheToDisk();

            lastWorldKey = key;
            chunkCache.clear();
            cacheDirty = false;
            saveTickCounter = 0;

            loadCacheFromDisk();

            // reset frames so no cross-dimension ghosts
            for (int i = 0; i < FRONT.length; i++) FRONT[i] = UNKNOWN_ARGB;
            for (int i = 0; i < BACK.length; i++) BACK[i] = UNKNOWN_ARGB;
            for (int i = 0; i < FRONT_H.length; i++) FRONT_H[i] = Integer.MIN_VALUE;
            for (int i = 0; i < BACK_H.length; i++) BACK_H[i] = Integer.MIN_VALUE;

            writeWholeArrayToTexture(FRONT);
            texture.upload();

            targetCenterX = Integer.MIN_VALUE;
            targetCenterZ = Integer.MIN_VALUE;
            rebuilding = false;
            buildRow = 0;
        }
    }

    private static String worldKeyString(ClientWorld world) {
        try {
            RegistryKey<World> rk = world.getRegistryKey();
            return rk.getValue().toString().replace(':', '_').replace('/', '_');
        } catch (Throwable t) {
            return "unknown_world";
        }
    }

    private static void startRebuild(int cx, int cz, boolean seedFromFront) {
        targetCenterX = cx;
        targetCenterZ = cz;
        rebuilding = true;
        buildRow = 0;

        if (seedFromFront) {
            System.arraycopy(FRONT, 0, BACK, 0, FRONT.length);
            System.arraycopy(FRONT_H, 0, BACK_H, 0, FRONT_H.length);
        } else {
            for (int i = 0; i < BACK.length; i++) BACK[i] = UNKNOWN_ARGB;
            for (int i = 0; i < BACK_H.length; i++) BACK_H[i] = Integer.MIN_VALUE;
        }

        writeWholeArrayToTexture(BACK);
        texture.upload();
    }

    private static void stepBuild(ClientWorld world) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int half = SAMPLE_SIZE / 2;

        int rows = 0;
        int lastRowWritten = -1;

        while (rows < ROWS_PER_TICK && buildRow < SAMPLE_SIZE) {
            int sy = buildRow;
            int z = targetCenterZ + (sy - half) * blocksPerPixel;

            for (int sx = 0; sx < SAMPLE_SIZE; sx++) {
                int x = targetCenterX + (sx - half) * blocksPerPixel;
                int idx = sy * SAMPLE_SIZE + sx;

                int cx = x >> 4;
                int cz = z >> 4;

                if (isChunkLoaded(world, cx, cz)) {
                    int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                    int y = Math.max(world.getBottomY(), topY - 1);

                    pos.set(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    MapColor mapColor = state.getMapColor(world, pos);

                    int rgb = mapColor.color;

                    rgb = shadeByHeight(rgb, topY);

                    if (WATER_TINT && mapColor == MapColor.WATER_BLUE) {
                        rgb = mix(rgb, 0x2A4DFF, 0.35f);
                    }

                    rgb = brighten(rgb, BRIGHTNESS);
                    int argb = 0xFF000000 | (rgb & 0x00FFFFFF);

                    BACK[idx] = argb;
                    BACK_H[idx] = topY;

                    writeCache(x, z, argb, topY);
                } else {
                    // Not loaded: ONLY show cached terrain, otherwise unknown.
                    if (readCache(x, z, tmpRead)) {
                        BACK[idx] = tmpRead.argb;
                        BACK_H[idx] = tmpRead.topY;
                    } else {
                        BACK[idx] = UNKNOWN_ARGB;
                        BACK_H[idx] = Integer.MIN_VALUE;
                    }
                }
            }

            applySlopeShadingForRow(sy);
            writeRowToTexture(sy);
            lastRowWritten = sy;

            buildRow++;
            rows++;
        }

        if (lastRowWritten >= 0) texture.upload();

        if (buildRow >= SAMPLE_SIZE) {
            System.arraycopy(BACK, 0, FRONT, 0, BACK.length);
            System.arraycopy(BACK_H, 0, FRONT_H, 0, BACK_H.length);
            rebuilding = false;
        }
    }

    private static boolean isChunkLoaded(ClientWorld world, int cx, int cz) {
        try {
            return world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false) != null;
        } catch (Throwable ignored) {
            try {
                return world.getChunkManager().isChunkLoaded(cx, cz);
            } catch (Throwable ignored2) {
                return true;
            }
        }
    }

    // ---- Cache helpers ----

    private static long packChunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static int localIndex(int x, int z) {
        return (z & 15) * 16 + (x & 15);
    }

    private static void writeCache(int x, int z, int argb, int topY) {
        int cx = x >> 4;
        int cz = z >> 4;
        long key = packChunkKey(cx, cz);

        ChunkCache cc = chunkCache.get(key);
        if (cc == null) {
            cc = new ChunkCache();
            chunkCache.put(key, cc);
        }

        int li = localIndex(x, z);
        cc.color[li] = argb;
        cc.height[li] = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, topY));

        cacheDirty = true;
    }

    private static final class CacheRead {
        int argb;
        int topY;
    }
    private static final CacheRead tmpRead = new CacheRead();

    private static boolean readCache(int x, int z, CacheRead out) {
        int cx = x >> 4;
        int cz = z >> 4;
        long key = packChunkKey(cx, cz);

        ChunkCache cc = chunkCache.get(key);
        if (cc == null) return false;

        int li = localIndex(x, z);
        int argb = cc.color[li];
        if (argb == UNKNOWN_ARGB) return false;

        short h = cc.height[li];
        if (h == Short.MIN_VALUE) return false;

        out.argb = argb;
        out.topY = h;
        return true;
    }

    // ---- Disk persistence ----

    private static Path cacheFilePath() {
        MinecraftClient mc = MinecraftClient.getInstance();
        File runDir = mc.runDirectory; // .minecraft
        Path dir = runDir.toPath().resolve("technofactions").resolve("minimap_cache");
        return dir.resolve(lastWorldKey + ".mmf.gz");
    }

    private static void saveCacheToDisk() {
        if (lastWorldKey == null) return;

        try {
            Path file = cacheFilePath();
            Files.createDirectories(file.getParent());

            try (OutputStream fos = Files.newOutputStream(file);
                 BufferedOutputStream bos = new BufferedOutputStream(fos);
                 GZIPOutputStream gzos = new GZIPOutputStream(bos);
                 DataOutputStream out = new DataOutputStream(gzos)) {

                out.writeInt(CACHE_MAGIC);
                out.writeInt(CACHE_VERSION);
                out.writeInt(chunkCache.size());

                for (Map.Entry<Long, ChunkCache> e : chunkCache.entrySet()) {
                    out.writeLong(e.getKey());
                    ChunkCache cc = e.getValue();
                    for (int i = 0; i < 256; i++) out.writeInt(cc.color[i]);
                    for (int i = 0; i < 256; i++) out.writeShort(cc.height[i]);
                }
            }

            cacheDirty = false;
        } catch (Throwable ignored) {
            // if saving fails, don't crash the client
        }
    }

    private static void loadCacheFromDisk() {
        if (lastWorldKey == null) return;

        Path file = cacheFilePath();
        if (!Files.exists(file)) return;

        try (InputStream fis = Files.newInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis);
             GZIPInputStream gzis = new GZIPInputStream(bis);
             DataInputStream in = new DataInputStream(gzis)) {

            int magic = in.readInt();
            int ver = in.readInt();
            if (magic != CACHE_MAGIC || ver != CACHE_VERSION) return;

            int count = in.readInt();
            for (int n = 0; n < count; n++) {
                long key = in.readLong();
                ChunkCache cc = new ChunkCache();
                for (int i = 0; i < 256; i++) cc.color[i] = in.readInt();
                for (int i = 0; i < 256; i++) cc.height[i] = in.readShort();
                chunkCache.put(key, cc);
            }

            cacheDirty = false;
        } catch (Throwable ignored) {
            // corrupt file? just ignore
        }
    }

    // ---- Texture writes ----

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

    // ---- Shading ----

    private static void applySlopeShadingForRow(int sy) {
        for (int sx = 0; sx < SAMPLE_SIZE; sx++) {
            int idx = sy * SAMPLE_SIZE + sx;

            int h = BACK_H[idx];
            if (h == Integer.MIN_VALUE) continue;

            int hW = (sx > 0) ? BACK_H[idx - 1] : h;
            int hN = (sy > 0) ? BACK_H[idx - SAMPLE_SIZE] : h;
            if (hW == Integer.MIN_VALUE) hW = h;
            if (hN == Integer.MIN_VALUE) hN = h;

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