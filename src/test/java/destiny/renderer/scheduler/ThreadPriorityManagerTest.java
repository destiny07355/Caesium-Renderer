package destiny.renderer.scheduler;

public final class ThreadPriorityManagerTest {

    public static void main(String[] args) {
        testSafeWorkerLimitCalculations();
        testPriorityTiers();
        testAdaptiveWorkerPriority();
        System.out.println("PASS  ThreadPriorityManager scheduling policy and tiers verified");
    }

    private static void testSafeWorkerLimitCalculations() {
        require(ThreadPriorityManager.calculateSafeWorkerLimit(1) == 1, "1 core must yield 1 worker");
        require(ThreadPriorityManager.calculateSafeWorkerLimit(2) == 1, "2 cores must yield 1 worker");
        require(ThreadPriorityManager.calculateSafeWorkerLimit(4) == 2, "4 cores must yield 2 workers");
        require(ThreadPriorityManager.calculateSafeWorkerLimit(6) == 3, "6 cores must yield 3 workers");
        require(ThreadPriorityManager.calculateSafeWorkerLimit(8) == 5, "8 cores must yield 5 workers");
        require(ThreadPriorityManager.calculateSafeWorkerLimit(12) == 8, "12 cores must yield 8 workers");
        require(ThreadPriorityManager.calculateSafeWorkerLimit(16) == 8, "16 cores must yield 8 workers");
        require(ThreadPriorityManager.calculateSafeWorkerLimit(32) == 10, "32 cores must be capped at 10 workers");
        require(ThreadPriorityManager.calculateSafeWorkerLimit(64) == 10, "64 cores must be capped at 10 workers");

        // Singleplayer reserves capacity for internal server thread
        require(ThreadPriorityManager.calculateSafeWorkerLimit(4, true) == 1, "4 cores singleplayer must reserve 1 worker");
        require(ThreadPriorityManager.calculateSafeWorkerLimit(8, true) == 4, "8 cores singleplayer must reserve 4 workers");
        require(ThreadPriorityManager.calculateSafeWorkerLimit(16, true) == 7, "16 cores singleplayer must reserve 7 workers");
        require(ThreadPriorityManager.calculateSafeWorkerLimit(32, true) == 9, "32 cores singleplayer must cap at 9 workers");
    }

    private static void testPriorityTiers() {
        require(ThreadPriorityManager.PRIORITY_RENDER_THREAD == 8, "Render thread priority must be 8");
        require(ThreadPriorityManager.PRIORITY_SERVER_THREAD == 7, "Server thread priority must be 7");
        require(ThreadPriorityManager.PRIORITY_NETTY_IO == 5, "Netty I/O priority must be 5");
        require(ThreadPriorityManager.PRIORITY_WORKER_NORMAL == 5, "Normal meshing worker priority must be 5");
        require(ThreadPriorityManager.PRIORITY_WORKER_LOW == 4, "Low meshing worker priority must be 4");
        require(ThreadPriorityManager.PRIORITY_BACKGROUND_FJ == 3, "Vanilla DFU/worldgen priority must be 3");
    }

    private static void testAdaptiveWorkerPriority() {
        ThreadPriorityManager.setWorkerPriorityLow(false);
        require(ThreadPriorityManager.getWorkerPriority() == 5, "Normal worker priority must be 5");

        ThreadPriorityManager.setWorkerPriorityLow(true);
        require(ThreadPriorityManager.getWorkerPriority() == 4, "Low worker priority must drop to 4");

        // Restore normal state
        ThreadPriorityManager.setWorkerPriorityLow(false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
