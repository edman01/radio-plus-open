package fi.radioplus.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.SparseBooleanArray;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Optional, user-enabled physical key routing while the radio UI is resumed. */
public final class SteeringKeyService extends AccessibilityService {
    private static volatile boolean radioVisible;
    private static volatile boolean connected;
    private static volatile boolean filterRequested;
    private final SparseBooleanArray consumed = new SparseBooleanArray();

    static void setRadioVisible(boolean visible) { radioVisible = visible; }
    static boolean isConnected() { return connected; }
    static boolean isFilterRequested() { return filterRequested; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        AccessibilityServiceInfo info = getServiceInfo();
        filterRequested = info != null
                && (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
        SteeringDiagnosticTrace.get().event("accessibility", "connected filter=" + filterRequested);
    }

    @Override public boolean onUnbind(Intent intent) {
        connected = false;
        filterRequested = false;
        consumed.clear();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        connected = false;
        filterRequested = false;
        consumed.clear();
        super.onDestroy();
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (RadioPlaybackService.shouldIgnoreRawMediaKeys()) {
            consumed.clear();
            return false;
        }
        if (event != null && radioVisible) {
            SteeringDiagnosticTrace.get().key("accessibility", event.getKeyCode(),
                    event.getAction(), event.getMetaState(), event.getRepeatCount(),
                    event.getScanCode(), event.getFlags(), event.getSource(), event.getDownTime(), event.getEventTime());
        }
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
