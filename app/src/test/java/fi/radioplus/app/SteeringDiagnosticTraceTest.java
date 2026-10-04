package fi.radioplus.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.Test;

import static org.junit.Assert.*;

public final class SteeringDiagnosticTraceTest {
    private final AtomicLong now = new AtomicLong(1_000L);
    private final SteeringDiagnosticTrace trace = new SteeringDiagnosticTrace(now::get);

    @Test public void inactiveTraceNeverRecordsAndReportsZeroCounts() {
        String inactive = trace.report();
        trace.key("receiver", 87, 0, 0, 0);
        trace.event("service", "connected");
        trace.stop();
        now.set(90_000L);
        assertFalse(trace.isRecording());
        assertEquals(0, trace.remainingSeconds());
        assertEquals(inactive, trace.report());
        assertTrue(inactive.contains("status=inactive elapsed_ms=0 remaining_s=0"));
        assertTrue(inactive.contains("keys=0 events=0 total=0 retained=0 dropped=0"));
        assertTrue(rows(inactive).isEmpty());
    }

    @Test public void beginStartsThirtySecondsWithoutAddingSyntheticEvents() {
        trace.begin();
        assertTrue(trace.isRecording());
        assertEquals(30, trace.remainingSeconds());
        assertTrue(trace.report().contains("status=recording elapsed_ms=0 remaining_s=30"));
        assertTrue(trace.report().contains("keys=0 events=0 total=0 retained=0 dropped=0"));
    }

    @Test public void remainingSecondsRoundsUpUntilTheExactDeadline() {
        trace.begin();
        long[] elapsed = {0L, 1L, 999L, 1_000L, 29_000L, 29_999L, 30_000L};
        int[] expected = {30, 30, 30, 29, 1, 1, 0};
        for (int index = 0; index < elapsed.length; index++) {
            now.set(1_000L + elapsed[index]);
            assertEquals("Remaining time at " + elapsed[index] + " ms",
                    expected[index], trace.remainingSeconds());
        }
        assertFalse(trace.isRecording());
        assertTrue(trace.report().contains("status=expired elapsed_ms=30000 remaining_s=0"));
    }

    @Test public void deadlineAcceptsTheLastMillisecondButRejectsTheBoundary() {
        trace.begin();
        now.set(30_999L);
        trace.key("receiver", 46, 0, 1, 0);
        now.set(31_000L);
        trace.key("receiver", 46, 1, 1, 0);
        trace.event("service", "too-late");
        assertFalse(trace.isRecording());
        String report = trace.report();
        assertTrue(report.contains("keys=1 events=0 total=1 retained=1 dropped=0"));
        assertEquals("+29999ms KEY source=receiver code=46 action=0 meta=1 repeat=0",
                rows(report).get(0));
        now.set(1_100L);
        trace.event("service", "clock-rolled-back");
        assertEquals("An expired capture cannot be revived by clock rollback", report, trace.report());
    }

    @Test public void everyObservingOrRecordingOperationEnforcesTimeout() {
        List<Consumer<SteeringDiagnosticTrace>> operations = Arrays.asList(
                value -> assertFalse(value.isRecording()),
                value -> assertEquals(0, value.remainingSeconds()),
                value -> assertTrue(value.report().contains("status=expired")),
                SteeringDiagnosticTrace::stop,
                value -> value.key("receiver", 87, 0, 0, 0),
                value -> value.event("service", "after-timeout"));
        for (Consumer<SteeringDiagnosticTrace> operation : operations) {
            AtomicLong clock = new AtomicLong(10L);
            SteeringDiagnosticTrace value = new SteeringDiagnosticTrace(clock::get);
            value.begin();
            value.event("service", "before-timeout");
            clock.set(30_010L);
            operation.accept(value);
            assertTrue(value.report().contains("status=expired elapsed_ms=30000 remaining_s=0"));
            assertTrue(value.report().contains("keys=0 events=1 total=1 retained=1 dropped=0"));
        }
    }

    @Test public void stopFreezesTheReportAndIgnoresLaterRecords() {
        trace.begin();
        trace.event("service", "connected");
        now.set(1_245L);
        trace.stop();
        String stopped = trace.report();
        assertTrue(stopped.contains("status=stopped elapsed_ms=245 remaining_s=0"));
        now.set(99_999L);
        trace.key("receiver", 87, 0, 0, 0);
        trace.event("service", "ignored");
        trace.stop();
        assertFalse(trace.isRecording());
        assertEquals(0, trace.remainingSeconds());
        assertEquals(stopped, trace.report());
    }

    @Test public void beginResetsActiveStoppedAndExpiredSessions() {
        for (int lifecycle = 0; lifecycle < 3; lifecycle++) {
            trace.begin();
            trace.key("old-source", 88, 1, 2, 3);
            trace.event("old-source", "old-event");
            if (lifecycle == 1) trace.stop();
            if (lifecycle == 2) {
                now.addAndGet(30_000L);
                assertFalse(trace.isRecording());
            }
            now.addAndGet(123L);
            trace.begin();
            assertTrue(trace.isRecording());
            assertEquals(30, trace.remainingSeconds());
            String report = trace.report();
            assertTrue(report.contains("status=recording elapsed_ms=0 remaining_s=30"));
            assertTrue(report.contains("keys=0 events=0 total=0 retained=0 dropped=0"));
            assertFalse(report.contains("old-source"));
        }
    }

    @Test public void clearDeletesRecordsAndCountersAndDisablesCapture() {
        String empty = trace.report();
        trace.begin();
        trace.key("receiver", 46, 0, 1, 0);
        trace.event("service", "connected");
        trace.clear();
        now.set(2_000L);
        trace.event("service", "ignored-after-clear");
        assertFalse(trace.isRecording());
        assertEquals(empty, trace.report());
        trace.clear();
        assertEquals(empty, trace.report());
        trace.begin();
        trace.event("fresh", "new-capture");
        assertEquals("+0ms EVENT source=fresh detail=new-capture", rows(trace.report()).get(0));
    }

    @Test public void keyRecordsRawNumericMetadataWithoutInterpretingIt() {
        trace.begin();
        now.set(1_017L);
        trace.key("media-session", 46, 1, 2, 3);
        trace.key("receiver", Integer.MIN_VALUE, Integer.MAX_VALUE, -1, Integer.MAX_VALUE);
        List<String> rows = rows(trace.report());
        assertEquals("+17ms KEY source=media-session code=46 action=1 meta=2 repeat=3", rows.get(0));
        assertTrue(rows.get(1).contains("code=-2147483648 action=2147483647 meta=-1 repeat=2147483647"));
        assertTrue(trace.report().contains("keys=2 events=0 total=2 retained=2 dropped=0"));
    }

    @Test public void fullKeyMetadataCountsOneEventAndPreservesRawIdentityFields() {
        trace.begin();
        now.set(1_025L);
        trace.key("dialog", 46, 1, 1, 0, 250, 128, 257, 1_234_567_890L, 1_234_567_999L);
        String report = trace.report();
        assertEquals(1, rows(report).size());
        assertTrue(report.contains("keys=1 events=0 total=1 retained=1 dropped=0"));
        assertEquals("+25ms KEY source=dialog code=46 action=1 meta=1 repeat=0"
                + " scan=250 flags=128 input=257 down=1234567890 time=1234567999",
                rows(report).get(0));
        trace.stop();
        String stopped = trace.report();
        trace.key("dialog", 46, 0, 1, 0, 250, 128, 257, 1L, 2L);
        assertEquals(stopped, trace.report());
    }

    @Test public void rolloverRetainsOnlyTheNewestFortyInChronologicalOrder() {
        trace.begin();
        for (int index = 0; index < 65; index++) {
            now.set(1_000L + index);
            if (index % 2 == 0) trace.key("receiver", 1_000 + index, 0, 0, 0);
            else trace.event("route", "step-" + index);
        }
        String report = trace.report();
        List<String> rows = rows(report);
        assertEquals(40, rows.size());
        assertTrue(report.contains("keys=33 events=32 total=65 retained=40 dropped=25"));
        for (int index = 0; index < rows.size(); index++) {
            assertTrue("Preserve arrival order after eviction", rows.get(index).startsWith(
                    "+" + (25 + index) + "ms "));
        }
        assertFalse(report.contains("code=1000 "));
        assertTrue(report.contains("code=1064 "));
    }

    @Test public void clockRollbackCannotReverseTimestampsOrExtendRemainingTime() {
        trace.begin();
        now.set(5_000L);
        trace.event("service", "first");
        assertEquals(26, trace.remainingSeconds());
        now.set(2_000L);
        trace.event("service", "clock-regressed");
        assertEquals(26, trace.remainingSeconds());
        assertTrue(rows(trace.report()).get(1).startsWith("+4000ms "));
        now.set(6_000L);
        trace.event("service", "clock-recovered");
        assertTrue(rows(trace.report()).get(2).startsWith("+5000ms "));
        assertEquals(25, trace.remainingSeconds());
    }

    @Test public void elapsedSubtractionHandlesClockValueRollover() {
        now.set(Long.MAX_VALUE - 10L);
        trace.begin();
        now.addAndGet(25L);
        trace.event("clock", "wrapped");
        assertEquals("+25ms EVENT source=clock detail=wrapped", rows(trace.report()).get(0));
        now.addAndGet(29_975L);
        assertFalse(trace.isRecording());
        assertTrue(trace.report().contains("elapsed_ms=30000"));
    }

    @Test public void completeLinesAreBoundedAndCannotContainInjectedControlLines() {
        trace.begin();
        char[] characters = new char[2_000];
        Arrays.fill(characters, 'x');
        String oversized = new String(characters);
        trace.event("receiver\n\t\u202e" + oversized,
                "detail\r\n\u0000\u2028\u2029" + oversized);
        trace.key(oversized, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE);
        trace.event(null, null);
        String report = trace.report();
        assertEquals(3, rows(report).size());
        assertEquals(6, report.split("\n", -1).length);
        for (String line : report.split("\n")) {
            assertTrue("Bound the complete line, including timestamp and metadata", line.length() <= 160);
            for (int index = 0; index < line.length(); index++) {
                assertTrue("Keep technical reports in printable ASCII",
                        line.charAt(index) >= 32 && line.charAt(index) <= 126);
            }
        }
        assertEquals("+0ms EVENT source=- detail=-", rows(report).get(2));
        assertTrue(report.length() < 7_000);
    }

    @Test public void concurrentWritersKeepExactTotalsAndTheHardRetentionBound() throws Exception {
        trace.begin();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                writes.add(executor.submit(() -> {
                    for (int index = 0; index < 100; index++) {
                        trace.key("receiver", 87, 0, 0, 0);
                        trace.event("service", "accepted");
                        assertTrue(rows(trace.report()).size() <= 40);
                    }
                }));
            }
            for (Future<?> write : writes) write.get(5L, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        trace.stop();
        String report = trace.report();
        assertTrue(report.contains("keys=400 events=400 total=800 retained=40 dropped=760"));
        assertEquals(40, rows(report).size());
        assertTrue(report.length() < 7_000);
    }

    private static List<String> rows(String report) {
        List<String> result = new ArrayList<>();
        for (String line : report.split("\n")) {
            if (line.startsWith("+")) result.add(line);
        }
        return result;
    }
}
