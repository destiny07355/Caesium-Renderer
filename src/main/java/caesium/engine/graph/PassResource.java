package caesium.engine.graph;

/**
 * A GPU resource tracked by the render graph. The graph owns its lifetime and layout
 * transitions; the current layout is threaded through pass execution so the backend can
 * emit the exact barrier set (ARCHITECTURE.md §6).
 */
public final class PassResource {

    public enum Kind {
        IMAGE,
        BUFFER
    }

    public enum Layout {
        UNDEFINED,
        GENERAL,
        COLOR_ATTACHMENT,
        DEPTH_ATTACHMENT,
        SHADER_READ,
        TRANSFER_SRC,
        TRANSFER_DST,
        PRESENT
    }

    private final String id;
    private final Kind kind;
    private Layout layout;

    public PassResource(String id, Kind kind) {
        this(id, kind, Layout.UNDEFINED);
    }

    public PassResource(String id, Kind kind, Layout initialLayout) {
        this.id = id;
        this.kind = kind;
        this.layout = initialLayout;
    }

    public String id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public Layout layout() {
        return layout;
    }

    /** Updates the resource's current layout as the graph threads a transition through it. */
    public void transition(Layout layout) {
        this.layout = layout;
    }

    @Override
    public String toString() {
        return id;
    }
}