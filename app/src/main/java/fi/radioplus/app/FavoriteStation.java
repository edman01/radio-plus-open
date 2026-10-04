package fi.radioplus.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Objects;

final class FavoriteStation {
    private static final int MAX_NAME_CODE_POINTS = 64;
    private static final int MAX_LOGO_CODE_POINTS = 256;

    final int band;
    final int frequency;
    final String name;
    final String logo;

    FavoriteStation(int band, int frequency, String name) {
        this(band, frequency, name, "");
    }

    FavoriteStation(int band, int frequency, String name, String logo) {
        this.band = band;
        this.frequency = frequency;
        this.name = limit(name, MAX_NAME_CODE_POINTS);
        this.logo = limit(logo, MAX_LOGO_CODE_POINTS);
    }

    String key() {
        return band + ":" + frequency;
    }

    boolean isFm() {
        return FrequencyRules.isFm(band);
    }

    String bandLabel() {
        return isFm() ? "FM" + (band + 1) : "AM";
    }

    String frequencyLabel() {
        if (isFm()) {
            return formatFmFrequency(frequency) + " MHz";
        }
        return frequency + " kHz";
    }

    boolean hasGeneratedFrequencyName() {
        return name.equalsIgnoreCase(frequencyLabel());
    }

    FavoriteStation withName(String newName) {
        return new FavoriteStation(band, frequency, newName, logo);
    }

    FavoriteStation withLogo(String newLogo) {
        return new FavoriteStation(band, frequency, name, newLogo);
    }

    static String formatFmFrequency(int frequency) {
        String pattern = frequency % 100 == 0 ? "%.1f" : "%.2f";
        return String.format(Locale.US, pattern, frequency / 1000.0);
    }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("band", band);
        object.put("frequency", frequency);
        object.put("name", name);
        object.put("logo", logo);
        return object;
    }

    static FavoriteStation fromJson(JSONObject object) throws JSONException {
        return new FavoriteStation(
                object.getInt("band"),
                object.getInt("frequency"),
                object.optString("name", ""),
                object.optString("logo", "")
        );
    }

    private static String limit(String value, int maximumCodePoints) {
        String clean = value == null ? "" : value.trim();
        int count = clean.codePointCount(0, clean.length());
        if (count <= maximumCodePoints) {
            return clean;
        }
        return clean.substring(0, clean.offsetByCodePoints(0, maximumCodePoints));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FavoriteStation)) {
            return false;
        }
        FavoriteStation station = (FavoriteStation) other;
        return band == station.band && frequency == station.frequency;
    }

    @Override
    public int hashCode() {
        return Objects.hash(band, frequency);
    }
}
