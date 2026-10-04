package fi.radioplus.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

final class FavoriteStore {
    private static final String TAG = "JunsunRadioPlus";
    private static final String PREFS = "radio_plus_favorites";
    private static final String KEY_STATIONS = "stations";
    private static final String KEY_CLEAN_FREQUENCY_NAMES_082 =
            "clean_frequency_names_082";

    private final Context context;
    private final SharedPreferences preferences;

    FavoriteStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateGeneratedFrequencyNames();
    }

    private synchronized void migrateGeneratedFrequencyNames() {
        if (preferences.getBoolean(KEY_CLEAN_FREQUENCY_NAMES_082, false)) {
            return;
        }
        ArrayList<FavoriteStation> stations = new ArrayList<>(load());
        boolean changed = false;
        for (int index = 0; index < stations.size(); index++) {
            FavoriteStation station = stations.get(index);
            if (station.hasGeneratedFrequencyName()) {
                stations.set(index, station.withName(AppLanguage.text(
                        context,
                        "Oma asema",
                        "My station"
                )));
                changed = true;
            }
        }
        if (changed) {
            persist(stations, false);
        }
        preferences.edit()
                .putBoolean(KEY_CLEAN_FREQUENCY_NAMES_082, true)
                .apply();
    }

    synchronized List<FavoriteStation> load() {
        ArrayList<FavoriteStation> result = new ArrayList<>();
        String raw;
        try {
            raw = preferences.getString(KEY_STATIONS, "[]");
        } catch (ClassCastException error) {
            Log.e(TAG, "Suosikkitiedon tyyppi ei ole kelvollinen", error);
            raw = "[]";
        }
        if (raw == null) {
            raw = "[]";
        }
        LinkedHashMap<String, FavoriteStation> unique = new LinkedHashMap<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                try {
                    FavoriteStation station =
                            FavoriteStation.fromJson(array.getJSONObject(i));
                    if (FrequencyRules.isValid(station.band, station.frequency)) {
                        unique.put(station.key(), station);
                    } else {
                        Log.w(TAG, "Virheellinen suosikkitaajuus ohitettiin");
                    }
                } catch (JSONException | RuntimeException error) {
                    Log.w(TAG, "Vioittunut suosikkirivi ohitettiin", error);
                }
            }
        } catch (JSONException error) {
            Log.e(TAG, "Suosikkien lukeminen epäonnistui", error);
        }
        result.addAll(unique.values());
        return result;
    }

    synchronized FavoriteStation find(int band, int frequency) {
        for (FavoriteStation station : load()) {
            if (station.band == band && station.frequency == frequency) {
                return station;
            }
        }
        return null;
    }

    synchronized void save(FavoriteStation station) {
        if (station == null
                || !FrequencyRules.isValid(station.band, station.frequency)) {
            Log.w(TAG, "Virheellistä suosikkia ei tallennettu");
            return;
        }
        ArrayList<FavoriteStation> stations = new ArrayList<>(load());
        int existingIndex = indexOf(stations, station);
        if (existingIndex >= 0) {
            stations.set(existingIndex, station);
        } else {
            stations.add(station);
        }
        persist(stations);
    }

    synchronized boolean updateRdsNameIfUnnamed(int band, int frequency, String rdsName) {
        String cleanName = RadioMetadataReader.clean(rdsName);
        if (cleanName.isEmpty()) {
            return false;
        }
        ArrayList<FavoriteStation> stations = new ArrayList<>(load());
        for (int i = 0; i < stations.size(); i++) {
            FavoriteStation station = stations.get(i);
            if (station.band == band
                    && station.frequency == frequency
                    && station.name.isEmpty()) {
                stations.set(i, station.withName(cleanName));
                persist(stations);
                return true;
            }
        }
        return false;
    }

    synchronized void delete(FavoriteStation station) {
        ArrayList<FavoriteStation> stations = new ArrayList<>(load());
        stations.removeIf(existing -> existing.equals(station));
        persist(stations);
    }

    synchronized void clear() {
        persist(Collections.emptyList());
    }

    synchronized void replaceOrder(List<FavoriteStation> stations) {
        persist(StationOrder.sanitize(stations));
    }

    private int indexOf(List<FavoriteStation> stations, FavoriteStation target) {
        for (int index = 0; index < stations.size(); index++) {
            if (stations.get(index).equals(target)) {
                return index;
            }
        }
        return -1;
    }

    private void persist(List<FavoriteStation> stations) {
        persist(stations, true);
    }

    private void persist(List<FavoriteStation> stations, boolean updateWidgets) {
        JSONArray array = new JSONArray();
        try {
            for (FavoriteStation station : stations) {
                array.put(station.toJson());
            }
            preferences.edit().putString(KEY_STATIONS, array.toString()).apply();
            if (updateWidgets) {
                StationWidgetProvider.requestDataRefresh(context);
                RadioPlaybackService.notifyMediaLibraryChanged();
            }
        } catch (JSONException error) {
            Log.e(TAG, "Suosikkien tallentaminen epäonnistui", error);
        }
    }
}
