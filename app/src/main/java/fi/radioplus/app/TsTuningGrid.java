package fi.radioplus.app;

/** Converts an absolute OEM frequency to the stock radio's zero-based tuning index. */
final class TsTuningGrid {
    private TsTuningGrid() { }

    static int indexFor(int frequency, int first, int second, int count) {
        long step = (long) second - first;
        // Reject incomplete, changing or corrupt OEM grids before issuing a command.
        if (first <= 0 || step <= 0 || count < 2 || count > 10_000) return -1;
        long offset = (long) frequency - first;
        if (offset < 0 || offset % step != 0) return -1;
        long index = offset / step;
        return index < count ? (int) index : -1;
    }
}
