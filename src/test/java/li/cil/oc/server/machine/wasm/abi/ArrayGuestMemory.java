package li.cil.oc.server.machine.wasm.abi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Heap-backed {@link GuestMemory} with a bump allocator, for tests.
 */
final class ArrayGuestMemory implements GuestMemory {
    private final ByteBuffer buffer;
    private int top;

    ArrayGuestMemory(final int size) {
        buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        top = 16; // Keep 0 unused, like a real allocator would.
    }

    @Override
    public long size() {
        return buffer.capacity();
    }

    @Override
    public byte readByte(final int address) {
        return buffer.get(address);
    }

    @Override
    public void writeByte(final int address, final byte value) {
        buffer.put(address, value);
    }

    @Override
    public short readShort(final int address) {
        return buffer.getShort(address);
    }

    @Override
    public void writeShort(final int address, final short value) {
        buffer.putShort(address, value);
    }

    @Override
    public int readInt(final int address) {
        return buffer.getInt(address);
    }

    @Override
    public void writeInt(final int address, final int value) {
        buffer.putInt(address, value);
    }

    @Override
    public long readLong(final int address) {
        return buffer.getLong(address);
    }

    @Override
    public void writeLong(final int address, final long value) {
        buffer.putLong(address, value);
    }

    @Override
    public byte[] readBytes(final int address, final int length) {
        final byte[] result = new byte[length];
        buffer.get(address, result);
        return result;
    }

    @Override
    public void writeBytes(final int address, final byte[] data) {
        buffer.put(address, data);
    }

    @Override
    public int realloc(final int oldPointer, final int oldSize, final int alignment, final int newSize) {
        top = (top + alignment - 1) / alignment * alignment;
        final int pointer = top;
        top += newSize;
        if (top > buffer.capacity()) {
            throw new IllegalStateException("out of memory");
        }
        return pointer;
    }
}

