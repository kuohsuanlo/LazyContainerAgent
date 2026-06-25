package io.github.kuohsuanlo.lazycontainer;

import java.lang.reflect.Method;

public final class DemoRunner {

    private static int passed;
    private static int failed;

    private DemoRunner() {}

    // Functional interface that allows checked exceptions
    @FunctionalInterface
    interface Thunk { void run() throws Exception; }

    public static void main(String[] args) {
        System.out.println("=== LazyContainerAgent DemoRunner ===");
        run("pendingMap lifecycle", DemoRunner::testPendingMap);
        run("isListTag edge cases", DemoRunner::testIsListTag);
        run("isEmptyListTag edge cases", DemoRunner::testIsEmptyListTag);
        run("findItemsField", DemoRunner::testFindItemsField);
        run("isBenignReorder edge cases", DemoRunner::testIsBenignReorder);
        System.out.println("\n=== " + passed + " passed, " + failed + " failed ===");
        System.exit(failed > 0 ? 1 : 0);
    }

    static void run(String label, Thunk task) {
        try {
            task.run();
            System.out.println("  \u2705 " + label);
            passed++;
        } catch (Throwable t) {
            System.out.println("  \u274c " + label + ": " + t);
            failed++;
        }
    }

    // ── Tests ──

    static void testPendingMap() {
        Object k1 = new Object(); Object v1 = new Object();
        Object k2 = new Object(); Object v2 = new Object();
        ContainerHelperInterceptor.pendingByItems.put(k1, v1);
        ContainerHelperInterceptor.pendingByItems.put(k2, v2);
        checkPending(2);
        ContainerHelperInterceptor.pendingByItems.remove(k1);
        checkPending(1);
        ContainerHelperInterceptor.pendingByItems.remove(k2);
        checkPending(0);
    }

    static void checkPending(int expected) {
        int actual = ContainerHelperInterceptor.pendingCount();
        if (actual != expected)
            throw new AssertionError("pendingCount: expected=" + expected + " actual=" + actual);
    }

    static void testIsListTag() throws Exception {
        Method m = ContainerHelperInterceptor.class.getDeclaredMethod("isListTag", Object.class);
        m.setAccessible(true);
        callExpect(m, false, (Object) null);
        callExpect(m, false, (Object) "hello");
        callExpect(m, false, (Object) 42);
    }

    static void testIsEmptyListTag() throws Exception {
        Method m = ContainerHelperInterceptor.class.getDeclaredMethod("isEmptyListTag", Object.class);
        m.setAccessible(true);
        callExpect(m, false, (Object) null);
        callExpect(m, false, (Object) new Object());
    }

    static void testFindItemsField() throws Exception {
        Method m = ContainerHelperInterceptor.class.getDeclaredMethod("findItemsField", Object.class);
        m.setAccessible(true);
        class Mock { public java.util.List<String> items = new java.util.ArrayList<>(); }
        Mock mc = new Mock();
        Object r = m.invoke(null, mc);
        if (r != mc.items) throw new AssertionError("should find 'items' field");

        class MockS { public java.util.List<String> itemStacks = new java.util.ArrayList<>(); }
        MockS ms = new MockS();
        Object r2 = m.invoke(null, ms);
        if (r2 != ms.itemStacks) throw new AssertionError("should find 'itemStacks' field");

        class NoField { public String x = "y"; }
        Object r3 = m.invoke(null, new NoField());
        if (r3 != null) throw new AssertionError("should return null for no-match");
    }

    static void testIsBenignReorder() throws Exception {
        Method m = ContainerHelperInterceptor.class.getDeclaredMethod("isBenignReorder", Object.class, Object.class);
        m.setAccessible(true);
        callExpect(m, false, null, null);
        callExpect(m, false, (Object) "raw", (Object) "eager");
    }

    static void callExpect(Method m, boolean expected, Object... args) throws Exception {
        boolean actual = (boolean) m.invoke(null, args);
        if (actual != expected) {
            String as = java.util.Arrays.toString(args);
            throw new AssertionError(m.getName() + as + ": expected=" + expected + " actual=" + actual);
        }
    }
}
