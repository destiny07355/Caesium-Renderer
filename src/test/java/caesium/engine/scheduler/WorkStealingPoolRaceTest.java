package caesium.engine.scheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Regression test for workers observing a partially initialized worker array. */
public final class WorkStealingPoolRaceTest {
    private static final int ITERATIONS = 100;
    private static final int WORKERS = 8;

    private WorkStealingPoolRaceTest() {}

    public static void main(String[] args) throws Exception {
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (thread.getName().startsWith("caesium-race-test-")) {
                workerFailure.compareAndSet(null, failure);
            } else if (previous != null) {
                previous.uncaughtException(thread, failure);
            }
        });

        try {
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                CountDownLatch completed = new CountDownLatch(WORKERS * 4);
                try (WorkStealingPool pool =
                         new WorkStealingPool(WORKERS, "caesium-race-test-" + iteration)) {
                    for (int task = 0; task < WORKERS * 4; task++) {
                        pool.submit(completed::countDown);
                    }
                    if (!completed.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("Worker pool failed to execute every submitted task");
                    }
                }

                Throwable failure = workerFailure.get();
                if (failure != null) {
                    throw new AssertionError("Worker crashed during pool construction", failure);
                }
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }

        System.out.println("PASS  workers never observe a partially initialized pool");
    }
}
