package fi.radioplus.app;

/**
 * On verified HCN radios the stock receiver can move the tuner for the same
 * physical key. Pick a list neighbour from the station before DOWN, not from
 * a subsequent OEM preset. All timestamps here use Android uptime.
 */
final class HcnWheelHistory {
    private static final long MAX_AGE_MS = 4500L; // three normal service polls
    // A vendor preset callback and Android's timestamp for the same press can
    // arrive in either order. This is a look-back for a real key, not a debounce.
    private static final long PRE_DOWN_SKEW_MS = 200L;
    private final Run[] readings = new Run[32];
    private final long[] pressedAt = new long[32];
    private final Point[] origins = new Point[32];
    private int cursor, pressCursor;
    private Point confirmed;
    private Point lastWheelSelection;
    private long lastChange;

    static final class Point {
        final int band, frequency;
        final long time;

        Point(int band, int frequency, long time) {
            this.band = band;
            this.frequency = frequency;
            this.time = time;
        }

        boolean matches(int band, int frequency) {
            return this.band == band && this.frequency == frequency;
        }
    }

    private static final class Run {
        final Point first;
        long last;
        Run(Point first) { this.first = first; last = first.time; }
    }

    synchronized boolean record(int band, int frequency, long time, boolean confirmedTune) {
        return record(band, frequency, time, confirmedTune, false);
    }

    synchronized void confirmWheel(int band, int frequency, long time) {
        record(band, frequency, time, true, true);
    }

    private boolean record(int band, int frequency, long time, boolean confirmedTune, boolean wheel) {
        if (time <= 0 || !FrequencyRules.isValid(band, frequency)) return false;
        // A UI read can acquire the lock just before the queued confirmation.
        // Keep that confirmation for the next queued key without rewinding the observations.
        if (confirmedTune && (confirmed == null || time >= confirmed.time)) {
            confirmed = new Point(band, frequency, time);
            // An explicit selection from another input supersedes the wheel.
            lastWheelSelection = wheel ? confirmed : null;
        }
        Run previous = readings[(cursor - 1 + readings.length) % readings.length];
        if (previous != null && time < previous.last) return false; // late sample from the other observer
        boolean changed = previous != null && (previous.first.band != band || previous.first.frequency != frequency);
        if (previous != null && !changed && time - previous.last <= MAX_AGE_MS) {
            // RDS and UI callbacks can repeat the same native preset dozens of
            // times during a press. They are one observation run, not 32 moves.
            previous.last = time;
        } else {
            readings[cursor] = new Run(new Point(band, frequency, time));
            cursor = (cursor + 1) % readings.length;
        }
        if (changed) lastChange = time;
        return changed;
    }

    synchronized boolean observedMoveDuring(long down, long now) {
        return down > 0 && down <= now && now - down <= MAX_AGE_MS
                && lastChange >= down && lastChange <= now;
    }

    private Point readingBefore(long down) {
        Point best = null;
        for (int i = 1; i <= readings.length; i++) {
            Run run = readings[(cursor - i + readings.length) % readings.length];
            if (run == null || run.first.time >= down) continue;
            long time = Math.min(run.last, down - 1);
            if (down - time <= MAX_AGE_MS && (best == null || best.time < time))
                best = new Point(run.first.band, run.first.frequency, time);
        }
        return best;
    }

    private Point originBeforeDown(long down) {
        Point latest = readingBefore(down);
        Point wheel = lastWheelSelection;
        if (latest == null || wheel == null || wheel.time >= down
                || wheel.band != latest.band || wheel.frequency == latest.frequency) return latest;
        long cutoff = down - PRE_DOWN_SKEW_MS;
        // A separately established station wins. Use our wheel destination
        // only if it was just confirmed or was still observed immediately
        // before this short race, with no gap in normal polling.
        if (wheel.time >= cutoff) return wheel;
        Point prior = readingBefore(cutoff);
        return prior != null && prior.time >= wheel.time && down - prior.time <= MAX_AGE_MS
                && prior.band == wheel.band && prior.frequency == wheel.frequency ? wheel : latest;
    }

    // Pin on the first DOWN, not on UP or after a queued tuner operation.
    // Even a burst of different native frequencies cannot evict this origin.
    synchronized void pinBeforeDown(long down, long now) {
        if (down <= 0 || down > now || now - down > MAX_AGE_MS) return;
        for (long saved : pressedAt) if (saved == down) return;
        pressedAt[pressCursor] = down;
        origins[pressCursor] = originBeforeDown(down);
        pressCursor = (pressCursor + 1) % origins.length;
    }

    synchronized Point beforePress(long down, long now) {
        if (down <= 0 || down > now || now - down > MAX_AGE_MS) return null;
        // A genuine second press queued before the first tune completed must
        // advance from its confirmed result, not either key's native preset.
        if (confirmed != null && confirmed.time >= down && confirmed.time <= now) return confirmed;
        for (int i = 0; i < origins.length; i++)
            if (pressedAt[i] == down && origins[i] != null) return origins[i];
        return originBeforeDown(down);
    }

    synchronized Point originForTuner(long down, long now, int liveBand, int liveFrequency) {
        Point origin = beforePress(down, now);
        if (origin == null || origin.matches(liveBand, liveFrequency) || origin.time >= down
                || observedMoveDuring(down, now)) return origin;
        // A pre-DOWN stock race is supported by an independent observation
        // of the new live preset, not just by a stale sample of the old one.
        Point rawBefore = readingBefore(down);
        if (rawBefore != null && rawBefore.matches(liveBand, liveFrequency)) return origin;
        // No corroborating move: follow the real tuner as in Community Beta 5.
        return null;
    }
}
