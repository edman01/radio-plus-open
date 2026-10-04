package fi.radioplus.app;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

final class AutoStartLauncher {
    private static final String TAG = "RadioAutoStart";
    private static long previousLaunchAt;

    private AutoStartLauncher() {
    }

    static synchronized boolean launch(Context context, String reason) {
        long now = SystemClock.elapsedRealtime();
        if (!AutoStartLaunchPolicy.isOutsideLaunchCooldown(previousLaunchAt, now)) {
            Log.i(TAG, "Duplicate startup trigger ignored: " + reason);
            return false;
        }
        previousLaunchAt = now;
        boolean playbackStarted = false;
        try {
            // A standard Android 13 build may decline to put an activity in
            // front from a boot receiver. Starting the foreground media
            // service still restores radio playback and exposes a tappable
            // notification; Junsun builds that permit startup activities also
            // continue through the activity request below.
            RadioPlaybackService.ensureRunning(context);
            playbackStarted = true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Startup playback service was blocked by the system", error);
        }
        Intent activity = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("radio_plus_auto_start_reason", reason);
        try {
            context.startActivity(activity);
            Log.i(TAG, "Radio+ UI requested after startup trigger: " + reason);
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Startup activity was blocked by the system", error);
            return playbackStarted;
        }
    }
}
