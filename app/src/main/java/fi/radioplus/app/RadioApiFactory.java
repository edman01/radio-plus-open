package fi.radioplus.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import com.hcn.autoradio.IRadioServiceAPI;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashMap;
import java.util.Map;

/** Selects a known contract by inspecting the installed stock APK, without loading its code. */
final class RadioApiFactory {
    static final String DESCRIPTOR = "com.hcn.autoradio.IRadioServiceAPI";
    private static final long MAX_APK_BYTES = 100L * 1024 * 1024;
    private static final ExecutorService INSPECTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Detection latest;
    // Shared by the UI and playback bindings, so a channel tap cannot initialize
    // the vendor audio path twice. Accessed only on INSPECTOR.
    private static NwdAudioRouting nwdAudio;
    private static final Map<String, String> cachedIdentities = new HashMap<>();
    private static final Map<String, Detection> cachedDetections = new HashMap<>();

    interface Callback { void onResolved(IRadioServiceAPI api, Detection detection); }
    interface DetectionCallback { void onDetected(Detection detection); }

    static final class Detection {
        final RadioBackendProfile profile;
        final String stockVersion;
        final String sha256;
        final String problem;
        final String stockPackage;

        Detection(RadioBackendProfile profile, String version, String hash, String problem) {
            this(profile, profile.stockPackage(), version, hash, problem);
        }

        Detection(RadioBackendProfile profile, String stockPackage, String version, String hash, String problem) {
            this.profile = profile;
            this.stockPackage = stockPackage;
            this.stockVersion = version;
            this.sha256 = hash;
            this.problem = problem;
        }
    }

    private RadioApiFactory() { }

    static void detect(Context context, DetectionCallback callback) {
        Context application = context.getApplicationContext();
        INSPECTOR.execute(() -> {
            Detection detection = inspectInstalled(application);
            latest = detection;
            MAIN.post(() -> callback.onDetected(detection));
        });
    }

    static void resolve(Context context, IBinder binder, Callback callback) {
        resolve(context, null, binder, callback);
    }

    static void resolve(Context context, Detection expected, IBinder binder, Callback callback) {
        Context application = context.getApplicationContext();
        INSPECTOR.execute(() -> {
            Detection detection = inspectInstalled(application);
            IRadioServiceAPI api = null;
            if (expected != null && (expected.profile != detection.profile
                    || !expected.sha256.equals(detection.sha256))) {
                detection = new Detection(RadioBackendProfile.UNKNOWN, detection.stockPackage,
                        detection.stockVersion, detection.sha256, "The stock APK changed while binding; reopen Radio+");
            }
            if (detection.profile != RadioBackendProfile.UNKNOWN) {
                try {
                    if (detection.profile == RadioBackendProfile.NWD_222 && nwdAudio == null) {
                        nwdAudio = new NwdAudioRouting(application);
                    }
                    api = detection.profile == RadioBackendProfile.NWD_222
                            ? new NwdRadioApi(binder, nwdAudio)
                            : create(detection.profile, binder);
                } catch (RemoteException | RuntimeException error) {
                    detection = new Detection(RadioBackendProfile.UNKNOWN, detection.stockPackage, detection.stockVersion,
                            detection.sha256, "The stock radio Binder does not match its APK");
                }
            }
            latest = detection;
            IRadioServiceAPI resolved = api;
            Detection result = detection;
            MAIN.post(() -> callback.onResolved(resolved, result));
        });
    }

    static IRadioServiceAPI create(RadioBackendProfile profile, IBinder binder)
            throws RemoteException {
        // UNKNOWN must be rejected before even looking up the Binder descriptor.
        if (profile == null || profile == RadioBackendProfile.UNKNOWN) {
            throw new RemoteException("Unrecognized stock radio contract");
        }
        if (profile.isTs()) return new TsRadioApi(binder);
        if (profile == RadioBackendProfile.NWD_222) {
            throw new RemoteException("NWD requires its verified audio-routing dependency");
        }
        if (binder == null || !DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
            throw new RemoteException("Unexpected stock radio Binder descriptor");
        }
        return profile == RadioBackendProfile.HCN_LEGACY_25
                ? new LegacyHcnRadioApi(binder) : IRadioServiceAPI.Stub.asInterface(binder);
    }

    private static Detection inspectInstalled(Context context) {
        Detection recognized = null;
        Detection lastUnknown = null;
        for (String stockPackage : new String[]{RadioBackendContract.PACKAGE_NAME, "com.ts.MainUI",
                "com.nwd.radio.service"}) {
            Detection candidate = inspectPackage(context, stockPackage);
            if (candidate == null) continue;
            if (candidate.profile == RadioBackendProfile.NWD_222) {
                // Both packages contain commands used by this adapter. A UI APK
                // or a service descriptor alone cannot establish this contract.
                Detection kernel = inspectPackage(context, "com.nwd.kernel");
                if (kernel == null || !RadioBackendProfile.verifiedNwdPair(candidate.sha256, kernel.sha256)) {
                    candidate = unknown(stockPackage, candidate.stockVersion,
                            "The NWD audio-routing service has not been verified");
                }
            }
            if (candidate.profile == RadioBackendProfile.UNKNOWN) {
                lastUnknown = candidate;
            } else if (recognized != null) {
                return unknown("", "Multiple verified stock radio backends are installed; automatic control blocked");
            } else {
                recognized = candidate;
            }
        }
        if (recognized != null) return recognized;
        return lastUnknown != null ? lastUnknown
                : unknown("", "No supported stock radio package was found");
    }

    private static Detection inspectPackage(Context context, String stockPackage) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    stockPackage, 0);
            ApplicationInfo application = info.applicationInfo;
            String version = info.versionName == null ? "" : info.versionName;
            if (application == null || !application.enabled || application.sourceDir == null
                    || (application.splitSourceDirs != null && application.splitSourceDirs.length > 0)) {
                return unknown(stockPackage, version, "The stock radio APK layout is not recognized");
            }
            File apk = new File(application.sourceDir);
            long length = apk.length();
            long modified = apk.lastModified();
            String identity = apk.getCanonicalPath() + ":" + info.lastUpdateTime
                    + ":" + length + ":" + modified;
            if (identity.equals(cachedIdentities.get(stockPackage))) {
                Detection cached = cachedDetections.get(stockPackage);
                if (cached != null) return cached;
            }
            if (!apk.isFile() || length <= 0 || length > MAX_APK_BYTES) {
                return unknown(stockPackage, version, "The stock radio APK could not be inspected");
            }
            String hash = sha256(apk);
            if (length != apk.length() || modified != apk.lastModified()) {
                return unknown(stockPackage, version, "The stock radio changed during inspection; reopen Radio+");
            }
            RadioBackendProfile profile = RadioBackendProfile.forApkSha256(hash);
            if (!stockPackage.equals(profile.stockPackage())) profile = RadioBackendProfile.UNKNOWN;
            Detection detection = new Detection(profile, stockPackage, version, hash,
                    profile == RadioBackendProfile.UNKNOWN
                            ? "This stock radio APK has not yet been verified" : "");
            cachedIdentities.put(stockPackage, identity);
            cachedDetections.put(stockPackage, detection);
            return detection;
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        } catch (IOException | RuntimeException | NoSuchAlgorithmException error) {
            return unknown(stockPackage, "", "The stock radio APK could not be inspected");
        }
    }

    private static Detection unknown(String version, String problem) {
        return new Detection(RadioBackendProfile.UNKNOWN, version, "", problem);
    }

    private static Detection unknown(String stockPackage, String version, String problem) {
        return new Detection(RadioBackendProfile.UNKNOWN, stockPackage, version, "", problem);
    }

    private static String sha256(File apk) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long bytes = 0;
        try (FileInputStream input = new FileInputStream(apk)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                bytes += count;
                if (bytes > MAX_APK_BYTES) throw new IOException("Unbounded stock APK");
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    static boolean supportsOemFavorites(IRadioServiceAPI api) {
        return api != null && !(api instanceof LegacyHcnRadioApi) && usesHcnFramework(api);
    }

    static boolean supportsSeparateAudioFocus(IRadioServiceAPI api) {
        return api != null && !(api instanceof LegacyHcnRadioApi) && usesHcnFramework(api);
    }

    static boolean usesHcnFramework(IRadioServiceAPI api) {
        return api != null && !(api instanceof TsRadioApi) && !(api instanceof NwdRadioApi);
    }

    static boolean supportsScanning(IRadioServiceAPI api) { return usesHcnFramework(api) || api instanceof NwdRadioApi; }
    static boolean supportsLocalMode(IRadioServiceAPI api) { return api != null && !(api instanceof TsRadioApi); }

    static boolean selectedSupportsScanning() {
        Detection detection = latest;
        return detection != null && (detection.profile == RadioBackendProfile.HCN_CURRENT_31
                || detection.profile == RadioBackendProfile.HCN_LEGACY_25
                || detection.profile == RadioBackendProfile.NWD_222);
    }
    static boolean selectedSupportsLocalMode() {
        Detection detection = latest;
        return selectedSupportsScanning()
                || (detection != null && detection.profile == RadioBackendProfile.NWD_222);
    }
    static boolean selectedSupportsTuning() {
        Detection detection = latest;
        return detection != null && detection.profile != RadioBackendProfile.UNKNOWN;
    }

    static String unsupportedMessage(Context context) {
        return AppLanguage.text(context,
                "Vakioradion ohjausrajapintaa ei tunnistettu. Ohjaus estettiin turvallisuussyistä. "
                        + "Avaa Asetukset → Tietoja sovelluksesta ja ilmoita radiomalli GitHubissa.",
                "Stock radio control interface not recognized. Control was blocked for safety. "
                        + "Open Settings → About and report your head unit model on GitHub.");
    }

    static String description(Context context) {
        Detection detection = latest;
        if (detection == null) return AppLanguage.text(context,
                "Vakioradion tunnistus: odottaa yhteyttä", "Stock radio detection: awaiting connection");
        String details = AppLanguage.text(context, "Radion ohjaus: ", "Radio control: ")
                + detection.profile.label;
        if (!detection.stockVersion.isEmpty()) details += "\n" + detection.stockPackage + " " + detection.stockVersion;
        if (!detection.sha256.isEmpty()) details += "\nAPK SHA-256: " + detection.sha256;
        if (!detection.problem.isEmpty()) details += "\n" + detection.problem;
        return details;
    }
}
