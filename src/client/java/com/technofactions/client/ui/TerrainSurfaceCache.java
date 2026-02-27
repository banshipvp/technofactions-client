package com.technofactions.client.ui;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Persistent per-server + per-dimension surface cache.
 *
 * Storage: region files like r.<rx>.<rz>.tfc
 * Region size: 32x32 chunks
 * Entry per chunk: 1 byte built + 256 * int (ARGB) + 256 * short (topY) = 1537 bytes
 */
public final class TerrainSurfaceCache {
    // ---- visuals matching your minimap tone ----
    private static final float BRIGHTNESS = 1.10f;
    private static final boolean WATER_TINT = true;
    private static final int UNKNOWN_ARGB = 0xFF0C0F14;

    private static final int REGION_SHIFT = 5;            // 32 chunks
    private static final int REGION_SIZE = 1 << REGION_SHIFT;
    private static final int ENTRY_BYTES = 1 + (256 * 4) + (256 * 2);

    // Tick fallback: scan this many chunks around player (round-robin, cheap)
    private static final int TICK_SCAN_RADIUS_CHUNKS = 6;

    private static volatile boolean HOOKS_INSTALLED = false;

    private static String activeRootKey = null;           // hashed key: server/save + dimension
    private static File activeDir = null;

    private static final class ChunkCache {
        boolean built;
        final int[] color = new int[256];
        final short[] topY = new short[256];

        ChunkCache() {
            built = false;
            for (int i = 0; i < 256; i++) {
                color[i] = UNKNOWN_ARGB;
                topY[i] = Short.MIN_VALUE;
            }
        }
    }

    // in-memory hot cache; persistent on disk
    private static final Map<Long, ChunkCache> mem = new HashMap<>(16384);

    // round-robin scan offsets
    private static int scanOffsetX = 0;
    private static int scanOffsetZ = 0;

    private TerrainSurfaceCache() {}

    /**
     * You can still call this from your ClientModInitializer, but you don't have to anymore.
     * We also auto-install hooks the first time ensureWorld() is called.
     */
    public static void register() {
        installHooksOnce();
    }

    private static void installHooksOnce() {
        if (HOOKS_INSTALLED) return;
        HOOKS_INSTALLED = true;

        System.out.println("[TechnoFactions] TerrainSurfaceCache hooks installed");

        // Primary: chunk load
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            try {
                ensureWorld(world);
                captureChunkIfNeeded(world, chunk);
            } catch (Throwable t) {
                System.out.println("[TechnoFactions] TerrainSurfaceCache CHUNK_LOAD error: " + t);
            }
        });

        // Fallback: scan loaded chunks around player
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (client.player == null) return;
                ClientWorld world = client.world;
                if (world == null) return;

                ensureWorld(world);
                tickScanNearby(world);
            } catch (Throwable t) {
                System.out.println("[TechnoFactions] TerrainSurfaceCache TICK error: " + t);
            }
        });
    }

    public static void ensureWorld(ClientWorld world) {
        // IMPORTANT: lazy install so this works even if you forgot to call register()
        installHooksOnce();

        String key = buildRootKey(world);
        if (activeRootKey != null && activeRootKey.equals(key)) return;

        activeRootKey = key;
        mem.clear();

        File root = new File(MinecraftClient.getInstance().runDirectory, "config/technofactions/minimap_cache");
        activeDir = new File(root, key);
        //noinspection ResultOfMethodCallIgnored
        activeDir.mkdirs();

        scanOffsetX = 0;
        scanOffsetZ = 0;

        System.out.println("[TechnoFactions] TerrainSurfaceCache activeDir=" + activeDir.getAbsolutePath());
    }

    /**
     * Read a cached surface sample (ARGB + topY) for world block (x,z).
     * Returns false if unknown/unexplored.
     */
    public static boolean read(int x, int z, Sample out) {
        if (activeDir == null) return false;

        int cx = x >> 4;
        int cz = z >> 4;

        ChunkCache cc = getOrLoadChunk(cx, cz);
        if (cc == null || !cc.built) return false;

        int li = ((z & 15) << 4) | (x & 15);
        int argb = cc.color[li];
        short h = cc.topY[li];

        if (argb == UNKNOWN_ARGB || h == Short.MIN_VALUE) return false;

        out.valid = true;
        out.argb = argb;
        out.topY = h;
        return true;
    }

    public static int unknownArgb() {
        return UNKNOWN_ARGB;
    }

    // ---------------------------------------------------------------------
    // Tick scan fallback
    // ---------------------------------------------------------------------

    private static void tickScanNearby(ClientWorld world) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        int pcx = mc.player.getChunkPos().x;
        int pcz = mc.player.getChunkPos().z;

        int R = TICK_SCAN_RADIUS_CHUNKS;

        int sx = scanOffsetX - R;
        int sz = scanOffsetZ - R;

        int cx = pcx + sx;
        int cz = pcz + sz;

        // advance scan
        scanOffsetX++;
        if (scanOffsetX > (R * 2)) {
            scanOffsetX = 0;
            scanOffsetZ++;
            if (scanOffsetZ > (R * 2)) scanOffsetZ = 0;
        }

        WorldChunk chunk = null;
        try {
            chunk = world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
        } catch (Throwable ignored) {}

        if (chunk == null) return;

        captureChunkIfNeeded(world, chunk);
    }

    // ---------------------------------------------------------------------
    // Capture + persistence
    // ---------------------------------------------------------------------

    private static void captureChunkIfNeeded(ClientWorld world, WorldChunk chunk) {
        if (activeDir == null) return;

        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;

        long pkey = packChunkKey(cx, cz);

        // If already built in memory, skip.
        ChunkCache cc = mem.get(pkey);
        if (cc != null && cc.built) return;

        // If exists on disk, load it (and skip capture)
        ChunkCache disk = readChunkFromDisk(cx, cz);
        if (disk != null && disk.built) {
            mem.put(pkey, disk);
            return;
        }

        if (cc == null) {
            cc = new ChunkCache();
            mem.put(pkey, cc);
        }

        buildFromWorld(world, cx, cz, cc);
        cc.built = true;
        writeChunkToDisk(cx, cz, cc);
    }

    private static void buildFromWorld(ClientWorld world, int cx, int cz, ChunkCache cc) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int baseX = cx << 4;
        int baseZ = cz << 4;

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = baseX + lx;
                int z = baseZ + lz;

                int topY;
                try {
                    topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                } catch (Throwable t) {
                    int li = (lz << 4) | lx;
                    cc.color[li] = UNKNOWN_ARGB;
                    cc.topY[li] = Short.MIN_VALUE;
                    continue;
                }

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
                int li = (lz << 4) | lx;

                cc.color[li] = argb;
                cc.topY[li] = (short) clampShort(topY);
            }
        }
    }

    private static ChunkCache getOrLoadChunk(int cx, int cz) {
        long key = packChunkKey(cx, cz);
        ChunkCache cached = mem.get(key);
        if (cached != null) return cached;

        ChunkCache loaded = readChunkFromDisk(cx, cz);
        if (loaded != null) {
            mem.put(key, loaded);
            return loaded;
        }

        ChunkCache empty = new ChunkCache();
        mem.put(key, empty);
        return empty;
    }

    private static void writeChunkToDisk(int cx, int cz, ChunkCache cc) {
        if (activeDir == null) return;

        int rx = Math.floorDiv(cx, REGION_SIZE);
        int rz = Math.floorDiv(cz, REGION_SIZE);

        int localX = cx - rx * REGION_SIZE;   // 0..31
        int localZ = cz - rz * REGION_SIZE;   // 0..31
        int localIndex = localZ * REGION_SIZE + localX;

        File f = new File(activeDir, "r." + rx + "." + rz + ".tfc");
        long offset = (long) localIndex * (long) ENTRY_BYTES;

        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.seek(offset);
            raf.writeByte(1); // built
            for (int i = 0; i < 256; i++) raf.writeInt(cc.color[i]);
            for (int i = 0; i < 256; i++) raf.writeShort(cc.topY[i]);

            // This is the proof line you should see at least once.
            // If you never see it, capture isn't running.
            // Keep it light: only prints occasionally.
            if ((cx & 31) == 0 && (cz & 31) == 0) {
                System.out.println("[TechnoFactions] TerrainSurfaceCache wrote " + f.getName() + " (chunk " + cx + "," + cz + ")");
            }
        } catch (Throwable t) {
            System.out.println("[TechnoFactions] TerrainSurfaceCache write failed: " + t);
        }
    }

    private static ChunkCache readChunkFromDisk(int cx, int cz) {
        if (activeDir == null) return null;

        int rx = Math.floorDiv(cx, REGION_SIZE);
        int rz = Math.floorDiv(cz, REGION_SIZE);

        int localX = cx - rx * REGION_SIZE;
        int localZ = cz - rz * REGION_SIZE;
        if (localX < 0 || localX >= REGION_SIZE || localZ < 0 || localZ >= REGION_SIZE) return null;

        int localIndex = localZ * REGION_SIZE + localX;

        File f = new File(activeDir, "r." + rx + "." + rz + ".tfc");
        if (!f.exists()) return null;

        long offset = (long) localIndex * (long) ENTRY_BYTES;

        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            if (raf.length() < offset + ENTRY_BYTES) return null;
            raf.seek(offset);

            int built = raf.readUnsignedByte();
            if (built != 1) return null;

            ChunkCache cc = new ChunkCache();
            cc.built = true;

            for (int i = 0; i < 256; i++) cc.color[i] = raf.readInt();
            for (int i = 0; i < 256; i++) cc.topY[i] = raf.readShort();

            return cc;
        } catch (Throwable t) {
            System.out.println("[TechnoFactions] TerrainSurfaceCache read failed: " + t);
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Root key (1.21.x safe)
    // ---------------------------------------------------------------------

    private static String buildRootKey(ClientWorld world) {
        MinecraftClient mc = MinecraftClient.getInstance();

        String dimPart;
        try {
            dimPart = world.getRegistryKey().getValue().toString();
        } catch (Throwable t) {
            dimPart = "unknown_dimension";
        }

        String worldPart = null;

        // Multiplayer
        try {
            ServerInfo si = mc.getCurrentServerEntry();
            if (si != null && si.address != null && !si.address.isBlank()) {
                worldPart = "mp_" + si.address.trim();
            }
        } catch (Throwable ignored) {}

        // Singleplayer
        if (worldPart == null) {
            try {
                if (mc.isInSingleplayer() && mc.getServer() != null) {
                    String levelName = mc.getServer().getSaveProperties().getLevelName();
                    if (levelName != null && !levelName.isBlank()) worldPart = "sp_" + levelName.trim();
                    else worldPart = "sp_singleplayer";
                } else {
                    worldPart = "sp_singleplayer";
                }
            } catch (Throwable ignored) {
                worldPart = "sp_singleplayer";
            }
        }

        return sha1Hex(worldPart + "|" + dimPart);
    }

    private static String sha1Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static long packChunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    // ---------------------------------------------------------------------
    // Color helpers
    // ---------------------------------------------------------------------

    public static final class Sample {
        public boolean valid;
        public int argb;
        public int topY;
    }

    private static int shadeByHeight(int rgb, int topY) {
        int shade = ((topY & 31) - 16);
        return add(rgb, shade * 0.008f);
    }

    private static int brighten(int rgb, float mult) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = clamp255((int) (r * mult));
        g = clamp255((int) (g * mult));
        b = clamp255((int) (b * mult));
        return (r << 16) | (g << 8) | b;
    }

    private static int add(int rgb, float amount) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int delta = (int) (amount * 255f);
        r = clamp255(r + delta);
        g = clamp255(g + delta);
        b = clamp255(b + delta);
        return (r << 16) | (g << 8) | b;
    }

    private static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    private static int clampShort(int v) {
        if (v < Short.MIN_VALUE + 1) return Short.MIN_VALUE + 1;
        if (v > Short.MAX_VALUE) return Short.MAX_VALUE;
        return v;
    }
}
