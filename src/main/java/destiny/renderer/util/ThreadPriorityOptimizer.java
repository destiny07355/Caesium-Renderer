package destiny.renderer.util;

import destiny.renderer.scheduler.ThreadPriorityManager;

/**
 * Optimizes OS thread scheduling priority for peak FPS and smooth 1% lows.
 * Delegates to {@link ThreadPriorityManager}.
 */
public final class ThreadPriorityOptimizer {

    private ThreadPriorityOptimizer() {}

    public static void boostMainThread() {
        ThreadPriorityManager.boostMainThread();
    }
}

