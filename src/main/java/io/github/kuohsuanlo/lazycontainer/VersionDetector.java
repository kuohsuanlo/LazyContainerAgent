package io.github.kuohsuanlo.lazycontainer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * MC 版本偵測 — 讀取 MinecraftServer class 的 classfile major version。
 *
 * <p>Mojang 每個主要 MC 版本對應一個固定的 Java classfile major version:</p>
 * <pre>
 * MC 1.12.x → Java 8  → major 52
 * MC 1.13.x → Java 8  → major 52
 * MC 1.14.x → Java 8  → major 52
 * MC 1.15.x → Java 8  → major 52
 * MC 1.16.x → Java 8  → major 52  (但 1.16.5 開始部分 class 用 Java 11)
 * MC 1.17.x → Java 16 → major 60
 * MC 1.18.x → Java 17 → major 61
 * MC 1.19.x → Java 17 → major 61
 * MC 1.20.x → Java 17 → major 61  (1.20.5 起 Java 21 → major 65)
 * MC 1.21.x → Java 21 → major 65
 * </pre>
 *
 * <p>然而 classfile major 不完全等於 MC 版本 (1.12-1.16 都用 Java 8)。
 * 因此需要輔助 heuristic:讀 MinecraftServer 的某個常數字串或 class 本身的名稱。</p>
 *
 * <pre>
 * REVIEW(D3): 版本偵測策略。
 *   方案 A: classfile major version (目前) — 簡單但 1.12-1.16 無法區分。
 *   方案 B: 讀 MinecraftServer 的 getServerVersion() 字串 (若有)。
 *   方案 C: 讀某個已知 class 的 bytecode 找特定字串 (如版本號常數)。
 *   方案 D: 用 -Dlazycontainer.version=1.21.11 手動指定 (覆蓋自動偵測)。
 *   目前:方案 A + D 混合。若自動偵測無法區分(同 major),fallback 到 D。
 *   系統:實際區分 1.12-1.16 需方案 B,但 Phase 2 優先做 major 級別區分。
 * </pre>
 */
public final class VersionDetector {

    private VersionDetector() {}

    /** 目前偵測到的版本 (null = 未偵測)。 */
    private static volatile McVersion detected;

    /**
     * 偵測 MC 版本。先看 -Dlazycontainer.version,若無則讀 MinecraftServer class bytes。
     * @return 偵測到的版本,或不確定時回傳 UNKNOWN
     */
    public static McVersion detect() {
        McVersion cached = detected;
        if (cached != null) return cached;

        // 1. Check system property override
        String explicit = System.getProperty("lazycontainer.version");
        if (explicit != null) {
            for (McVersion v : McVersion.values()) {
                if (v.versionStr.equals(explicit)) {
                    detected = v;
                    return v;
                }
            }
            System.err.println("[LazyContainer] WARN: unknown -Dlazycontainer.version="
                    + explicit + ", falling back to auto-detect");
        }

        // 2. Auto-detect from MinecraftServer class
        try {
            Class<?> msClass = Class.forName("net.minecraft.server.MinecraftServer",
                    false, Thread.currentThread().getContextClassLoader());
            try (InputStream in = msClass.getResourceAsStream(
                    "/net/minecraft/server/MinecraftServer.class")) {
                if (in == null) {
                    // Try from classloader
                    byte[] buf = readAll(msClass);
                    if (buf == null) {
                        detected = McVersion.UNKNOWN;
                        return detected;
                    }
                    return fromBytes(buf);
                }
                return fromBytes(in.readAllBytes());
            }
        } catch (Exception e) {
            System.err.println("[LazyContainer] WARN: version detection failed — "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            detected = McVersion.UNKNOWN;
            return detected;
        }
    }

    static McVersion fromBytes(byte[] classBytes) {
        if (classBytes.length < 8) return McVersion.UNKNOWN;
        int major = ((classBytes[6] & 0xFF) << 8) | (classBytes[7] & 0xFF);

        // 1. Collect all candidates matching this major version
        List<McVersion> candidates = new ArrayList<>();
        for (McVersion v : McVersion.values()) {
            if (v.classMajor == major && v != McVersion.UNKNOWN) {
                candidates.add(v);
            }
        }

        if (candidates.isEmpty()) {
            System.err.println("[LazyContainer] WARN: unknown classfile major " + major
                    + "; use -Dlazycontainer.version=1.xx.x to override");
            System.err.println("[LazyContainer]       supported versions: "
                    + java.util.Arrays.toString(McVersion.values()));
            detected = McVersion.UNKNOWN;
            return detected;
        }

        // 2. If multiple versions share this major, scan CP for a version string
        McVersion best = candidates.get(0);
        if (candidates.size() > 1) {
            String cpVersion = scanConstantPoolForVersion(classBytes);
            if (cpVersion != null) {
                for (McVersion v : candidates) {
                    if (v.versionStr.equals(cpVersion)) {
                        best = v;
                        break;
                    }
                }
            }
        }

        detected = best;
        System.out.println("[LazyContainer] detected MC version: " + best
                + " (classfile major " + major + ")");
        return best;
    }

    /**
     * 掃描 class file constant pool 找版本字串 (如 "1.12.2", "1.16.5")。
     * CONSTANT_Utf8_info 的結構: tag(1 byte) + length(2 bytes) + bytes(length bytes)
     * 我們找 bytes 中匹配 "\d+\.\d+\.\d+" 的 entry。
     */
    static String scanConstantPoolForVersion(byte[] b) {
        if (b.length < 10) return null;
        int pos = 8; // skip magic(4) + version(4)
        int cpCount = ((b[pos++] & 0xFF) << 8) | (b[pos++] & 0xFF);
        int maxScan = Math.min(b.length, pos + 5000); // limit: first 5KB of CP

        while (pos < maxScan && pos < b.length - 3) {
            int tag = b[pos++] & 0xFF;
            switch (tag) {
                case 1: { // CONSTANT_Utf8
                    int len = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
                    pos += 2;
                    if (len > 0 && pos + len <= b.length) {
                        String s = new String(b, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                        if (s.matches("\\d+\\.\\d+(\\.\\d+)?")) {
                            // Match a known version string
                            for (McVersion v : McVersion.values()) {
                                if (v != McVersion.UNKNOWN && v.versionStr.equals(s)) {
                                    return s;
                                }
                            }
                        }
                    }
                    pos += len;
                    break;
                }
                case 7:  pos += 2; break;  // CONSTANT_Class
                case 9:  case 10: case 11:  pos += 4; break; // CONSTANT_Fieldref/Methodref/InterfaceMethodref
                case 3:  case 4:  pos += 4; break;  // CONSTANT_Integer/Float
                case 5:  case 6:  pos += 8; break;  // CONSTANT_Long/Double (takes 2 slots)
                case 12: pos += 4; break;  // CONSTANT_NameAndType
                case 8:  pos += 2; break;  // CONSTANT_String
                case 15: pos += 3; break;  // CONSTANT_MethodHandle
                case 16: pos += 2; break;  // CONSTANT_MethodType
                case 17: pos += 4; break;  // CONSTANT_Dynamic
                case 18: pos += 2; break;  // CONSTANT_InvokeDynamic
                case 19: case 20: pos += 4; break; // CONSTANT_Module/ Package
                default: return null; // unknown tag → stop scan
            }
        }
        return null;
    }

    private static byte[] readAll(Class<?> clazz) {
        try (var in = clazz.getClassLoader().getResourceAsStream(
                "net/minecraft/server/MinecraftServer.class")) {
            if (in != null) return in.readAllBytes();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static McVersion current() {
        if (detected == null) return detect();
        return detected;
    }

    /**
     * 所有支援的 MC 版本。
     * UNKNOWN 表示無法偵測或版本不支援 → agent 應退回純 vanilla (不注入)。
     */
    public enum McVersion {
        UNKNOWN(0, "unknown"),
        V1_12_2(52, "1.12.2"),
        V1_16_5(52, "1.16.5"),  // same major as 1.12 → 需手動指定或額外 heuristic
        V1_17_1(60, "1.17.1"),
        V1_18_2(61, "1.18.2"),
        V1_19_4(61, "1.19.4"),  // same major as 1.18 → 需手動指定
        V1_20_4(63, "1.20.4"),
        V1_21_11(65, "1.21.11"),
        V26_2(69, "26.2");  // Paper 26.2 = Java 25 = classfile major 69

        final int classMajor;
        final String versionStr;

        McVersion(int classMajor, String versionStr) {
            this.classMajor = classMajor;
            this.versionStr = versionStr;
        }
    }
}
