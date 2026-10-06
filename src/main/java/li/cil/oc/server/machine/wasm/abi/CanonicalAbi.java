package li.cil.oc.server.machine.wasm.abi;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of the component model's canonical ABI for the subset of
 * WIT used by OpenComputers: primitives, strings, lists, records/tuples and
 * variants (including options, results and enums). Strings are always UTF-8.
 * <p>
 * See <a href="https://github.com/WebAssembly/component-model/blob/main/design/mvp/CanonicalABI.md">CanonicalABI.md</a>.
 */
public final class CanonicalAbi {
    private CanonicalAbi() {
    }

    // ----------------------------------------------------------------------- //
    // Layout

    public static int alignment(final WitType type) {
        return switch (type) {
            case WitType.Primitive p -> switch (p) {
                case BOOL, S8, U8 -> 1;
                case S16, U16 -> 2;
                case S32, U32, F32, CHAR, STRING -> 4;
                case S64, U64, F64 -> 8;
            };
            case WitType.ListType ignored -> 4;
            case WitType.RecordType r -> {
                int alignment = 1;
                for (final WitType field : r.fields()) {
                    alignment = Math.max(alignment, alignment(field));
                }
                yield alignment;
            }
            case WitType.VariantType v -> Math.max(discriminantSize(v), maxCaseAlignment(v));
        };
    }

    public static int size(final WitType type) {
        return switch (type) {
            case WitType.Primitive p -> switch (p) {
                case BOOL, S8, U8 -> 1;
                case S16, U16 -> 2;
                case S32, U32, F32, CHAR -> 4;
                case S64, U64, F64, STRING -> 8;
            };
            case WitType.ListType ignored -> 8;
            case WitType.RecordType r -> {
                int size = 0;
                for (final WitType field : r.fields()) {
                    size = alignTo(size, alignment(field));
                    size += size(field);
                }
                yield alignTo(size, alignment(r));
            }
            case WitType.VariantType v -> {
                int size = alignTo(discriminantSize(v), maxCaseAlignment(v));
                int maxCaseSize = 0;
                for (final Optional<WitType> c : v.cases()) {
                    if (c.isPresent()) {
                        maxCaseSize = Math.max(maxCaseSize, size(c.get()));
                    }
                }
                yield alignTo(size + maxCaseSize, alignment(v));
            }
        };
    }

    static int discriminantSize(final WitType.VariantType variant) {
        final int count = variant.cases().size();
        if (count <= 1 << 8) return 1;
        if (count <= 1 << 16) return 2;
        return 4;
    }

    static int maxCaseAlignment(final WitType.VariantType variant) {
        int alignment = 1;
        for (final Optional<WitType> c : variant.cases()) {
            if (c.isPresent()) {
                alignment = Math.max(alignment, alignment(c.get()));
            }
        }
        return alignment;
    }

    static int payloadOffset(final WitType.VariantType variant) {
        return alignTo(discriminantSize(variant), maxCaseAlignment(variant));
    }

    static int alignTo(final int value, final int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    // ----------------------------------------------------------------------- //
    // Flattening

    public static List<CoreType> flatten(final WitType type) {
        final List<CoreType> result = new ArrayList<>();
        flattenInto(type, result);
        return Collections.unmodifiableList(result);
    }

    public static List<CoreType> flattenAll(final List<WitType> types) {
        final List<CoreType> result = new ArrayList<>();
        for (final WitType type : types) {
            flattenInto(type, result);
        }
        return Collections.unmodifiableList(result);
    }

    private static void flattenInto(final WitType type, final List<CoreType> out) {
        switch (type) {
            case WitType.Primitive p -> {
                switch (p) {
                    case BOOL, S8, U8, S16, U16, S32, U32, CHAR -> out.add(CoreType.I32);
                    case S64, U64 -> out.add(CoreType.I64);
                    case F32 -> out.add(CoreType.F32);
                    case F64 -> out.add(CoreType.F64);
                    case STRING -> {
                        out.add(CoreType.I32);
                        out.add(CoreType.I32);
                    }
                }
            }
            case WitType.ListType ignored -> {
                out.add(CoreType.I32);
                out.add(CoreType.I32);
            }
            case WitType.RecordType r -> {
                for (final WitType field : r.fields()) {
                    flattenInto(field, out);
                }
            }
            case WitType.VariantType v -> {
                out.add(CoreType.I32);
                out.addAll(flattenVariantPayload(v));
            }
        }
    }

    static List<CoreType> flattenVariantPayload(final WitType.VariantType variant) {
        final List<CoreType> flat = new ArrayList<>();
        for (final Optional<WitType> c : variant.cases()) {
            if (c.isEmpty()) continue;
            final List<CoreType> caseFlat = flatten(c.get());
            for (int i = 0; i < caseFlat.size(); i++) {
                if (i < flat.size()) {
                    flat.set(i, join(flat.get(i), caseFlat.get(i)));
                } else {
                    flat.add(caseFlat.get(i));
                }
            }
        }
        return flat;
    }

    static CoreType join(final CoreType a, final CoreType b) {
        if (a == b) return a;
        if ((a == CoreType.I32 && b == CoreType.F32) || (a == CoreType.F32 && b == CoreType.I32)) return CoreType.I32;
        return CoreType.I64;
    }

    // ----------------------------------------------------------------------- //
    // Loading from / storing to linear memory

    public static Object load(final GuestMemory memory, final int pointer, final WitType type) {
        checkRange(memory, pointer, size(type));
        return switch (type) {
            case WitType.Primitive p -> switch (p) {
                case BOOL -> memory.readByte(pointer) != 0;
                case S8 -> (int) memory.readByte(pointer);
                case U8 -> memory.readByte(pointer) & 0xFF;
                case S16 -> (int) memory.readShort(pointer);
                case U16 -> memory.readShort(pointer) & 0xFFFF;
                case S32, U32 -> memory.readInt(pointer);
                case S64, U64 -> memory.readLong(pointer);
                case F32 -> Float.intBitsToFloat(memory.readInt(pointer));
                case F64 -> Double.longBitsToDouble(memory.readLong(pointer));
                case CHAR -> checkChar(memory.readInt(pointer));
                case STRING -> loadString(memory, memory.readInt(pointer), memory.readInt(pointer + 4));
            };
            case WitType.ListType l -> loadList(memory, memory.readInt(pointer), memory.readInt(pointer + 4), l.element());
            case WitType.RecordType r -> {
                final Object[] fields = new Object[r.fields().size()];
                int offset = 0;
                for (int i = 0; i < fields.length; i++) {
                    final WitType field = r.fields().get(i);
                    offset = alignTo(offset, alignment(field));
                    fields[i] = load(memory, pointer + offset, field);
                    offset += size(field);
                }
                yield fields;
            }
            case WitType.VariantType v -> {
                final int index = switch (discriminantSize(v)) {
                    case 1 -> memory.readByte(pointer) & 0xFF;
                    case 2 -> memory.readShort(pointer) & 0xFFFF;
                    default -> memory.readInt(pointer);
                };
                final Optional<WitType> c = checkCase(v, index);
                yield new VariantValue(index, c.isPresent() ? load(memory, pointer + payloadOffset(v), c.get()) : null);
            }
        };
    }

    public static void store(final GuestMemory memory, final int pointer, final WitType type, final Object value) {
        checkRange(memory, pointer, size(type));
        switch (type) {
            case WitType.Primitive p -> {
                switch (p) {
                    case BOOL -> memory.writeByte(pointer, (byte) ((Boolean) value ? 1 : 0));
                    case S8, U8 -> memory.writeByte(pointer, (byte) ((Number) value).intValue());
                    case S16, U16 -> memory.writeShort(pointer, (short) ((Number) value).intValue());
                    case S32, U32 -> memory.writeInt(pointer, ((Number) value).intValue());
                    case S64, U64 -> memory.writeLong(pointer, ((Number) value).longValue());
                    case F32 -> memory.writeInt(pointer, Float.floatToRawIntBits(((Number) value).floatValue()));
                    case F64 -> memory.writeLong(pointer, Double.doubleToRawLongBits(((Number) value).doubleValue()));
                    case CHAR -> memory.writeInt(pointer, checkChar(((Number) value).intValue()));
                    case STRING -> {
                        final int[] stored = storeString(memory, (String) value);
                        memory.writeInt(pointer, stored[0]);
                        memory.writeInt(pointer + 4, stored[1]);
                    }
                }
            }
            case WitType.ListType l -> {
                final int[] stored = storeList(memory, l.element(), value);
                memory.writeInt(pointer, stored[0]);
                memory.writeInt(pointer + 4, stored[1]);
            }
            case WitType.RecordType r -> {
                final Object[] fields = (Object[]) value;
                checkArity(r, fields);
                int offset = 0;
                for (int i = 0; i < fields.length; i++) {
                    final WitType field = r.fields().get(i);
                    offset = alignTo(offset, alignment(field));
                    store(memory, pointer + offset, field, fields[i]);
                    offset += size(field);
                }
            }
            case WitType.VariantType v -> {
                final VariantValue variant = (VariantValue) value;
                final Optional<WitType> c = checkHostCase(v, variant.index());
                switch (discriminantSize(v)) {
                    case 1 -> memory.writeByte(pointer, (byte) variant.index());
                    case 2 -> memory.writeShort(pointer, (short) variant.index());
                    default -> memory.writeInt(pointer, variant.index());
                }
                if (c.isPresent()) {
                    store(memory, pointer + payloadOffset(v), c.get(), variant.payload());
                }
            }
        }
    }

    // ----------------------------------------------------------------------- //
    // Lifting from / lowering to flat core values

    public static Object liftFlat(final GuestMemory memory, final FlatReader reader, final WitType type) {
        return switch (type) {
            case WitType.Primitive p -> switch (p) {
                case BOOL -> (int) reader.next() != 0;
                case S8 -> (int) (byte) reader.next();
                case U8 -> (int) reader.next() & 0xFF;
                case S16 -> (int) (short) reader.next();
                case U16 -> (int) reader.next() & 0xFFFF;
                case S32, U32 -> (int) reader.next();
                case S64, U64 -> reader.next();
                case F32 -> Float.intBitsToFloat((int) reader.next());
                case F64 -> Double.longBitsToDouble(reader.next());
                case CHAR -> checkChar((int) reader.next());
                case STRING -> {
                    final int pointer = (int) reader.next();
                    final int length = (int) reader.next();
                    yield loadString(memory, pointer, length);
                }
            };
            case WitType.ListType l -> {
                final int pointer = (int) reader.next();
                final int length = (int) reader.next();
                yield loadList(memory, pointer, length, l.element());
            }
            case WitType.RecordType r -> {
                final Object[] fields = new Object[r.fields().size()];
                for (int i = 0; i < fields.length; i++) {
                    fields[i] = liftFlat(memory, reader, r.fields().get(i));
                }
                yield fields;
            }
            case WitType.VariantType v -> {
                final int index = (int) reader.next();
                final List<CoreType> joined = flattenVariantPayload(v);
                final long[] slots = new long[joined.size()];
                for (int i = 0; i < slots.length; i++) {
                    slots[i] = reader.next();
                }
                final Optional<WitType> c = checkCase(v, index);
                if (c.isEmpty()) {
                    yield new VariantValue(index, null);
                }
                final List<CoreType> wanted = flatten(c.get());
                final long[] coerced = new long[wanted.size()];
                for (int i = 0; i < coerced.length; i++) {
                    coerced[i] = coerceLift(joined.get(i), wanted.get(i), slots[i]);
                }
                yield new VariantValue(index, liftFlat(memory, new FlatReader(coerced), c.get()));
            }
        };
    }

    public static void lowerFlat(final GuestMemory memory, final FlatWriter writer, final WitType type, final Object value) {
        switch (type) {
            case WitType.Primitive p -> {
                switch (p) {
                    case BOOL -> writer.add((Boolean) value ? 1 : 0);
                    case S8 -> writer.add((byte) ((Number) value).intValue());
                    case U8 -> writer.add(((Number) value).intValue() & 0xFF);
                    case S16 -> writer.add((short) ((Number) value).intValue());
                    case U16 -> writer.add(((Number) value).intValue() & 0xFFFF);
                    case S32, U32 -> writer.add(((Number) value).intValue());
                    case S64, U64 -> writer.add(((Number) value).longValue());
                    case F32 -> writer.add(Float.floatToRawIntBits(((Number) value).floatValue()));
                    case F64 -> writer.add(Double.doubleToRawLongBits(((Number) value).doubleValue()));
                    case CHAR -> writer.add(checkChar(((Number) value).intValue()));
                    case STRING -> {
                        final int[] stored = storeString(memory, (String) value);
                        writer.add(stored[0]);
                        writer.add(stored[1]);
                    }
                }
            }
            case WitType.ListType l -> {
                final int[] stored = storeList(memory, l.element(), value);
                writer.add(stored[0]);
                writer.add(stored[1]);
            }
            case WitType.RecordType r -> {
                final Object[] fields = (Object[]) value;
                checkArity(r, fields);
                for (int i = 0; i < fields.length; i++) {
                    lowerFlat(memory, writer, r.fields().get(i), fields[i]);
                }
            }
            case WitType.VariantType v -> {
                final VariantValue variant = (VariantValue) value;
                final Optional<WitType> c = checkHostCase(v, variant.index());
                final List<CoreType> joined = flattenVariantPayload(v);
                writer.add(variant.index());
                int written = 0;
                if (c.isPresent()) {
                    final FlatWriter payload = new FlatWriter();
                    lowerFlat(memory, payload, c.get(), variant.payload());
                    final List<CoreType> have = flatten(c.get());
                    final long[] values = payload.toArray();
                    for (int i = 0; i < values.length; i++) {
                        writer.add(coerceLower(have.get(i), joined.get(i), values[i]));
                    }
                    written = values.length;
                }
                for (int i = written; i < joined.size(); i++) {
                    writer.add(0L);
                }
            }
        }
    }

    private static long coerceLift(final CoreType have, final CoreType want, final long value) {
        if (have == want) return value;
        if (have == CoreType.I32 && want == CoreType.F32) return value;
        if (have == CoreType.I64 && (want == CoreType.I32 || want == CoreType.F32)) return (int) value;
        if (have == CoreType.I64 && want == CoreType.F64) return value;
        throw new IllegalStateException("cannot coerce " + have + " to " + want);
    }

    private static long coerceLower(final CoreType have, final CoreType want, final long value) {
        if (have == want) return value;
        if (have == CoreType.F32 && want == CoreType.I32) return value;
        if ((have == CoreType.I32 || have == CoreType.F32) && want == CoreType.I64) return value & 0xFFFFFFFFL;
        if (have == CoreType.F64 && want == CoreType.I64) return value;
        throw new IllegalStateException("cannot coerce " + have + " to " + want);
    }

    // ----------------------------------------------------------------------- //
    // Function calls

    /**
     * Lifts the arguments of a host function imported by the guest.
     */
    public static Object[] liftImportArguments(final GuestMemory memory, final WitFunction function, final long[] arguments) {
        final WitType tuple = new WitType.RecordType(function.params());
        if (function.paramsInMemory()) {
            final int pointer = (int) arguments[0];
            checkAlignment(pointer, alignment(tuple));
            return (Object[]) load(memory, pointer, tuple);
        }
        return (Object[]) liftFlat(memory, new FlatReader(arguments), tuple);
    }

    /**
     * Lowers the result of a host function imported by the guest, returning
     * the core results.
     */
    public static long[] lowerImportResult(final GuestMemory memory, final WitFunction function, final long[] arguments, final Object result) {
        if (function.result() == null) {
            return new long[0];
        }
        if (function.resultsInMemory()) {
            final int pointer = (int) arguments[arguments.length - 1];
            checkAlignment(pointer, alignment(function.result()));
            store(memory, pointer, function.result(), result);
            return new long[0];
        }
        final FlatWriter writer = new FlatWriter();
        lowerFlat(memory, writer, function.result(), result);
        return writer.toArray();
    }

    /**
     * Lowers the arguments for a call to a function exported by the guest.
     */
    public static long[] lowerExportArguments(final GuestMemory memory, final WitFunction function, final Object[] arguments) {
        final WitType tuple = new WitType.RecordType(function.params());
        if (function.paramsInMemory()) {
            final int pointer = memory.realloc(0, 0, alignment(tuple), size(tuple));
            checkAlignment(pointer, alignment(tuple));
            store(memory, pointer, tuple, arguments);
            return new long[]{pointer};
        }
        final FlatWriter writer = new FlatWriter();
        lowerFlat(memory, writer, tuple, arguments);
        return writer.toArray();
    }

    /**
     * Lifts the result of a call to a function exported by the guest.
     */
    public static Object liftExportResult(final GuestMemory memory, final WitFunction function, final long[] results) {
        if (function.result() == null) {
            return null;
        }
        if (function.resultsInMemory()) {
            final int pointer = (int) results[0];
            checkAlignment(pointer, alignment(function.result()));
            return load(memory, pointer, function.result());
        }
        return liftFlat(memory, new FlatReader(results), function.result());
    }

    // ----------------------------------------------------------------------- //
    // Strings and lists

    private static String loadString(final GuestMemory memory, final int pointer, final int length) {
        checkRange(memory, pointer, Integer.toUnsignedLong(length));
        final byte[] bytes = memory.readBytes(pointer, length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (final CharacterCodingException e) {
            throw new AbiException("invalid UTF-8 in string");
        }
    }

    private static int[] storeString(final GuestMemory memory, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final int pointer = memory.realloc(0, 0, 1, bytes.length);
        checkRange(memory, pointer, bytes.length);
        memory.writeBytes(pointer, bytes);
        return new int[]{pointer, bytes.length};
    }

    private static Object loadList(final GuestMemory memory, final int pointer, final int length, final WitType element) {
        final int elementSize = size(element);
        checkAlignment(pointer, alignment(element));
        checkRange(memory, pointer, Integer.toUnsignedLong(length) * elementSize);
        if (element == WitType.Primitive.U8) {
            return memory.readBytes(pointer, length);
        }
        final List<Object> list = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            list.add(load(memory, pointer + i * elementSize, element));
        }
        return Collections.unmodifiableList(list);
    }

    private static int[] storeList(final GuestMemory memory, final WitType element, final Object value) {
        final int elementSize = size(element);
        final int elementAlignment = alignment(element);
        if (element == WitType.Primitive.U8 && value instanceof byte[] bytes) {
            final int pointer = memory.realloc(0, 0, elementAlignment, bytes.length);
            checkRange(memory, pointer, bytes.length);
            memory.writeBytes(pointer, bytes);
            return new int[]{pointer, bytes.length};
        }
        final List<?> list = (List<?>) value;
        final long byteLength = (long) list.size() * elementSize;
        if (byteLength > Integer.MAX_VALUE) {
            throw new AbiException("list too large");
        }
        final int pointer = memory.realloc(0, 0, elementAlignment, (int) byteLength);
        checkAlignment(pointer, elementAlignment);
        checkRange(memory, pointer, byteLength);
        for (int i = 0; i < list.size(); i++) {
            store(memory, pointer + i * elementSize, element, list.get(i));
        }
        return new int[]{pointer, list.size()};
    }

    // ----------------------------------------------------------------------- //
    // Validation

    private static void checkRange(final GuestMemory memory, final int pointer, final long length) {
        if (Integer.toUnsignedLong(pointer) + length > memory.size()) {
            throw new AbiException("out of bounds memory access");
        }
    }

    private static void checkAlignment(final int pointer, final int alignment) {
        if (Integer.remainderUnsigned(pointer, alignment) != 0) {
            throw new AbiException("misaligned pointer");
        }
    }

    private static int checkChar(final int codePoint) {
        if (codePoint < 0 || codePoint > 0x10FFFF || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new AbiException("invalid char");
        }
        return codePoint;
    }

    private static Optional<WitType> checkCase(final WitType.VariantType variant, final int index) {
        if (index < 0 || index >= variant.cases().size()) {
            throw new AbiException("invalid variant case");
        }
        return variant.cases().get(index);
    }

    private static Optional<WitType> checkHostCase(final WitType.VariantType variant, final int index) {
        if (index < 0 || index >= variant.cases().size()) {
            throw new IllegalArgumentException("invalid variant case " + index);
        }
        return variant.cases().get(index);
    }

    private static void checkArity(final WitType.RecordType record, final Object[] fields) {
        if (fields.length != record.fields().size()) {
            throw new IllegalArgumentException("expected " + record.fields().size() + " fields, got " + fields.length);
        }
    }

    // ----------------------------------------------------------------------- //

    public static final class FlatReader {
        private final long[] values;
        private int position;

        public FlatReader(final long[] values) {
            this.values = values;
        }

        public long next() {
            if (position >= values.length) {
                throw new AbiException("not enough core values");
            }
            return values[position++];
        }
    }

    public static final class FlatWriter {
        private long[] values = new long[8];
        private int size;

        public void add(final long value) {
            if (size == values.length) {
                values = java.util.Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        public long[] toArray() {
            return java.util.Arrays.copyOf(values, size);
        }
    }
}

