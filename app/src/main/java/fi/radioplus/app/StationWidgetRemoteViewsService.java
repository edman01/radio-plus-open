package fi.radioplus.app;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

public final class StationWidgetRemoteViewsService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(
                getApplicationContext(),
                intent.getIntExtra(
                        AppWidgetManager.EXTRA_APPWIDGET_ID,
                        AppWidgetManager.INVALID_APPWIDGET_ID
                )
        );
    }

    private static final class Factory implements RemoteViewsFactory {
        private final Context context;
        private final int appWidgetId;
        private final ArrayList<FavoriteStation> stations = new ArrayList<>();

        Factory(Context context, int appWidgetId) {
            this.context = context;
            this.appWidgetId = appWidgetId;
        }

        @Override
        public void onCreate() {
            reload();
        }

        @Override
        public void onDataSetChanged() {
            reload();
        }

        private void reload() {
            int mode = StationWidgetPreferences.getMode(context, appWidgetId);
            List<FavoriteStation> updated = mode == StationWidgetMode.STATIONS
                    ? new StationStore(context).load()
                    : new FavoriteStore(context).load();
            stations.clear();
            stations.addAll(updated);
        }

        @Override
        public void onDestroy() {
            stations.clear();
        }

        @Override
        public int getCount() {
            return stations.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= stations.size()) {
                return null;
            }
            FavoriteStation station = stations.get(position);
            RemoteViews row = new RemoteViews(
                    context.getPackageName(),
                    R.layout.widget_station_row
            );
            String displayName = station.name.isEmpty()
                    ? AppLanguage.text(context, "Oma asema", "My station")
                    : AppLanguage.stationName(context, station.name);
            row.setTextViewText(R.id.widget_station_name, displayName);
            row.setTextViewText(
                    R.id.widget_station_frequency,
                    station.bandLabel() + "  \u2022  " + station.frequencyLabel()
            );

            Intent fillIn = new Intent();
            fillIn.putExtra(RadioPlaybackService.EXTRA_STATION_BAND, station.band);
            fillIn.putExtra(
                    RadioPlaybackService.EXTRA_STATION_FREQUENCY,
                    station.frequency
            );
            fillIn.putExtra(RadioPlaybackService.EXTRA_STATION_NAME, displayName);
            row.setOnClickFillInIntent(R.id.widget_station_row, fillIn);
            return row;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            if (position < 0 || position >= stations.size()) {
                return position;
            }
            FavoriteStation station = stations.get(position);
            return ((long) station.band << 32)
                    | (station.frequency & 0xffffffffL);
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }
}
