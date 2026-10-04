package fi.radioplus.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StationStore {
    private static final String TAG = "JunsunRadioPlus";
    private static final String PREFS = "radio_plus_station_catalog";
    private static final String KEY_STATIONS = "stations";

    private final Context context;
    private final SharedPreferences preferences;

    StationStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized List<FavoriteStation> load() {
        ArrayList<FavoriteStation> result = new ArrayList<>();
        String raw;
        try {
            raw = preferences.getString(KEY_STATIONS, "[]");
        } catch (ClassCastException error) {
            Log.e(TAG, "Asemalistan tiedon tyyppi ei ole kelvollinen", error);
            raw = "[]";
        }
        if (raw == null) {
            raw = "[]";
        }
        LinkedHashMap<String, FavoriteStation> unique = new LinkedHashMap<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                try {
                    FavoriteStation station =
                            FavoriteStation.fromJson(array.getJSONObject(index));
                    if (FrequencyRules.isValid(station.band, station.frequency)) {
                        unique.put(station.key(), station);
                    }
                } catch (JSONException | RuntimeException error) {
                    Log.w(TAG, "Vioittunut asemalistan rivi ohitettiin", error);
                }
            }
        } catch (JSONException error) {
            Log.e(TAG, "Asemalistan lukeminen epäonnistui", error);
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
            Log.w(TAG, "Virheellistä asemaa ei tallennettu asemalistaan");
            return;
        }
        ArrayList<FavoriteStation> stations = new ArrayList<>(load());
        int existingIndex = StationOrder.indexOf(stations, station.key());
        if (existingIndex >= 0) {
            stations.set(existingIndex, station);
        } else {
            stations.add(station);
        }
        persist(stations);
    }

    synchronized int mergeDiscovered(
            int band,
            int[] frequencies,
            Map<Integer, String> observedNames
    ) {
        StationCatalog.MergeResult result = StationCatalog.mergeDiscovered(
                load(),
                band,
                frequencies,
                observedNames
        );
        persist(result.stations);
        return result.added;
    }

    synchronized void mergeLegacy(List<FavoriteStation> legacyStations) {
        persist(StationCatalog.mergeLegacy(load(), legacyStations));
    }

    synchronized void replaceOrder(List<FavoriteStation> stations) {
        persist(StationOrder.sanitize(stations));
    }

    synchronized boolean updateRdsNameIfUnnamed(
            int band,
            int frequency,
            String rdsName
    ) {
        String cleanName = RadioMetadataReader.clean(rdsName);
        if (cleanName.isEmpty()) {
            return false;
        }
        ArrayList<FavoriteStation> stations = new ArrayList<>(load());
        for (int index = 0; index < stations.size(); index++) {
            FavoriteStation station = stations.get(index);
            if (station.band == band
                    && station.frequency == frequency
                    && station.name.isEmpty()) {
                stations.set(index, station.withName(cleanName));
                persist(stations);
                return true;
            }
        }
        return false;
    }

    private void persist(List<FavoriteStation> stations) {
        JSONArray array = new JSONArray();
        try {
            for (FavoriteStation station : stations) {
                array.put(station.toJson());
            }
            preferences.edit().putString(KEY_STATIONS, array.toString()).apply();
            StationWidgetProvider.requestDataRefresh(context);
            RadioPlaybackService.notifyMediaLibraryChanged();
        } catch (JSONException error) {
            Log.e(TAG, "Asemalistan tallentaminen epäonnistui", error);
        }
    }
}
