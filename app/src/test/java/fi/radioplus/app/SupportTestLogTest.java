package fi.radioplus.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class SupportTestLogTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void logSurvivesReopeningAndPreservesTechnicalNumbers() {
        SupportTestLog log = new SupportTestLog(temporary.getRoot());
        log.append(SupportTestLog.Event.TUNE_REQUEST, 0, 101700);
        String recovered = new SupportTestLog(temporary.getRoot()).read();
        assertTrue(recovered.contains("TUNE_REQUEST a=0 b=101700"));
    }
    @Test public void rotationBoundsStorageAndRetainsNewestEntries() {
        SupportTestLog log = new SupportTestLog(temporary.getRoot());
        for (int i = 0; i < 10000; i++) log.append(SupportTestLog.Event.SOURCE, i, 0);
        long length = 0;
        for (java.io.File file : temporary.getRoot().listFiles()) length += file.length();
        assertTrue(length < 132000);
        assertTrue(log.read().contains("SOURCE a=9999 b=0"));
        assertFalse(log.read().contains("SOURCE a=0 b=0"));
    }
    @Test public void crashLogOmitsExceptionMessagesPathsAndThreadNames() {
        SupportTestLog log = new SupportTestLog(temporary.getRoot());
        RuntimeException failure = new RuntimeException("private station name /storage/private/photo.jpg token=SECRET");
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("fi.radioplus.app.MainActivity", "onCreate", "private-file", 123)});
        log.crash(failure);
        String text = log.read();
        assertTrue(text.contains("RuntimeException")); assertTrue(text.contains("MainActivity.onCreate:123"));
        assertFalse(text.contains("SECRET")); assertFalse(text.contains("private")); assertFalse(text.contains("station"));
        String errorLine = SupportTestLog.formatFailure(SupportTestLog.Event.ENDPOINT_ERROR, failure);
        assertTrue(errorLine.contains("RuntimeException")); assertTrue(errorLine.contains("MainActivity.onCreate:123"));
        assertFalse(errorLine.contains("SECRET")); assertFalse(errorLine.contains("private"));
    }
    @Test public void suggestionsOnlyDescribeRecordedProblemsAndNeverClaimAudioSuccess() {
        SupportTestLog log = new SupportTestLog(temporary.getRoot());
        log.writeProblem(SupportTestLog.Problem.STOCK_NOT_OWNER);
        String report = SupportTestLog.interpretation(log.read());
        assertTrue(report.contains("Start stock FM"));
        assertTrue(report.contains("not proof of speaker sound"));
        assertFalse(report.contains("Send all three installed APKs"));
    }
}
