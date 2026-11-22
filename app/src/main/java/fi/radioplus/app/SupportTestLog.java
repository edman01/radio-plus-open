package fi.radioplus.app;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Opt-in test-build log: bounded private files, fixed labels/numbers, manual export only. */
final class SupportTestLog {
    enum Event { START, DETECTED, CONNECTED, CAPABILITIES, BIND_ERROR, SOURCE, TUNE_REQUEST, TUNE_CONFIRMED,
        SCAN_REQUEST, SCAN_RESULTS, COMMAND_REJECTED, ENDPOINT_ERROR, OWNER_STABLE, ROUTE, MIX_FLAGS, ROUTE_ACTION,
        BAND_RECALL, TUNE_READBACK, LAUNCHER_PRESET, SESSION_ARROW, WHEEL_LISTENER, WHEEL_OBSERVED, WHEEL_ARROW }
    enum Problem {
        STOCK_APK_UNKNOWN("The installed stock APK is not recognized. Send the stock radio/services APKs from this same firmware; do not replace them."),
        STOCK_COMBINATION("The three Reglink APKs do not match an inspected set. Send all three installed APKs from the same unit."),
        TEST_BUILD_REQUIRED("Reglink control is opt-in. Use the test APK for your exact firmware, not the normal community build."),
        TUNER_NOT_SUPPORTED("The selected tuner is not the supported external MCU implementation. Send the radio-module information without changing factory settings."),
        HARDWARE_NOT_READY("Runtime hardware fields are missing or unstable. Start the stock radio, wait, reopen Radio+ and retry once."),
        STOCK_NOT_OWNER("The stock radio does not own an unmixed radio route. Start stock FM, press Home, leave it running, then open Radio+."),
        GLOBAL_MUTE("The firmware reports global mute. Check the stock radio and other audio/phone activity. Radio+ will not force global unmute."),
        SOURCE_READ_FAILED("The audio owner/source could not be read safely. Send this log and the installed stock-service APK; do not force a source change."),
        TUNE_UNCONFIRMED("The requested frequency was not confirmed. Check stock tuning on the same band and report the result; Radio+ did not resend the command."),
        SCAN_UNCONFIRMED("Scan completion/results were not confirmed. Check stock scanning and report whether it completes and finds stations."),
        SOURCE_CHANGED_DURING_SCAN("The source changed during scanning; results were discarded. Restart Radio+ before scanning again and leave stock radio running."),
        SERVICE_ACCESS_DENIED("The firmware denied service access. Send this log and the installed service APK; do not root or replace firmware just to bypass it."),
        ENDPOINT_FAILED("A service/protocol call failed. Send this log and confirm whether the stock radio still works; a device-side service log may be needed.");
        final String nextCheck;
        Problem(String check) { nextCheck = check; }
    }
    private static final int LIMIT = 64 * 1024;
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), task -> { Thread t = new Thread(task, "RadioSupportLog"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private static volatile SupportTestLog instance;
    private final File current;
    private final File previous;
    private final long started = System.nanoTime();

    SupportTestLog(File directory) {
        current = new File(directory, "radio-support-test.log");
        previous = new File(directory, "radio-support-test.previous.log");
    }

    static synchronized void init(Context context) {
        if (!BuildConfig.REGLINK_STOCK_ASSISTED || instance != null) return;
        instance = new SupportTestLog(context.getApplicationContext().getFilesDir());
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            try { instance.crash(failure); } catch (Throwable ignored) { }
            finally {
                if (original != null) original.uncaughtException(thread, failure);
                else android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
        event(Event.START, BuildConfig.VERSION_CODE, android.os.Build.VERSION.SDK_INT);
    }

    static void event(Event event) { event(event, 0, 0); }
    static void event(Event event, int a, int b) {
        SupportTestLog log = instance;
        if (log != null) WRITER.execute(() -> log.append(event, a, b));
    }

    static void problem(Problem problem) {
        SupportTestLog log = instance;
        if (log != null) WRITER.execute(() -> log.writeProblem(problem));
    }

    static void failure(Event event, Throwable error) {
        SupportTestLog log = instance;
        // Format now; never retain an arbitrary Throwable on the writer queue.
        if (log != null) { String technical = formatFailure(event, error); WRITER.execute(() -> log.writeTechnical(technical)); }
    }

    static String formatFailure(Event event, Throwable error) {
        String type = error.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_$]", "");
        String location = "";
        for (StackTraceElement frame : error.getStackTrace()) {
            if (frame.getClassName().startsWith("fi.radioplus.app.")) {
                location = (frame.getClassName() + "." + frame.getMethodName()).replaceAll("[^A-Za-z0-9_.$]", "") + ":" + frame.getLineNumber();
                break;
            }
        }
        return "ERROR " + event.name() + " type=" + type + " at=" + location;
    }

    static void apk(String packageName, String sha256) {
        if (!"com.reglink.services".equals(packageName) && !"com.reglink.apps.radio".equals(packageName)
                && !"com.reglink.apps.mtkradio".equals(packageName)) return;
        if (sha256 == null || !sha256.matches("[a-fA-F0-9]{64}")) return;
        SupportTestLog log = instance;
        if (log != null) WRITER.execute(() -> log.writeApk(packageName, sha256));
    }

    static String exportSnapshot() {
        try { WRITER.submit(() -> {}).get(2, TimeUnit.SECONDS); } catch (Exception ignored) { }
        SupportTestLog log = instance;
        String body = log == null ? "Logging not initialized.\n" : log.read();
        return header() + interpretation(body) + body;
    }

    static String interpretation(String body) {
        StringBuilder result = new StringBuilder("Suggested next checks (not an automatic diagnosis):\n");
        boolean found = false;
        for (Problem problem : Problem.values()) {
            if (body.contains("PROBLEM " + problem.name() + "\n")) {
                found = true; result.append("- ").append(problem.name()).append(": ").append(problem.nextCheck).append('\n');
            }
        }
        // Interpret only the most recent listener visit; an old accepted press
        // is not evidence that the latest car/route delivered one.
        int start = Math.max(body.lastIndexOf(" START "), body.lastIndexOf(" WHEEL_LISTENER "));
        if (start >= 0 && body.startsWith(" WHEEL_LISTENER a=1 ", start)) {
            String visit = body.substring(start);
            if (visit.contains(" WHEEL_ARROW a=65 b=1\n") || visit.contains(" WHEEL_ARROW a=66 b=1\n")) {
                result.append("- Radio+ received a wheel arrow and queued a list change. Check tuner readback and what you heard.\n");
            } else if (visit.contains(" WHEEL_ARROW a=65 b=0\n") || visit.contains(" WHEEL_ARROW a=66 b=0\n")) {
                result.append("- An arrow reached Radio+ but was passed back to the firmware. Check that the stock radio was playing and the main Radio+ screen was focused.\n");
            } else if (visit.contains(" WHEEL_OBSERVED a=65 ") || visit.contains(" WHEEL_OBSERVED a=66 ")) {
                result.append("- The firmware announced a previous/next key, but Radio+ did not record handling it. Another handler may have taken it first.\n");
            } else {
                result.append("- No wheel arrow recorded for this visit. That does not prove the button was not pressed.\n");
            }
            found = true;
        }
        if (!found) result.append("- No classified failure was retained. Describe what happened and whether sound was audible.\n");
        result.append("Confirmed source/frequency is not proof of speaker sound. Suggestions may need further device testing.\n\nEvents:\n");
        return result.toString();
    }

    private static String header() {
        StringBuilder profiles = new StringBuilder();
        for (RadioBackendProfile profile : RadioBackendProfile.values()) {
            profiles.append(profile.ordinal()).append('=').append(profile.name()).append(' ');
        }
        return "Radio+ device-test support log\nVersion: " + BuildConfig.VERSION_NAME
                + " code=" + BuildConfig.VERSION_CODE + "\n"
                + "No device identifiers, names, location, user text, logos, URIs or Android logcat.\n"
                + "TUNE_REQUEST/CONFIRMED: a=band, b=kHz. SOURCE: a=accepted stock route, b=global mute.\n"
                + "BAND_RECALL: target band/kHz. TUNE_READBACK: last observed band/kHz after timeout. "
                + "LAUNCHER_PRESET: Android key code (87 next, 88 previous).\n"
                + "SESSION_ARROW: key and mask (1 AGAMA, 2 virtual keyboard, 4/8 zero down/event time, 16 active).\n"
                + "WHEEL_LISTENER: 0=default, 1=fallback registered, 2=opted in. WHEEL_OBSERVED: 0/1=observer ready; "
                + "65/66=firmware previous/next notice, not proof Radio+ was offered the key. "
                + "WHEEL_ARROW: 65/66, b=queued by Radio+ (1) or passed through (0). "
                + "No notice is inconclusive; some CAN paths bypass the key dispatcher.\n"
                + "OWNER_STABLE: a=stock owner on both reads, b=matching routes. ROUTE: platform/model. "
                + "MIX_FLAGS: mix/flags. ROUTE_ACTION: a=action. Missing route=-1.\n"
                + "DETECTED/CONNECTED: a=profile enum ordinal. START: a=version code, b=SDK.\n"
                + "CAPABILITIES: a=kind (0 unknown,1 native FM,2 external FM/AM), b=module.\n"
                + "Profiles: " + profiles + "\n\n";
    }

    synchronized void append(Event event, int a, int b) {
        write("+" + (System.nanoTime() - started) / 1_000_000L + "ms " + event.name() + " a=" + a + " b=" + b);
    }
    synchronized void writeProblem(Problem problem) { write("PROBLEM " + problem.name()); }
    private synchronized void writeTechnical(String text) { write(text); }
    private synchronized void writeApk(String packageName, String hash) { write("APK " + packageName + " sha256=" + hash); }

    synchronized void crash(Throwable failure) {
        // Never record exception messages, intents, thread names or arbitrary
        // arguments: those can contain paths, station names and personal data.
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 3; depth++, cause = cause.getCause()) {
            write("APP_CRASH cause=" + cause.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_$]", ""));
            int retained = 0;
            for (StackTraceElement frame : cause.getStackTrace()) {
                if (frame.getClassName().startsWith("fi.radioplus.app.") && retained++ < 10) {
                    String method = (frame.getClassName() + "." + frame.getMethodName()).replaceAll("[^A-Za-z0-9_.$]", "");
                    write("  " + method.substring(0, Math.min(method.length(), 140)) + ":" + frame.getLineNumber());
                }
            }
        }
    }

    private void write(String line) {
        try {
            if (!current.getParentFile().isDirectory() && !current.getParentFile().mkdirs()) return;
            if (current.length() >= LIMIT) Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
            byte[] bytes = (line.substring(0, Math.min(180, line.length())) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(current, true)) { out.write(bytes); }
        } catch (IOException | RuntimeException ignored) { /* Diagnostics must not break playback. */ }
    }

    synchronized String read() { return readFile(previous) + readFile(current); }
    private static String readFile(File file) {
        try {
            if (!file.isFile() || file.length() > LIMIT + 512) return "";
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) { return ""; }
    }
}
