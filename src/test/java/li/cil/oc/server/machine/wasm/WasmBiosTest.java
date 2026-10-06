package li.cil.oc.server.machine.wasm;

import li.cil.oc.server.machine.wasm.abi.OcMachineWorld;
import li.cil.oc.server.machine.wasm.abi.VariantValue;
import li.cil.oc.server.machine.wasm.abi.WitFunction;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static li.cil.oc.server.machine.wasm.abi.OcMachineWorld.*;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Runs the boot loader shipped as "EEPROM (WASM BIOS)" against simulated
 * EEPROM and file system components.
 */
public class WasmBiosTest {
    private static final String RESOURCE = "/data/opencomputers/opencomputers/eeproms/wasmbios/bios.wasm.gz";
    private static final int EEPROM_SIZE = 8192;
    private static final int HANDLE = 5;

    /** Contents of /init.wasm by file system address. */
    private final Map<String, byte[]> files = new HashMap<>();
    private String bootAddress = "";
    private String writtenBootAddress;
    private final List<Integer> released = new ArrayList<>();
    private String openFile;
    private int readOffset;

    private static byte[] resource(final String name) throws IOException {
        try (InputStream stream = WasmBiosTest.class.getResourceAsStream(name)) {
            if (stream == null) throw new IOException("missing " + name);
            return stream.readAllBytes();
        }
    }

    private static Object[] tree(final VariantValue... nodes) {
        final List<Object> roots = new ArrayList<>();
        for (int i = 0; i < nodes.length; i++) roots.add(i);
        return new Object[]{List.of((Object[]) nodes), roots};
    }

    private static VariantValue ok(final VariantValue... nodes) {
        return VariantValue.of(CALL_RESULT_OK, tree(nodes));
    }

    private VariantValue invoke(final String address, final String method) {
        if (address.equals("eeprom-address")) {
            return switch (method) {
                case "getData" -> ok(VariantValue.of(NODE_BYTES, bootAddress.getBytes(StandardCharsets.UTF_8)));
                case "setData" -> VariantValue.of(CALL_RESULT_PENDING);
                default -> VariantValue.of(CALL_RESULT_ERR, VariantValue.of(CALL_ERROR_NO_SUCH_METHOD, null));
            };
        }
        return switch (method) {
            case "open" -> {
                if (!files.containsKey(address)) {
                    yield VariantValue.of(CALL_RESULT_ERR, VariantValue.of(CALL_ERROR_FAILED, "/init.wasm"));
                }
                openFile = address;
                readOffset = 0;
                yield ok(VariantValue.of(NODE_OPAQUE, HANDLE));
            }
            case "read" -> {
                // Small chunks, so the boot loader has to read repeatedly.
                final byte[] data = files.get(openFile);
                if (readOffset >= data.length) yield ok(VariantValue.of(NODE_NIL));
                final int end = Math.min(data.length, readOffset + 1000);
                final byte[] chunk = Arrays.copyOfRange(data, readOffset, end);
                readOffset = end;
                yield ok(VariantValue.of(NODE_BYTES, chunk));
            }
            case "close" -> ok();
            default -> VariantValue.of(CALL_RESULT_ERR, VariantValue.of(CALL_ERROR_NO_SUCH_METHOD, null));
        };
    }

    private WasmGuest bios() throws Exception {
        final WasmGuest.Bindings bindings = new WasmGuest.Bindings();
        for (final Map.Entry<String, List<WitFunction>> entry : OcMachineWorld.IMPORTS.entrySet()) {
            for (final WitFunction function : entry.getValue()) {
                bindings.bind(entry.getKey(), function, arguments -> null);
            }
        }
        bindings.bind(COMPONENT, COMPONENT_ENUMERATE, arguments -> {
            final String filter = (String) VariantValue.unwrapOption(arguments[0]);
            final List<Object> entries = new ArrayList<>();
            if ("eeprom".equals(filter)) entries.add(new Object[]{"eeprom-address", "eeprom"});
            if ("filesystem".equals(filter)) {
                for (final String address : List.of("tmp-address", "fs-a", "fs-b")) {
                    entries.add(new Object[]{address, "filesystem"});
                }
            }
            return entries;
        });
        bindings.bind(COMPONENT, COMPONENT_INVOKE, arguments -> {
            if (arguments[1].equals("setData")) {
                @SuppressWarnings("unchecked") final List<Object> nodes = (List<Object>) ((Object[]) arguments[2])[0];
                writtenBootAddress = (String) ((VariantValue) nodes.get(0)).payload();
            }
            return invoke((String) arguments[0], (String) arguments[1]);
        });
        bindings.bind(COMPUTER, COMPUTER_TMP_ADDRESS, arguments -> VariantValue.some("tmp-address"));
        bindings.bind(USERDATA, USERDATA_RELEASE, arguments -> {
            released.add((Integer) arguments[0]);
            return null;
        });

        final byte[] stored = resource(RESOURCE);
        assertTrue("BIOS must fit on a default EEPROM, is " + stored.length + " bytes", stored.length <= EEPROM_SIZE);
        return WasmGuest.instantiate(ModuleFormat.decode(stored, 1 << 20), 4, bindings, new ExecutionBudget());
    }

    private static byte[] module() throws IOException {
        // Any payload works, the host validates what is handed over.
        return resource("/wasm/test-guest.wasm");
    }

    private static void assertExec(final byte[] expected, final VariantValue step) {
        assertEquals(STEP_EXEC, step.index());
        assertArrayEquals(expected, (byte[]) step.payload());
    }

    @Test
    public void bootsFromTheBootAddress() throws Exception {
        final byte[] module = module();
        files.put("fs-a", "wrong".getBytes(StandardCharsets.UTF_8));
        files.put("fs-b", module);
        bootAddress = "fs-b";

        assertExec(module, bios().resume(VariantValue.of(EVENT_BOOT)));
        assertEquals(null, writtenBootAddress);
        assertEquals(List.of(HANDLE), released);
    }

    @Test
    public void searchesFileSystemsAndRemembersTheBootAddress() throws Exception {
        final byte[] module = module();
        files.put("tmp-address", "never booted from the tmpfs".getBytes(StandardCharsets.UTF_8));
        files.put("fs-b", module);

        final WasmGuest bios = bios();
        // Writing the boot address is not a direct call.
        assertEquals(VariantValue.of(STEP_SYNC), bios.resume(VariantValue.of(EVENT_BOOT)));
        assertEquals("fs-b", writtenBootAddress);
        assertExec(module, bios.resume(VariantValue.of(EVENT_SYNC_RESULT, ok())));
    }

    @Test
    public void reportsMissingBootMedium() throws Exception {
        final VariantValue step = bios().resume(VariantValue.of(EVENT_BOOT));
        assertEquals(VariantValue.of(STEP_CRASH, "no bootable medium found: /init.wasm"), step);
    }
}

