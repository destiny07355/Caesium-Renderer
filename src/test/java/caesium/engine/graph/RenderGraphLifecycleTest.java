package caesium.engine.graph;

import caesium.engine.CaesiumEngine;
import caesium.engine.backend.GpuCommandEncoder;
import caesium.engine.backend.NullBackend;
import caesium.engine.device.FrameContext;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class RenderGraphLifecycleTest {
    public static void main(String[] args) {
        AtomicInteger closes = new AtomicInteger();
        CaesiumEngine engine = new CaesiumEngine(new NullBackend(), 1, 1);
        engine.graph().addPass(new ClosingPass(closes));
        engine.start();
        engine.stop();
        require(closes.get() == 1, "engine shutdown must close each pass exactly once");
        require(engine.graph().passCount() == 0, "closed graph must publish no passes");
        System.out.println("PASS  render graph owns pass resource lifetime");
    }

    private record ClosingPass(AtomicInteger closes) implements RenderPass {
        public String id() { return "lifecycle-test"; }
        public String name() { return "Lifecycle test"; }
        public Set<PassResource> reads() { return Set.of(); }
        public Set<PassResource> writes() { return Set.of(); }
        public Set<PassResource> resources() { return Set.of(); }
        public Set<String> dependencies() { return Set.of(); }
        public boolean hasWork(FrameContext frame) { return false; }
        public void execute(GpuCommandEncoder encoder, FrameContext frame) {}
        public void close() { closes.incrementAndGet(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
