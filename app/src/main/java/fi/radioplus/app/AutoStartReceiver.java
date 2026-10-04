package fi.radioplus.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class AutoStartReceiver extends BroadcastReceiver {
    private static final String TAG = "RadioAutoStart";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!AutoStartLaunchPolicy.isStartupAction(action)) {
            return;
        }
        if (!AutoStartPreferences.isEnabled(context)) {
            Log.i(TAG, "Startup trigger ignored because auto-start is disabled");
            return;
        }
        AutoStartLauncher.launch(context, action);
    }
}
