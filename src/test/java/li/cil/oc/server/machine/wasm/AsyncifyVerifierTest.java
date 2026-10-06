package li.cil.oc.server.machine.wasm;

import li.cil.oc.server.machine.wasm.abi.OcMachineWorld;
import li.cil.oc.server.machine.wasm.abi.WitFunction;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Checks {@link AsyncifyVerifier} against variants of the test guest built
 * with different wasm-opt options (see src/test/wasm/README.md).
 */
public class AsyncifyVerifierTest {
    private static byte[] resource(final String name) throws IOException {
        try (InputStream stream = AsyncifyVerifierTest.class.getResourceAsStream("/wasm/" + name)) {
            if (stream == null) throw new IOException("missing test resource " + name);
            return stream.readAllBytes();
        }
    }

    private static WasmGuest instantiate(final String name) throws Exception {
        final WasmGuest.Bindings bindings = new WasmGuest.Bindings();
        for (final Map.Entry<String, List<WitFunction>> entry : OcMachineWorld.IMPORTS.entrySet()) {
            for (final WitFunction function : entry.getValue()) {
                bindings.bind(entry.getKey(), function, arguments -> null);
            }
        }
        bindings.provide(new WasiSupport(new GuestOutput(null), new GuestOutput(null)).functions());
        return WasmGuest.instantiate(resource(name), 16, bindings, new ExecutionBudget());
    }

    private static void assertRejected(final String name, final String... fragments) {
        final WasmGuestException e = assertThrows(WasmGuestException.class, () -> instantiate(name));
        for (final String fragment : fragments) {
            assertTrue(e.getMessage(), e.getMessage().contains(fragment));
        }
    }

    @Test
    public void fullyInstrumentedModulesAreAccepted() throws Exception {
        assertTrue(instantiate("test-guest-asyncify.wasm").canSuspend());
    }

    @Test
    public void modulesWithoutAsyncifyAreNotVerified() throws Exception {
        assertFalse(instantiate("test-guest.wasm").canSuspend());
        assertFalse(instantiate("test-guest-wasip1.wasm").canSuspend());
    }

    @Test
    public void uninstrumentedImportsAreRejected() {
        // wasm-opt --asyncify --pass-arg=asyncify-ignore-imports
        assertRejected("test-guest-asyncify-ignore-imports.wasm",
                "not correctly instrumented", "oc:machine/component@0.1.0.invoke");
    }

    @Test
    public void importListsMissingInvokeAreRejected() {
        // wasm-opt --asyncify --pass-arg=asyncify-imports@oc:machine/computer@0.1.0.address
        assertRejected("test-guest-asyncify-wrong-list.wasm",
                "not correctly instrumented", "oc:machine/component@0.1.0.invoke");
    }

    @Test
    public void importListsWithVersionedNamesAreAccepted() throws Exception {
        // wasm-opt --asyncify --pass-arg=asyncify-imports@oc:machine/component@0.1.0.invoke,oc:machine/userdata@0.1.0.invoke
        // Only the listed imports are instrumented, which is all the host needs.
        assertTrue(instantiate("test-guest-asyncify-list.wasm").canSuspend());
    }

    @Test
    public void partialAsyncifyExportsAreRejected() {
        assertRejected("test-guest-asyncify-no-get-state.wasm",
                "not correctly instrumented", "missing exports asyncify_get_state");
    }
}

