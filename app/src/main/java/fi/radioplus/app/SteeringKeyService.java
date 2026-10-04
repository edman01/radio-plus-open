package fi.radioplus.app;

import android.accessibilityservice.AccessibilityService;
import android.util.SparseBooleanArray;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Optional, user-enabled physical key routing while the radio UI is resumed. */
public final class SteeringKeyService extends AccessibilityService {
    private static volatile boolean radioVisible;
    private final SparseBooleanArray consumed = new SparseBooleanArray();

    static void setRadioVisible(boolean visible) { radioVisible = visible; }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event == null || !RadioPlaybackService.supportsMediaKey(event.getKeyCode())) return false;
        int key = event.getKeyCode();
        boolean finishingPress = consumed.get(key);
        if (!radioVisible && !finishingPress) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP) return false;
        KeyEvent forwarded = radioVisible ? event
                : KeyEvent.changeFlags(event, event.getFlags() | KeyEvent.FLAG_CANCELED);
        boolean delivered = RadioMediaButtonReceiver.dispatch(this, forwarded);
        if (event.getAction() == KeyEvent.ACTION_UP) consumed.delete(key);
        else if (delivered) consumed.put(key, true);
        if (delivered) Log.i("RadioSteeringKeys", "Forwarded media key " + key + " action=" + event.getAction());
        return delivered || finishingPress;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { consumed.clear(); }
}
