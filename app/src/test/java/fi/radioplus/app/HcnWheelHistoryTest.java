package fi.radioplus.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class HcnWheelHistoryTest {
    private final HcnWheelHistory history = new HcnWheelHistory();

    @Test public void capturedDownSurvivesABurstOfDifferentStockStations() {
        history.record(0, 98100, 1000, false);
        history.pinBeforeDown(1100, 1101);
        for (int i = 0; i < 80; i++) history.record(0, 99500 + 100 * i, 1102 + i, false);
        history.pinBeforeDown(1100, 1190); // same press delivered a second way
        assertEquals(98100, history.beforePress(1100, 1200).frequency);
    }

    @Test public void repeatedNativeReportsCannotEraseTheOriginOfAnUpOnlyKey() {
        history.record(0, 98100, 1000, false);
        for (int i = 0; i < 100; i++) history.record(0, 103100, 1100 + i, false);
        assertEquals(98100, history.beforePress(1100, 1250).frequency);
        assertEquals(103100, history.beforePress(1201, 1250).frequency);
    }

    @Test public void gapInTheSameFrequencyDoesNotInventARecentPrePressOrigin() {
        history.record(0, 98100, 1, false);
        history.record(0, 98100, 7000, false);
        assertNull(history.beforePress(6999, 7005));
        assertFalse(history.record(0, 103100, 6000, false)); // stale observer callback
        assertEquals(98100, history.beforePress(7005, 7010).frequency);
    }

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

    @Test public void intervalWithOnlyPostDownReadingsCannotJustifyAnotherNativeStep() {
        history.record(0, 98100, 1100, false);
        history.record(0, 103100, 1150, false);
        assertNull(history.beforePress(1050, 1200));
        assertTrue(history.observedMoveDuring(1050, 1200));
        assertFalse(history.observedMoveDuring(1151, 1200));
        assertFalse(history.observedMoveDuring(0, 1200));
        assertFalse(history.observedMoveDuring(1050, 7000));
    }

    @Test public void aRacingObservationDoesNotEraseAPriorRealTuneForAQueuedKey() {
        history.record(0, 98100, 1000, false);
        history.record(0, 103100, 1170, false);
        history.record(0, 99900, 1165, true);
        assertEquals(99900, history.beforePress(1150, 1200).frequency);
        assertEquals(103100, history.beforePress(1180, 1200).frequency);
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
