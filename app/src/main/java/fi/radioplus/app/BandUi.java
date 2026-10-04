package fi.radioplus.app;

final class BandUi {
    private BandUi() {
    }

    static String labelForBand(int band) {
        return band >= 3 ? "AM" : "FM";
    }
}
