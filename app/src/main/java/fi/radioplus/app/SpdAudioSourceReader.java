package fi.radioplus.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.Objects;

/** Reads the existing SPD audio owner without requesting or restoring a source. */
final class SpdAudioSourceReader {
    private static final String DESCRIPTOR = "com.spd.system.aidl.ISpdService";

    @FunctionalInterface
    public interface SourceProbe {
        String currentSource() throws Exception;
    }

    private final SourceProbe probe;

    SpdAudioSourceReader(Context context) {
        this(new FrameworkProbe(Objects.requireNonNull(context).getClassLoader()));
    }

    SpdAudioSourceReader(SourceProbe probe) {
        this.probe = Objects.requireNonNull(probe);
    }

    String currentSource() {
        try {
            String source = probe.currentSource();
            if (source == null) return null;
            for (int i = 0; i < source.length(); i++) {
                if (Character.isISOControl(source.charAt(i))) return null;
            }
            source = source.trim();
            if (source.isEmpty() || source.length() > 512) return null;
            // SPD appends an optional device suffix. Match its last-plus rule;
            // do not broaden ownership to similarly named packages or activities.
            int suffix = source.lastIndexOf('+');
            if (suffix >= 0) {
                if (suffix == 0 || suffix == source.length() - 1) return null;
                source = source.substring(0, suffix);
            }
            for (int i = 0; i < source.length(); i++) {
                if (Character.isWhitespace(source.charAt(i))) return null;
            }
            return source;
        } catch (Exception | LinkageError failure) {
            // A previous radio owner must never survive a failed fresh read.
            return null;
        }
    }

    @SuppressLint("PrivateApi")
    private static final class FrameworkProbe implements SourceProbe {
        private final ClassLoader loader;
        private Method checkService;
        private Method asInterface;
        private Method getCurrentSourceType;

        FrameworkProbe(ClassLoader loader) {
            this.loader = loader;
        }

        @Override public synchronized String currentSource() throws Exception {
            if (checkService == null) {
                Class<?> manager = Class.forName("android.os.ServiceManager");
                Class<?> service = Class.forName(DESCRIPTOR, false, loader);
                Class<?> stub = Class.forName(DESCRIPTOR + "$Stub", false, loader);
                Method checker = manager.getMethod("checkService", String.class);
                Method factory = stub.getMethod("asInterface", IBinder.class);
                Method getter = service.getMethod("getCurrentSourceType", boolean.class);
                asInterface = factory;
                getCurrentSourceType = getter;
                checkService = checker;
            }
            // Do not use SpdManager.getInstance(): its reconnect fallback can
            // start a test service. checkService never starts or binds one.
            Object candidate = checkService.invoke(null, "spd");
            if (!(candidate instanceof IBinder)) return null;
            IBinder binder = (IBinder) candidate;
            if (!binder.isBinderAlive() || !DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
                return null;
            }
            Object service = asInterface.invoke(null, binder);
            if (service == null) return null;
            Object value = getCurrentSourceType.invoke(service, true);
            return value instanceof String ? (String) value : null;
        }
    }
}
