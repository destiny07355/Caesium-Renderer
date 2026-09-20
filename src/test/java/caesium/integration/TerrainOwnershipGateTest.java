package caesium.integration;

public final class TerrainOwnershipGateTest {
    public static void main(String[] args) {
        TerrainOwnershipGate gate = new TerrainOwnershipGate();
        gate.beginFrame();
        require(!gate.mayCancelVanilla(), "cold gate must preserve vanilla");
        gate.recordGroup(TerrainOwnershipGate.OPAQUE, true);
        gate.recordGroup(TerrainOwnershipGate.TRANSLUCENT, true);
        gate.recordGroup(TerrainOwnershipGate.TRIPWIRE, true);
        gate.beginFrame();
        require(gate.mayCancelVanilla(), "complete valid warm-up frame must arm ownership");
        gate.recordGroup(TerrainOwnershipGate.OPAQUE, false);
        require(!gate.mayCancelVanilla(), "a live group failure must restore fallback immediately");
        gate.beginFrame();
        require(!gate.mayCancelVanilla(), "failed frame must not re-arm ownership");
        gate.recordGroup(TerrainOwnershipGate.OPAQUE, true);
        gate.recordGroup(TerrainOwnershipGate.TRANSLUCENT, true);
        gate.beginFrame();
        require(!gate.mayCancelVanilla(), "missing tripwire group must preserve vanilla");
        gate.reset();
        gate.recordGroup(TerrainOwnershipGate.ALL_GROUPS, true);
        gate.recordGroup(TerrainOwnershipGate.OPAQUE | TerrainOwnershipGate.TRANSLUCENT, true);
        gate.recordGroup(TerrainOwnershipGate.OPAQUE, true);
        gate.recordGroup(TerrainOwnershipGate.TRANSLUCENT, true);
        gate.recordGroup(TerrainOwnershipGate.TRIPWIRE, true);
        gate.beginFrame();
        require(!gate.mayCancelVanilla(), "combined group masks must preserve vanilla");
        gate.reset();
        gate.recordGroup(TerrainOwnershipGate.OPAQUE, true);
        gate.recordGroup(TerrainOwnershipGate.OPAQUE, true);
        gate.recordGroup(TerrainOwnershipGate.TRANSLUCENT, true);
        gate.recordGroup(TerrainOwnershipGate.TRIPWIRE, true);
        gate.beginFrame();
        require(!gate.mayCancelVanilla(), "duplicate groups must preserve vanilla");
        System.out.println("PASS  staged terrain ownership fallback gate");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
