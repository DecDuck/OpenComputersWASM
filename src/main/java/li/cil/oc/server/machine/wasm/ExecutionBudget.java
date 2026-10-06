package li.cil.oc.server.machine.wasm;

import com.dylibso.chicory.runtime.ExecutionListener;
import com.dylibso.chicory.runtime.MStack;
import com.dylibso.chicory.wasm.types.Instruction;

/**
 * Enforces a wall-clock limit on guest execution. The interpreter reports
 * every executed instruction; the clock is only sampled periodically to keep
 * the overhead low.
 * <p>
 * Not thread safe: a budget belongs to a single machine, whose architecture
 * is only ever run by one thread at a time.
 */
public final class ExecutionBudget implements ExecutionListener {
    private static final int CHECK_INTERVAL_MASK = 0x3FF;

    private boolean armed;
    private long deadline;
    private int counter;

    /**
     * Starts enforcing the limit.
     *
     * @param timeoutNanos how long the guest may run from now on.
     */
    public void arm(final long timeoutNanos) {
        deadline = System.nanoTime() + timeoutNanos;
        counter = 0;
        armed = true;
    }

    public void disarm() {
        armed = false;
    }

    @Override
    public void onExecution(final Instruction instruction, final MStack stack) {
        if (armed && (++counter & CHECK_INTERVAL_MASK) == 0 && System.nanoTime() - deadline > 0) {
            armed = false;
            throw new WasmTimeoutException();
        }
    }
}

