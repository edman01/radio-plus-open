package fi.radioplus.app;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Explicitly enabled, short-lived steering diagnostics kept only in RAM.
 * Callers must supply fixed technical labels, never typed text, station names,
 * arbitrary intent contents, identifiers, or exception messages. Key collection
 * must also exclude text-entry contexts: numeric key codes can reveal typing.
 */
final class SteeringDiagnosticTrace {
    private static final long RECORDING_MILLIS = 30_000L;
    private static final int MAX_ENTRIES = 40;
    private static final int MAX_LINE_LENGTH = 160;
    private static final int MAX_SOURCE_LENGTH = 32;
    private static final SteeringDiagnosticTrace INSTANCE = new SteeringDiagnosticTrace(
            () -> System.nanoTime() / 1_000_000L);

    private enum State {
        INACTIVE("inactive"), RECORDING("recording"), STOPPED("stopped"), EXPIRED("expired");

        final String label;

        State(String label) {
            this.label = label;
        }
    }

    private final LongSupplier clock;
    private final ArrayDeque<String> entries = new ArrayDeque<>(MAX_ENTRIES);
    private State state = State.INACTIVE;
    private long startedAt;
    private long elapsedMillis;
    private long keyCount;
    private long eventCount;

    static SteeringDiagnosticTrace get() {
        return INSTANCE;
    }

    /** The supplied monotonic clock returns milliseconds, not wall-clock time. */
    SteeringDiagnosticTrace(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized void begin() {
        reset();
        startedAt = clock.getAsLong();
        state = State.RECORDING;
    }

    synchronized void stop() {
        updateTimeout();
        if (state == State.RECORDING) state = State.STOPPED;
    }

    synchronized void clear() {
        reset();
    }

    synchronized boolean isRecording() {
        updateTimeout();
        return state == State.RECORDING;
    }

    /** Rounded up while recording; zero before, after, or at the deadline. */
    synchronized int remainingSeconds() {
        updateTimeout();
        return remainingSecondsInternal();
    }

    synchronized void key(String source, int code, int action, int meta, int repeat) {
        updateTimeout();
        if (state != State.RECORDING) return;
        keyCount = increment(keyCount);
        append("KEY source=" + clean(source, MAX_SOURCE_LENGTH)
                + " code=" + code + " action=" + action
                + " meta=" + meta + " repeat=" + repeat);
    }

    synchronized void key(String source, int code, int action, int meta, int repeat,
            int scan, int flags, int inputSource, long downTime, long eventTime) {
        updateTimeout();
        if (state != State.RECORDING) return;
        keyCount = increment(keyCount);
        append("KEY source=" + clean(source, MAX_SOURCE_LENGTH)
                + " code=" + code + " action=" + action
                + " meta=" + meta + " repeat=" + repeat
                + " scan=" + scan + " flags=" + flags + " input=" + inputSource
                + " down=" + downTime + " time=" + eventTime);
    }

    /** Details may contain fixed technical labels and numeric diagnostic values only. */
    synchronized void event(String source, String detail) {
        updateTimeout();
        if (state != State.RECORDING) return;
        eventCount = increment(eventCount);
        append("EVENT source=" + clean(source, MAX_SOURCE_LENGTH)
                + " detail=" + clean(detail, MAX_LINE_LENGTH));
    }

    /** Returns an immutable, bounded plain-text snapshot; never persists or exports it. */
    synchronized String report() {
        updateTimeout();
        long total = keyCount > Long.MAX_VALUE - eventCount
                ? Long.MAX_VALUE : keyCount + eventCount;
        StringBuilder report = new StringBuilder(256 + entries.size() * MAX_LINE_LENGTH);
        report.append("Radio+ steering diagnostic (RAM only)\n")
                .append("status=").append(state.label)
                .append(" elapsed_ms=").append(elapsedMillis)
                .append(" remaining_s=").append(remainingSecondsInternal()).append('\n')
                .append("keys=").append(keyCount)
                .append(" events=").append(eventCount)
                .append(" total=").append(total)
                .append(" retained=").append(entries.size())
                .append(" dropped=").append(total - entries.size());
        for (String entry : entries) report.append('\n').append(entry);
        return report.toString();
    }

    private void reset() {
        entries.clear();
        state = State.INACTIVE;
        startedAt = 0L;
        elapsedMillis = 0L;
        keyCount = 0L;
        eventCount = 0L;
    }

    private void updateTimeout() {
        if (state != State.RECORDING) return;
        // Subtraction avoids deadline-addition overflow. A faulty clock moving
        // backwards cannot reorder entries, extend remaining time, or revive an
        // expired session. The production clock is monotonic.
        long observedElapsed = clock.getAsLong() - startedAt;
        elapsedMillis = Math.min(RECORDING_MILLIS, Math.max(elapsedMillis, observedElapsed));
        if (elapsedMillis >= RECORDING_MILLIS) state = State.EXPIRED;
    }

    private int remainingSecondsInternal() {
        return state == State.RECORDING
                ? (int) ((RECORDING_MILLIS - elapsedMillis + 999L) / 1_000L) : 0;
    }

    private void append(String detail) {
        if (entries.size() == MAX_ENTRIES) entries.removeFirst();
        entries.addLast(clean("+" + elapsedMillis + "ms " + detail, MAX_LINE_LENGTH));
    }

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? value : value + 1L;
    }

    private static String clean(String value, int maximumLength) {
        if (value == null || value.isEmpty()) return "-";
        int length = Math.min(value.length(), maximumLength);
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            char character = value.charAt(index);
            // Keep one ASCII technical line; no control codes, bidi controls,
            // Unicode line separators, or giant caller strings are retained.
            result.append(character >= 32 && character <= 126 ? character : ' ');
        }
        return result.toString();
    }
}
