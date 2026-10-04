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
        if (event == null) {
            return;
        }
        SteeringDiagnosticTrace.get().key("receiver", event.getKeyCode(),
                event.getAction(), event.getMetaState(), event.getRepeatCount(),
                event.getScanCode(), event.getFlags(), event.getSource(), event.getDownTime(), event.getEventTime());
        int command = MediaButtonKeyMapping.commandKeyCode(event.getKeyCode(), event.getMetaState());
        if (!RadioPlaybackService.supportsMediaKey(command)) {
            Log.i(TAG, "Ignored media-button code=" + event.getKeyCode()
                    + " meta=" + event.getMetaState() + " action=" + event.getAction());
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
            SteeringDiagnosticTrace.get().event("dispatch", "service-start-failed");
            Log.w(TAG, "Media button could not start radio controls", error);
            return false;
        }
    }
}
