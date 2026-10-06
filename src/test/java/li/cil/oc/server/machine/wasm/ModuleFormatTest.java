package li.cil.oc.server.machine.wasm;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ModuleFormatTest {
    private static final byte[] MODULE = {0, 'a', 's', 'm', 1, 0, 0, 0};

    private static byte[] gzip(final byte[] data) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(data);
        }
        return bytes.toByteArray();
    }

    @Test
    public void plainModulesPassThrough() throws Exception {
        assertArrayEquals(MODULE, ModuleFormat.decode(MODULE, 1024));
    }

    @Test
    public void compressedModulesAreDecompressed() throws Exception {
        assertArrayEquals(MODULE, ModuleFormat.decode(gzip(MODULE), 1024));
    }

    @Test
    public void otherDataIsNotAModule() throws Exception {
        assertNull(ModuleFormat.decode("local init = ...".getBytes(StandardCharsets.UTF_8), 1024));
        assertNull(ModuleFormat.decode(new byte[0], 1024));
    }

    @Test
    public void compressedNonModulesAreRejected() throws Exception {
        final byte[] lua = gzip("print('hi')".getBytes(StandardCharsets.UTF_8));
        final WasmGuestException e = assertThrows(WasmGuestException.class, () -> ModuleFormat.decode(lua, 1024));
        assertTrue(e.getMessage(), e.getMessage().contains("not a WebAssembly module"));
    }

    @Test
    public void sizeLimitAppliesToDecompressedData() throws Exception {
        final byte[] big = new byte[4096];
        System.arraycopy(MODULE, 0, big, 0, MODULE.length);
        final byte[] compressed = gzip(big);
        assertTrue(compressed.length < 1024);
        assertThrows(WasmGuestException.class, () -> ModuleFormat.decode(compressed, 1024));
        assertThrows(WasmGuestException.class, () -> ModuleFormat.decode(big, 1024));
    }
}

