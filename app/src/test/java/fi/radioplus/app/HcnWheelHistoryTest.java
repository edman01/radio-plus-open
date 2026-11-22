package fi.radioplus.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class HcnWheelHistoryTest {
    private final HcnWheelHistory history = new HcnWheelHistory();

    @Test public void nativeReadAtOrAfterDownDoesNotMoveTheStartingPoint() {
        history.record(0, 93400, 1000, true);
        history.record(1, 92100, 1100, false); // real independent tune before the key
        history.record(1, 102300, 1200, false);
        history.record(1, 107900, 1201, false);
        HcnWheelHistory.Point p = history.beforePress(1200, 1250);
        assertEquals(1, p.band);
        assertEquals(92100, p.frequency);
    }

    @Test public void independentQueuedPressUsesThePreviousConfirmedSelection() {
        history.record(0, 93400, 1000, false);
        history.record(3, 999, 1175, true);
        history.record(0, 94800, 1180, false);
        assertEquals(999, history.beforePress(1110, 1200).frequency);
        assertEquals(94800, history.beforePress(1190, 1200).frequency);
    }

    @Test public void missingStaleOrIncompatibleClocksFallBackToLiveRadio() {
        assertNull(history.beforePress(100, 120));
        history.record(3, 999, 200, true);
        history.record(0, 123, 4990, false);
        assertNull(history.beforePress(5000, 5050));
        assertNull(history.beforePress(201, 5000));
        assertNull(history.beforePress(300, 290));
        assertNull(history.beforePress(0, 300));
    }

    @Test public void originalDownSurvivesRewrittenUpAndDownOnlyFallback() {
        MediaKeyPressTracker keys = new MediaKeyPressTracker();
        keys.onDown(87, false, 0, 9000, 100);
        assertEquals(100, keys.onUp(87, 9020, 120).downTime);
        keys.onDown(88, false, 0, 9100, 200);
        assertEquals(200, keys.onFallback(9900, 500).downTime);
        assertEquals(500, keys.onUp(87, 9950, 500).downTime);
    }
}
