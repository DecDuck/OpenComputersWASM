package li.cil.oc.server.machine.wasm.abi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Structural description of a WIT type, sufficient to apply the component
 * model's canonical ABI.
 * <p>
 * Java representations of values of each type:
 * <ul>
 * <li>{@code bool}: {@link Boolean}</li>
 * <li>{@code s8}, {@code u8}, {@code s16}, {@code u16}, {@code s32},
 * {@code u32}: {@link Integer} ({@code u32} uses the raw bit pattern)</li>
 * <li>{@code s64}, {@code u64}: {@link Long}</li>
 * <li>{@code f32}: {@link Float}, {@code f64}: {@link Double}</li>
 * <li>{@code char}: {@link Integer} code point</li>
 * <li>{@code string}: {@link String}</li>
 * <li>{@code list<u8>}: {@code byte[]}; other lists: {@link List}</li>
 * <li>records and tuples: {@code Object[]}</li>
 * <li>variants, options, results and enums: {@link VariantValue}</li>
 * </ul>
 */
public sealed interface WitType permits WitType.Primitive, WitType.ListType, WitType.RecordType, WitType.VariantType {
    enum Primitive implements WitType {
        BOOL, S8, U8, S16, U16, S32, U32, S64, U64, F32, F64, CHAR, STRING
    }

    record ListType(WitType element) implements WitType {
    }

    /**
     * Records and tuples share the same canonical ABI representation.
     */
    record RecordType(List<WitType> fields) implements WitType {
        public RecordType {
            fields = List.copyOf(fields);
        }
    }

    /**
     * Variants; an empty optional marks a case without payload. Options,
     * results and enums are expressed as variants.
     */
    record VariantType(List<Optional<WitType>> cases) implements WitType {
        public VariantType {
            if (cases.isEmpty()) {
                throw new IllegalArgumentException("variants need at least one case");
            }
            cases = List.copyOf(cases);
        }
    }

    // ----------------------------------------------------------------------- //

    static WitType list(final WitType element) {
        return new ListType(element);
    }

    static WitType record(final WitType... fields) {
        return new RecordType(Arrays.asList(fields));
    }

    static WitType tuple(final WitType... fields) {
        return record(fields);
    }

    /**
     * Creates a variant; pass {@code null} for cases without payload.
     */
    static WitType variant(final WitType... cases) {
        final List<Optional<WitType>> list = new ArrayList<>(cases.length);
        for (final WitType type : cases) {
            list.add(Optional.ofNullable(type));
        }
        return new VariantType(Collections.unmodifiableList(list));
    }

    static WitType option(final WitType some) {
        return variant(null, some);
    }

    /**
     * Creates a {@code result<ok, err>}; pass {@code null} for {@code _}.
     */
    static WitType result(final WitType ok, final WitType err) {
        return variant(ok, err);
    }
}

