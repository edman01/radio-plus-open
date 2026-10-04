package fi.radioplus.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MediaKeyPressTrackerTest {
    private static final int NEXT = 87;

    @Test public void lateRepeatAfterFallbackCannotToggleMuteTwice() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(164, false, 0, 100L);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onFallback(800L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onDown(164, true, 1, 900L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onUp(164, 1200L).action);
    }

    @Test
    public void normalDownUpDispatchesExactlyOneShortAction() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();

        assertEquals(
                MediaKeyPressTracker.Action.SCHEDULE_SHORT,
                tracker.onDown(NEXT, false, 0, 100L).action
        );
        assertEquals(
                MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onUp(NEXT, 250L).action
        );
    }

    @Test
    public void delayedUpAfterFallbackDoesNotDispatchTwice() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L);

        assertEquals(
                MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onFallback(700L).action
        );
        assertEquals(
                MediaKeyPressTracker.Action.NONE,
                tracker.onUp(NEXT, 900L).action
        );
    }

    @Test
    public void actionUpOnlyAdapterStillDispatchesShortAction() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();

        assertEquals(
                MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onUp(NEXT, 100L).action
        );
    }

    @Test
    public void longPressCancelsShortAndDispatchesOnlyOnce() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L);

        assertEquals(
                MediaKeyPressTracker.Action.DISPATCH_LONG,
                tracker.onDown(NEXT, true, 1, 600L).action
        );
        assertEquals(
                MediaKeyPressTracker.Action.NONE,
                tracker.onDown(NEXT, true, 2, 700L).action
        );
        assertEquals(
                MediaKeyPressTracker.Action.NONE,
                tracker.onUp(NEXT, 800L).action
        );
    }
}
