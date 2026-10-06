package li.cil.oc.server.machine.wasm;

/**
 * Raised when a guest module cannot be loaded or linked. The message is
 * user-facing: it is shown as the machine's crash reason.
 */
public final class WasmGuestException extends Exception {
    public WasmGuestException(final String message) {
        super(message);
    }

    public WasmGuestException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

