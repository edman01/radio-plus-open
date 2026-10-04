package fi.radioplus.app;

import java.util.ArrayList;

/**
 * Collapses the different media-key event sequences emitted by Junsun CAN
 * adapters into one short or long action per physical press.
 */
final class MediaKeyPressTracker {
    static final int UNKNOWN_KEY = -1;
    private static final long UNKNOWN_DOWN_TIME = -1L;
    private static final int MAX_TRACKED_PRESSES = 64;
    private static final int MAX_REWRITTEN_IDENTITIES = 64;
    private static final long REWRITTEN_IDENTITY_WINDOW_MS = 2000L;

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

    private enum State { PENDING, DISPATCHED, FINISHED }

    private static final class Press {
        final int keyCode;
        final long downTime;
        final long startedAt;
        State state = State.PENDING;
        private long[] rewrittenIdentities;
        private int rewrittenIdentityCursor;

        Press(int keyCode, long downTime, long startedAt) {
            this.keyCode = keyCode;
            this.downTime = downTime;
            this.startedAt = startedAt;
        }

        boolean hasIdentity(long identity) {
            if (downTime == identity) return true;
            if (rewrittenIdentities != null) {
                for (long rewritten : rewrittenIdentities) {
                    if (rewritten == identity) return true;
                }
            }
            return false;
        }

        void rememberIdentity(long identity) {
            if (identity <= 0L || hasIdentity(identity)) return;
            if (rewrittenIdentities == null) {
                rewrittenIdentities = new long[MAX_REWRITTEN_IDENTITIES];
            }
            rewrittenIdentities[rewrittenIdentityCursor] = identity;
            rewrittenIdentityCursor = (rewrittenIdentityCursor + 1)
                    % rewrittenIdentities.length;
        }
    }

    // Keep completed identities too: a copied UP event can have a different
    // eventTime while still belonging to the very same physical press.
    private final ArrayList<Press> presses = new ArrayList<>();

    synchronized Decision onDown(
            int keyCode,
            boolean longPress,
            int repeatCount,
            long eventTime
    ) {
        return onDown(keyCode, longPress, repeatCount, eventTime, UNKNOWN_DOWN_TIME);
    }

    synchronized Decision onDown(
            int keyCode,
            boolean longPress,
            int repeatCount,
            long eventTime,
            long downTime
    ) {
        if (repeatCount < 0) {
            return none();
        }
        boolean held = longPress || repeatCount > 0;
        Press press = downTime > 0 || held
                ? findPress(keyCode, downTime, true) : null;
        // Never infer identity for a fresh DOWN: two rapid physical presses
        // must remain distinct even when they use the same command key.
        if (press == null && held && downTime > 0L) {
            press = findRewrittenPress(keyCode, eventTime, downTime);
            if (press != null) press.rememberIdentity(downTime);
        }
        if (press != null && press.state != State.PENDING) {
            // A delayed repeat or release must never repeat an action already
            // emitted by the DOWN-only fallback, regardless of hold duration.
            return none();
        }
        if (press == null) {
            press = addPress(keyCode, downTime, eventTime);
        } else if (!held) {
            return none();
        }
        if (held) {
            press.state = State.DISPATCHED;
            return new Decision(Action.DISPATCH_LONG, keyCode);
        }
        return new Decision(Action.SCHEDULE_SHORT, keyCode);
    }

    synchronized Decision onFallback(long eventTime) {
        return onFallback(eventTime, 0L);
    }

    synchronized Decision onFallback(long eventTime, long timeoutMillis) {
        for (Press press : presses) {
            if (press.state == State.PENDING
                    && fallbackDelay(press, eventTime, timeoutMillis) == 0L) {
                press.state = State.DISPATCHED;
                return new Decision(Action.DISPATCH_SHORT, press.keyCode);
            }
        }
        return none();
    }

    /** Reschedule after every event; another key's UP must not cancel this timer. */
    synchronized long nextFallbackDelay(long eventTime, long timeoutMillis) {
        long delay = -1L;
        for (Press press : presses) {
            if (press.state == State.PENDING) {
                long candidate = fallbackDelay(press, eventTime, timeoutMillis);
                delay = delay < 0L ? candidate : Math.min(delay, candidate);
            }
        }
        return delay;
    }

    synchronized Decision onUp(int keyCode, long eventTime) {
        return onUp(keyCode, eventTime, UNKNOWN_DOWN_TIME);
    }

    synchronized Decision onUp(int keyCode, long eventTime, long downTime) {
        Press press = findPress(keyCode, downTime, false);
        if (press == null && downTime > 0L) {
            press = findRewrittenPress(keyCode, eventTime, downTime);
            if (press != null) press.rememberIdentity(downTime);
        }
        if (press != null) {
            boolean dispatch = press.state == State.PENDING;
            press.state = State.FINISHED;
            return dispatch ? new Decision(Action.DISPATCH_SHORT, keyCode) : none();
        }
        // Some MCU/CAN adapters emit only ACTION_UP. Cache a usable identity,
        // but do not treat zero/missing downTime as one everlasting press.
        if (downTime > 0L) {
            addPress(keyCode, downTime, eventTime).state = State.FINISHED;
        }
        return new Decision(Action.DISPATCH_SHORT, keyCode);
    }

    synchronized void cancel(int keyCode, long downTime) {
        Press press = findPress(keyCode, downTime, true);
        if (press != null) {
            press.state = State.FINISHED;
        } else if (downTime > 0L) {
            addPress(keyCode, downTime, 0L).state = State.FINISHED;
        }
    }

    private Press findPress(int keyCode, long downTime, boolean newest) {
        if (downTime > 0L) {
            for (Press press : presses) {
                if (press.keyCode == keyCode && press.hasIdentity(downTime)) {
                    return press;
                }
            }
            return null;
        }
        // Legacy/no-identity adapters can still overlap different keys. A
        // release consumes just one matching open press, never another key.
        for (int offset = 0; offset < presses.size(); offset++) {
            int index = newest ? presses.size() - 1 - offset : offset;
            Press press = presses.get(index);
            if (press.keyCode == keyCode && press.state != State.FINISHED) {
                return press;
            }
        }
        return null;
    }

    /**
     * A few adapters rebuild UP/repeat events with a new positive downTime.
     * Infer identity only for one recent open press of the same canonical key.
     * This is deliberately not a debounce: fresh DOWNs, ambiguous overlaps,
     * missing identities and old presses are left independent. A genuinely
     * separate UP-only input overlapping that one open press is indistinguishable
     * without a stable device press ID; this bounded inference is the tradeoff.
     */
    private Press findRewrittenPress(int keyCode, long eventTime, long downTime) {
        Press match = null;
        for (Press press : presses) {
            if (press.keyCode != keyCode || press.state == State.FINISHED
                    || press.downTime <= 0L) continue;
            // Receipt time and KeyEvent downTime can use different Android
            // clocks. Check ordering/age within each clock, never across them.
            long receivedAge = eventTime - press.startedAt;
            long identityAge = downTime - press.downTime;
            if (receivedAge < 0L || receivedAge > REWRITTEN_IDENTITY_WINDOW_MS
                    || identityAge < 0L || identityAge > REWRITTEN_IDENTITY_WINDOW_MS) {
                continue;
            }
            if (match != null) return null;
            match = press;
        }
        return match;
    }

    private Press addPress(int keyCode, long downTime, long eventTime) {
        if (presses.size() >= MAX_TRACKED_PRESSES) {
            int eviction = 0;
            for (int index = 0; index < presses.size(); index++) {
                if (presses.get(index).state == State.FINISHED) {
                    eviction = index;
                    break;
                }
            }
            presses.remove(eviction);
        }
        Press press = new Press(keyCode, downTime, eventTime);
        presses.add(press);
        return press;
    }

    private long fallbackDelay(Press press, long eventTime, long timeoutMillis) {
        long elapsed = Math.max(0L, eventTime - press.startedAt);
        return Math.max(0L, timeoutMillis - elapsed);
    }

    synchronized void reset() {
        presses.clear();
    }

    private static Decision none() {
        return new Decision(Action.NONE, UNKNOWN_KEY);
    }
}
