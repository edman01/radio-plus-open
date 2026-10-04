package fi.radioplus.app;

/** Prevents one incomplete RDS decode from replacing a complete track title. */
final class RdsTextStabilizer {
    private static final int INITIAL_CONFIRMATIONS = 2;
    private static final int CHANGE_CONFIRMATIONS = 3;

    private long stationKey = Long.MIN_VALUE;
    private String stableText = "";
    private String candidateText = "";
    private int candidateCount;

    synchronized String observe(long observedStationKey, String rawText) {
        if (stationKey != observedStationKey) {
            reset();
            stationKey = observedStationKey;
        }
        String cleanText = RadioMetadataReader.clean(rawText);
        if (cleanText.isEmpty() || cleanText.equals(stableText)) {
            clearCandidate();
            return stableText;
        }
        if (!stableText.isEmpty()
                && RdsTextQuality.isLikelyIncompleteVersion(stableText, cleanText)) {
            clearCandidate();
            return stableText;
        }
        if (cleanText.equals(candidateText)) {
            candidateCount++;
        } else {
            candidateText = cleanText;
            candidateCount = 1;
        }
        int confirmations = stableText.isEmpty()
                ? INITIAL_CONFIRMATIONS
                : CHANGE_CONFIRMATIONS;
        if (candidateCount >= confirmations) {
            stableText = RdsTextQuality.preferMoreComplete(stableText, candidateText);
            clearCandidate();
        }
        return stableText;
    }

    synchronized void reset() {
        stationKey = Long.MIN_VALUE;
        stableText = "";
        clearCandidate();
    }

    private void clearCandidate() {
        candidateText = "";
        candidateCount = 0;
    }
}
