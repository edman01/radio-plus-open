package fi.radioplus.app;

import android.media.session.MediaSession;
import android.os.Build;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

/** Only launcher-originated preset requests; never consume system/CAN media keys. */
final class AgamaLauncherControls {
    static final String AGAMA = "altergames.carlauncher";
    private AgamaLauncherControls() { }

    static boolean isUntimedArrow(KeyEvent key) {
        return key != null && key.getDownTime() == 0 && key.getEventTime() == 0
                && (key.getKeyCode() == KeyEvent.KEYCODE_MEDIA_NEXT
                || key.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    static String caller(MediaSession session) {
        try {
            if (Build.VERSION.SDK_INT >= 28) return session.getCurrentControllerInfo().getPackageName();
            // Oreo's framework only exposes the last calling package, not a per-event
            // RemoteUserInfo. Use it only in a callback, with the virtual-key check below.
            if (Build.VERSION.SDK_INT == 27) {
                Object name = MediaSession.class.getMethod("getCallingPackage").invoke(session);
                if (name instanceof String) return (String) name;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Missing on some vendor ROMs. Never substitute event shape for caller identity.
        }
        return null;
    }

    static boolean accepts(String caller, KeyEvent key) {
        if (!AGAMA.equals(caller) || key == null || key.getDeviceId() != KeyCharacterMap.VIRTUAL_KEYBOARD
                || key.getScanCode() != 0 || key.getRepeatCount() != 0 || key.isCanceled()
                || (key.getAction() != KeyEvent.ACTION_DOWN && key.getAction() != KeyEvent.ACTION_UP)
                || (key.getKeyCode() != KeyEvent.KEYCODE_MEDIA_NEXT
                && key.getKeyCode() != KeyEvent.KEYCODE_MEDIA_PREVIOUS)) return false;
        // AGAMA 6.1.3 uses KeyEvent(action, code). On Oreo, reject even virtual
        // events carrying physical press timestamps, in case last caller has raced.
        return Build.VERSION.SDK_INT >= 28 || (key.getDownTime() == 0 && key.getEventTime() == 0);
    }
}
