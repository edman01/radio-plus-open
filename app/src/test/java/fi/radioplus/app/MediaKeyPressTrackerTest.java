package fi.radioplus.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MediaKeyPressTrackerTest {
    private static final int NEXT = 87;
    private static final int PREVIOUS = 88;
    private static final long FALLBACK_DELAY = 600L;

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

    @Test
    public void releaseOverFiveSecondsAfterFallbackDoesNotDispatchTwice() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);

        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onFallback(700L, FALLBACK_DELAY).action);
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onUp(NEXT, 20_000L, 100L).action);
        assertEquals(-1L, tracker.nextFallbackDelay(20_000L, FALLBACK_DELAY));
    }

    @Test
    public void legacyReleaseOverFiveSecondsAfterFallbackDoesNotDispatchTwice() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L);
        tracker.onFallback(700L);

        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onUp(NEXT, 20_000L).action);
    }

    @Test
    public void copiedEventsWithChangedDeliveryTimesShareOnePressIdentity() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        assertEquals(MediaKeyPressTracker.Action.SCHEDULE_SHORT,
                tracker.onDown(NEXT, false, 0, 100L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onDown(NEXT, false, 0, 110L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onUp(NEXT, 120L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onUp(NEXT, 125L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onDown(NEXT, true, 1, 130L, 100L).action);
    }

    @Test
    public void rapidDistinctSameKeyPressesAreNotThrottled() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        for (long downTime = 100L; downTime < 105L; downTime++) {
            assertEquals(MediaKeyPressTracker.Action.SCHEDULE_SHORT,
                    tracker.onDown(NEXT, false, 0, downTime, downTime).action);
            assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                    tracker.onUp(NEXT, downTime + 1L, downTime).action);
        }
    }

    @Test
    public void overlappingKeysEachDispatchOnceInReleaseOrder() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);
        tracker.onDown(PREVIOUS, false, 0, 110L, 110L);

        MediaKeyPressTracker.Decision previous = tracker.onUp(PREVIOUS, 120L, 110L);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, previous.action);
        assertEquals(PREVIOUS, previous.keyCode);
        assertEquals(580L, tracker.nextFallbackDelay(120L, FALLBACK_DELAY));
        MediaKeyPressTracker.Decision next = tracker.onUp(NEXT, 130L, 100L);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, next.action);
        assertEquals(NEXT, next.keyCode);
        assertEquals(-1L, tracker.nextFallbackDelay(130L, FALLBACK_DELAY));
    }

    @Test
    public void unrelatedUpOnlyEventDoesNotDiscardPendingOrHeldPress() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onUp(PREVIOUS, 110L, 110L).action);
        assertEquals(590L, tracker.nextFallbackDelay(110L, FALLBACK_DELAY));
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_LONG,
                tracker.onDown(NEXT, true, 1, 500L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onUp(PREVIOUS, 510L, 510L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onUp(NEXT, 520L, 100L).action);
    }

    @Test
    public void downOnlyPressesKeepIndependentDeadlines() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);
        tracker.onDown(PREVIOUS, false, 0, 150L, 150L);
        tracker.onDown(NEXT, false, 0, 200L, 200L);

        assertEquals(500L, tracker.nextFallbackDelay(200L, FALLBACK_DELAY));
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onFallback(699L, FALLBACK_DELAY).action);
        assertEquals(NEXT, tracker.onFallback(700L, FALLBACK_DELAY).keyCode);
        assertEquals(50L, tracker.nextFallbackDelay(700L, FALLBACK_DELAY));
        assertEquals(PREVIOUS, tracker.onFallback(750L, FALLBACK_DELAY).keyCode);
        assertEquals(50L, tracker.nextFallbackDelay(750L, FALLBACK_DELAY));
        assertEquals(NEXT, tracker.onFallback(800L, FALLBACK_DELAY).keyCode);
        assertEquals(-1L, tracker.nextFallbackDelay(800L, FALLBACK_DELAY));
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onUp(NEXT, 900L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onUp(NEXT, 910L, 200L).action);
    }

    @Test
    public void longRepeatAfterFallbackCannotDispatchAgainEvenAfterOtherKey() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);
        tracker.onFallback(700L, FALLBACK_DELAY);
        tracker.onUp(PREVIOUS, 710L, 710L);

        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onDown(NEXT, true, 1, 10_000L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onUp(NEXT, 20_000L, 100L).action);
    }

    @Test
    public void upOnlyPressesUseIdentityWithoutDroppingDistinctTaps() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onUp(NEXT, 100L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onUp(NEXT, 101L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onUp(NEXT, 102L, 102L).action);
    }

    @Test
    public void upOnlyAdapterWithMissingDownTimeCanPressAgain() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onUp(NEXT, 100L, 0L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onUp(NEXT, 110L, 0L).action);
    }

    @Test
    public void cancelDiscardsOnlyItsOwnPressAndIgnoresLateRelease() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);
        tracker.onDown(PREVIOUS, false, 0, 110L, 110L);
        tracker.cancel(NEXT, 100L);

        assertEquals(590L, tracker.nextFallbackDelay(120L, FALLBACK_DELAY));
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onUp(NEXT, 130L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onUp(PREVIOUS, 140L, 110L).action);
        assertEquals(-1L, tracker.nextFallbackDelay(140L, FALLBACK_DELAY));
    }

    @Test
    public void canceledUpOnlyEventCannotBeReplayedThroughAnotherRoute() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.cancel(NEXT, 100L);
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onUp(NEXT, 110L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onUp(NEXT, 120L, 120L).action);
    }

    @Test
    public void completedHistoryDoesNotEvictAnOlderHeldPress() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);
        tracker.onFallback(700L, FALLBACK_DELAY);
        for (long downTime = 800L; downTime < 1_000L; downTime++) {
            tracker.onUp(PREVIOUS, downTime, downTime);
        }
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onUp(NEXT, 20_000L, 100L).action);
    }

    @Test
    public void rewrittenPositiveReleaseFinishesOriginalAndBothIdentitiesStayFinished() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);

        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onUp(NEXT, 150L, 150L).action);
        assertEquals(-1L, tracker.nextFallbackDelay(150L, FALLBACK_DELAY));
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onFallback(700L, FALLBACK_DELAY).action);
        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onUp(NEXT, 800L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onUp(NEXT, 20_000L, 150L).action);
    }

    @Test
    public void rewrittenReleaseAfterFallbackDoesNotDispatchAgain() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onFallback(700L, FALLBACK_DELAY).action);

        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onUp(NEXT, 750L, 750L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onUp(NEXT, 800L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onUp(NEXT, 900L, 750L).action);
    }

    @Test
    public void rewrittenHeldRepeatDispatchesOnceAndRetainsEveryRecentIdentity() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);

        assertEquals(MediaKeyPressTracker.Action.DISPATCH_LONG,
                tracker.onDown(NEXT, true, 1, 500L, 500L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onDown(NEXT, true, 2, 600L, 600L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onUp(NEXT, 700L, 700L).action);
        for (long identity : new long[]{100L, 500L, 600L, 700L}) {
            assertEquals(MediaKeyPressTracker.Action.NONE,
                    tracker.onUp(NEXT, 20_000L, identity).action);
        }
    }

    @Test
    public void rewrittenHeldRepeatAfterFallbackCannotDispatchAgain() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);
        tracker.onFallback(700L, FALLBACK_DELAY);

        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onDown(NEXT, true, 1, 800L, 800L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onUp(NEXT, 900L, 800L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onUp(NEXT, 1000L, 100L).action);
    }

    @Test
    public void exactIdentityWinsWhenTwoSameKeyPressesAreOpen() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);
        tracker.onDown(NEXT, false, 0, 120L, 120L);

        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(NEXT, 140L, 120L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(NEXT, 150L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onFallback(800L, FALLBACK_DELAY).action);
    }

    @Test
    public void ambiguousRewrittenReleaseDoesNotConsumeEitherOpenPress() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);
        tracker.onDown(NEXT, false, 0, 120L, 120L);

        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(NEXT, 140L, 140L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(NEXT, 150L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(NEXT, 160L, 120L).action);
    }

    @Test
    public void ambiguousRewrittenRepeatDoesNotConsumeEitherOpenPress() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);
        tracker.onDown(NEXT, false, 0, 120L, 120L);

        assertEquals(MediaKeyPressTracker.Action.DISPATCH_LONG,
                tracker.onDown(NEXT, true, 1, 140L, 140L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(NEXT, 150L, 100L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(NEXT, 160L, 120L).action);
    }

    @Test
    public void exactTwoSecondBoundaryCanCorrelateRewrittenIdentity() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);

        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(NEXT, 2100L, 2100L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onFallback(2101L, FALLBACK_DELAY).action);
    }

    @Test
    public void outdatedOpenPressDoesNotSwallowAnUpOnlyInput() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);
        tracker.onFallback(700L, FALLBACK_DELAY);

        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(NEXT, 2101L, 200L).action);
        assertEquals(MediaKeyPressTracker.Action.NONE, tracker.onUp(NEXT, 2200L, 100L).action);
    }

    @Test
    public void wildlyRewrittenTimestampCannotMatchEvenWhenDeliveryIsRecent() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);

        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(NEXT, 200L, 2101L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onFallback(700L, FALLBACK_DELAY).action);
    }

    @Test
    public void reversedReceiptOrIdentityTimeCannotBeInferred() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 100L);

        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(NEXT, 99L, 150L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(NEXT, 200L, 99L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onFallback(700L, FALLBACK_DELAY).action);
    }

    @Test
    public void positiveReleaseDoesNotInferAnUnknownOriginalIdentity() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        tracker.onDown(NEXT, false, 0, 100L, 0L);

        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT, tracker.onUp(NEXT, 150L, 150L).action);
        assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                tracker.onFallback(700L, FALLBACK_DELAY).action);
    }

    @Test
    public void rewrittenReleasesDoNotThrottleRapidFreshPressesOrReversals() {
        MediaKeyPressTracker tracker = new MediaKeyPressTracker();
        int[] keys = {NEXT, NEXT, PREVIOUS};
        for (int index = 0; index < keys.length; index++) {
            long time = 100L + index * 10L;
            assertEquals(MediaKeyPressTracker.Action.SCHEDULE_SHORT,
                    tracker.onDown(keys[index], false, 0, time, time).action);
            assertEquals(MediaKeyPressTracker.Action.DISPATCH_SHORT,
                    tracker.onUp(keys[index], time + 1L, time + 1L).action);
        }
        assertEquals(MediaKeyPressTracker.Action.NONE,
                tracker.onFallback(800L, FALLBACK_DELAY).action);
    }
}
