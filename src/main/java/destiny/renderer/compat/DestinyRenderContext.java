package destiny.renderer.compat;

import java.util.ArrayList;
import java.util.List;

/**
 * Stub — DestinyRenderer FRAPI RenderContext implementation.
 *
 * <p>Accumulates packed vertex data from quad emission without implementing
 * the version-sensitive {@code net.fabricmc.fabric.api.renderer.v1.render.RenderContext}
 * interface. Full interface implementation is deferred to v1.1.
 */
public final class DestinyRenderContext {

    private final List<long[]> emittedVertices = new ArrayList<>();
    private final DestinyQuadEmitter emitter = new DestinyQuadEmitter(emittedVertices);

    /** Clears accumulated vertices for a new block. */
    public void reset() {
        emittedVertices.clear();
        emitter.reset();
    }

    /**
     * Returns the internal quad emitter for manual quad submission.
     *
     * @return the DestinyQuadEmitter used by this context
     */
    public DestinyQuadEmitter getEmitter() {
        return emitter;
    }

    /**
     * Returns all packed vertices accumulated from quad emission.
     * Array length is a multiple of 4 (4 vertices per quad).
     *
     * @return flat array of packed 64-bit vertex longs
     */
    public long[] getPackedVertices() {
        int total = emittedVertices.size() * 4;
        long[] result = new long[total];
        int pos = 0;
        for (long[] arr : emittedVertices) {
            System.arraycopy(arr, 0, result, pos, arr.length);
            pos += arr.length;
        }
        return result;
    }

    /** @return number of quads accumulated since the last {@link #reset()}. */
    public int quadCount() {
        return emittedVertices.size();
    }
}
