package li.cil.oc.server.machine.wasm.abi;

/**
 * Raised when a guest violates the canonical ABI, for example by passing an
 * out-of-bounds pointer, invalid UTF-8 or an unknown variant case. This is
 * equivalent to a trap and should stop the machine.
 */
public final class AbiException extends RuntimeException {
    public AbiException(final String message) {
        super(message);
    }
}

