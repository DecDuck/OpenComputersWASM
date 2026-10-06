package li.cil.oc.server.machine.wasm;

import com.dylibso.chicory.log.Logger;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportFunction;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sandboxed WASI preview 1 ({@code wasi_snapshot_preview1}) for guests built
 * for {@code wasm32-wasip1}, so that standard library features work:
 * <ul>
 * <li>stdout and stderr go to {@link GuestOutput}s; stdin is always at EOF;</li>
 * <li>clocks and random numbers work normally;</li>
 * <li>there are no arguments, environment variables or file systems;</li>
 * <li>{@code poll_oneoff} never blocks: all subscriptions are reported as
 * ready immediately, so sleeping returns right away. Guests must yield to
 * the host by returning {@code step::sleep} instead;</li>
 * <li>{@code proc_exit} throws {@link WasiExitException}, which stops the
 * machine.</li>
 * </ul>
 */
public final class WasiSupport implements Closeable {
    public static final String MODULE = "wasi_snapshot_preview1";

    private static final int ERRNO_SUCCESS = 0;
    private static final int ERRNO_BADF = 8;
    private static final int ERRNO_INVAL = 28;

    private static final int EVENT_TYPE_CLOCK = 0;
    private static final int EVENT_TYPE_FD_READ = 1;
    private static final int EVENT_TYPE_FD_WRITE = 2;
    private static final int EVENT_RW_FLAG_HANGUP = 1;
    private static final int SUBSCRIPTION_SIZE = 48;
    private static final int EVENT_SIZE = 32;
    private static final long WRITE_READY_BYTES = 1 << 16;

    private final WasiPreview1 wasi;
    private final List<ImportFunction> functions;

    public WasiSupport(final GuestOutput stdout, final GuestOutput stderr) {
        final WasiOptions options = WasiOptions.builder()
                .withStdin(new ByteArrayInputStream(new byte[0]), false)
                .withStdout(stdout, false)
                .withStderr(stderr, false)
                .withRandom(new SecureRandom())
                .withThrowOnExit0(true)
                .build();
        wasi = WasiPreview1.builder()
                .withOptions(options)
                .withLogger(SilentLogger.INSTANCE)
                .build();

        final List<ImportFunction> list = new ArrayList<>();
        for (final HostFunction function : wasi.toHostFunctions()) {
            if (!function.name().equals("poll_oneoff")) {
                list.add(function);
            }
        }
        list.add(new HostFunction(MODULE, "poll_oneoff",
                FunctionType.of(List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I32)),
                (instance, arguments) -> new long[]{pollOneoff(instance.memory(),
                        (int) arguments[0], (int) arguments[1], (int) arguments[2], (int) arguments[3])}));
        functions = List.copyOf(list);
    }

    /**
     * Host functions implementing {@code wasi_snapshot_preview1}.
     */
    public List<ImportFunction> functions() {
        return functions;
    }

    @Override
    public void close() {
        wasi.close();
    }

    /**
     * Exit code of a {@code proc_exit} call that unwound through the given
     * throwable, if any.
     */
    public static Optional<Integer> exitCode(final Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof WasiExitException exit) {
                return Optional.of(exit.exitCode());
            }
        }
        return Optional.empty();
    }

    /**
     * Non-blocking {@code poll_oneoff}: every subscription is ready at once.
     * Clock subscriptions fire immediately, stdin reports end of file, and
     * stdout/stderr are always writable.
     */
    static int pollOneoff(final Memory memory, final int in, final int out, final int subscriptions, final int eventsPointer) {
        if (subscriptions <= 0) {
            return ERRNO_INVAL;
        }
        for (int i = 0; i < subscriptions; i++) {
            final int subscription = in + i * SUBSCRIPTION_SIZE;
            final int event = out + i * EVENT_SIZE;
            final long userdata = memory.readLong(subscription);
            final int type = memory.read(subscription + 8) & 0xFF;

            int error = ERRNO_SUCCESS;
            long bytes = 0;
            int flags = 0;
            switch (type) {
                case EVENT_TYPE_CLOCK -> {
                }
                case EVENT_TYPE_FD_READ -> {
                    if (memory.readInt(subscription + 16) == 0) {
                        flags = EVENT_RW_FLAG_HANGUP;
                    } else {
                        error = ERRNO_BADF;
                    }
                }
                case EVENT_TYPE_FD_WRITE -> {
                    final int fd = memory.readInt(subscription + 16);
                    if (fd == 1 || fd == 2) {
                        bytes = WRITE_READY_BYTES;
                    } else {
                        error = ERRNO_BADF;
                    }
                }
                default -> {
                    return ERRNO_INVAL;
                }
            }

            memory.writeLong(event, userdata);
            memory.writeShort(event + 8, (short) error);
            memory.writeByte(event + 10, (byte) type);
            memory.writeLong(event + 16, bytes);
            memory.writeShort(event + 24, (short) flags);
        }
        memory.writeI32(eventsPointer, subscriptions);
        return ERRNO_SUCCESS;
    }

    private enum SilentLogger implements Logger {
        INSTANCE;

        @Override
        public void log(final Level level, final String message, final Throwable throwable) {
        }

        @Override
        public boolean isLoggable(final Level level) {
            return false;
        }
    }
}

