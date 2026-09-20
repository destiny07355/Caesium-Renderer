package destiny.renderer.system.common;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Base platform system implementation providing shared lifecycle and metrics tracking
 * for both Java 21 and Java 25 subsystems.
 */
public abstract class AbstractRendererSystem implements IRendererSystem {

    protected final Logger logger;
    protected final AtomicLong totalAllocatedBytes = new AtomicLong(0L);
    protected volatile boolean initialized = false;
    protected long frameCounter = 0L;

    protected AbstractRendererSystem(String loggerName) {
        this.logger = Logger.getLogger(loggerName);
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public long getTotalAllocatedBytes() {
        return totalAllocatedBytes.get();
    }
}
