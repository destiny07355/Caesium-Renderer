package caesium.engine.scheduler;

import java.util.ArrayDeque;

/**
 * A small work-stealing thread pool used for meshing and other CPU-heavy engine work. Each
 * worker keeps a LIFO deque; idle workers steal from the head (oldest) end of another
 * worker's deque to balance load. Priorities are handled by the scheduler before
 * submission — this pool is intentionally priority-agnostic (ARCHITECTURE.md §13.5).
 */
public final class WorkStealingPool implements AutoCloseable {

    public interface Task {
        void run();
    }

    private static final long POLL_PARK_MS = 2L;

    private final Worker[] workers;
    private final java.util.concurrent.atomic.AtomicInteger cursor = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean closed;

    public WorkStealingPool(int threads, String name) {
        int n = Math.max(1, threads);
        workers = new Worker[n];
        for (int i = 0; i < n; i++) {
            workers[i] = new Worker(name + "-" + i);
            workers[i].start();
        }
    }

    public void submit(Task task) {
        if (closed) {
            throw new IllegalStateException("Caesium: work pool is closed");
        }
        int idx = Math.floorMod(cursor.getAndIncrement(), workers.length);
        workers[idx].push(task);
    }

    /** Blocks until every worker has drained its queue. */
    public void awaitIdle() {
        boolean busy;
        do {
            busy = false;
            for (Worker w : workers) {
                if (!w.isIdle()) {
                    busy = true;
                    break;
                }
            }
            if (busy) {
                try {
                    Thread.sleep(POLL_PARK_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } while (busy);
    }

    /**
     * Blocks until every worker has drained its queue, or the timeout elapses.
     *
     * @param timeoutMs maximum milliseconds to wait
     * @return true if all workers became idle within the timeout, false if timed out
     */
    public boolean awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean busy;
        do {
            busy = false;
            for (Worker w : workers) {
                if (!w.isIdle()) {
                    busy = true;
                    break;
                }
            }
            if (busy) {
                if (System.currentTimeMillis() >= deadline) return false;
                try {
                    Thread.sleep(POLL_PARK_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        } while (busy);
        return true;
    }

    public int workerCount() {
        return workers.length;
    }

    @Override
    public void close() {
        closed = true;
        for (Worker w : workers) {
            w.shutdownFlag = true;
        }
        for (Worker w : workers) {
            synchronized (w) {
                w.notifyAll();
            }
        }
        for (Worker w : workers) {
            try {
                w.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Task steal(Worker thief) {
        for (Worker w : workers) {
            if (w == thief) {
                continue;
            }
            synchronized (w.queue) {
                if (!w.queue.isEmpty()) {
                    return w.queue.pollFirst();
                }
            }
        }
        return null;
    }

    private final class Worker extends Thread {
        final ArrayDeque<Task> queue = new ArrayDeque<>();
        volatile boolean shutdownFlag;
        volatile boolean idle = true;

        Worker(String name) {
            super(name);
            setDaemon(true);
        }

        boolean isIdle() {
            return idle;
        }

        void push(Task task) {
            synchronized (queue) {
                queue.addFirst(task);
            }
            synchronized (this) {
                notify();
            }
        }

        @Override
        public void run() {
            while (!shutdownFlag) {
                Task task;
                synchronized (queue) {
                    task = queue.pollLast();
                }
                if (task == null) {
                    task = steal(this);
                }
                if (task == null) {
                    idle = true;
                    synchronized (this) {
                        try {
                            wait(POLL_PARK_MS);
                        } catch (InterruptedException e) {
                            if (shutdownFlag) {
                                break;
                            }
                        }
                    }
                } else {
                    idle = false;
                    try {
                        task.run();
                    } catch (Throwable ignored) {
                        // A failed job must not kill the pool.
                    }
                }
            }
        }
    }
}