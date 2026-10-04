package fi.radioplus.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public final class RadioMediaKeyMappingTest {
    @Test
    public void acceptsAndroidAndJunsunStationKeys() {
        assertTrue(RadioPlaybackService.supportsMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertTrue(RadioPlaybackService.supportsMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        assertTrue(RadioPlaybackService.supportsMediaKey(274));
        assertTrue(RadioPlaybackService.supportsMediaKey(275));
        assertTrue(RadioPlaybackService.supportsMediaKey(272));
        assertTrue(RadioPlaybackService.supportsMediaKey(273));
        assertTrue(RadioPlaybackService.supportsMediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD));
        assertTrue(RadioPlaybackService.supportsMediaKey(KeyEvent.KEYCODE_MEDIA_REWIND));
        assertFalse(RadioPlaybackService.supportsMediaKey(KeyEvent.KEYCODE_VOLUME_UP));
    }

    @Test
    public void mapsJunsunTunerKeysInTheSameDirectionAsMediaKeys() {
        assertTrue(RadioPlaybackService.isNextMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertTrue(RadioPlaybackService.isNextMediaKey(274));
        assertTrue(RadioPlaybackService.isNextMediaKey(272));
        assertTrue(RadioPlaybackService.isNextMediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD));
        assertTrue(RadioPlaybackService.isPreviousMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        assertTrue(RadioPlaybackService.isPreviousMediaKey(275));
        assertTrue(RadioPlaybackService.isPreviousMediaKey(273));
        assertTrue(RadioPlaybackService.isPreviousMediaKey(KeyEvent.KEYCODE_MEDIA_REWIND));
        assertFalse(RadioPlaybackService.isNextMediaKey(275));
        assertFalse(RadioPlaybackService.isPreviousMediaKey(274));
    }
}
