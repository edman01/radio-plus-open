package fi.radioplus.app;

/** Keeps background radio-state updates from overriding a page chosen by the user. */
final class StationPageSelectionPolicy {
    private StationPageSelectionPolicy() {
    }

    static int targetPage(
            int currentPage,
            int stationIndex,
            int itemsPerPage,
            boolean followRequested,
            boolean pageManuallySelected
    ) {
        if (!followRequested
                || pageManuallySelected
                || stationIndex < 0
                || itemsPerPage <= 0) {
            return currentPage;
        }
        return stationIndex / itemsPerPage;
    }
}
