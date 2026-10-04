package fi.radioplus.app;

/**
 * Collapses the different media-key event sequences emitted by Junsun CAN
 * adapters into one short or long action per physical press.
 */
final class MediaKeyPressTracker {
    static final int UNKNOWN_KEY = -1;
    private static final long FALLBACK_RELEASE_WINDOW_MS = 5_000L;

    enum Action {
        NONE,
        SCHEDULE_SHORT,
        DISPATCH_SHORT,
        DISPATCH_LONG
    }

    static final class Decision {
        final Action action;
        final int keyCode;

        Decision(Action action, int keyCode) {
            this.action = action;
            this.keyCode = keyCode;
        }
    }

    private int pendingKey = UNKNOWN_KEY;
    private int fallbackDispatchedKey = UNKNOWN_KEY;
    private long fallbackDispatchedAt;
    private int longPressKey = UNKNOWN_KEY;

    synchronized Decision onDown(
            int keyCode,
            boolean longPress,
            int repeatCount,
            long eventTime
    ) {
        if (longPress || repeatCount > 0) {
            pendingKey = UNKNOWN_KEY;
            // A delayed repeat must not undo a mute already dispatched by the
            // DOWN-only adapter fallback (or advance another station).
            if (fallbackDispatchedKey == keyCode) {
                fallbackDispatchedKey = UNKNOWN_KEY;
                longPressKey = keyCode;
                return none();
            }
            fallbackDispatchedKey = UNKNOWN_KEY;
            if (longPressKey == keyCode) {
                return none();
            }
            longPressKey = keyCode;
            return new Decision(Action.DISPATCH_LONG, keyCode);
        }
        if (repeatCount != 0) {
            return none();
        }
        pendingKey = keyCode;
        fallbackDispatchedKey = UNKNOWN_KEY;
        fallbackDispatchedAt = eventTime;
        longPressKey = UNKNOWN_KEY;
        return new Decision(Action.SCHEDULE_SHORT, keyCode);
    }

    synchronized Decision onFallback(long eventTime) {
        if (pendingKey == UNKNOWN_KEY) {
            return none();
        }
        int keyCode = pendingKey;
        pendingKey = UNKNOWN_KEY;
        fallbackDispatchedKey = keyCode;
        fallbackDispatchedAt = eventTime;
        return new Decision(Action.DISPATCH_SHORT, keyCode);
    }

    synchronized Decision onUp(int keyCode, long eventTime) {
        if (longPressKey == keyCode) {
            reset();
            return none();
        }
        if (pendingKey == keyCode) {
            reset();
            return new Decision(Action.DISPATCH_SHORT, keyCode);
        }
        if (fallbackDispatchedKey == keyCode
                && eventTime >= fallbackDispatchedAt
                && eventTime - fallbackDispatchedAt <= FALLBACK_RELEASE_WINDOW_MS) {
            reset();
            return none();
        }
        // Some Junsun MCU/CAN adapters emit only ACTION_UP.
        reset();
        return new Decision(Action.DISPATCH_SHORT, keyCode);
    }

    synchronized void reset() {
        pendingKey = UNKNOWN_KEY;
        fallbackDispatchedKey = UNKNOWN_KEY;
        fallbackDispatchedAt = 0L;
        longPressKey = UNKNOWN_KEY;
    }

    private static Decision none() {
        return new Decision(Action.NONE, UNKNOWN_KEY);
    }
}
