package li.cil.oc.server.machine.wasm.abi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Compact binary encoding of WIT values (in the Java representation
 * described in {@link WitType}), used to persist host-side state such as
 * pending calls. Not related to the canonical ABI's memory layout.
 */
public final class WitValueCodec {
    private static final int MAX_LENGTH = 64 * 1024 * 1024;

    private WitValueCodec() {
    }

    public static byte[] encode(final WitType type, final Object value) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            write(out, type, value);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static Object decode(final WitType type, final byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            final Object value = read(in, type);
            if (in.available() > 0) {
                throw new IOException("trailing data");
            }
            return value;
        }
    }

    public static void write(final DataOutputStream out, final WitType type, final Object value) throws IOException {
        switch (type) {
            case WitType.Primitive p -> {
                switch (p) {
                    case BOOL -> out.writeBoolean((Boolean) value);
                    case S8, U8, S16, U16, S32, U32, CHAR -> out.writeInt(((Number) value).intValue());
                    case S64, U64 -> out.writeLong(((Number) value).longValue());
                    case F32 -> out.writeFloat(((Number) value).floatValue());
                    case F64 -> out.writeDouble(((Number) value).doubleValue());
                    case STRING -> writeBytes(out, ((String) value).getBytes(StandardCharsets.UTF_8));
                }
            }
            case WitType.ListType l -> {
                if (l.element() == WitType.Primitive.U8 && value instanceof byte[] bytes) {
                    writeBytes(out, bytes);
                } else {
                    final List<?> list = (List<?>) value;
                    out.writeInt(list.size());
                    for (final Object element : list) {
                        write(out, l.element(), element);
                    }
                }
            }
            case WitType.RecordType r -> {
                final Object[] fields = (Object[]) value;
                for (int i = 0; i < r.fields().size(); i++) {
                    write(out, r.fields().get(i), fields[i]);
                }
            }
            case WitType.VariantType v -> {
                final VariantValue variant = (VariantValue) value;
                out.writeInt(variant.index());
                final Optional<WitType> c = v.cases().get(variant.index());
                if (c.isPresent()) {
                    write(out, c.get(), variant.payload());
                }
            }
        }
    }

    public static Object read(final DataInputStream in, final WitType type) throws IOException {
        return switch (type) {
            case WitType.Primitive p -> switch (p) {
                case BOOL -> in.readBoolean();
                case S8, U8, S16, U16, S32, U32, CHAR -> in.readInt();
                case S64, U64 -> in.readLong();
                case F32 -> in.readFloat();
                case F64 -> in.readDouble();
                case STRING -> new String(readBytes(in), StandardCharsets.UTF_8);
            };
            case WitType.ListType l -> {
                if (l.element() == WitType.Primitive.U8) {
                    yield readBytes(in);
                }
                final int size = readLength(in);
                final List<Object> list = new ArrayList<>(Math.min(size, 1024));
                for (int i = 0; i < size; i++) {
                    list.add(read(in, l.element()));
                }
                yield Collections.unmodifiableList(list);
            }
            case WitType.RecordType r -> {
                final Object[] fields = new Object[r.fields().size()];
                for (int i = 0; i < fields.length; i++) {
                    fields[i] = read(in, r.fields().get(i));
                }
                yield fields;
            }
            case WitType.VariantType v -> {
                final int index = in.readInt();
                if (index < 0 || index >= v.cases().size()) {
                    throw new IOException("invalid variant case " + index);
                }
                final Optional<WitType> c = v.cases().get(index);
                yield new VariantValue(index, c.isPresent() ? read(in, c.get()) : null);
            }
        };
    }

    private static void writeBytes(final DataOutputStream out, final byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(final DataInputStream in) throws IOException {
        final byte[] bytes = new byte[readLength(in)];
        in.readFully(bytes);
        return bytes;
    }

    private static int readLength(final DataInputStream in) throws IOException {
        final int length = in.readInt();
        if (length < 0 || length > MAX_LENGTH) {
            throw new IOException("invalid length " + length);
        }
        return length;
    }
}

