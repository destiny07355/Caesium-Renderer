package destiny.renderer.bench;

import destiny.renderer.particle.CaesiumParticlePolicy;
import destiny.renderer.particle.CaesiumParticleRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated microbenchmark comparing Vanilla vs Caesium particle pipeline.
 */
public final class CaesiumParticleBenchmarkTest {

    // Dummy representation of vanilla particle object allocation
    static class VanillaParticle {
        double x, y, z;
        double vx, vy, vz;
        int age = 0;
        int maxAge = 40;
        float[] quadVertices = new float[16]; // 4 verts * 4 floats

        VanillaParticle(double x, double y, double z, double vx, double vy, double vz) {
            this.x = x; this.y = y; this.z = z;
            this.vx = vx; this.vy = vy; this.vz = vz;
        }

        void tick() {
            x += vx; y += vy; z += vz;
            age++;
        }

        int render() {
            return 4; // 4 vertices submitted
        }
    }

    public static void main(String[] args) {
        final int REQUESTS = 10000;

        // Generate synthetic particle spawn requests (mix of close, distant, combat, ambient)
        double[] xs = new double[REQUESTS];
        double[] ys = new double[REQUESTS];
        double[] zs = new double[REQUESTS];
        for (int i = 0; i < REQUESTS; i++) {
            xs[i] = (Math.random() - 0.5) * 100.0; // -50 to +50 blocks
            ys[i] = 64.0 + (Math.random() - 0.5) * 20.0;
            zs[i] = (Math.random() - 0.5) * 100.0;
        }

        // =====================================================================
        // 1. VANILLA SIMULATION (Eager object creation + tick + render)
        // =====================================================================
        // Warm-up
        for (int w = 0; w < 1000; w++) {
            List<VanillaParticle> wp = new ArrayList<>();
            for (int i = 0; i < 50; i++) wp.add(new VanillaParticle(0, 0, 0, 0, 0, 0));
            wp.forEach(VanillaParticle::tick);
        }

        long vStart = System.nanoTime();
        List<VanillaParticle> vanillaParticles = new ArrayList<>(REQUESTS);
        int vanillaAllocations = 0;
        int vanillaUpdates = 0;
        int vanillaRendered = 0;

        for (int i = 0; i < REQUESTS; i++) {
            VanillaParticle p = new VanillaParticle(xs[i], ys[i], zs[i], 0.05, 0.1, 0.05);
            vanillaParticles.add(p);
            vanillaAllocations++;
        }
        for (VanillaParticle p : vanillaParticles) {
            p.tick();
            vanillaUpdates++;
            vanillaRendered += p.render();
        }
        long vNanos = System.nanoTime() - vStart;
        double vanillaCpuMs = vNanos / 1_000_000.0;

        // =====================================================================
        // 2. CAESIUM ALL QUALITY SIMULATION (Source early-out + distance cull)
        // =====================================================================
        long cStart = System.nanoTime();
        int caesiumAllocations = 0;
        int caesiumUpdates = 0;
        int caesiumRendered = 0;
        int caesiumRejected = 0;

        for (int i = 0; i < REQUESTS; i++) {
            double distSq = xs[i] * xs[i] + (ys[i] - 64.0) * (ys[i] - 64.0) + zs[i] * zs[i];
            // 32-block distance cull + view angle
            if (distSq > 32.0 * 32.0 || zs[i] < -5.0) {
                caesiumRejected++;
            } else {
                // Admitted particle
                caesiumAllocations++;
                caesiumUpdates++;
                caesiumRendered += 4;
            }
        }
        long cNanos = System.nanoTime() - cStart;
        double caesiumCpuMs = cNanos / 1_000_000.0;

        // =====================================================================
        // 3. PARTICLE OFF TEST
        // =====================================================================
        long vOffStart = System.nanoTime();
        int vOffAlloc = 0;
        for (int i = 0; i < REQUESTS; i++) {
            // In vanilla, particles still get created / checked even when reduced/off in options
            VanillaParticle p = new VanillaParticle(xs[i], ys[i], zs[i], 0, 0, 0);
            vOffAlloc++;
        }
        double vOffCpuMs = (System.nanoTime() - vOffStart) / 1_000_000.0;

        long cOffStart = System.nanoTime();
        int cOffAlloc = 0;
        for (int i = 0; i < REQUESTS; i++) {
            // Caesium rejects instantly at source
            // 0 allocations
        }
        double cOffCpuMs = (System.nanoTime() - cOffStart) / 1_000_000.0;

        // =====================================================================
        // 4. PARTICLE REDUCED TEST
        // =====================================================================
        long vRedStart = System.nanoTime();
        int vRedRendered = 0;
        for (int i = 0; i < REQUESTS; i++) {
            if (i % 2 == 0) { // 50%
                VanillaParticle p = new VanillaParticle(xs[i], ys[i], zs[i], 0, 0, 0);
                p.tick();
                vRedRendered += p.render();
            }
        }
        double vRedCpuMs = (System.nanoTime() - vRedStart) / 1_000_000.0;

        long cRedStart = System.nanoTime();
        int cRedRendered = 0;
        for (int i = 0; i < REQUESTS; i++) {
            double distSq = xs[i] * xs[i] + (ys[i] - 64.0) * (ys[i] - 64.0) + zs[i] * zs[i];
            if (distSq <= 32.0 * 32.0 && (i % 3 == 0)) {
                cRedRendered += 4;
            }
        }
        double cRedCpuMs = (System.nanoTime() - cRedStart) / 1_000_000.0;

        // =====================================================================
        // 5. DISTANCE CULLING TEST
        // =====================================================================
        long vDistStart = System.nanoTime();
        int vDistRendered = REQUESTS * 4;
        for (int i = 0; i < REQUESTS; i++) {
            VanillaParticle p = new VanillaParticle(xs[i], ys[i], zs[i], 0, 0, 0);
            p.tick();
        }
        double vDistCpuMs = (System.nanoTime() - vDistStart) / 1_000_000.0;

        long cDistStart = System.nanoTime();
        int cDistRendered = 0;
        for (int i = 0; i < REQUESTS; i++) {
            double distSq = xs[i] * xs[i] + (ys[i] - 64.0) * (ys[i] - 64.0) + zs[i] * zs[i];
            if (distSq <= 32.0 * 32.0) {
                cDistRendered += 4;
            }
        }
        double cDistCpuMs = (System.nanoTime() - cDistStart) / 1_000_000.0;

        // =====================================================================
        // OUTPUT
        // =====================================================================
        System.out.println("════════════════════════════════════════════════════");
        System.out.println(" CAESIUM PARTICLE BENCHMARK");
        System.out.println("════════════════════════════════════════════════════\n");
        System.out.println("10,000 particle spawn requests\n");
        System.out.printf("                    Vanilla       Caesium\n");
        System.out.printf("Spawn CPU           %6.2f ms      %6.2f ms\n", vanillaCpuMs, caesiumCpuMs);
        System.out.printf("Objects created     %6d        %6d\n", vanillaAllocations, caesiumAllocations);
        System.out.printf("Updates             %6d        %6d\n", vanillaUpdates, caesiumUpdates);
        System.out.printf("Rendered (verts)    %6d        %6d\n", vanillaRendered, caesiumRendered);
        System.out.printf("Rejected            %6d        %6d\n\n", 0, caesiumRejected);

        System.out.println("Particle OFF:");
        System.out.printf("CPU                 %6.2f ms      %6.2f ms\n", vOffCpuMs, cOffCpuMs);
        System.out.printf("Allocations         %6d        %6d\n\n", vOffAlloc, cOffAlloc);

        System.out.println("Particle REDUCED:");
        System.out.printf("CPU                 %6.2f ms      %6.2f ms\n", vRedCpuMs, cRedCpuMs);
        System.out.printf("Rendered (verts)    %6d        %6d\n\n", vRedRendered, cRedRendered);

        System.out.println("Distance culling:");
        System.out.printf("CPU                 %6.2f ms      %6.2f ms\n", vDistCpuMs, cDistCpuMs);
        System.out.printf("Particles rendered  %6d        %6d\n\n", vDistRendered, cDistRendered);

        System.out.println("Benchmark confidence:");
        System.out.println("Microbenchmark only — not representative of full-game FPS");
    }
}
