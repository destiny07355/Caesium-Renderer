package destiny.renderer.system;

import destiny.renderer.system.common.CommonMemoryUtils;
import destiny.renderer.system.common.IRendererSystem;
import destiny.renderer.system.java21.Java21System;
import destiny.renderer.system.java25.Java25System;
import java.nio.ByteBuffer;

public final class MultiVersionSystemTest {

    public static void main(String[] args) {
        testCommonMemoryUtils();
        testJava21System();
        testJava25System();
        testSystemDispatcher();
        System.out.println("PASS  multi-version Java 21 / Java 25 platform system isolation and dynamic dispatch");
    }

    private static void testCommonMemoryUtils() {
        require(CommonMemoryUtils.align8(0) == 0, "align8(0) should be 0");
        require(CommonMemoryUtils.align8(1) == 8, "align8(1) should be 8");
        require(CommonMemoryUtils.align8(8) == 8, "align8(8) should be 8");
        require(CommonMemoryUtils.align8(9) == 16, "align8(9) should be 16");

        require(CommonMemoryUtils.align16(0) == 0, "align16(0) should be 0");
        require(CommonMemoryUtils.align16(5) == 16, "align16(5) should be 16");
        require(CommonMemoryUtils.align16(16) == 16, "align16(16) should be 16");
        require(CommonMemoryUtils.align16(17) == 32, "align16(17) should be 32");
    }

    private static void testJava21System() {
        Java21System sys = new Java21System();
        require(sys.javaVersion() == 21, "Java 21 system must report version 21");
        require("system/java21".equals(sys.systemFolder()), "Java 21 folder mismatch");

        sys.initialize();
        require(sys.isInitialized(), "Java 21 system must be initialized");

        sys.beginFrame();
        ByteBuffer buf = sys.allocateFrameBuffer(1024);
        require(buf != null && buf.capacity() >= 1024, "Buffer allocation failed");
        buf.putInt(0, 0xCAFE);
        require(buf.getInt(0) == 0xCAFE, "Buffer read/write mismatch");

        long ptr = sys.allocatePersistent("test_buffer", 2048);
        require(ptr != 0L, "Persistent allocation failed");
        require(sys.getTotalAllocatedBytes() > 0, "Allocated bytes must be positive");

        sys.freePersistent("test_buffer");
        sys.endFrame();
        sys.shutdown();
        require(!sys.isInitialized(), "Java 21 system must be uninitialized after shutdown");
    }

    private static void testJava25System() {
        Java25System sys = new Java25System();
        require(sys.javaVersion() == 25, "Java 25 system must report version 25");
        require("system/java25".equals(sys.systemFolder()), "Java 25 folder mismatch");

        sys.initialize();
        require(sys.isInitialized(), "Java 25 system must be initialized");

        sys.beginFrame();
        ByteBuffer buf = sys.allocateFrameBuffer(1024);
        require(buf != null && buf.capacity() >= 1024, "Buffer allocation failed");
        buf.putInt(0, 0xBEEF);
        require(buf.getInt(0) == 0xBEEF, "Buffer read/write mismatch");

        long handle = sys.allocatePersistent("test_buffer_25", 2048);
        require(handle != 0L, "Persistent allocation failed");
        require(sys.getTotalAllocatedBytes() > 0, "Allocated bytes must be positive");

        sys.freePersistent("test_buffer_25");
        sys.endFrame();
        sys.shutdown();
        require(!sys.isInitialized(), "Java 25 system must be uninitialized after shutdown");
    }

    private static void testSystemDispatcher() {
        IRendererSystem active = SystemDispatcher.getActiveSystem();
        require(active != null, "SystemDispatcher must provide an active system");
        int jvmVer = SystemDispatcher.getDetectedJavaVersion();
        require(jvmVer >= 21, "Detected JVM version must be >= 21, got: " + jvmVer);

        if (jvmVer >= 25) {
            require(active.javaVersion() == 25, "Java 25 runtime must select Java 25 system");
            require("system/java25".equals(active.systemFolder()), "Java 25 runtime must select system/java25 folder");
        } else {
            require(active.javaVersion() == 21, "Java 21 runtime must select Java 21 system");
            require("system/java21".equals(active.systemFolder()), "Java 21 runtime must select system/java21 folder");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
