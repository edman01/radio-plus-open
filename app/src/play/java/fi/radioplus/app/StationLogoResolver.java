package fi.radioplus.app;

/**
 * Import-only distribution: no catalogue, automatic name mapping or bundled
 * artwork. Old builtin tokens are deliberately ignored, including restored data.
 * User-selected files are decoded separately by FavoriteLogoStore and callers.
 */
final class StationLogoResolver {
    static final String BUILTIN_PREFIX = "builtin:";
    static final String FILE_PREFIX = "file:";
    static final String NO_LOGO = "none";
    static final String[] BUILTIN_NAMES = {};
    static final String[] BUILTIN_TOKENS = {};

    private StationLogoResolver() {}

    static boolean needsWhiteBackground(int resource) {
        return false;
    }

    static int resolve(String stationName) {
        return 0;
    }

    static int resolveExplicit(String logoToken) {
        return 0;
    }

    static int resolveForStation(String logoToken, boolean customLogoDecoded,
            boolean unnamed, String displayName) {
        return 0;
    }
}
