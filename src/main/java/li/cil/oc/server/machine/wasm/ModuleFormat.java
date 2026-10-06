package li.cil.oc.server.machine.wasm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Recognizes the formats a boot module may be stored in: a plain WebAssembly
 * binary, or a gzip-compressed one (e.g. {@code gzip -9 shell.wasm}), which
 * helps modules fit on small EEPROMs.
 */
public final class ModuleFormat {
    private static final byte[] WASM_MAGIC = {0, 'a', 's', 'm'};

    private ModuleFormat() {
    }

    public static boolean isModule(final byte[] data) {
        return startsWith(data, WASM_MAGIC);
    }

    public static boolean isCompressed(final byte[] data) {
        return data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B;
    }

    /**
     * Returns the module stored in {@code data}, decompressing it if needed,
     * or {@code null} if the data is neither a module nor gzip-compressed
     * (e.g. a Lua BIOS).
     *
     * @param maxSize maximum size of the (decompressed) module.
     * @throws WasmGuestException if the data is compressed but not a valid
     *                            module, or exceeds the size limit.
     */
    public static byte[] decode(final byte[] data, final int maxSize) throws WasmGuestException {
        if (isModule(data)) {
            checkSize(data.length, maxSize);
            return data;
        }
        if (!isCompressed(data)) {
            return null;
        }
        final byte[] module;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                checkSize(out.size(), maxSize);
            }
            module = out.toByteArray();
        } catch (final IOException e) {
            throw new WasmGuestException("invalid compressed module: " + e.getMessage(), e);
        }
        if (!isModule(module)) {
            throw new WasmGuestException("compressed data is not a WebAssembly module");
        }
        return module;
    }

    private static void checkSize(final long size, final int maxSize) throws WasmGuestException {
        if (size > maxSize) {
            throw new WasmGuestException("module too large (more than " + maxSize + " bytes)");
        }
    }

    private static boolean startsWith(final byte[] data, final byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}

