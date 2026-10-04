package fi.radioplus.app;

import java.util.Locale;

/** Selects the most complete version of a repeatedly decoded RDS RT message. */
final class RdsTextQuality {
    private RdsTextQuality() {
    }

    static String preferMoreComplete(String currentValue, String candidateValue) {
        String current = RadioMetadataReader.clean(currentValue);
        String candidate = RadioMetadataReader.clean(candidateValue);
        if (candidate.isEmpty()) {
            return current;
        }
        if (current.isEmpty() || current.equals(candidate)) {
            return candidate;
        }
        if (isLikelyIncompleteVersion(current, candidate)) {
            return current;
        }
        return candidate;
    }

    static boolean isLikelyIncompleteVersion(String completeValue, String candidateValue) {
        String complete = compact(completeValue);
        String candidate = compact(candidateValue);
        if (candidate.isEmpty() || candidate.length() >= complete.length()) {
            return false;
        }
        if (candidate.length() < Math.max(4, complete.length() / 2)) {
            return false;
        }
        if (isSubsequence(candidate, complete)) {
            return true;
        }
        int permittedEdits = Math.max(2, complete.length() / 10);
        return boundedEditDistance(complete, candidate, permittedEdits) <= permittedEdits;
    }

    private static String compact(String value) {
        return RadioMetadataReader.clean(value)
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isSubsequence(String candidate, String complete) {
        int candidateIndex = 0;
        for (int index = 0;
             index < complete.length() && candidateIndex < candidate.length();
             index++) {
            if (complete.charAt(index) == candidate.charAt(candidateIndex)) {
                candidateIndex++;
            }
        }
        return candidateIndex == candidate.length();
    }

    private static int boundedEditDistance(String first, String second, int limit) {
        if (Math.abs(first.length() - second.length()) > limit) {
            return limit + 1;
        }
        int[] previous = new int[second.length() + 1];
        int[] current = new int[second.length() + 1];
        for (int index = 0; index <= second.length(); index++) {
            previous[index] = index;
        }
        for (int firstIndex = 1; firstIndex <= first.length(); firstIndex++) {
            current[0] = firstIndex;
            int rowMinimum = current[0];
            for (int secondIndex = 1; secondIndex <= second.length(); secondIndex++) {
                int substitution = previous[secondIndex - 1]
                        + (first.charAt(firstIndex - 1) == second.charAt(secondIndex - 1)
                        ? 0
                        : 1);
                current[secondIndex] = Math.min(
                        Math.min(previous[secondIndex] + 1, current[secondIndex - 1] + 1),
                        substitution
                );
                rowMinimum = Math.min(rowMinimum, current[secondIndex]);
            }
            if (rowMinimum > limit) {
                return limit + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[second.length()];
    }
}
