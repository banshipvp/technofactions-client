package com.technofactions.client.ui;

public final class TerrainMinimapShared {
    private static int exclusiveDepth = 0;

    private TerrainMinimapShared() {}

    public static void beginExclusive() {
        exclusiveDepth++;
    }

    public static void endExclusive() {
        exclusiveDepth = Math.max(0, exclusiveDepth - 1);
    }

    public static boolean isExclusive() {
        return exclusiveDepth > 0;
    }

    public static void resetExclusive() {
        exclusiveDepth = 0;
    }
}