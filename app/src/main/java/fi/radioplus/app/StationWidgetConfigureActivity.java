package fi.radioplus.app;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

public final class StationWidgetConfigureActivity extends Activity {
    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLanguage.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        setContentView(R.layout.activity_widget_configure);

        Intent intent = getIntent();
        if (intent != null) {
            appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
            );
        }
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        Button favorites = findViewById(R.id.widget_choose_favorites);
        Button stations = findViewById(R.id.widget_choose_stations);
        favorites.setOnClickListener(ignored -> finishConfiguration(
                StationWidgetMode.FAVORITES
        ));
        stations.setOnClickListener(ignored -> finishConfiguration(
                StationWidgetMode.STATIONS
        ));
        findViewById(R.id.widget_config_cancel).setOnClickListener(
                ignored -> finish()
        );
    }

    private void finishConfiguration(int mode) {
        StationWidgetPreferences.setMode(this, appWidgetId, mode);
        StationWidgetProvider.updateWidget(
                this,
                AppWidgetManager.getInstance(this),
                appWidgetId
        );

        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(RESULT_OK, result);
        finish();
    }
}
