package fi.radioplus.app;

/**
 * On verified HCN radios the stock receiver can move the tuner for the same
 * physical key. Pick a list neighbour from the station before DOWN, not from
 * a subsequent OEM preset. All timestamps here use Android uptime.
 */
final class HcnWheelHistory {
    private static final long MAX_AGE_MS = 4500L; // three normal service polls
    private final Point[] readings = new Point[32];
    private int cursor;
    private Point confirmed;

    static final class Point {
        final int band, frequency;
        final long time;

        Point(int band, int frequency, long time) {
            this.band = band;
            this.frequency = frequency;
            this.time = time;
        }
    }

    synchronized void record(int band, int frequency, long time, boolean confirmedTune) {
        if (time <= 0 || !FrequencyRules.isValid(band, frequency)) return;
        Point point = new Point(band, frequency, time);
        readings[cursor] = point;
        cursor = (cursor + 1) % readings.length;
        if (confirmedTune) confirmed = point;
    }

    synchronized Point beforePress(long down, long now) {
        if (down <= 0 || down > now || now - down > MAX_AGE_MS) return null;
        // A distinct, queued second press follows the first confirmed Radio+
        // selection even if both DOWN events preceded that confirmation.
        if (confirmed != null && confirmed.time >= down && confirmed.time <= now) return confirmed;
        Point best = null;
        for (int i = 1; i <= readings.length; i++) {
            Point p = readings[(cursor - i + readings.length) % readings.length];
            if (p != null && p.time < down && down - p.time <= MAX_AGE_MS
                    && (best == null || best.time < p.time)) best = p;
        }
        return best;
    }
}
