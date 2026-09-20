package caesium.engine.scheduler;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

/**
 * High-performance lock-free work-stealing thread pool for chunk meshing and engine computation.
 *
 * <p>Each worker owns a lock-free Chase-Lev work-stealing deque. The owner pushes and pops
 * tasks locally (LIFO) without acquiring monitor locks. Thief threads steal from the top
 * of peer deques using atomic CAS (FIFO). External task submissions from non-worker threads
 * are enqueued to a lock-free incoming queue and immediately unpark the target worker
 * via {@link LockSupport#unpark(Thread)}.
 */
public final class WorkStealingPool implements AutoCloseable {

    public interface Task {
        void run();
    }

    private final Worker[] workers;
    private final AtomicInteger cursor = new AtomicInteger();
    private volatile boolean closed;
    private volatile int activeWorkerLimit;

    public WorkStealingPool(int threads, String name) {
        int n = Math.max(1, threads);
        workers = new Worker[n];
        activeWorkerLimit = n;
        for (int i = 0; i < n; i++) {
            workers[i] = new Worker(name + "-" + i, i);
        }
        for (Worker worker : workers) {
            worker.start();
        }
    }

    public void setActiveWorkerLimit(int limit) {
        this.activeWorkerLimit = Math.max(1, Math.min(limit, workers.length));
    }

    public int activeWorkerLimit() {
        return activeWorkerLimit;
    }

    public void submit(Task task) {
        if (closed) {
            throw new IllegalStateException("Caesium: work pool is closed");
        }
        int limit = Math.max(1, Math.min(activeWorkerLimit, workers.length));
        int idx = Math.floorMod(cursor.getAndIncrement(), limit);
        workers[idx].push(task);
        unparkOneIdleWorker();
    }

    private void unparkOneIdleWorker() {
        int limit = Math.max(1, Math.min(activeWorkerLimit, workers.length));
        for (int i = 0; i < limit; i++) {
            Worker w = workers[i];
            if (w != null && w != Thread.currentThread() && w.idle) {
                LockSupport.unpark(w);
                break;
            }
        }
    }

    /** Blocks until every worker has drained its queue. */
    public void awaitIdle() {
        while (!isPoolIdle()) {
            LockSupport.parkNanos(500_000L); // 0.5 ms low-latency park
        }
    }

    /**
     * Blocks until every worker has drained its queue, or the timeout elapses.
     *
     * @param timeoutMs maximum milliseconds to wait
     * @return true if all workers became idle within the timeout, false if timed out
     */
    public boolean awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!isPoolIdle()) {
            if (System.currentTimeMillis() >= deadline) return false;
            LockSupport.parkNanos(500_000L);
        }
        return true;
    }

    private boolean isPoolIdle() {
        for (Worker w : workers) {
            if (!w.isIdle()) return false;
        }
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
            LockSupport.unpark(w);
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
        int n = workers.length;
        int limit = Math.max(1, Math.min(activeWorkerLimit, n));
        int myIdx = thief.workerIndex;

        // 1. Locality-aware first pass: check immediate adjacent ring neighbors
        int right = (myIdx + 1) % n;
        if (right < limit && right != myIdx) {
            Task task = tryStealFrom(workers[right]);
            if (task != null) return task;
        }
        int left = (myIdx - 1 + n) % n;
        if (left < limit && left != myIdx && left != right) {
            Task task = tryStealFrom(workers[left]);
            if (task != null) return task;
        }

        // 2. Global scan across all active workers
        int start = Math.floorMod(thief.hashCode(), limit);
        for (int i = 0; i < limit; i++) {
            int victimIdx = (start + i) % limit;
            if (victimIdx == myIdx || victimIdx == right || victimIdx == left) continue;
            Task task = tryStealFrom(workers[victimIdx]);
            if (task != null) return task;
        }
        return null;
    }

    private Task tryStealFrom(Worker victim) {
        if (victim == null) return null;
        Task task = victim.deque.steal();
        if (task != null) return task;
        return victim.incomingQueue.poll();
    }

    /**
     * Lock-free Chase-Lev work-stealing circular deque.
     */
    private static final class ChaseLevDeque {
        private static final int CAPACITY = 4096;
        private static final int MASK = CAPACITY - 1;

        private final AtomicReferenceArray<Task> buffer = new AtomicReferenceArray<>(CAPACITY);
        private volatile long top = 0;
        private volatile long bottom = 0;

        private static final VarHandle TOP;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                TOP = l.findVarHandle(ChaseLevDeque.class, "top", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        public boolean push(Task task) {
            long b = bottom;
            long t = top;
            if (b - t >= CAPACITY - 1) {
                return false;
            }
            buffer.set((int) (b & MASK), task);
            VarHandle.fullFence();
            bottom = b + 1;
            return true;
        }

        public Task pop() {
            long b = bottom - 1;
            bottom = b;
            VarHandle.fullFence();
            long t = top;
            long size = b - t;
            if (size < 0) {
                bottom = t;
                return null;
            }
            Task task = buffer.get((int) (b & MASK));
            if (size > 0) {
                buffer.set((int) (b & MASK), null);
                return task;
            }
            // size == 0: race with thief
            if (TOP.compareAndSet(this, t, t + 1)) {
                buffer.set((int) (b & MASK), null);
                bottom = t + 1;
                return task;
            } else {
                bottom = t + 1;
                return null;
            }
        }

        public Task steal() {
            long t = top;
            VarHandle.fullFence();
            long b = bottom;
            long size = b - t;
            if (size <= 0) return null;
            Task task = buffer.get((int) (t & MASK));
            if (task == null) return null;
            if (TOP.compareAndSet(this, t, t + 1)) {
                buffer.set((int) (t & MASK), null);
                return task;
            }
            return null;
        }

        public boolean isEmpty() {
            return bottom <= top;
        }

        public int size() {
            long s = bottom - top;
            return s < 0 ? 0 : (int) s;
        }
    }

    private final class Worker extends Thread {
        final ChaseLevDeque deque = new ChaseLevDeque();
        final ConcurrentLinkedQueue<Task> incomingQueue = new ConcurrentLinkedQueue<>();
        final int workerIndex;
        volatile boolean shutdownFlag;
        volatile boolean idle = true;

        Worker(String name, int workerIndex) {
            super(name);
            this.workerIndex = workerIndex;
            setDaemon(true);
            setPriority(Thread.NORM_PRIORITY);
        }

        boolean isIdle() {
            return idle && deque.isEmpty() && incomingQueue.isEmpty();
        }

        void push(Task task) {
            if (Thread.currentThread() == this) {
                if (!deque.push(task)) {
                    incomingQueue.offer(task);
                }
                unparkOneIdleWorker();
            } else {
                incomingQueue.offer(task);
                LockSupport.unpark(this);
            }
        }

        @Override
        public void run() {
            while (!shutdownFlag) {
                Task task = deque.pop();
                if (task == null) {
                    task = incomingQueue.poll();
                }
                if (task == null && workerIndex < activeWorkerLimit) {
                    task = steal(this);
                }
                if (task == null) {
                    idle = true;
                    if (deque.isEmpty() && incomingQueue.isEmpty() && !shutdownFlag) {
                        LockSupport.park(this);
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
