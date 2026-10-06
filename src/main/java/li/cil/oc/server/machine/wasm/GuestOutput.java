package li.cil.oc.server.machine.wasm;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Sink for a guest's stdout or stderr.
 * <p>
 * Keeps the most recent output so it can be shown when the guest crashes
 * (e.g. Rust panic messages), and optionally forwards complete lines to a
 * consumer, at a limited rate.
 */
public final class GuestOutput extends OutputStream {
    private static final int TAIL_CAPACITY = 4096;
    private static final int MAX_LINE_LENGTH = 512;
    private static final int MAX_LINES_PER_WINDOW = 32;
    private static final long WINDOW_NANOS = 10_000_000_000L;
    private static final int SUMMARY_LENGTH = 256;

    private final Consumer<String> lineSink;
    private final byte[] tail = new byte[TAIL_CAPACITY];
    private int tailStart;
    private int tailSize;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private long windowStart = System.nanoTime();
    private int linesInWindow;
    private boolean suppressed;

    /**
     * @param lineSink receives complete lines; may be {@code null} to only
     *                 keep the tail.
     */
    public GuestOutput(final Consumer<String> lineSink) {
        this.lineSink = lineSink;
    }

    @Override
    public void write(final int b) {
        tail[(tailStart + tailSize) % TAIL_CAPACITY] = (byte) b;
        if (tailSize < TAIL_CAPACITY) {
            tailSize++;
        } else {
            tailStart = (tailStart + 1) % TAIL_CAPACITY;
        }

        if (lineSink == null) return;
        if (b == '\n') {
            emitLine();
        } else if (line.size() < MAX_LINE_LENGTH) {
            line.write(b);
        }
    }

    @Override
    public void write(final byte[] data, final int offset, final int length) {
        for (int i = 0; i < length; i++) {
            write(data[offset + i]);
        }
    }

    @Override
    public void flush() {
        if (lineSink != null && line.size() > 0) {
            emitLine();
        }
    }

    private void emitLine() {
        final String text = line.toString(StandardCharsets.UTF_8);
        line.reset();
        final long now = System.nanoTime();
        if (now - windowStart > WINDOW_NANOS) {
            windowStart = now;
            linesInWindow = 0;
            suppressed = false;
        }
        if (linesInWindow < MAX_LINES_PER_WINDOW) {
            linesInWindow++;
            lineSink.accept(text);
        } else if (!suppressed) {
            suppressed = true;
            lineSink.accept("(further output suppressed)");
        }
    }

    /**
     * The most recently written output, decoded as UTF-8.
     */
    public String tail() {
        final byte[] bytes = new byte[tailSize];
        for (int i = 0; i < tailSize; i++) {
            bytes[i] = tail[(tailStart + i) % TAIL_CAPACITY];
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * A short description of the last output, suitable as a crash message:
     * the last two non-empty lines, ignoring hints such as Rust's
     * {@code note: run with RUST_BACKTRACE=1}. Empty if there was no output.
     */
    public String summary() {
        final List<String> lines = new ArrayList<>();
        for (final String candidate : tail().split("\r?\n")) {
            final String trimmed = candidate.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("note:")) {
                lines.add(trimmed);
            }
        }
        final String summary = String.join(" ", lines.subList(Math.max(0, lines.size() - 2), lines.size()));
        return summary.length() > SUMMARY_LENGTH ? summary.substring(0, SUMMARY_LENGTH - 3) + "..." : summary;
    }
}

