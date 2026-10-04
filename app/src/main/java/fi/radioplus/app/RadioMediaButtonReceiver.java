package fi.radioplus.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Restarts the media foreground service when a steering-wheel media key
 * arrives while Android has reclaimed the app process. The stock Junsun radio
 * exposes the same MEDIA_BUTTON receiver pattern.
 */
public final class RadioMediaButtonReceiver extends BroadcastReceiver {
    private static final String TAG = "RadioMediaButtons";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return;
        }
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null || !RadioPlaybackService.supportsMediaKey(event.getKeyCode())) {
            return;
        }

        dispatch(context, event);
    }

    static boolean dispatch(Context context, KeyEvent event) {
        Intent service = new Intent(context, RadioPlaybackService.class);
        service.setAction(Intent.ACTION_MEDIA_BUTTON);
        service.putExtra(Intent.EXTRA_KEY_EVENT, event);
        try {
            context.startForegroundService(service);
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Media button could not start radio controls", error);
            return false;
        }
    }
}
