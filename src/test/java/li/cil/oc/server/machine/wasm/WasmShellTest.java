package li.cil.oc.server.machine.wasm;

import li.cil.oc.server.machine.wasm.abi.OcMachineWorld;
import li.cil.oc.server.machine.wasm.abi.VariantValue;
import li.cil.oc.server.machine.wasm.abi.WitFunction;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static li.cil.oc.server.machine.wasm.abi.OcMachineWorld.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Runs the shell shipped as "EEPROM (WASM Shell)" against a simulated GPU and
 * screen, feeding it keyboard signals like a real machine would.
 */
public class WasmShellTest {
    private static final String RESOURCE = "/data/opencomputers/opencomputers/eeproms/wasmshell/shell.wasm.gz";
    private static final int EEPROM_SIZE = 8192;
    private static final int WIDTH = 50;
    private static final int HEIGHT = 16;

    private final char[][] screen = new char[HEIGHT][WIDTH];
    private final List<String> methods = new ArrayList<>();
    private boolean bindPending = true;
    private int beeps;

    private static byte[] shellResource() throws IOException {
        try (InputStream stream = WasmShellTest.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("missing " + RESOURCE);
            return stream.readAllBytes();
        }
    }

    @Before
    public void clearScreen() {
        for (final char[] row : screen) Arrays.fill(row, ' ');
    }

    // ----------------------------------------------------------------------- //
    // Simulated hardware

    @SuppressWarnings("unchecked")
    private static List<Object> values(final Object tree) {
        final Object[] fields = (Object[]) tree;
        final List<Object> nodes = (List<Object>) fields[0];
        final List<Object> result = new ArrayList<>();
        for (final Object root : (List<Object>) fields[1]) {
            result.add(((VariantValue) nodes.get((Integer) root)).payload());
        }
        return result;
    }

    private static int i(final Object value) {
        return ((Number) value).intValue();
    }

    private static Object[] tree(final VariantValue... nodes) {
        final List<Object> roots = new ArrayList<>();
        for (int i = 0; i < nodes.length; i++) roots.add(i);
        return new Object[]{List.of((Object[]) nodes), roots};
    }

    private VariantValue gpu(final String method, final List<Object> args) {
        methods.add(method);
        switch (method) {
            case "bind" -> {
                if (bindPending) {
                    bindPending = false;
                    return VariantValue.of(CALL_RESULT_PENDING);
                }
            }
            case "getResolution" -> {
                return VariantValue.of(CALL_RESULT_OK, tree(VariantValue.of(NODE_INTEGER, (long) WIDTH), VariantValue.of(NODE_INTEGER, (long) HEIGHT)));
            }
            case "set" -> {
                final String text = (String) args.get(2);
                final int y = i(args.get(1)) - 1;
                for (int k = 0; k < text.length(); k++) {
                    final int x = i(args.get(0)) - 1 + k;
                    if (x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT) screen[y][x] = text.charAt(k);
                }
            }
            case "fill" -> {
                final char c = ((String) args.get(4)).charAt(0);
                for (int y = i(args.get(1)) - 1; y < i(args.get(1)) - 1 + i(args.get(3)) && y < HEIGHT; y++) {
                    for (int x = i(args.get(0)) - 1; x < i(args.get(0)) - 1 + i(args.get(2)) && x < WIDTH; x++) {
                        screen[y][x] = c;
                    }
                }
            }
            case "copy" -> {
                final int x0 = i(args.get(0)) - 1, y0 = i(args.get(1)) - 1, w = i(args.get(2)), h = i(args.get(3));
                final int dx = i(args.get(4)), dy = i(args.get(5));
                final char[][] copy = new char[HEIGHT][];
                for (int y = 0; y < HEIGHT; y++) copy[y] = screen[y].clone();
                for (int y = y0; y < y0 + h; y++) {
                    for (int x = x0; x < x0 + w; x++) {
                        if (y + dy >= 0 && y + dy < HEIGHT && x + dx >= 0 && x + dx < WIDTH) screen[y + dy][x + dx] = copy[y][x];
                    }
                }
            }
            case "beep" -> beeps++;
            default -> {
                return VariantValue.of(CALL_RESULT_ERR, VariantValue.of(CALL_ERROR_NO_SUCH_METHOD, null));
            }
        }
        return VariantValue.of(CALL_RESULT_OK, tree());
    }

    private WasmGuest.Bindings bindings() {
        final WasmGuest.Bindings bindings = new WasmGuest.Bindings();
        for (final Map.Entry<String, List<WitFunction>> entry : OcMachineWorld.IMPORTS.entrySet()) {
            for (final WitFunction function : entry.getValue()) {
                bindings.bind(entry.getKey(), function, arguments -> null);
            }
        }
        bindings.bind(COMPONENT, COMPONENT_ENUMERATE, arguments -> {
            final String filter = (String) VariantValue.unwrapOption(arguments[0]);
            final List<Object> entries = new ArrayList<>();
            if (filter == null || filter.equals("gpu")) entries.add(new Object[]{"gpu-0000-address", "gpu"});
            if (filter == null || filter.equals("screen")) entries.add(new Object[]{"screen-0000-address", "screen"});
            return entries;
        });
        bindings.bind(COMPONENT, COMPONENT_INVOKE, arguments -> gpu((String) arguments[1], values(arguments[2])));
        bindings.bind(COMPUTER, COMPUTER_ADDRESS, arguments -> "computer-address");
        bindings.bind(COMPUTER, COMPUTER_FREE_MEMORY, arguments -> 100L * 1024);
        bindings.bind(COMPUTER, COMPUTER_TOTAL_MEMORY, arguments -> 256L * 1024);
        bindings.bind(COMPUTER, COMPUTER_UPTIME, arguments -> 42.5);
        return bindings;
    }

    private WasmGuest boot() throws Exception {
        final byte[] stored = shellResource();
        assertTrue("shell must fit on a default EEPROM, is " + stored.length + " bytes", stored.length <= EEPROM_SIZE);
        final WasmGuest guest = WasmGuest.instantiate(ModuleFormat.decode(stored, 1 << 20), 4, bindings(), new ExecutionBudget());

        // Binding the GPU is not a direct call: the shell has to wait for it.
        assertEquals(VariantValue.of(STEP_SYNC), guest.resume(VariantValue.of(EVENT_BOOT)));
        assertEquals(VariantValue.of(STEP_WAIT), guest.resume(VariantValue.of(EVENT_SYNC_RESULT, VariantValue.of(CALL_RESULT_OK, tree()))));
        return guest;
    }

    private static VariantValue key(final int character, final int code) {
        return VariantValue.of(EVENT_SIGNAL, new Object[]{"key_down", tree(
                VariantValue.of(NODE_TEXT, "keyboard"),
                VariantValue.of(NODE_INTEGER, (long) character),
                VariantValue.of(NODE_INTEGER, (long) code),
                VariantValue.of(NODE_TEXT, "player"))});
    }

    private static VariantValue type(final WasmGuest guest, final String line) {
        for (final char c : line.toCharArray()) {
            assertEquals(VariantValue.of(STEP_WAIT), guest.resume(key(c, 0)));
        }
        return guest.resume(key(13, 28));
    }

    private String row(final int row) {
        return new String(screen[row - 1]).stripTrailing();
    }

    // ----------------------------------------------------------------------- //

    @Test
    public void bootsToAPrompt() throws Exception {
        boot();
        assertEquals("WebAssembly shell", row(1));
        assertEquals("Type 'help' for a list of commands.", row(2));
        assertEquals("> _", row(3));
        // bind completes through the sync result, it is not called again.
        assertEquals(List.of("bind", "getResolution", "fill", "set", "set", "set"), methods);
    }

    @Test
    public void runsCommands() throws Exception {
        final WasmGuest guest = boot();

        assertEquals(VariantValue.of(STEP_WAIT), type(guest, "echo hello world"));
        assertEquals("> echo hello world", row(3));
        assertEquals("hello world", row(4));
        assertEquals("> _", row(5));

        type(guest, "help");
        assertEquals("help clear echo components mem uptime", row(6));

        type(guest, "mem");
        assertEquals("100 / 256 KiB free", row(9));

        type(guest, "components");
        assertEquals("gpu-0000 gpu", row(11));
        assertEquals("screen-0 screen", row(12));

        type(guest, "nope");
        assertEquals("unknown command: nope", row(14));

        type(guest, "beep");
        assertEquals(1, beeps);
    }

    @Test
    public void editsInput() throws Exception {
        final WasmGuest guest = boot();
        guest.resume(key('x', 0));
        guest.resume(key('y', 0));
        assertEquals("> xy_", row(3));
        guest.resume(key(8, 14));
        assertEquals("> x_", row(3));
    }

    @Test
    public void scrollsWhenFull() throws Exception {
        final WasmGuest guest = boot();
        for (int n = 0; n < 20; n++) {
            type(guest, "echo line " + n);
        }
        assertEquals("line 19", row(HEIGHT - 1));
        assertEquals("> _", row(HEIGHT));
        assertEquals("> echo line 19", row(HEIGHT - 2));
    }

    @Test
    public void clearsAndShutsDown() throws Exception {
        final WasmGuest guest = boot();
        type(guest, "clear");
        assertEquals("> _", row(1));
        assertEquals("", row(2));
        assertEquals(VariantValue.of(STEP_SHUTDOWN, true), type(guest, "reboot"));
        assertEquals(VariantValue.of(STEP_SHUTDOWN, false), type(guest, "shutdown"));
    }

    @Test
    public void survivesSnapshots() throws Exception {
        final WasmGuest guest = boot();
        guest.resume(key('a', 0));

        final GuestSnapshot snapshot = GuestSnapshot.fromBytes(guest.snapshot().toBytes());
        final WasmGuest restored = WasmGuest.restore(ModuleFormat.decode(shellResource(), 1 << 20), 4, bindings(), new ExecutionBudget(), snapshot);
        type(restored, "bc");
        assertEquals("> abc", row(3));
        assertEquals("unknown command: abc", row(4));
    }
}


