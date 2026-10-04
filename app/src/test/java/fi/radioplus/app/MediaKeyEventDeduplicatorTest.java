package fi.radioplus.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MediaKeyEventDeduplicatorTest {
    @Test public void sameEventFromTwoRoutesIsHandledOnce() {
        MediaKeyEventDeduplicator events = new MediaKeyEventDeduplicator();
        assertTrue(events.accept(164, 1, 100, 150, 0));
        assertFalse(events.accept(164, 1, 100, 150, 0));
    }
    @Test public void downUpAndRapidDistinctPressesAreNotDropped() {
        MediaKeyEventDeduplicator events = new MediaKeyEventDeduplicator();
        assertTrue(events.accept(87, 0, 100, 100, 0));
        assertTrue(events.accept(87, 1, 100, 110, 0));
        assertTrue(events.accept(87, 0, 120, 120, 0));
        assertTrue(events.accept(87, 1, 120, 130, 0));
        assertTrue(events.accept(88, 1, 120, 130, 0));
    }
    @Test public void repeatCanBeObservedOnceAndCacheIsBounded() {
        MediaKeyEventDeduplicator events = new MediaKeyEventDeduplicator();
        assertTrue(events.accept(87, 0, 100, 100, 0));
        assertTrue(events.accept(87, 0, 100, 100, 1));
        assertFalse(events.accept(87, 0, 100, 100, 1));
        for (int i = 0; i < 16; i++) assertTrue(events.accept(87, 1, 200 + i, 200 + i, 0));
        assertTrue(events.accept(87, 0, 100, 100, 0));
    }
    @Test public void muteUpOnlyAndHeldPressEachDispatchOnce() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(164, 100).action);
        assertEquals(MediaKeyPressTracker.Action.SCHEDULE_SHORT, tracker.onDown(164, false, 0, 500).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_LONG, tracker.onDown(164, true, 1, 1100).action);
        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onDown(164, true, 2, 1200).action);
        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onUp(164, 1300).action);
    }
}
