package fi.radioplus.app;

import android.content.Context;

final class StationWidgetMode {
    static final int FAVORITES = 0;
    static final int STATIONS = 1;

    private StationWidgetMode() {
    }

    static int normalize(int mode) {
        return mode == STATIONS ? STATIONS : FAVORITES;
    }

    static String title(Context context, int mode) {
        return normalize(mode) == STATIONS
                ? AppLanguage.text(context, "ASEMALISTA", "STATION LIST")
                : AppLanguage.text(context, "SUOSIKIT", "FAVORITES");
    }

    static String title(int mode) {
        return normalize(mode) == STATIONS ? "STATION LIST" : "FAVORITES";
    }

    static String emptyText(Context context, int mode) {
        return normalize(mode) == STATIONS
                ? AppLanguage.text(
                        context,
                        "Ei vielä löydettyjä asemia",
                        "No stations found yet"
                )
                : AppLanguage.text(context, "Ei vielä suosikkeja", "No favorites yet");
    }

    static String emptyText(int mode) {
        return normalize(mode) == STATIONS
                ? "No stations found yet"
                : "No favorites yet";
    }
}
