package destiny.renderer.mixin;

import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * ModernFix-style thread priority optimization.
 * Deprioritizes background tasks (DFU, data fixers, asset loading) so they
 * cannot preempt the render thread and cause 1% low frame drops.
 */
@Mixin(Util.class)
public abstract class UtilWorkerPriorityMixin {

    @ModifyArg(
        method = "createWorker",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/concurrent/ForkJoinPool;<init>(ILjava/util/concurrent/ForkJoinPool$ForkJoinWorkerThreadFactory;Ljava/lang/Thread$UncaughtExceptionHandler;Z)V"
        ),
        index = 1
    )
    private static ForkJoinPool.ForkJoinWorkerThreadFactory caesium$adjustBackgroundWorkerPriority(
        ForkJoinPool.ForkJoinWorkerThreadFactory factory
    ) {
        return pool -> {
            ForkJoinWorkerThread thread = factory.newThread(pool);
            if (thread != null) {
                // Keep at normal priority (5) so background worldgen / chunk loading has full CPU scheduling bandwidth
                // while staying safely below the main render thread (8) and server thread (7)
                thread.setPriority(Thread.NORM_PRIORITY);
            }
            return thread;
        };
    }
}
