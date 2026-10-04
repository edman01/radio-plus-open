package fi.radioplus.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

public final class StationWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(
            Context context,
            AppWidgetManager appWidgetManager,
            int[] appWidgetIds
    ) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            StationWidgetPreferences.delete(context, appWidgetId);
        }
    }

    static void updateWidget(
            Context context,
            AppWidgetManager appWidgetManager,
            int appWidgetId
    ) {
        int mode = StationWidgetPreferences.getMode(context, appWidgetId);
        RemoteViews views = new RemoteViews(
                context.getPackageName(),
                R.layout.widget_station_list
        );
        views.setTextViewText(R.id.widget_title, "RADIO +");
        views.setTextViewText(
                R.id.widget_mode,
                StationWidgetMode.title(context, mode)
        );
        views.setTextViewText(
                R.id.widget_empty,
                StationWidgetMode.emptyText(context, mode)
        );

        Intent adapter = new Intent(context, StationWidgetRemoteViewsService.class);
        adapter.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        adapter.setData(Uri.parse(
                "radioplus://widget/" + appWidgetId + "/" + mode
        ));
        views.setRemoteAdapter(R.id.widget_station_list, adapter);
        views.setEmptyView(R.id.widget_station_list, R.id.widget_empty);

        Intent open = new Intent(context, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_header, openPendingIntent);

        Intent tune = new Intent(context, RadioPlaybackService.class);
        tune.setAction(RadioPlaybackService.ACTION_TUNE_STATION);
        int tuneFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The widget host must be able to add the selected row's extras.
            tuneFlags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent tuneTemplate = PendingIntent.getForegroundService(
                context,
                appWidgetId,
                tune,
                tuneFlags
        );
        views.setPendingIntentTemplate(R.id.widget_station_list, tuneTemplate);

        appWidgetManager.updateAppWidget(appWidgetId, views);
        appWidgetManager.notifyAppWidgetViewDataChanged(
                appWidgetId,
                R.id.widget_station_list
        );
    }

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(
                context,
                StationWidgetProvider.class
        ));
        for (int id : ids) {
            updateWidget(context, manager, id);
        }
    }

    static void requestDataRefresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(
                context,
                StationWidgetProvider.class
        ));
        if (ids.length > 0) {
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_station_list);
        }
    }
}
