package fi.radioplus.app;

import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MediaButtonKeyMappingTest {
    @Test public void hcnPresetAliasesMapOnlyInTheMediaButtonChannel() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                MediaButtonKeyMapping.commandKeyCode(KeyEvent.KEYCODE_L, 0));
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT,
                MediaButtonKeyMapping.commandKeyCode(KeyEvent.KEYCODE_R, KeyEvent.META_SHIFT_ON));
        assertFalse(RadioPlaybackService.supportsMediaKey(KeyEvent.KEYCODE_L));
        assertFalse(RadioPlaybackService.supportsMediaKey(KeyEvent.KEYCODE_R));
    }

    @Test public void plainRightAndAlternateRightAreNotSkipCommands() {
        for (int meta : new int[]{0, KeyEvent.META_ALT_ON, KeyEvent.META_CTRL_ON,
                KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON}) {
            int command = MediaButtonKeyMapping.commandKeyCode(KeyEvent.KEYCODE_R, meta);
            assertFalse(RadioPlaybackService.supportsMediaKey(command));
        }
    }

    @Test public void existingMediaAndTunerCodesKeepTheirMeaning() {
        for (int code : new int[]{KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_VOLUME_MUTE}) {
            assertEquals(code, MediaButtonKeyMapping.commandKeyCode(code, 0));
            assertTrue(RadioPlaybackService.supportsMediaKey(code));
        }
        assertFalse(RadioPlaybackService.supportsMediaKey(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(RadioPlaybackService.supportsMediaKey(KeyEvent.KEYCODE_VOLUME_DOWN));
    }

    @Test public void everyNextAliasUsesOnePressIdentity() {
        for (int code : new int[]{KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 272, 274}) {
            assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, MediaButtonKeyMapping.commandKeyCode(code, 0));
            assertTrue(RadioPlaybackService.supportsMediaKey(code));
        }
    }

    @Test public void everyPreviousAliasUsesOnePressIdentity() {
        for (int code : new int[]{KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_REWIND, 273, 275}) {
            assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, MediaButtonKeyMapping.commandKeyCode(code, 0));
            assertTrue(RadioPlaybackService.supportsMediaKey(code));
        }
    }

    @Test public void splitOemDownAndAndroidUpLeaveNoSecondFallback() {
        for (int[] aliases : new int[][]{{274, 87}, {87, 272}, {90, 87},
                {275, 88}, {88, 273}, {89, 88}}) {
            MediaKeyPressTracker tracker = new MediaKeyPressTracker();
            assertEquals(MediaKeyPressTracker.Action.SCHEDULE_SHORT, tracker.onDown(
                    MediaButtonKeyMapping.commandKeyCode(aliases[0], 0), false, 0, 100L, 100L).action);
            assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(
                    MediaButtonKeyMapping.commandKeyCode(aliases[1], 0), 160L, 100L).action);
            assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onFallback(800L, 600L).action);
            assertEquals(-1L, tracker.nextFallbackDelay(800L, 600L));
        }
    }

    @Test public void otherLettersDoNotBecomeRadioCommands() {
        for (int code : new int[]{KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_F,
                KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER}) {
            assertFalse(RadioPlaybackService.supportsMediaKey(
                    MediaButtonKeyMapping.commandKeyCode(code, KeyEvent.META_SHIFT_ON)));
        }
    }
}
