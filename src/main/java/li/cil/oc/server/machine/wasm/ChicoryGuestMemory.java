package li.cil.oc.server.machine.wasm;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import li.cil.oc.server.machine.wasm.abi.AbiException;
import li.cil.oc.server.machine.wasm.abi.GuestMemory;

/**
 * {@link GuestMemory} backed by a Chicory instance's memory and its exported
 * realloc function.
 */
final class ChicoryGuestMemory implements GuestMemory {
    private final Instance instance;
    private final String reallocName;
    private ExportFunction realloc;

    ChicoryGuestMemory(final Instance instance, final String reallocName) {
        this.instance = instance;
        this.reallocName = reallocName;
    }

    private Memory memory() {
        return instance.memory();
    }

    @Override
    public long size() {
        return (long) memory().pages() * Memory.PAGE_SIZE;
    }

    @Override
    public byte readByte(final int address) {
        return memory().read(address);
    }

    @Override
    public void writeByte(final int address, final byte value) {
        memory().writeByte(address, value);
    }

    @Override
    public short readShort(final int address) {
        return memory().readShort(address);
    }

    @Override
    public void writeShort(final int address, final short value) {
        memory().writeShort(address, value);
    }

    @Override
    public int readInt(final int address) {
        return memory().readInt(address);
    }

    @Override
    public void writeInt(final int address, final int value) {
        memory().writeI32(address, value);
    }

    @Override
    public long readLong(final int address) {
        return memory().readLong(address);
    }

    @Override
    public void writeLong(final int address, final long value) {
        memory().writeLong(address, value);
    }

    @Override
    public byte[] readBytes(final int address, final int length) {
        return memory().readBytes(address, length);
    }

    @Override
    public void writeBytes(final int address, final byte[] data) {
        memory().write(address, data);
    }

    @Override
    public int realloc(final int oldPointer, final int oldSize, final int alignment, final int newSize) {
        if (realloc == null) {
            realloc = instance.export(reallocName);
            if (realloc == null) {
                throw new AbiException("module does not export " + reallocName);
            }
        }
        final long[] result = realloc.apply(oldPointer, oldSize, alignment, newSize);
        if (result == null || result.length != 1) {
            throw new AbiException(reallocName + " returned an unexpected number of results");
        }
        return (int) result[0];
    }
}

