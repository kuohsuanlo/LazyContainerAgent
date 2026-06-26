package io.github.kuohsuanlo.lazycontainer;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;


class TransformerInjectionTest {

    @BeforeAll
    static void setup() throws Exception {
        System.setProperty("lazycontainer.version", "1.21.11");
        Field f = VersionDetector.class.getDeclaredField("detected");
        f.setAccessible(true);
        f.set(null, null);
    }

    private LazyContainerTransformer t;

    @BeforeEach
    void initTransformer() {
        t = new LazyContainerTransformer();
        t.init();
    }

    @Test
    void transformerReady() {
        assertTrue(t.isReady());
    }

    @Test
    void containerHelperLoadInjection() {
        byte[] input = containerHelperBytes();
        byte[] output = t.transform(null, "net/minecraft/world/ContainerHelper", null, null, input);
        assertNotNull(output, "should modify ContainerHelper");
        assertDoesNotThrow(() -> verifyBytecode(output));
        assertTrue(countInvocations(output, "loadAllItems", "onLoadItem") > 0,
                "output should contain onLoadItem call in loadAllItems");
    }

    @Test
    void containerHelperSaveInjection() {
        byte[] input = containerHelperBytes();
        byte[] output = t.transform(null, "net/minecraft/world/ContainerHelper", null, null, input);
        assertNotNull(output);
        assertTrue(countInvocations(output, "saveAllItems", "onSaveItem") > 0,
                "output should contain onSaveItem call in saveAllItems");
    }

    @Test
    void leafGetItemsInjection() {
        byte[] input = leafClassBytes("net/minecraft/world/level/block/entity/ChestBlockEntity",
                "getItems", "()Lnet/minecraft/core/NonNullList;");
        byte[] output = t.transform(null, "net/minecraft/world/level/block/entity/ChestBlockEntity", null, null, input);
        assertNotNull(output, "should modify ChestBlockEntity");
        assertDoesNotThrow(() -> verifyBytecode(output));
        assertTrue(countInvocations(output, "getItems", "ensure") > 0,
                "output should contain ensure call in getItems");
    }

    @Test
    void leafGetContentsInjection() {
        byte[] input = leafClassBytes("net/minecraft/world/level/block/entity/BarrelBlockEntity",
                "getContents", "()Ljava/util/List;");
        byte[] output = t.transform(null, "net/minecraft/world/level/block/entity/BarrelBlockEntity", null, null, input);
        assertNotNull(output);
        assertTrue(countInvocations(output, "getContents", "ensure") > 0);
    }

    @Test
    void unknownClassReturnsNull() {
        byte[] input = containerHelperBytes();
        byte[] output = t.transform(null, "some/random/Class", null, null, input);
        assertNull(output, "should skip unknown classes");
    }

    // ── helpers ──

    private byte[] containerHelperBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/world/ContainerHelper",
                null, "java/lang/Object", null);

        addStaticMethod(cw, "loadAllItems",
                "(Lnet/minecraft/world/level/storage/ValueInput;Lnet/minecraft/core/NonNullList;)V");
        addStaticMethod(cw, "saveAllItems",
                "(Lnet/minecraft/world/level/storage/ValueOutput;Lnet/minecraft/core/NonNullList;)V");
        addStaticMethod(cw, "saveAllItems",
                "(Lnet/minecraft/world/level/storage/ValueOutput;Lnet/minecraft/core/NonNullList;Z)V");

        cw.visitEnd();
        return cw.toByteArray();
    }

    private byte[] leafClassBytes(String internalName, String methodName, String methodDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, methodDesc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // also add getContents for classes that have it
        if (!methodName.equals("getContents")) {
            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getContents", "()Ljava/util/List;", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private void addStaticMethod(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, desc.chars().filter(c -> c == 'L').sum() + 1);
        mv.visitEnd();
    }

    private void verifyBytecode(byte[] classBytes) {
        new ClassReader(classBytes); // throws if malformed
    }

    private int countInvocations(byte[] classBytes, String methodName, String targetMethod) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        int count = 0;
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals(methodName)) continue;
            for (var insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min && min.name.contains(targetMethod)) {
                    count++;
                }
            }
        }
        return count;
    }
}
