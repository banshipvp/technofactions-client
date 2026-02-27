package com.technofactions.client.state;

import net.minecraft.network.PacketByteBuf;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class ClaimCache {

    public record Cell(byte type, String name) {}

    private static final Map<Long, Cell> cells = new HashMap<>();

    private ClaimCache() {}

    public static synchronized void clear() {
        cells.clear();
    }

    public static synchronized void put(int x, int z, byte type, String name) {
        cells.put(key(x, z), new Cell(type, name));
    }

    public static synchronized Cell get(int x, int z) {
        return cells.get(key(x, z));
    }

    public static synchronized void remove(int x, int z) {
        cells.remove(key(x, z));
    }

    /**
     * Prevent unbounded growth: remove cached cells far away from the current view.
     * keepRadius is in CHUNKS.
     */
    public static synchronized void pruneOutside(int centerCx, int centerCz, int keepRadius) {
        long minX = (long) centerCx - keepRadius;
        long maxX = (long) centerCx + keepRadius;
        long minZ = (long) centerCz - keepRadius;
        long maxZ = (long) centerCz + keepRadius;

        Iterator<Map.Entry<Long, Cell>> it = cells.entrySet().iterator();
        while (it.hasNext()) {
            long k = it.next().getKey();
            int x = (int) (k >> 32);
            int z = (int) (k & 0xFFFFFFFFL);

            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                it.remove();
            }
        }
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    // Format must match server snapshot:
    // int count, then repeating: int cx, int cz, byte type, String name(64)
    public static void readSnapshot(PacketByteBuf buf) {
        int count = buf.readInt();

        synchronized (ClaimCache.class) {
            // MERGE snapshot so we don't get the "box wipe" look
            for (int i = 0; i < count; i++) {
                int cx = buf.readInt();
                int cz = buf.readInt();
                byte type = buf.readByte();
                String name = buf.readString(64);
                cells.put(key(cx, cz), new Cell(type, name));
            }
        }
    }
}