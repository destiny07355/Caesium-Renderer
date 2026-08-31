package destiny.renderer.compat;

import java.util.ArrayList;
import java.util.List;

/**
 * Stub — DestinyRenderer FRAPI MeshBuilder implementation.
 * Deferred to v1.1. See {@link FRAPICompatLayer} for rationale.
 *
 * <p>Holds an internal list of packed vertex longs for forward-compatibility
 * with the v1.1 FRAPI integration that will hook into FabricBakedModel emission.
 */
public final class DestinyMeshBuilder {

    private final List<long[]> quads = new ArrayList<>();

    /** Clears all accumulated quads. */
    public void reset() {
        quads.clear();
    }

    /**
     * Manually adds pre-packed quad vertices (used internally by the mesher stub).
     *
     * @param packedVerts four packed 64-bit vertex longs (one quad)
     */
    public void addQuad(long[] packedVerts) {
        if (packedVerts != null && packedVerts.length == 4) {
            quads.add(packedVerts);
        }
    }

    /**
     * Collects all accumulated packed vertices into a flat array.
     *
     * @return flat array of packed longs, length = quadCount × 4
     */
    public long[] build() {
        int total = quads.size() * 4;
        long[] result = new long[total];
        int pos = 0;
        for (long[] q : quads) {
            System.arraycopy(q, 0, result, pos, 4);
            pos += 4;
        }
        quads.clear();
        return result;
    }

    /** @return number of quads accumulated since last {@link #reset()} or {@link #build()}. */
    public int quadCount() {
        return quads.size();
    }
}
