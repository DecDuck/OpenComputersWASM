package li.cil.oc.server.machine.wasm.abi;

/**
 * Little-endian view of a guest's linear memory, plus access to the guest's
 * allocator ({@code cabi_realloc}).
 * <p>
 * Implementations must throw on out-of-bounds accesses.
 */
public interface GuestMemory {
    /**
     * Current size of the memory, in bytes.
     */
    long size();

    byte readByte(int address);

    void writeByte(int address, byte value);

    short readShort(int address);

    void writeShort(int address, short value);

    int readInt(int address);

    void writeInt(int address, int value);

    long readLong(int address);

    void writeLong(int address, long value);

    byte[] readBytes(int address, int length);

    void writeBytes(int address, byte[] data);

    /**
     * Calls the guest's {@code cabi_realloc(old_ptr, old_size, align, new_size)}.
     */
    int realloc(int oldPointer, int oldSize, int alignment, int newSize);
}

