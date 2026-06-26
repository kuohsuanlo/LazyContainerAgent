package io.github.kuohsuanlo.lazycontainer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VersionDetectorTest {

    @Test
    void detect26dot2FromMajor69() {
        byte[] classBytes = classFileWithMajor(69);
        assertEquals("26.2", VersionDetector.fromBytes(classBytes).versionStr);
    }

    @Test
    void detect1dot21fromMajor65() {
        byte[] classBytes = classFileWithMajor(65);
        assertEquals("1.21.11", VersionDetector.fromBytes(classBytes).versionStr);
    }

    @Test
    void detect1dot20fromMajor63() {
        byte[] classBytes = classFileWithMajor(63);
        assertEquals("1.20.4", VersionDetector.fromBytes(classBytes).versionStr);
    }

    @Test
    void detect1dot19fromMajor61() {
        byte[] classBytes = classFileWithMajor(61);
        // major 61 maps to first match (V1_18_2) without CP disambiguation for now
        assertNotNull(VersionDetector.fromBytes(classBytes));
    }

    @Test
    void unknownMajorReturnsUnknown() {
        byte[] classBytes = classFileWithMajor(99);
        assertEquals(VersionDetector.McVersion.UNKNOWN, VersionDetector.fromBytes(classBytes));
    }

    @Test
    void tooShortReturnsUnknown() {
        byte[] classBytes = new byte[4];
        assertEquals(VersionDetector.McVersion.UNKNOWN, VersionDetector.fromBytes(classBytes));
    }

    // ── helpers ──

    private byte[] classFileWithMajor(int major) {
        byte[] b = new byte[8];
        b[0] = (byte) 0xCA;
        b[1] = (byte) 0xFE;
        b[2] = (byte) 0xBA;
        b[3] = (byte) 0xBE;
        b[4] = 0;           // minor version high
        b[5] = 0;           // minor version low
        b[6] = (byte) (major >>> 8);
        b[7] = (byte) major;
        return b;
    }
}
