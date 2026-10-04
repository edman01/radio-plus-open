package fi.radioplus.app;

import android.view.KeyEvent;

/** Compatibility aliases, exclusively for events delivered as MEDIA_BUTTON. */
final class MediaButtonKeyMapping {
    private MediaButtonKeyMapping() { }

    static int commandKeyCode(int keyCode, int metaState) {
        // Some HCN firmware sends letter codes through its media-button channel.
        // Do not apply these aliases to an Activity or accessibility key filter:
        // there they can be ordinary text input or keyboard shortcuts.
        if (keyCode == KeyEvent.KEYCODE_L) return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        if (keyCode == KeyEvent.KEYCODE_R && metaState == KeyEvent.META_SHIFT_ON) {
            return KeyEvent.KEYCODE_MEDIA_NEXT;
        }
        // The same CAN press may arrive as an OEM step/skip code on DOWN and
        // a standard media code on UP. All of these mean ONE adjacent station
        // in Radio+, including long presses. Normalize before event dedup and
        // press tracking so a mismatched edge cannot leave a second fallback.
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case 272: // OEM skip next
            case 274: // HCN K_STEP_FORWARD
                return KeyEvent.KEYCODE_MEDIA_NEXT;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case 273: // OEM skip previous
            case 275: // HCN K_STEP_BACKWARD
                return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            default:
                // In particular, plain R and ALT+R are not station-skip commands.
                return keyCode;
        }
    }
}
