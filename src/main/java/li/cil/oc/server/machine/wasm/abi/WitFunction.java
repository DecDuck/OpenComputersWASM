package li.cil.oc.server.machine.wasm.abi;

import java.util.List;

/**
 * Signature of a WIT function.
 *
 * @param name   the function's WIT name, e.g. {@code tmp-address}.
 * @param params the parameter types.
 * @param result the result type, or {@code null} if it returns nothing.
 */
public record WitFunction(String name, List<WitType> params, WitType result) {
    /**
     * Maximum number of flat parameters before they are passed in memory.
     */
    public static final int MAX_FLAT_PARAMS = 16;

    /**
     * Maximum number of flat results before they are passed in memory.
     */
    public static final int MAX_FLAT_RESULTS = 1;

    public WitFunction {
        params = List.copyOf(params);
    }

    public static WitFunction of(final String name, final WitType result, final WitType... params) {
        return new WitFunction(name, List.of(params), result);
    }

    public List<CoreType> flatParams() {
        return CanonicalAbi.flattenAll(params);
    }

    public List<CoreType> flatResults() {
        return result == null ? List.of() : CanonicalAbi.flatten(result);
    }

    public boolean paramsInMemory() {
        return flatParams().size() > MAX_FLAT_PARAMS;
    }

    public boolean resultsInMemory() {
        return flatResults().size() > MAX_FLAT_RESULTS;
    }

    /**
     * Core parameter types of this function when imported by the guest
     * (i.e. implemented by the host).
     */
    public List<CoreType> importCoreParams() {
        final List<CoreType> core = new java.util.ArrayList<>(paramsInMemory() ? List.of(CoreType.I32) : flatParams());
        if (resultsInMemory()) {
            core.add(CoreType.I32);
        }
        return List.copyOf(core);
    }

    /**
     * Core result types of this function when imported by the guest.
     */
    public List<CoreType> importCoreResults() {
        return resultsInMemory() ? List.of() : flatResults();
    }

    /**
     * Core parameter types of this function when exported by the guest.
     */
    public List<CoreType> exportCoreParams() {
        return paramsInMemory() ? List.of(CoreType.I32) : flatParams();
    }

    /**
     * Core result types of this function when exported by the guest.
     */
    public List<CoreType> exportCoreResults() {
        return resultsInMemory() ? List.of(CoreType.I32) : flatResults();
    }
}

