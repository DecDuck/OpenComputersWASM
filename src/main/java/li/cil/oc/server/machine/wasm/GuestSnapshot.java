package li.cil.oc.server.machine.wasm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Complete execution state of a guest instance between two host calls into
 * it: linear memory, mutable globals and table contents, plus the state of a
 * call suspended through Asyncify, if any.
 * <p>
 * Restoring this into a fresh instance of the same module continues exactly
 * where the snapshot was taken. The host never interrupts a guest in the
 * middle of an export call, except through Asyncify, whose unwound call stack
 * lives in linear memory, so nothing else is needed.
 *
 * @param pages              number of memory pages.
 * @param memory             memory contents, {@code pages * 64 KiB} bytes.
 * @param globalsLow         low 64 bits of every global, in index order.
 * @param globalsHigh        high 64 bits of every global (only used by v128).
 * @param tables             function references of every table entry, with
 *                           {@code -1} for null references.
 * @param asyncifyBuffer     address of the Asyncify data buffer, or 0.
 * @param asyncifyBufferSize size of the Asyncify data buffer, or 0.
 * @param suspendedOn        import the guest is suspended in, or {@code null}.
 * @param suspendedArgs      core arguments of the export call that suspended,
 *                           needed to rewind; {@code null} if not suspended.
 */
public record GuestSnapshot(
        int pages,
        byte[] memory,
        long[] globalsLow,
        long[] globalsHigh,
        int[][] tables,
        int asyncifyBuffer,
        int asyncifyBufferSize,
        String suspendedOn,
        long[] suspendedArgs) {
    private static final int MAGIC = 0x4F43574D; // "OCWM"
    private static final int VERSION = 1;

    public boolean isSuspended() {
        return suspendedOn != null;
    }

    /**
     * Serializes and compresses the snapshot.
     */
    public byte[] toBytes() {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, new Deflater(Deflater.BEST_SPEED)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(pages);
            out.writeInt(memory.length);
            out.write(memory);
            out.writeInt(globalsLow.length);
            for (int i = 0; i < globalsLow.length; i++) {
                out.writeLong(globalsLow[i]);
                out.writeLong(globalsHigh[i]);
            }
            out.writeInt(tables.length);
            for (final int[] table : tables) {
                out.writeInt(table.length);
                for (final int ref : table) {
                    out.writeInt(ref);
                }
            }
            out.writeInt(asyncifyBuffer);
            out.writeInt(asyncifyBufferSize);
            out.writeBoolean(suspendedOn != null);
            if (suspendedOn != null) {
                out.writeUTF(suspendedOn);
                out.writeInt(suspendedArgs.length);
                for (final long arg : suspendedArgs) {
                    out.writeLong(arg);
                }
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * Restores a snapshot written by {@link #toBytes()}.
     */
    public static GuestSnapshot fromBytes(final byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
            if (in.readInt() != MAGIC) throw new IOException("not a WebAssembly machine snapshot");
            if (in.readInt() != VERSION) throw new IOException("unsupported snapshot version");
            final int pages = checkCount(in.readInt());
            final int memoryLength = in.readInt();
            if ((long) pages * WasmGuest.PAGE_SIZE != memoryLength) throw new IOException("invalid memory size");
            final byte[] memory = new byte[memoryLength];
            in.readFully(memory);
            final int globalCount = checkCount(in.readInt());
            final long[] globalsLow = new long[globalCount];
            final long[] globalsHigh = new long[globalCount];
            for (int i = 0; i < globalCount; i++) {
                globalsLow[i] = in.readLong();
                globalsHigh[i] = in.readLong();
            }
            final int[][] tables = new int[checkCount(in.readInt())][];
            for (int i = 0; i < tables.length; i++) {
                tables[i] = new int[checkCount(in.readInt())];
                for (int j = 0; j < tables[i].length; j++) {
                    tables[i][j] = in.readInt();
                }
            }
            final int asyncifyBuffer = in.readInt();
            final int asyncifyBufferSize = in.readInt();
            String suspendedOn = null;
            long[] suspendedArgs = null;
            if (in.readBoolean()) {
                suspendedOn = in.readUTF();
                suspendedArgs = new long[checkCount(in.readInt())];
                for (int i = 0; i < suspendedArgs.length; i++) {
                    suspendedArgs[i] = in.readLong();
                }
            }
            return new GuestSnapshot(pages, memory, globalsLow, globalsHigh, tables, asyncifyBuffer, asyncifyBufferSize, suspendedOn, suspendedArgs);
        }
    }

    private static int checkCount(final int count) throws IOException {
        if (count < 0 || count > 1 << 24) throw new IOException("invalid count " + count);
        return count;
    }
}

