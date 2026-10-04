package fi.radioplus.app;

/** Same event may arrive through both a foreground window and a media session. */
final class MediaKeyEventDeduplicator {
    private final long[][] recent = new long[16][];
    private int cursor;

    synchronized boolean accept(int key, int action, long downTime, long eventTime, int repeat) {
        for (long[] prior : recent) {
            if (prior != null && prior[0] == key && prior[1] == action
                    && prior[2] == downTime && prior[3] == eventTime && prior[4] == repeat) {
                return false;
            }
        }
        recent[cursor] = new long[]{key, action, downTime, eventTime, repeat};
        cursor = (cursor + 1) % recent.length;
        return true;
    }
}
