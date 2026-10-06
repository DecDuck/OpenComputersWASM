package li.cil.oc.server.machine.wasm;

/**
 * Thrown from inside the interpreter when a guest ran for too long without
 * returning control to the host.
 */
public final class WasmTimeoutException extends RuntimeException {
    public WasmTimeoutException() {
        super("too long without yielding", null, false, false);
    }
}

