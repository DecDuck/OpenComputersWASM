package li.cil.oc.server.machine.wasm.abi;

/**
 * Value of a variant, option, result or enum: the index of the active case
 * and its payload ({@code null} for cases without payload).
 */
public record VariantValue(int index, Object payload) {
    public static VariantValue of(final int index) {
        return new VariantValue(index, null);
    }

    public static VariantValue of(final int index, final Object payload) {
        return new VariantValue(index, payload);
    }

    public static VariantValue none() {
        return new VariantValue(0, null);
    }

    public static VariantValue some(final Object value) {
        return new VariantValue(1, value);
    }

    public static VariantValue ok(final Object value) {
        return new VariantValue(0, value);
    }

    public static VariantValue err(final Object value) {
        return new VariantValue(1, value);
    }

    /**
     * Converts an option's representation back to a nullable value.
     */
    public static Object unwrapOption(final Object option) {
        final VariantValue value = (VariantValue) option;
        return value.index() == 0 ? null : value.payload();
    }
}

