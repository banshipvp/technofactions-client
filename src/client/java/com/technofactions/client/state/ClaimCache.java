package com.technofactions.client.state;

import net.minecraft.network.PacketByteBuf;

import java.util.HashMap;
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

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    // Format must match server snapshot:
    // int count, then repeating: int cx, int cz, byte type, String name(64)
    public static void readSnapshot(PacketByteBuf buf) {
        int count = buf.readInt();

        synchronized (ClaimCache.class) {
            cells.clear();
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
