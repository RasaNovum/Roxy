package net.rasanovum.roxy.compat;

import java.util.function.IntPredicate;

public final class RoxyTerrainSlabsCompat {
    private static final long LOWERED_SNOW = Long.MIN_VALUE;
    private static final long FULLY_OPAQUE = 1L << 54;
    private static final long CULLS_SAME = 1L << 53;
    private static final int OCCLUSION_FLAGS = 0b101;
    private static final int MISSING_FACE = 0xff;
    private static final int SECTION_PLANE = 32 * 32;
    private static final int BELOW_FACE = 2 * SECTION_PLANE;
    private static final int BELOW_NEIGHBOR_BIT = 1 << 2;
    private static final long QUAD_TYPE_MASK = 0b110;
    private static final long DIRECTIONAL_QUAD = 0b100;
    private static final long NON_DIRECTIONAL_QUAD = 0b010;

    private RoxyTerrainSlabsCompat() {
    }

    public static void markSnow(long[] raw, long[] neighbors, int neighborMask, long[] mesh,
                                int[] opaque, int[] nonOpaque, IntPredicate isSnow, IntPredicate isBottomSlab) {
        for (int row = 0; row < nonOpaque.length; row++) {
            int remaining = opaque[row] | nonOpaque[row];
            while (remaining != 0) {
                int x = Integer.numberOfTrailingZeros(remaining);
                remaining &= remaining - 1;
                int index = row * 32 + x;
                long snow = raw[index];
                if (!isSnow.test(blockId(snow))) continue;

                long below;
                if (index >= SECTION_PLANE) {
                    below = raw[index - SECTION_PLANE];
                } else if ((neighborMask & BELOW_NEIGHBOR_BIT) != 0) {
                    below = neighbors[BELOW_FACE + index];
                } else {
                    continue;
                }
                if (!isBottomSlab.test(blockId(below))) continue;

                int meshIndex = index * 2;
                long metadata = mesh[meshIndex + 1];
                if ((metadata & FULLY_OPAQUE) != 0) {
                    int bit = 1 << x;
                    opaque[row] &= ~bit;
                    nonOpaque[row] |= bit;
                    mesh[meshIndex] |= (snow & 0xff00000000000000L) >>> 1;
                }
                long quad = mesh[meshIndex] | LOWERED_SNOW;
                if (index < SECTION_PLANE && (quad & QUAD_TYPE_MASK) == DIRECTIONAL_QUAD) {
                    quad = (quad & ~QUAD_TYPE_MASK) | NON_DIRECTIONAL_QUAD;
                }
                mesh[meshIndex] = quad;
                mesh[meshIndex + 1] = loweredMetadata(metadata);
            }
        }
    }

    public static boolean occludesSnow(boolean occludes, long quad) {
        return occludes && (quad & LOWERED_SNOW) == 0;
    }

    private static long loweredMetadata(long metadata) {
        metadata = (metadata & ~(FULLY_OPAQUE | CULLS_SAME)) | MISSING_FACE;
        for (int face = 1; face < 6; face++) {
            int shift = face * Byte.SIZE;
            if (((metadata >>> shift) & MISSING_FACE) != MISSING_FACE) {
                metadata &= ~((long) OCCLUSION_FLAGS << shift);
            }
        }
        return metadata;
    }

    private static int blockId(long voxel) {
        return (int) (voxel >>> 27) & 0xfffff;
    }
}
