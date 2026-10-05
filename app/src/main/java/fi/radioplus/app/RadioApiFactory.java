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

/** Selects a known contract by inspecting the installed stock APK, without loading its code. */
final class RadioApiFactory {
    static final String DESCRIPTOR = "com.hcn.autoradio.IRadioServiceAPI";
    private static final long MAX_APK_BYTES = 100L * 1024 * 1024;
    private static final ExecutorService INSPECTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Detection latest;
    private static String cachedIdentity;
    private static Detection cached;

    interface Callback { void onResolved(IRadioServiceAPI api, Detection detection); }
    interface DetectionCallback { void onDetected(Detection detection); }

    static final class Detection {
        final RadioBackendProfile profile;
        final String stockVersion;
        final String sha256;
        final String problem;

        Detection(RadioBackendProfile profile, String version, String hash, String problem) {
            this.profile = profile;
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
        Context application = context.getApplicationContext();
        INSPECTOR.execute(() -> {
            Detection detection = inspectInstalled(application);
            IRadioServiceAPI api = null;
            if (detection.profile != RadioBackendProfile.UNKNOWN) {
                try {
                    api = create(detection.profile, binder);
                } catch (RemoteException | RuntimeException error) {
                    detection = new Detection(RadioBackendProfile.UNKNOWN, detection.stockVersion,
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
        if (binder == null || !DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
            throw new RemoteException("Unexpected stock radio Binder descriptor");
        }
        return profile == RadioBackendProfile.HCN_LEGACY_25
                ? new LegacyHcnRadioApi(binder) : IRadioServiceAPI.Stub.asInterface(binder);
    }

    private static Detection inspectInstalled(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    RadioBackendContract.PACKAGE_NAME, 0);
            ApplicationInfo application = info.applicationInfo;
            String version = info.versionName == null ? "" : info.versionName;
            if (application == null || application.sourceDir == null
                    || (application.splitSourceDirs != null && application.splitSourceDirs.length > 0)) {
                return unknown(version, "The stock radio APK layout is not recognized");
            }
            File apk = new File(application.sourceDir);
            long length = apk.length();
            long modified = apk.lastModified();
            String identity = apk.getCanonicalPath() + ":" + info.lastUpdateTime
                    + ":" + length + ":" + modified;
            if (identity.equals(cachedIdentity) && cached != null) return cached;
            if (!apk.isFile() || length <= 0 || length > MAX_APK_BYTES) {
                return unknown(version, "The stock radio APK could not be inspected");
            }
            String hash = sha256(apk);
            if (length != apk.length() || modified != apk.lastModified()) {
                return unknown(version, "The stock radio changed during inspection; reopen Radio+");
            }
            RadioBackendProfile profile = RadioBackendProfile.forApkSha256(hash);
            Detection detection = new Detection(profile, version, hash,
                    profile == RadioBackendProfile.UNKNOWN
                            ? "This stock radio APK has not yet been verified" : "");
            cachedIdentity = identity;
            cached = detection;
            return detection;
        } catch (PackageManager.NameNotFoundException error) {
            return unknown("", "The stock com.hcn.autoradio package was not found");
        } catch (IOException | RuntimeException | NoSuchAlgorithmException error) {
            return unknown("", "The stock radio APK could not be inspected");
        }
    }

    private static Detection unknown(String version, String problem) {
        return new Detection(RadioBackendProfile.UNKNOWN, version, "", problem);
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
        return api != null && !(api instanceof LegacyHcnRadioApi);
    }

    static boolean supportsSeparateAudioFocus(IRadioServiceAPI api) {
        return api != null && !(api instanceof LegacyHcnRadioApi);
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
        if (!detection.stockVersion.isEmpty()) details += "\ncom.hcn.autoradio " + detection.stockVersion;
        if (!detection.sha256.isEmpty()) details += "\nAPK SHA-256: " + detection.sha256;
        if (!detection.problem.isEmpty()) details += "\n" + detection.problem;
        return details;
    }
}
