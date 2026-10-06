package li.cil.oc.server.machine.wasm;

import li.cil.oc.server.machine.wasm.abi.OcMachineWorld;
import li.cil.oc.server.machine.wasm.abi.VariantValue;
import li.cil.oc.server.machine.wasm.abi.WitFunction;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static li.cil.oc.server.machine.wasm.abi.OcMachineWorld.*;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Tests against modules generated from the WIT files: the wasm-tools dummy
 * modules check that every host signature matches the WIT definitions, and
 * the wit-bindgen test guest (src/test/wasm/test-guest) checks that values
 * survive the round trip through real generated bindings.
 */
public class WasmGuestTest {
    private static final int MAX_PAGES = 16;

    private static byte[] resource(final String name) throws IOException {
        try (InputStream stream = WasmGuestTest.class.getResourceAsStream("/wasm/" + name)) {
            if (stream == null) throw new IOException("missing test resource " + name);
            return stream.readAllBytes();
        }
    }

    /**
     * Binds every host function to a handler returning a neutral value.
     */
    private static WasmGuest.Bindings stubBindings() {
        final WasmGuest.Bindings bindings = new WasmGuest.Bindings();
        for (final Map.Entry<String, List<WitFunction>> entry : OcMachineWorld.IMPORTS.entrySet()) {
            for (final WitFunction function : entry.getValue()) {
                bindings.bind(entry.getKey(), function, arguments -> null);
            }
        }
        return bindings;
    }

    private static Object[] tree(final Object... rootNodes) {
        final List<Object> roots = new ArrayList<>();
        for (int i = 0; i < rootNodes.length; i++) roots.add(i);
        return new Object[]{List.of(rootNodes), roots};
    }

    // ----------------------------------------------------------------------- //

    @Test
    public void legacyDummyModuleLinks() throws Exception {
        final WasmGuest guest = WasmGuest.instantiate(resource("dummy-legacy.wasm"), MAX_PAGES, stubBindings(), new ExecutionBudget());
        assertEquals(MAX_PAGES, guest.maxPages());
    }

    @Test
    public void cm32p2DummyModuleLinks() throws Exception {
        WasmGuest.instantiate(resource("dummy-cm32p2.wasm"), MAX_PAGES, stubBindings(), new ExecutionBudget());
    }

    @Test
    public void missingHostFunctionIsReported() throws Exception {
        final WasmGuest.Bindings bindings = new WasmGuest.Bindings();
        final WasmGuestException e = assertThrows(WasmGuestException.class,
                () -> WasmGuest.instantiate(resource("dummy-legacy.wasm"), MAX_PAGES, bindings, new ExecutionBudget()));
        assertTrue(e.getMessage(), e.getMessage().startsWith("unimplemented import"));
    }

    @Test
    public void invalidModuleIsReported() {
        final WasmGuestException e = assertThrows(WasmGuestException.class,
                () -> WasmGuest.instantiate(new byte[]{0, 'a', 's', 'm', 9, 9, 9, 9}, MAX_PAGES, stubBindings(), new ExecutionBudget()));
        assertTrue(e.getMessage(), e.getMessage().startsWith("invalid WebAssembly module"));
    }

    @Test
    public void insufficientMemoryIsReported() {
        final WasmGuestException e = assertThrows(WasmGuestException.class,
                () -> WasmGuest.instantiate(resource("test-guest.wasm"), 0, stubBindings(), new ExecutionBudget()));
        assertTrue(e.getMessage(), e.getMessage().startsWith("not enough memory"));
    }

    // ----------------------------------------------------------------------- //

    private static final class Host {
        final AtomicReference<Object[]> invoked = new AtomicReference<>();
        final AtomicInteger released = new AtomicInteger(-1);
        final AtomicInteger invocations = new AtomicInteger();
        boolean suspendInvoke;
        final GuestOutput stdout = new GuestOutput(null);
        final GuestOutput stderr = new GuestOutput(null);
        VariantValue invokeResult;
        VariantValue tmpAddress = VariantValue.none();
        String module = "test-guest.wasm";
        boolean wasi;

        WasmGuest.Bindings bindings() {
            final WasmGuest.Bindings bindings = stubBindings();
            bindings.bind(COMPUTER, COMPUTER_ADDRESS, arguments -> "machine-address");
            bindings.bind(COMPUTER, COMPUTER_TMP_ADDRESS, arguments -> tmpAddress);
            bindings.bind(COMPONENT, COMPONENT_ENUMERATE, arguments -> List.of(
                    new Object[]{"echo-address", "echo"},
                    new Object[]{"other-address", "other"}));
            bindings.bind(UNICODE, UNICODE_STRING_WIDTH, arguments -> {
                assertEquals("a\u4e00", arguments[0]);
                return 3;
            });
            bindings.bind(COMPONENT, COMPONENT_INVOKE, arguments -> {
                invoked.set(arguments);
                invocations.incrementAndGet();
                if (suspendInvoke) return WasmGuest.SUSPEND;
                return invokeResult != null ? invokeResult : VariantValue.of(CALL_RESULT_OK, arguments[2]);
            });
            bindings.bind(USERDATA, USERDATA_RELEASE, arguments -> {
                released.set((Integer) arguments[0]);
                return null;
            });
            if (wasi) {
                bindings.provide(new WasiSupport(stdout, stderr).functions());
            }
            return bindings;
        }

        WasmGuest instantiate() throws Exception {
            return WasmGuest.instantiate(resource(module), MAX_PAGES, bindings(), new ExecutionBudget());
        }
    }

    private static Host wasiHost() {
        final Host host = new Host();
        host.module = "test-guest-wasip1.wasm";
        host.wasi = true;
        return host;
    }

    @Test
    public void directCallsRoundTripThroughGuest() throws Exception {
        assertDirectCallsRoundTrip(new Host());
    }

    @Test
    public void directCallsRoundTripThroughWasiGuest() throws Exception {
        assertDirectCallsRoundTrip(wasiHost());
    }

    @SuppressWarnings("unchecked")
    private static void assertDirectCallsRoundTrip(final Host host) throws Exception {
        final WasmGuest guest = host.instantiate();

        assertEquals(VariantValue.of(STEP_SLEEP, 9.0), guest.resume(VariantValue.of(EVENT_BOOT)));

        final Object[] arguments = host.invoked.get();
        assertEquals("echo-address", arguments[0]);
        assertEquals("echo", arguments[1]);
        final Object[] tree = (Object[]) arguments[2];
        final List<Object> nodes = (List<Object>) tree[0];
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), tree[1]);
        assertEquals(VariantValue.of(NODE_TEXT, "machine-address"), nodes.get(0));
        assertEquals(VariantValue.of(NODE_INTEGER, 203L), nodes.get(1));
        assertEquals(VariantValue.of(NODE_ARRAY, List.of(0, 1)), nodes.get(2));
        final List<Object> table = (List<Object>) ((VariantValue) nodes.get(3)).payload();
        assertArrayEquals(new Object[]{0, 1}, (Object[]) table.get(0));
        assertArrayEquals(new Object[]{6, 2}, (Object[]) table.get(1));
        assertEquals(VariantValue.of(NODE_NUMBER, 1.5), nodes.get(4));
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) ((VariantValue) nodes.get(5)).payload());
        assertEquals(VariantValue.of(NODE_BOOLEAN, true), nodes.get(6));
        assertEquals(VariantValue.of(NODE_NIL), nodes.get(7));
        assertEquals(VariantValue.of(NODE_OPAQUE, 7), nodes.get(8));
    }

    @Test
    public void pendingCallsCompleteThroughSyncResult() throws Exception {
        final Host host = new Host();
        host.invokeResult = VariantValue.of(CALL_RESULT_PENDING);
        final WasmGuest guest = host.instantiate();

        assertEquals(VariantValue.of(STEP_SYNC), guest.resume(VariantValue.of(EVENT_BOOT)));
        final VariantValue result = VariantValue.of(CALL_RESULT_OK, tree(VariantValue.of(NODE_TEXT, "done")));
        assertEquals(VariantValue.of(STEP_SHUTDOWN, true), guest.resume(VariantValue.of(EVENT_SYNC_RESULT, result)));
    }

    @Test
    public void callErrorsReachGuest() throws Exception {
        final Host host = new Host();
        host.invokeResult = VariantValue.of(CALL_RESULT_ERR, VariantValue.of(CALL_ERROR_BAD_ARGUMENT, "nope"));
        final WasmGuest guest = host.instantiate();

        assertEquals(VariantValue.of(STEP_CRASH, "bad argument: nope"), guest.resume(VariantValue.of(EVENT_BOOT)));
    }

    @Test
    public void signalsAndWakeUpsReachGuest() throws Exception {
        final Host host = new Host();
        final WasmGuest guest = host.instantiate();

        final Object[] args = tree(VariantValue.of(NODE_TEXT, "a"), VariantValue.of(NODE_INTEGER, 30L));
        assertEquals(VariantValue.of(STEP_CRASH, "key_down:2:2"), guest.resume(VariantValue.of(EVENT_SIGNAL, new Object[]{"key_down", args})));
        assertEquals(7, host.released.get());

        assertEquals(VariantValue.of(STEP_SLEEP, 1.0), guest.resume(VariantValue.of(EVENT_WAKE)));
        host.tmpAddress = VariantValue.some("tmp-address");
        assertEquals(VariantValue.of(STEP_CRASH, "tmp-address"), guest.resume(VariantValue.of(EVENT_WAKE)));
    }

    @Test
    public void timeoutStopsGuest() throws Exception {
        final Host host = new Host();
        final ExecutionBudget budget = new ExecutionBudget();
        final WasmGuest guest = WasmGuest.instantiate(resource("test-guest.wasm"), MAX_PAGES, host.bindings(), budget);

        budget.arm(0);
        final RuntimeException e = assertThrows(RuntimeException.class,
                () -> guest.resume(VariantValue.of(EVENT_SIGNAL, new Object[]{"key_down", tree()})));
        Throwable cause = e;
        while (cause != null && !(cause instanceof WasmTimeoutException)) cause = cause.getCause();
        assertTrue("expected a timeout, got " + e, cause instanceof WasmTimeoutException);
    }

    // ----------------------------------------------------------------------- //
    // WASI preview 1

    private static VariantValue signal(final String name) {
        return VariantValue.of(EVENT_SIGNAL, new Object[]{name, tree()});
    }

    @Test
    public void wasiGuestRequiresWasi() {
        final Host host = wasiHost();
        host.wasi = false;
        final WasmGuestException e = assertThrows(WasmGuestException.class, host::instantiate);
        assertTrue(e.getMessage(), e.getMessage().startsWith("WASI is not available"));
    }

    @Test
    public void wasiStandardLibraryWorks() throws Exception {
        final Host host = wasiHost();
        final WasmGuest guest = host.instantiate();

        assertEquals(VariantValue.of(STEP_CRASH, "wasi ok true true true 1"), guest.resume(signal("wasi")));
        assertEquals("hello stdout\n", host.stdout.tail());
        assertEquals("hello stderr\n", host.stderr.tail());
    }

    @Test
    public void wasiPanicMessageIsCaptured() throws Exception {
        final Host host = wasiHost();
        final WasmGuest guest = host.instantiate();

        assertThrows(RuntimeException.class, () -> guest.resume(signal("panic")));
        final String summary = host.stderr.summary();
        assertTrue(summary, summary.contains("panicked at") && summary.endsWith("boom"));
    }

    @Test
    public void wasiExitCodeIsReported() throws Exception {
        final WasmGuest guest = wasiHost().instantiate();

        final RuntimeException e = assertThrows(RuntimeException.class, () -> guest.resume(signal("exit")));
        assertEquals(Optional.of(3), WasiSupport.exitCode(e));
    }

    @Test
    public void guestOutputIsRateLimited() {
        final List<String> lines = new ArrayList<>();
        final GuestOutput output = new GuestOutput(lines::add);
        for (int i = 0; i < 100; i++) {
            output.write(("line " + i + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, ("line " + i + "\n").length());
        }
        assertEquals(33, lines.size());
        assertEquals("(further output suppressed)", lines.get(32));
        assertEquals("line 98 line 99", output.summary());
    }

    // ----------------------------------------------------------------------- //
    // Persistence

    private static final VariantValue WAKE = VariantValue.of(EVENT_WAKE);

    private static WasmGuest restore(final Host host, final GuestSnapshot snapshot) throws Exception {
        final GuestSnapshot reloaded = GuestSnapshot.fromBytes(snapshot.toBytes());
        return WasmGuest.restore(resource(host.module), MAX_PAGES, host.bindings(), new ExecutionBudget(), reloaded);
    }

    @Test
    public void snapshotsPreserveGuestState() throws Exception {
        for (final Host host : List.of(new Host(), wasiHost())) {
            final WasmGuest guest = host.instantiate();
            assertEquals(VariantValue.of(STEP_SLEEP, 1.0), guest.resume(WAKE));
            assertEquals(VariantValue.of(STEP_SLEEP, 2.0), guest.resume(WAKE));

            final WasmGuest restored = restore(host, guest.snapshot());
            assertEquals(VariantValue.of(STEP_SLEEP, 3.0), restored.resume(WAKE));
            assertEquals(VariantValue.of(STEP_SLEEP, 4.0), restored.resume(WAKE));
            // The original is unaffected by the copy.
            assertEquals(VariantValue.of(STEP_SLEEP, 3.0), guest.resume(WAKE));
        }
    }

    @Test
    public void restoreRequiresEnoughMemory() throws Exception {
        final Host host = new Host();
        final WasmGuest guest = host.instantiate();
        guest.resume(VariantValue.of(EVENT_BOOT));
        final GuestSnapshot snapshot = guest.snapshot();
        final WasmGuestException e = assertThrows(WasmGuestException.class,
                () -> WasmGuest.restore(resource(host.module), snapshot.pages() - 1, host.bindings(), new ExecutionBudget(), snapshot));
        assertTrue(e.getMessage(), e.getMessage().contains("memory"));
    }

    @Test
    public void guestsWithoutAsyncifyCannotSuspend() throws Exception {
        final Host host = new Host();
        host.suspendInvoke = true;
        final WasmGuest guest = host.instantiate();
        assertTrue(!guest.canSuspend());
        assertThrows(IllegalStateException.class, () -> guest.resume(VariantValue.of(EVENT_BOOT)));
    }

    @Test
    public void asyncifyGuestsSuspendInsideImportCalls() throws Exception {
        final Host host = new Host();
        host.module = "test-guest-asyncify.wasm";
        host.suspendInvoke = true;
        final WasmGuest guest = host.instantiate();
        assertTrue(guest.canSuspend());

        assertEquals(null, guest.resume(VariantValue.of(EVENT_BOOT)));
        assertTrue(guest.isSuspended());
        assertEquals(1, host.invocations.get());

        final VariantValue result = VariantValue.of(CALL_RESULT_OK, tree(VariantValue.of(NODE_TEXT, "a"), VariantValue.of(NODE_TEXT, "b")));
        assertEquals(VariantValue.of(STEP_SLEEP, 2.0), guest.continueSuspended(result));
        assertTrue(!guest.isSuspended());
        // The import was completed through the rewind, not called again.
        assertEquals(1, host.invocations.get());
        assertEquals(VariantValue.of(STEP_SLEEP, 1.0), guest.resume(WAKE));
    }

    @Test
    public void suspendedGuestsSurviveSnapshots() throws Exception {
        final Host host = new Host();
        host.module = "test-guest-asyncify.wasm";
        host.suspendInvoke = true;
        final WasmGuest guest = host.instantiate();
        assertEquals(VariantValue.of(STEP_SLEEP, 1.0), guest.resume(WAKE));
        assertEquals(null, guest.resume(VariantValue.of(EVENT_BOOT)));

        // Persist the guest while it is in the middle of component.invoke...
        final Host restoredHost = new Host();
        restoredHost.module = host.module;
        final WasmGuest restored = restore(restoredHost, guest.snapshot());
        assertTrue(restored.isSuspended());

        // ...and finish that call in the restored instance.
        final VariantValue result = VariantValue.of(CALL_RESULT_OK, tree(VariantValue.of(NODE_NIL)));
        assertEquals(VariantValue.of(STEP_SLEEP, 1.0), restored.continueSuspended(result));
        assertEquals(0, restoredHost.invocations.get());
        assertEquals(VariantValue.of(STEP_SLEEP, 2.0), restored.resume(WAKE));
    }
}

