package destiny.renderer.hardware;

public final class HardwareCapabilityDetectorTest {
    public static void main(String[] args) {
        require(HardwareCapabilityDetector.detectIGPU("Intel", "Intel(R) UHD Graphics 630", 4096),
                "Intel(R) UHD must be classified as integrated");
        require(!HardwareCapabilityDetector.detectIGPU("Intel", "Intel Arc A770", 16384),
                "Intel Arc must remain discrete");
        require(HardwareCapabilityDetector.detectIGPU("AMD", "Radeon Vega 8 Graphics", 0),
                "Vega integrated graphics must be detected without a VRAM query");
        System.out.println("PASS  hardware profile normalization");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
