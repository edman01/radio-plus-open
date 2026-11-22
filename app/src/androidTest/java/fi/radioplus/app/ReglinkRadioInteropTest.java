package fi.radioplus.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Synthetic protocol endpoints only; no OEM APK is bound here. Request
 * bookkeeping and persisted results are not evidence of physical reception.
 */
public final class ReglinkRadioInteropTest {
    private interface Hook { void run() throws RemoteException; }
    static final class Tuner extends Binder {
        final List<Integer> calls = new ArrayList<>();
        String band = "fm";
        int frequency = 9810;
        boolean supportsAm = true;
        boolean scanning;
        boolean powered = true;
        boolean deferTune;
        int rememberedFm = 9810, rememberedAm = 531;
        final List<String> tunes = new ArrayList<>();
        boolean changingFrequency;
        boolean stopEmitsCallback = true;
        int rejectCode = -1;
        int trailingCode = -1;
        int emptyReplyCode = -1;
        IBinder callback;
        Hook afterRegistration;
        Hook afterTune;
        Hook afterScan;
        Hook afterStop;
        Hook beforeBandRead;

        Tuner() { attachInterface(null, ReglinkRadioApi.RADIO_DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            data.enforceInterface(ReglinkRadioApi.RADIO_DESCRIPTOR);
            calls.add(code);
            assertEquals(0, flags);
            if (code == rejectCode) return false;
            switch (code) {
                case 1:
                    callback = data.readStrongBinder();
                    if (afterRegistration != null) afterRegistration.run();
                    break;
                case 2:
                    if (callback == data.readStrongBinder()) callback = null;
                    break;
                case 6:
                    String targetBand = data.readString();
                    int target = data.readInt();
                    assertTrue("fm".equals(targetBand) || "am".equals(targetBand));
                    tunes.add(targetBand + ":" + target);
                    if (!deferTune) {
                        if ("am".equals(band)) rememberedAm = frequency; else rememberedFm = frequency;
                        band = targetBand;
                        frequency = target != 0 ? target : "am".equals(band) ? rememberedAm : rememberedFm;
                    }
                    if (afterTune != null) afterTune.run();
                    break;
                case 10: case 11: break;
                case 12:
                    scanning = true;
                    emit(band, frequency, 5);
                    if (afterScan != null) afterScan.run();
                    break;
                case 13:
                    scanning = false;
                    if (stopEmitsCallback) emit(band, frequency, 1);
                    if (afterStop != null) afterStop.run();
                    break;
                case 20:
                    String queried = data.readString();
                    assertTrue("fm".equals(queried) || "am".equals(queried));
                    assertEquals(0, data.dataAvail());
                    reply.writeNoException();
                    reply.writeInt("fm".equals(queried) || supportsAm ? 1 : 0);
                    return true;
                case 5:
                    if (beforeBandRead != null) beforeBandRead.run();
                    break;
                case 7: case 14: case 15: break;
                default: fail("Unsafe/unexpected radio transaction " + code);
            }
            assertEquals("Trailing input for code " + code, 0, data.dataAvail());
            if (code == emptyReplyCode) return true;
            reply.writeNoException();
            if (code == 5) reply.writeString(band);
            if (code == 7) reply.writeInt(changingFrequency ? (frequency += 10) : frequency);
            if (code == 14) reply.writeInt(scanning ? 1 : 0);
            if (code == 15) reply.writeInt(powered ? 1 : 0);
            if (code == trailingCode) reply.writeInt(42);
            return true;
        }

        void emit(String band, int frequency, int state) throws RemoteException {
            if (callback != null) send(callback, band, frequency, state);
        }

        static void send(IBinder target, String band, int frequency, int state) throws RemoteException {
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(ReglinkRadioApi.CALLBACK_DESCRIPTOR);
                data.writeString(band);
                data.writeInt(frequency);
                data.writeInt(state);
                assertTrue(target.transact(1, data, null, IBinder.FLAG_ONEWAY));
            } finally { data.recycle(); }
        }

        long count(int code) { return calls.stream().filter(value -> value == code).count(); }
    }

    static final class SharedVariables extends Binder {
        String hfp = "connected";
        boolean reverse, bt;
        String module = "4754";
        boolean initialized = true;
        boolean onboardRadio;
        boolean rejectReads;
        final List<Integer> calls = new ArrayList<>();

        SharedVariables() { attachInterface(null, ReglinkCapabilityReader.SHARED_DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(ReglinkCapabilityReader.SHARED_DESCRIPTOR);
            calls.add(code);
            assertEquals(0, flags);
            if (rejectReads) return false;
            String key = data.readString();
            reply.writeNoException();
            if (code == 7) {
                assertTrue("Env.RadioHwModule".equals(key) || "phone.hfp_state".equals(key));
                assertNull(data.readString());
                reply.writeString("phone.hfp_state".equals(key) ? hfp : module);
            } else {
                assertEquals(9, code);
                assertTrue("Env.Init_AllCompleted".equals(key) || "Env.OnboardRadio".equals(key)
                        || "Env.IsReversing".equals(key) || "bt.connected".equals(key));
                int defaultValue = data.readInt();
                assertTrue(defaultValue == 0 || defaultValue == 1);
                reply.writeInt(("Env.Init_AllCompleted".equals(key) ? initialized
                        : "Env.IsReversing".equals(key) ? reverse : "bt.connected".equals(key) ? bt : onboardRadio) ? 1 : 0);
            }
            assertEquals(0, data.dataAvail());
            return true;
        }
    }

    static final class Common extends Binder {
        final ReglinkWheelInteropTest.Keys keys = new ReglinkWheelInteropTest.Keys();
        final ReglinkWheelInteropTest.Notices notices = new ReglinkWheelInteropTest.Notices();
        final IBinder tuner;
        final SharedVariables shared = new SharedVariables();
        final List<Integer> calls = new ArrayList<>();
        final List<String> services = new ArrayList<>();
        final Hub hub = new Hub();
        final Media media = new Media();
        Common(IBinder tuner) {
            this.tuner = tuner;
            attachInterface(null, ReglinkRadioApi.COMMON_DESCRIPTOR);
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(ReglinkRadioApi.COMMON_DESCRIPTOR);
            calls.add(code);
            assertEquals(2, code);
            assertEquals(0, flags);
            String service = data.readString();
            assertTrue("Radio".equals(service) || "SharedVariable".equals(service)
                    || "MediaHub".equals(service) || "Media".equals(service)
                    || "Key".equals(service) || "Message".equals(service));
            services.add(service);
            assertEquals(0, data.dataAvail());
            reply.writeNoException();
            reply.writeStrongBinder("Radio".equals(service) ? tuner : "Media".equals(service) ? media
                    : "MediaHub".equals(service) ? hub : "Key".equals(service) ? keys
                    : "Message".equals(service) ? notices : shared);
            return true;
        }
    }

    static final class Hub extends Binder {
        String owner = "radio";
        Hub() { attachInterface(null, ReglinkStockSession.HUB_DESCRIPTOR); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(ReglinkStockSession.HUB_DESCRIPTOR);
            assertEquals("Must not register/activate/replace OEM clients", 7, code);
            assertEquals(0, data.dataAvail()); assertEquals(0, flags);
            reply.writeNoException(); reply.writeString(owner); return true;
        }
    }

    private static final class Media extends Binder {
        int platform = 2, model = 1, mix, sourceFlags, action = 1;
        Runnable afterSourceRead;
        boolean muted, malformed;
        Media() { attachInterface(null, ReglinkStockSession.MEDIA_DESCRIPTOR); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(ReglinkStockSession.MEDIA_DESCRIPTOR);
            assertTrue("No source/volume/global mute writes", code == 3 || code == 6);
            assertEquals(0, data.dataAvail()); assertEquals(0, flags);
            reply.writeNoException();
            if (code == 6) reply.writeInt(muted ? 1 : 0);
            else {
                reply.writeInt(1); reply.writeInt(platform); reply.writeInt(model);
                reply.writeInt(mix); reply.writeInt(sourceFlags);
                if (!malformed) reply.writeInt(action);
                if (afterSourceRead != null) afterSourceRead.run();
            }
            return true;
        }
    }

    @Test public void assistedAdoptionAndCloseNeverTouchPowerFocusSourceOrMute() throws Exception {
        Tuner tuner = new Tuner(); Common common = new Common(tuner);
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(common, RadioBackendProfile.REGLINK_S574_ASSISTED)) {
            assertTrue(api.isStockAssisted()); assertTrue(api.requestStockPlayback());
            assertTrue(api.readHealth().radioOwnsSource());
            assertFalse(api.readHealth().muted);
            assertEquals(1, tuner.count(1));
            assertTrue(RadioApiFactory.supportsScanning(api));
            assertFalse(RadioApiFactory.usesHcnFramework(api));
            assertFalse(RadioApiFactory.supportsSeparateAudioFocus(api));
        }
        assertEquals(1, tuner.count(2));
        assertEquals(0, tuner.count(3)); assertEquals(0, tuner.count(4));
    }

    @Test public void s602CallBlocksCommandsAndOnlyStockCanRestoreAudio() throws Exception {
        Tuner tuner = new Tuner(); Common common = new Common(tuner);
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(common, RadioBackendProfile.REGLINK_S602_ASSISTED)) {
            assertTrue(api.requestStockPlayback());
            common.media.platform = 1; // Call route, even if hub still lists the stock radio.
            assertFalse(api.readHealth().radioOwnsSource());
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(0, 101700));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            assertEquals(0, tuner.count(6)); assertEquals(0, tuner.count(12));
            common.media.platform = 2; common.media.model = 2; // OEM reports its restored route.
            assertTrue(api.requestStockPlayback());
            assertTrue(api.tuneToBand(0, 101700)); assertTrue(api.tuneToBand(0, 101700));
            assertTrue(api.tuneToBand(3, 999)); assertEquals(Arrays.asList("fm:10170", "am:0", "am:999"), tuner.tunes);
            tuner.afterScan = () -> { tuner.emit("am", 999, 13); tuner.emit("am", 999, 13); };
            api.startScan();
            assertArrayEquals(new int[]{999}, api.readScanPresets(3, () -> true));
            assertEquals(0, tuner.count(3)); assertEquals(0, tuner.count(4));
        }
    }

    @Test public void s602BandButtonRecallsOnceInEachDirectionAndPresetsStillWork() throws Exception {
        Tuner t = new Tuner(); t.frequency = 9120; t.rememberedAm = 999;
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(new Common(t), RadioBackendProfile.REGLINK_S602_ASSISTED)) {
            api.onBandEvent(); assertEquals(999, api.getCurrentFreq());
            api.onBandEvent(); assertEquals(91200, api.getCurrentFreq());
            assertTrue(api.tuneToBand(0, 104800));
            assertEquals(Arrays.asList("am:0", "fm:0", "fm:10480"), t.tunes);
        }
    }

    @Test public void s602BandButtonNeverReplaysAnUnconfirmedToggle() throws Exception {
        Tuner t = new Tuner(); t.deferTune = true;
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(new Common(t), RadioBackendProfile.REGLINK_S602_ASSISTED)) {
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::onBandEvent);
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::onBandEvent);
            t.deferTune = false; assertTrue(api.tuneToBand(0, 91200));
            assertEquals(Arrays.asList("am:0", "fm:9120"), t.tunes);
        }
    }

    @Test public void queuedBandTapUsesDisplayedBandAndDoesNotInterruptTheStockUnmute() throws Exception {
        Tuner t = new Tuner(); Common c = new Common(t); long[] dispatched = {0};
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(c, RadioBackendProfile.REGLINK_S602_ASSISTED)) {
            t.afterTune = () -> dispatched[0] = android.os.SystemClock.elapsedRealtime();
            api.recallBandFrom(0, () -> true);
            assertTrue(android.os.SystemClock.elapsedRealtime() - dispatched[0] >= 1500);
            api.recallBandFrom(0, () -> true); // same displayed button, already on AM
            api.recallBandFrom(3, () -> false); // activity no longer owns this request
            c.media.platform = 512; // phone is never a restored radio route
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.recallBandFrom(3, () -> true));
            assertEquals(Arrays.asList("am:0"), t.tunes);
        }
    }

    @Test public void otherReglinkModulesAndProfilesKeepTheirDirectBandButtons() throws Exception {
        for (RadioBackendProfile p : new RadioBackendProfile[]{
                RadioBackendProfile.REGLINK_S574_ASSISTED, RadioBackendProfile.REGLINK_S602_ASSISTED}) {
            for (String module : new String[]{"4754", "4755", "7786"}) {
                if (p == RadioBackendProfile.REGLINK_S602_ASSISTED && "4754".equals(module)) continue;
                Tuner t = new Tuner(); Common c = new Common(t); c.shared.module = module;
                try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(c, p)) {
                    api.onBandEvent(); api.onBandEvent();
                    assertEquals(Arrays.asList("am:531", "fm:8750"), t.tunes);
                }
            }
        }
    }

    @Test public void s602BandRecallDoesNotLockPresetsOrRetuneAnAlreadyRestoredStation() throws Exception {
        Tuner t = new Tuner(); t.frequency = 10480;
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(new Common(t), RadioBackendProfile.REGLINK_S602_ASSISTED)) {
            assertTrue(api.tuneToBand(3, 531)); assertTrue(api.tuneToBand(0, 100900));
            assertTrue(api.tuneToBand(0, 104800)); assertTrue(api.tuneToBand(3, 531));
            assertTrue(api.tuneToBand(0, 104800));
            assertEquals(Arrays.asList("am:0", "fm:0", "fm:10090", "fm:10480", "am:0", "fm:0"), t.tunes);
        }
    }

    @Test public void s602CompletedTimeoutCanBeSupersededButNeverReplayedOrUsedToStartScan() throws Exception {
        Tuner t = new Tuner(); t.deferTune = true;
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(new Common(t), RadioBackendProfile.REGLINK_S602_ASSISTED)) {
            assertFalse(api.tuneToBand(3, 531));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(3, 531));
            t.deferTune = false; assertTrue(api.tuneToBand(0, 104800));
            assertEquals(Arrays.asList("am:0", "fm:10480"), t.tunes);
        }
    }

    @Test public void s602RecallWaitsForCoherentUnmutedTunerBeforeFineTune() throws Exception {
        Tuner t = new Tuner(); Common c = new Common(t);
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(c, RadioBackendProfile.REGLINK_S602_ASSISTED)) {
            t.afterTune = () -> {
                if (t.tunes.size() == 1) {
                    c.media.muted = true; t.frequency = 9810; // FM value alongside the new AM band.
                    assertThrows(ReglinkRadioApi.BandTransitionReadback.class, api::snapshotForPoll);
                    final int[] bandReads = {0};
                    t.beforeBandRead = () -> {
                        if (++bandReads[0] == 4) t.frequency = 531;
                        if (bandReads[0] == 8) c.media.muted = false;
                    };
                } else assertFalse(c.media.muted);
            };
            assertTrue(api.tuneToBand(3, 999)); assertEquals(Arrays.asList("am:0", "am:999"), t.tunes);
        }
    }

    @Test public void temporaryPowerReadbackDuringBandRecallNeverTriggersPowerOrExtraTune() throws Exception {
        Tuner t = new Tuner(); t.rememberedAm = 999;
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(new Common(t), RadioBackendProfile.REGLINK_S602_ASSISTED)) {
            t.afterTune = () -> {
                t.powered = false;
                int[] reads = {0};
                t.beforeBandRead = () -> { if (++reads[0] == 5) t.powered = true; };
            };
            api.recallBandFrom(0, () -> true);
            assertEquals(999, api.getCurrentFreq());
            assertEquals(Arrays.asList("am:0"), t.tunes);
            assertEquals(0, t.count(3)); assertEquals(0, t.count(4));
        }
    }

    @Test public void s602RecallLosingStockRouteOrCancelledCannotSendSecondCommand() throws Exception {
        for (boolean cancel : new boolean[]{true, false}) {
            Tuner t = new Tuner(); Common c = new Common(t);
            try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(c, RadioBackendProfile.REGLINK_S602_ASSISTED)) {
                if (cancel) assertFalse(api.tuneToBand(3, 999, () -> t.tunes.isEmpty()));
                else {
                    t.afterTune = () -> c.hub.owner = "bluetooth";
                    assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(3, 999));
                }
                assertEquals(Arrays.asList("am:0"), t.tunes);
            }
        }
    }

    @Test public void agamaPresetKeysRequireKnownCallerAndAreNotPhysicalOrRepeatedKeys() {
        android.view.KeyEvent key = new android.view.KeyEvent(0, 87);
        assertTrue(AgamaLauncherControls.accepts(AgamaLauncherControls.AGAMA, key));
        assertTrue(AgamaLauncherControls.accepts(AgamaLauncherControls.AGAMA, new android.view.KeyEvent(1, 88)));
        assertFalse(AgamaLauncherControls.accepts(null, key));
        assertFalse(AgamaLauncherControls.accepts("android", key));
        assertFalse(AgamaLauncherControls.accepts(AgamaLauncherControls.AGAMA, new android.view.KeyEvent(0, 86)));
        assertFalse(AgamaLauncherControls.accepts(AgamaLauncherControls.AGAMA,
                new android.view.KeyEvent(123, 123, 0, 87, 0, 0, 3, 87)));
        assertFalse(AgamaLauncherControls.accepts(AgamaLauncherControls.AGAMA,
                new android.view.KeyEvent(0, 0, 0, 88, 2, 0, -1, 0)));
    }

    @Test public void directedMediaKeyExposesItsActualControllerInsideCallback() throws Exception {
        android.app.Instrumentation ins = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        android.content.Context ctx = ins.getTargetContext();
        android.media.session.MediaSession session = new android.media.session.MediaSession(ctx, "reglink-controller-test");
        java.util.concurrent.ArrayBlockingQueue<String> callers = new java.util.concurrent.ArrayBlockingQueue<>(2);
        try {
            session.setCallback(new android.media.session.MediaSession.Callback() {
                @Override public boolean onMediaButtonEvent(android.content.Intent intent) {
                    callers.offer(String.valueOf(AgamaLauncherControls.caller(session)));
                    return true;
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
            session.setActive(true);
            session.getController().dispatchMediaButtonEvent(new android.view.KeyEvent(0, 87));
            String actual = callers.poll(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue("Framework caller: " + actual, ctx.getPackageName().equals(actual)
                    || ins.getContext().getPackageName().equals(actual));
        } finally { session.release(); }
    }

    @Test public void s602ConfirmedScanCanBeReloadedAndTunedFromSavedFavorite() throws Exception {
        android.content.Context base = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().getTargetContext();
        String prefix = "reglink-test-" + java.util.UUID.randomUUID() + "-";
        java.util.Set<String> created = new java.util.HashSet<>();
        android.content.Context isolated = new android.content.ContextWrapper(base) {
            @Override public android.content.Context getApplicationContext() { return this; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                created.add(prefix + name);
                return base.getSharedPreferences(prefix + name, mode);
            }
        };
        Tuner tuner = new Tuner(); Common c = new Common(tuner);
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(c, RadioBackendProfile.REGLINK_S602_ASSISTED)) {
            RadioServiceClient.dispatchScan(api, true, () -> true);
            tuner.emit("fm", 9010, 13); tuner.emit("fm", 10450, 13); tuner.emit("fm", 9010, 13);
            tuner.emit("fm", 9020, 5); // Progress only: never save as a station.
            int[] found = api.readScanPresets(0, () -> true);
            assertEquals(2, new StationStore(isolated).mergeDiscovered(0, found, java.util.Collections.emptyMap()));
            StationStore reopened = new StationStore(isolated);
            assertNull(reopened.find(0, 90200));
            FavoriteStation saved = reopened.find(0, 104500);
            assertNotNull(saved);
            new FavoriteStore(isolated).save(saved.withName("Test FM"));
            FavoriteStation favorite = new FavoriteStore(isolated).find(0, 104500);
            assertEquals("Test FM", favorite.name);
            assertTrue(api.tuneToBand(favorite.band, favorite.frequency));
            assertEquals(10450, tuner.frequency); assertEquals(1, tuner.count(6));
        } finally {
            for (String name : created) base.deleteSharedPreferences(name);
        }
    }

    @Test public void restoredRouteIsNotABlanketFirmwareOrModuleException() throws Exception {
        for (RadioBackendProfile profile : new RadioBackendProfile[]{
                RadioBackendProfile.REGLINK_S574_ASSISTED, RadioBackendProfile.REGLINK_S602_ASSISTED}) {
            for (String module : new String[]{"4754", "4755", "7786"}) {
                Tuner tuner = new Tuner(); Common common = new Common(tuner); common.shared.module = module;
                try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(common, profile)) {
                    assertTrue(api.requestStockPlayback());
                    common.media.model = 2;
                    if (profile == RadioBackendProfile.REGLINK_S602_ASSISTED && module.equals("4754")) {
                        assertTrue(api.requestStockPlayback());
                        common.media.action = 2;
                    }
                    assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(0, 101700));
                    assertEquals(0, tuner.count(6));
                }
            }
        }
    }

    @Test public void s602RejectsMixedMutedForeignAndUnstableRestoredRoutes() throws Exception {
        Common c = new Common(new Tuner()); c.media.model = 2;
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(c, RadioBackendProfile.REGLINK_S602_ASSISTED)) {
            c.hub.owner = "phone";
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            c.hub.owner = "radio"; c.media.sourceFlags = 2;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            c.media.sourceFlags = 0; c.media.mix = 512;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            c.media.mix = 0; c.media.muted = true;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            c.media.muted = false; c.media.afterSourceRead = () -> c.media.model = 3 - c.media.model;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            assertEquals(0, ((Tuner)c.tuner).count(12));
        }
    }

    @Test public void s602InterruptedScanNeverPublishesStaleStationsOrStealsRoute() throws Exception {
        for (boolean unavailable : new boolean[]{false, true}) {
            Tuner tuner = new Tuner(); Common c = new Common(tuner);
            try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(c, RadioBackendProfile.REGLINK_S602_ASSISTED)) {
                api.startScan(); tuner.emit("fm", 9010, 13);
                if (unavailable) c.media.malformed = true; else c.media.platform = 1;
                if (unavailable) api.readHealth(); else api.snapshotForPoll();
                c.media.malformed = false; c.media.platform = 2; c.media.model = 2;
                assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.readScanPresets(0, () -> true));
                assertEquals(0, tuner.count(13)); assertEquals(0, tuner.count(6));
            }
        }
    }

    @Test public void cachedBinderCannotCarryS602RoutePermissionBackToS574() throws Exception {
        Common c = new Common(new Tuner()); c.media.model = 2;
        ReglinkRadioConnectionCache cache = new ReglinkRadioConnectionCache();
        try {
            ReglinkRadioApi newer = cache.resolve(c, RadioBackendProfile.REGLINK_S602_ASSISTED);
            assertTrue(newer.requestStockPlayback());
            ReglinkRadioApi older = cache.resolve(c, RadioBackendProfile.REGLINK_S574_ASSISTED);
            assertTrue(newer.isClosed());
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, older::requestStockPlayback);
            assertThrows(RemoteException.class, () -> cache.resolve(c, RadioBackendProfile.UNKNOWN));
            assertTrue(older.isClosed());
        } finally { cache.clear(); }
    }

    @Test public void uiLateStopNeverRestartsFinishedScanAndFailedStartCannotReuseIt() throws Exception {
        Tuner tuner = new Tuner(); Common c = new Common(tuner);
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(c, RadioBackendProfile.REGLINK_S602_ASSISTED)) {
            RadioServiceClient.dispatchScan(api, false, () -> true); // Idle stop.
            RadioServiceClient.dispatchScan(api, true, () -> false); // Cancelled start.
            RadioServiceClient.dispatchScan(api, true, () -> true);
            tuner.emit("fm", 9010, 13); tuner.scanning = false; tuner.emit("fm", 9010, 1);
            api.snapshot();
            RadioServiceClient.dispatchScan(api, false, () -> true);
            assertArrayEquals(new int[]{90100}, api.readScanPresets(0, () -> true));
            c.media.muted = true;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class,
                    () -> RadioServiceClient.dispatchScan(api, true, () -> true));
            c.media.muted = false;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.readScanPresets(0, () -> true));
            assertEquals(1, tuner.count(12)); assertEquals(0, tuner.count(13));
        }
    }

    @Test public void assistedTuneConfirmsFmAndAmWithoutDuplicateTune() throws Exception {
        Tuner tuner = new Tuner(); Common common = new Common(tuner);
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(common, RadioBackendProfile.REGLINK_S574_ASSISTED)) {
            assertTrue(api.tuneToBand(0, 101700)); assertEquals(10170, tuner.frequency);
            assertTrue(api.tuneToBand(0, 101700)); assertEquals(1, tuner.count(6));
            assertTrue(api.tuneToBand(3, 999)); assertEquals("am", tuner.band);
            assertEquals(999, api.getCurrentFreq()); assertEquals(2, tuner.count(6));
        }
    }

    @Test public void assistedScanSavesOnlyConfirmedSignalsAndAllowsItsOwnScanMute() throws Exception {
        Tuner tuner = new Tuner(); Common common = new Common(tuner);
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(common, RadioBackendProfile.REGLINK_S574_ASSISTED)) {
            tuner.afterScan = () -> {
                common.media.muted = true; // OEM scans use shared mute internally.
                tuner.emit("fm", 9010, 13); tuner.emit("fm", 10450, 13);
                tuner.emit("fm", 9010, 13); tuner.emit("fm", 9020, 5);
            };
            tuner.afterStop = () -> common.media.muted = false;
            api.startScan();
            assertArrayEquals(new int[]{90100, 104500}, api.readScanPresets(0, () -> true));
            assertEquals(1, tuner.count(12)); assertEquals(1, tuner.count(13));
        }
    }

    @Test public void assistedRejectsMissingStockOwnerAndPausedOrMixedSource() throws Exception {
        Tuner tuner = new Tuner(); Common common = new Common(tuner);
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(common, RadioBackendProfile.REGLINK_S574_ASSISTED)) {
            common.hub.owner = "bluetooth";
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(0, 101700));
            common.hub.owner = "radio"; common.media.platform = 1;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            common.media.platform = 2; common.media.mix = 8;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::requestStockPlayback);
            common.media.mix = 0; common.media.muted = true;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(3, 999));
            common.media.muted = false; tuner.powered = false;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::requestStockPlayback);
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(0, 101700));
            assertEquals(0, tuner.count(6)); assertEquals(0, tuner.count(12));
            assertEquals(0, tuner.count(3)); assertEquals(0, tuner.count(4));
        }
    }

    @Test public void assistedSourceLossDuringTuneNeverReplaysOrRestoresAudio() throws Exception {
        Tuner tuner = new Tuner(); Common common = new Common(tuner);
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(common, RadioBackendProfile.REGLINK_S574_ASSISTED)) {
            tuner.afterTune = () -> common.hub.owner = "bluetooth";
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(0, 101700));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(0, 102100));
            assertEquals(1, tuner.count(6)); assertEquals(0, tuner.count(3));
        }
    }

    @Test public void assistedSourceLossDuringScanDiscardsResultsWithoutStoppingAnotherOwner() throws Exception {
        Tuner tuner = new Tuner(); Common common = new Common(tuner);
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(common, RadioBackendProfile.REGLINK_S574_ASSISTED)) {
            api.startScan(); tuner.emit("fm", 9010, 13); common.hub.owner = "bluetooth";
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.stopScan(() -> true));
            common.hub.owner = "radio";
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.readScanPresets(0, () -> true));
            assertEquals(0, tuner.count(13));
        }
    }

    @Test public void assistedRejectsNativeTunerBeforeFetchingOrMutatingRadio() throws Exception {
        Tuner tuner = new Tuner(); Common common = new Common(tuner);
        common.shared.module = "mtk_radio"; common.shared.onboardRadio = true;
        assertThrows(RemoteException.class, () -> ReglinkRadioApi.stockAssisted(common, RadioBackendProfile.REGLINK_S574_ASSISTED));
        assertEquals(0, tuner.calls.size());
    }

    @Test public void assistedMalformedSourceReplyCannotReachMutation() throws Exception {
        Tuner tuner = new Tuner(); Common common = new Common(tuner);
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(common, RadioBackendProfile.REGLINK_S574_ASSISTED)) {
            common.media.malformed = true;
            assertThrows(RemoteException.class, () -> api.tuneToBand(0, 101700));
            assertEquals(0, tuner.count(6));
        }
    }

    @Test public void assistedBindingsShareOneObserverAndOperationState() throws Exception {
        Tuner tuner = new Tuner(); Common common = new Common(tuner);
        ReglinkRadioConnectionCache cache = new ReglinkRadioConnectionCache();
        ReglinkRadioApi first = cache.resolve(common, RadioBackendProfile.REGLINK_S574_ASSISTED);
        assertSame(first, cache.resolve(common, RadioBackendProfile.REGLINK_S574_ASSISTED)); assertEquals(1, tuner.count(1));
        assertTrue(first.tuneToBand(0, 101700));
        assertEquals(101700, cache.resolve(common, RadioBackendProfile.REGLINK_S574_ASSISTED).getCurrentFreq());
        first.close();
        ReglinkRadioApi renewed = cache.resolve(common, RadioBackendProfile.REGLINK_S574_ASSISTED);
        assertNotSame(first, renewed); assertEquals(2, tuner.count(1)); renewed.close();
    }

    @Test public void assistedServiceUiEntryAdoptsAudioAndYieldsWithoutAnyAudioWrite() throws Exception {
        exerciseService(false, false);
    }

    /** Optional end-to-end check: operate the actual launcher's Next then Previous controls. */
    @Test public void realAgamaFollowsConfirmedStationAndFavoriteLists() throws Exception {
        org.junit.Assume.assumeTrue("Manual emulator launcher fixture not requested", "true".equals(
                androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("reglinkAgamaManual")));
        exerciseService(true, true);
    }

    @Test public void launcherListTracksPlaybackNotBrowsingOnS602() throws Exception {
        exerciseService(true, false);
    }

    private void exerciseService(boolean agama, boolean manual) throws Exception {
        org.junit.Assume.assumeTrue("Service injection is emulator-only", "ranchu".equals(android.os.Build.HARDWARE)
                || "goldfish".equals(android.os.Build.HARDWARE));
        android.app.Instrumentation instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        android.content.Context context = instrumentation.getTargetContext();
        assertNull(reflected(RadioPlaybackService.class, "runningInstance", null));
        Tuner tuner = new Tuner(); Common common = new Common(tuner);
        try (ReglinkRadioApi api = ReglinkRadioApi.stockAssisted(common, agama
                ? RadioBackendProfile.REGLINK_S602_ASSISTED : RadioBackendProfile.REGLINK_S574_ASSISTED)) {
            java.util.concurrent.CountDownLatch connected = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<android.media.browse.MediaBrowser> reference = new java.util.concurrent.atomic.AtomicReference<>();
            instrumentation.runOnMainSync(() -> reference.set(new android.media.browse.MediaBrowser(context,
                    new android.content.ComponentName(context, RadioPlaybackService.class),
                    new android.media.browse.MediaBrowser.ConnectionCallback() {
                        @Override public void onConnected() {
                            try {
                                Object service = reflected(RadioPlaybackService.class, "runningInstance", null);
                                java.lang.reflect.Field field = RadioPlaybackService.class.getDeclaredField("radio");
                                field.setAccessible(true); field.set(service, api);
                            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                            connected.countDown();
                        }
                    }, null)));
            android.media.browse.MediaBrowser browser = reference.get();
            try {
                instrumentation.runOnMainSync(browser::connect);
                assertTrue(connected.await(5, java.util.concurrent.TimeUnit.SECONDS));
                RadioPlaybackService service = (RadioPlaybackService) reflected(RadioPlaybackService.class, "runningInstance", null);
                context.startForegroundService(new android.content.Intent(context, RadioPlaybackService.class)
                        .setAction("fi.radioplus.app.action.OPEN_UI"));
                awaitRequested(true);
                drainService(instrumentation, service);
                assertTrue(RadioPlaybackService.isPlaybackRequested());
                assertEquals(Boolean.TRUE, reflected(RadioPlaybackService.class, "oemRouteActive", service));
                assertTrue(RadioPlaybackService.shouldIgnoreRawMediaKeys());
                if (agama) {
                    String prefix = "reglink-agama-" + java.util.UUID.randomUUID() + "-";
                    java.util.Set<String> prefs = new java.util.HashSet<>();
                    android.content.Context isolated = new android.content.ContextWrapper(context) {
                        @Override public android.content.Context getApplicationContext() { return this; }
                        @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                            prefs.add(prefix + name);
                            return context.getSharedPreferences(prefix + name, mode);
                        }
                    };
                    FavoriteStore favorites = new FavoriteStore(isolated);
                    StationStore catalog = new StationStore(isolated);
                    StationNavigationStore nav = new StationNavigationStore(isolated);
                    try {
                        FavoriteStation alpha = new FavoriteStation(0, 98100, "QA Alpha");
                        catalog.save(alpha);
                        catalog.save(new FavoriteStation(0, 100900, "QA Catalog"));
                        setServiceField(instrumentation, service, "stationStore", catalog);
                        setServiceField(instrumentation, service, "favoriteStore", favorites);
                        setServiceField(instrumentation, service, "stationNavigationStore", nav);
                        assertTrue(nav.playbackFromFavorites()); // dev40 upgrade, no confirmed selection yet.
                        for (boolean fromFavorites : new boolean[]{false, true}) {
                            if (fromFavorites) {
                                favorites.save(alpha); // Same station in both lists, different successor.
                                favorites.save(new FavoriteStation(0, 104800, "QA Favorite"));
                            }
                            nav.setFavoritesSelected(fromFavorites);
                            RadioPlaybackService.tuneStation(context, alpha, fromFavorites);
                            nav.setFavoritesSelected(!fromFavorites); // Browse without playing.
                            drainService(instrumentation, service);
                            assertEquals(fromFavorites, new StationNavigationStore(isolated).playbackFromFavorites());
                            int initialWrites = tuner.tunes.size();
                            // A wheel-like hardware event from a non-launcher controller must
                            // not add a Radio+ tune or turn the OEM seek into a second skip.
                            android.media.session.MediaController wheel = new android.media.session.MediaController(
                                    context, browser.getSessionToken());
                            long down = android.os.SystemClock.uptimeMillis();
                            for (int action : new int[]{android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP})
                                wheel.dispatchMediaButtonEvent(new android.view.KeyEvent(down, down, action,
                                        android.view.KeyEvent.KEYCODE_MEDIA_NEXT, 0, 0, 3, 87));
                            wheel.getTransportControls().skipToNext();
                            drainService(instrumentation, service);
                            assertEquals(initialWrites, tuner.tunes.size());
                            if (manual) {
                                android.util.Log.i("ReglinkAgamaQA", fromFavorites ? "READY_FAVORITES" : "READY_CATALOG");
                                long end = android.os.SystemClock.elapsedRealtime() + 120_000;
                                while (android.os.SystemClock.elapsedRealtime() < end) {
                                    drainService(instrumentation, service);
                                    if (tuner.tunes.size() >= initialWrites + 2) break;
                                    android.os.SystemClock.sleep(200);
                                }
                            } else {
                                java.lang.reflect.Method skip = RadioPlaybackService.class.getDeclaredMethod(
                                        "selectAdjacentStation", boolean.class, ReglinkRadioApi.class);
                                skip.setAccessible(true);
                                instrumentation.runOnMainSync(() -> {
                                    try { skip.invoke(service, true, api); skip.invoke(service, false, api); }
                                    catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                                });
                            }
                            drainService(instrumentation, service);
                            assertEquals(Arrays.asList(fromFavorites ? "fm:10480" : "fm:10090", "fm:9810"),
                                    tuner.tunes.subList(initialWrites, tuner.tunes.size()));
                        }
                        if (!manual && BuildConfig.REGLINK_STOCK_ASSISTED) {
                            // Foreground wheel follows the highlighted tab, unlike a launcher
                            // that cannot see it. Run the actual service/tuner path, including
                            // its cancellation guard and the registered OEM callback.
                            java.util.concurrent.atomic.AtomicBoolean visible = new java.util.concurrent.atomic.AtomicBoolean(true);
                            try (ReglinkWheelKeys keys = new ReglinkWheelKeys(common, android.os.Process.myUid(),
                                    visible::get, next -> RadioPlaybackService.postWheelSkip(api, next, visible::get))) {
                                keys.open();
                                long ts = System.currentTimeMillis();
                                for (boolean favTab : new boolean[]{false, true}) {
                                    nav.setFavoritesSelected(favTab);
                                    int before = tuner.tunes.size();
                                    assertTrue(common.keys.press(66, ++ts));
                                    assertTrue(common.keys.press(66, ts)); // one physical gesture
                                    drainService(instrumentation, service);
                                    assertEquals(favTab, nav.playbackFromFavorites());
                                    assertTrue(common.keys.press(65, ++ts));
                                    drainService(instrumentation, service);
                                    assertEquals(Arrays.asList(favTab ? "fm:10480" : "fm:10090", "fm:9810"),
                                            tuner.tunes.subList(before, tuner.tunes.size()));
                                }
                                int before = tuner.tunes.size();
                                // Cancel an accepted press before the main thread runs it.
                                instrumentation.runOnMainSync(() -> {
                                    assertTrue(RadioPlaybackService.postWheelSkip(api, true, visible::get));
                                    visible.set(false);
                                });
                                drainService(instrumentation, service);
                                assertEquals(before, tuner.tunes.size());
                            }
                        }
                        // A rejected selection from the other list must not replace the origin.
                        RadioPlaybackService.tuneStation(context, new FavoriteStation(3, 522, "Invalid for this tuner"), false);
                        drainService(instrumentation, service);
                        assertTrue(nav.playbackFromFavorites());
                        return;
                    } finally { for (String name : prefs) context.deleteSharedPreferences(name); }
                }
                android.media.session.MediaController controller = new android.media.session.MediaController(context, browser.getSessionToken());
                controller.getTransportControls().skipToNext();
                controller.getTransportControls().skipToPrevious();
                controller.getTransportControls().pause(); drainService(instrumentation, service);
                assertTrue("Do not claim a pause that cannot be performed safely", RadioPlaybackService.isPlaybackRequested());
                common.hub.owner = "bluetooth";
                context.startForegroundService(new android.content.Intent(context, RadioPlaybackService.class)
                        .setAction("fi.radioplus.app.action.OPEN_UI"));
                awaitRequested(false);
                drainService(instrumentation, service);
                assertFalse(RadioPlaybackService.isPlaybackRequested());
                assertEquals(0, tuner.count(3)); assertEquals(0, tuner.count(4));
                assertEquals(0, tuner.count(6)); assertEquals(0, tuner.count(12));
                assertTrue(context.getSystemService(android.media.AudioManager.class).getActivePlaybackConfigurations().isEmpty());
            } finally {
                instrumentation.runOnMainSync(() -> {
                    browser.disconnect(); context.stopService(new android.content.Intent(context, RadioPlaybackService.class));
                });
                long end = android.os.SystemClock.elapsedRealtime() + 5000;
                while (reflected(RadioPlaybackService.class, "runningInstance", null) != null
                        && android.os.SystemClock.elapsedRealtime() < end) android.os.SystemClock.sleep(20);
                assertNull(reflected(RadioPlaybackService.class, "runningInstance", null));
            }
        }
    }

    private static Object reflected(Class<?> type, String name, Object target) {
        try { java.lang.reflect.Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void setServiceField(android.app.Instrumentation ins, RadioPlaybackService service,
            String name, Object value) {
        ins.runOnMainSync(() -> {
            try {
                java.lang.reflect.Field field = RadioPlaybackService.class.getDeclaredField(name);
                field.setAccessible(true); field.set(service, value);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }

    private static void awaitRequested(boolean expected) {
        long end = android.os.SystemClock.elapsedRealtime() + 5000;
        while (RadioPlaybackService.isPlaybackRequested() != expected && android.os.SystemClock.elapsedRealtime() < end) {
            android.os.SystemClock.sleep(20);
        }
        assertEquals(expected, RadioPlaybackService.isPlaybackRequested());
    }

    private static void drainService(android.app.Instrumentation instrumentation, RadioPlaybackService service) throws Exception {
        instrumentation.waitForIdleSync();
        ((java.util.concurrent.ExecutorService) reflected(RadioPlaybackService.class, "executor", service))
                .submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
    }

    @Test public void constructorAndPollingOnlyUseVerifiedReadTransactions() throws Exception {
        Tuner tuner = new Tuner();
        Common common = new Common(tuner);
        try (ReglinkRadioApi api = new ReglinkRadioApi(common)) {
            assertSame(common, api.asBinder());
            assertEquals(Arrays.asList(2, 2), common.calls);
            assertEquals(Arrays.asList("SharedVariable", "Radio"), common.services);
            assertEquals(Arrays.asList(9, 7, 9, 9, 7, 9), common.shared.calls);
            ReglinkRadioApi.Snapshot state = api.snapshot();
            assertEquals(0, state.band);
            assertEquals(98100, state.khz);
            assertTrue(state.powered);
            assertFalse(state.callbackStateKnown);
            assertFalse(state.stereo);
            assertNull(tuner.callback);
            assertFalse(api.IsAS()); assertFalse(api.IsScan()); assertFalse(api.IsSeek());
            for (int call : tuner.calls) assertTrue(Arrays.asList(5, 7, 14, 15, 20).contains(call));
        }
    }

    @Test public void dormantProfileCannotBindOrMasqueradeAsHcn() throws Exception {
        Tuner tuner = new Tuner();
        Common common = new Common(tuner);
        assertThrows(RemoteException.class,
                () -> RadioApiFactory.create(RadioBackendProfile.REGLINK_S540, common));
        assertTrue(common.calls.isEmpty());
        assertTrue(tuner.calls.isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> RadioBackendContract.serviceIntent(RadioBackendProfile.REGLINK_S540));
        try (ReglinkRadioApi api = new ReglinkRadioApi(common)) {
            assertFalse(RadioApiFactory.usesHcnFramework(api));
            assertFalse(RadioApiFactory.supportsScanning(api));
            assertFalse(RadioApiFactory.supportsLocalMode(api));
            assertFalse(RadioApiFactory.supportsOemFavorites(api));
            assertFalse(RadioApiFactory.supportsSeparateAudioFocus(api));
        }
    }

    @Test public void wrongAndUnavailableEndpointsFailClosed() throws Exception {
        Binder wrong = new Binder(); wrong.attachInterface(null, "unrelated");
        assertThrows(RemoteException.class, () -> new ReglinkRadioApi(null));
        assertThrows(RemoteException.class, () -> new ReglinkRadioApi(wrong));
        assertThrows(RemoteException.class, () -> new ReglinkRadioApi(new Common(wrong)));
        assertThrows(RemoteException.class, () -> new ReglinkRadioApi(new Common(null)));
        Tuner tuner = new Tuner(); tuner.band = ""; tuner.frequency = 0;
        assertThrows(RemoteException.class, () -> new ReglinkRadioApi(new Common(tuner)));
        assertFalse(tuner.calls.contains(6));
    }

    @Test public void directTuneUsesAtomicBandAndCorrectFmAmUnits() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            assertTrue(api.tuneToBand(2, 101700));
            assertEquals("fm", tuner.band); assertEquals(10170, tuner.frequency);
            assertTrue(api.tuneToBand(3, 999));
            assertEquals("am", tuner.band); assertEquals(999, tuner.frequency);
            assertEquals(3, api.snapshot().band);
            assertEquals(999, api.getCurrentFreq());
            assertEquals(2, tuner.count(6));
        }
    }

    @Test public void nativeHardwareCannotAcquireAmFromPermissiveFacade() throws Exception {
        Tuner tuner = new Tuner();
        Common common = new Common(tuner);
        common.shared.module = "mtk_radio";
        common.shared.onboardRadio = true;
        try (ReglinkRadioApi api = new ReglinkRadioApi(common)) {
            assertTrue(api.supportsBand(0));
            assertTrue(api.supportsBand(2));
            assertFalse(api.supportsBand(3));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(3, 999));
            assertEquals(0, tuner.count(6));
            assertTrue(api.tuneToBand(0, 101700));
            assertEquals(1, tuner.count(6));
        }
    }

    @Test public void unknownOrUninitializedHardwareIsRejectedBeforeFetchingRadio() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            Tuner tuner = new Tuner();
            Common common = new Common(tuner);
            if (scenario == 0) common.shared.module = "sprd_radio";
            if (scenario == 1) common.shared.initialized = false;
            if (scenario == 2) common.shared.onboardRadio = true;
            assertThrows(RemoteException.class, () -> new ReglinkRadioApi(common));
            assertEquals(Arrays.asList("SharedVariable"), common.services);
            assertTrue(tuner.calls.isEmpty());
        }
    }

    @Test public void changedHardwareLatchesControlOffEvenIfOriginalModuleReturns() throws Exception {
        for (int scenario = 0; scenario < 4; scenario++) {
            Tuner tuner = new Tuner();
            Common common = new Common(tuner);
            try (ReglinkRadioApi api = new ReglinkRadioApi(common)) {
                if (scenario == 0) common.shared.module = "4755";
                if (scenario == 1) common.shared.initialized = false;
                if (scenario == 2) common.shared.onboardRadio = true;
                if (scenario == 3) common.shared.rejectReads = true;
                assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(0, 101700));
                common.shared.module = "4754";
                common.shared.initialized = true;
                common.shared.onboardRadio = false;
                common.shared.rejectReads = false;
                assertFalse(api.supportsBand(0));
                assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
                assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(0, 98100));
                assertEquals(98100, api.snapshot().khz); // Read-only diagnosis remains possible.
                assertEquals(0, tuner.count(6));
                assertEquals(0, tuner.count(12));
                assertEquals(0, tuner.count(13));
            }
        }
    }

    @Test public void hardwareChangeDuringRegistrationOrBeforeStopCannotMutateTuner() throws Exception {
        Tuner tuner = new Tuner();
        Common common = new Common(tuner);
        try (ReglinkRadioApi api = new ReglinkRadioApi(common)) {
            tuner.afterRegistration = () -> common.shared.module = "7786";
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            assertEquals(0, tuner.count(12));
        }
        Tuner scanning = new Tuner();
        Common scanningCommon = new Common(scanning);
        try (ReglinkRadioApi api = new ReglinkRadioApi(scanningCommon)) {
            api.startScan();
            scanningCommon.shared.initialized = false;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.stopScan(() -> true));
            assertEquals(0, scanning.count(13));
        }
    }

    @Test public void alreadyTunedRequestIsReadOnlyEvenWhenPausedOrUsingAnFmAlias() throws Exception {
        Tuner tuner = new Tuner(); tuner.powered = false;
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            assertTrue(api.tuneToBand(0, 98100));
            assertTrue(api.tuneToBand(2, 98100));
            api.gotoFreq(98100);
            api.gotoFreq2("98100");
            assertEquals(0, tuner.count(6));
            assertFalse(tuner.powered);
            assertFalse(api.tuneToBand(0, 98100, () -> false));
            tuner.band = "am"; tuner.frequency = 999;
            assertTrue(api.tuneToBand(3, 999));
            assertEquals(0, tuner.count(6));
        }
    }

    @Test public void invalidAndUnsupportedTargetsDoNotMutate() throws Exception {
        Tuner tuner = new Tuner(); tuner.supportsAm = false;
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(3, 999));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(3, 522));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(0, 98150));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(9, 98100));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.gotoFreq2("bad"));
            assertFalse(api.tuneToBand(0, 101700, () -> false));
            assertEquals(0, tuner.count(6));
            assertEquals(0, tuner.count(12));
        }
    }

    @Test public void cancelledDeliveredTuneBlocksReplayUntilReadbackMatches() throws Exception {
        Tuner tuner = new Tuner(); tuner.deferTune = true;
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            assertFalse(api.tuneToBand(0, 101700, () -> tuner.count(6) == 0));
            assertEquals(1, tuner.count(6));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(0, 98100));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(0, 102100));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::onSeekDownEvent);
            assertEquals(1, tuner.count(6));
            tuner.frequency = 10170;
            assertEquals(101700, api.snapshot().khz);
            tuner.deferTune = false;
            assertTrue(api.tuneToBand(0, 102100));
            assertEquals(2, tuner.count(6));
        }
    }

    @Test public void snapshotDuringDispatchCannotAcknowledgeAnUncertainTune() throws Exception {
        Tuner tuner = new Tuner(); tuner.deferTune = true;
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            tuner.afterTune = () -> {
                tuner.frequency = 10170;
                assertEquals(101700, api.snapshot().khz);
                tuner.frequency = 9810;
            };
            assertFalse(api.tuneToBand(0, 101700, () -> tuner.count(6) == 0));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class,
                    () -> api.tuneToBand(0, 98100));
            assertEquals(1, tuner.count(6));
            tuner.afterTune = null;
            tuner.frequency = 10170;
            assertEquals(101700, api.snapshot().khz);
            assertTrue(api.tuneToBand(0, 101700)); // Read-only after reconciliation.
            assertEquals(1, tuner.count(6));
        }
    }

    @Test public void unconfirmedHardwareSeekIsDisabledAndManualDirectionIsPreserved() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::onSeekDownEvent);
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::onSeekUpEvent);
            assertEquals(0, tuner.count(10)); assertEquals(0, tuner.count(11));
            api.onManualDownEvent(); assertEquals(9820, tuner.frequency);
            api.onManualUpEvent(); assertEquals(9810, tuner.frequency);
        }
    }

    @Test public void callbackProgressCannotReplaceReadbackFrequency() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            api.startObserving(); api.startObserving();
            assertEquals(1, tuner.count(1));
            tuner.emit("fm", 9810, 35);
            ReglinkRadioApi.Snapshot state = api.snapshot();
            assertTrue(state.callbackStateKnown); assertTrue(state.stereo); assertTrue(state.seeking);
            tuner.emit("fm", 10210, 3);
            state = api.snapshot();
            assertEquals(98100, state.khz);
            assertFalse(state.callbackStateKnown);
            tuner.emit("fm", 98100, 3); // Known malformed OEM final-callback scale.
            assertEquals(98100, api.snapshot().khz);
        }
    }

    @Test public void requestedScansCaptureOnlyMatchingSignalConfirmedValidResults() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            api.startObserving();
            tuner.emit("fm", 8810, 13); // Not our scan.
            api.startScan();
            tuner.emit("fm", 9010, 5); // Traversal, no valid-signal bit.
            tuner.emit("unknown", 999, 13); // Malformed band is not handoff evidence.
            tuner.emit("fm", 9815, 13); // Off app's explicit 100 kHz target grid.
            tuner.emit("fm", 9010, 13);
            tuner.emit("fm", 9010, 13); // Duplicate.
            tuner.emit("fm", 10210, 13);
            tuner.scanning = false;
            tuner.emit("fm", 102100, 9); // Invalid frequency but terminal scan event.
            assertArrayEquals(new int[]{90100, 102100}, api.readScanPresets(0, () -> true));
            assertEquals(0, tuner.count(13));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class,
                    () -> api.readScanPresets(3, () -> true));
        }
    }

    @Test public void stopOnlyTouchesRequestedScanAndWaitsForItsTerminalCallback() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            tuner.scanning = true;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            assertFalse(api.stopScan(() -> true));
            assertEquals(0, tuner.count(12)); assertEquals(0, tuner.count(13));
            tuner.scanning = false;
            api.onASEvent();
            tuner.emit("fm", 9010, 13);
            assertFalse(api.stopScan(() -> false));
            assertEquals(0, tuner.count(13));
            api.onASEvent();
            assertEquals(1, tuner.count(13));
            assertArrayEquals(new int[]{90100}, api.readScanPresets(0, () -> true));
        }
    }

    @Test public void getterFalseDoesNotDiscardQueuedScanResults() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            api.startScan();
            tuner.scanning = false; // Synchronous getter overtakes Binder callback queue.
            api.snapshot();
            tuner.emit("fm", 9010, 13);
            tuner.emit("fm", 9810, 1);
            assertArrayEquals(new int[]{90100}, api.readScanPresets(0, () -> true));
        }
    }

    @Test public void terminalCallbackCannotOverrideGetterThatStillReportsScanning() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            api.startScan();
            tuner.emit("fm", 9010, 13);
            tuner.emit("fm", 9810, 1); // Callback precedes a consistent getter.
            assertThrows(ReglinkRadioApi.CommandRejectedException.class,
                    () -> api.readScanPresets(0, () -> true));
            assertEquals(0, tuner.count(13)); // A contradictory scan must not be retuned.
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            tuner.scanning = false;
            assertArrayEquals(new int[]{90100}, api.readScanPresets(0, () -> true));
            assertEquals(0, tuner.count(13));
        }
    }

    @Test public void bandHandoffInvalidatesScanWithoutStoppingTheNewBand() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            api.startScan();
            tuner.emit("fm", 9010, 13);
            tuner.band = "am"; tuner.frequency = 999;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.stopScan(() -> true));
            assertEquals(0, tuner.count(13));
            tuner.emit("fm", 9810, 1); // Delayed old-band completion cannot revive results.
            tuner.scanning = false;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class,
                    () -> api.readScanPresets(0, () -> true));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class,
                    () -> api.tuneToBand(3, 999));
            assertEquals(0, tuner.count(6));
        }
    }

    @Test public void newScanAfterCompletionInvalidatesOldResultsWithoutStoppingIt() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            api.startScan();
            tuner.emit("fm", 9010, 13);
            tuner.scanning = false;
            tuner.emit("fm", 9810, 1);
            assertArrayEquals(new int[]{90100}, api.readScanPresets(0, () -> true));
            tuner.scanning = true;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class,
                    () -> api.readScanPresets(0, () -> true));
            assertEquals(1, tuner.count(12));
            assertEquals(0, tuner.count(13));
        }
    }

    @Test public void queuedScanStartFollowingTerminalCannotBeMixedIntoOldResults() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            api.startScan();
            tuner.emit("fm", 9010, 13);
            tuner.emit("fm", 9810, 1);
            tuner.emit("fm", 10210, 13);
            tuner.scanning = false;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class,
                    () -> api.readScanPresets(0, () -> true));
            assertEquals(0, tuner.count(13));
        }
    }

    @Test public void completeInterveningScanInvalidatesCachedResultsBetweenGetterPolls() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            api.startScan();
            tuner.emit("fm", 9010, 13);
            tuner.scanning = false;
            tuner.emit("fm", 9810, 1);
            assertArrayEquals(new int[]{90100}, api.readScanPresets(0, () -> true));
            tuner.emit("fm", 9810, 5);
            tuner.emit("fm", 10210, 13);
            tuner.emit("fm", 9810, 1);
            assertThrows(ReglinkRadioApi.CommandRejectedException.class,
                    () -> api.readScanPresets(0, () -> true));
            assertEquals(0, tuner.count(13));
        }
    }

    @Test public void validBandHandoffBetweenGetterPollsInvalidatesResults() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            api.startScan();
            tuner.emit("fm", 9010, 13);
            tuner.emit("am", 999, 13);
            tuner.emit("fm", 9810, 1);
            tuner.scanning = false;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class,
                    () -> api.readScanPresets(0, () -> true));
            assertEquals(0, tuner.count(13));
        }
    }

    @Test public void callbackOvertakingGetterSnapshotCannotFabricateScanCompletion() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            api.startScan();
            tuner.emit("fm", 9010, 13);
            tuner.scanning = false;
            int[] bandReads = {0};
            tuner.beforeBandRead = () -> {
                if (++bandReads[0] == 2) {
                    tuner.scanning = true;
                    tuner.emit("fm", 9810, 1);
                    tuner.beforeBandRead = null;
                }
            };
            assertThrows(ReglinkRadioApi.CommandRejectedException.class,
                    () -> api.readScanPresets(0, () -> true));
            assertEquals(0, tuner.count(13));
            tuner.scanning = false;
            assertArrayEquals(new int[]{90100}, api.readScanPresets(0, () -> true));
        }
    }

    @Test public void continuouslyChangingCallbackEpochExhaustsBoundedSnapshotRetries() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            api.startObserving();
            long previousBandReads = tuner.count(5);
            tuner.beforeBandRead = () -> tuner.emit("fm", 9810, 1);
            assertThrows(RemoteException.class, api::snapshot);
            assertEquals(6, tuner.count(5) - previousBandReads);
            assertEquals(0, tuner.count(6));
            tuner.beforeBandRead = null;
        }
    }

    @Test public void invalidationDuringFinalResultTokenCheckPreventsCachedCopy() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            api.startScan();
            tuner.emit("fm", 9010, 13);
            tuner.scanning = false;
            tuner.emit("fm", 9810, 1);
            assertArrayEquals(new int[]{90100}, api.readScanPresets(0, () -> true));
            int[] checks = {0};
            assertThrows(ReglinkRadioApi.CommandRejectedException.class,
                    () -> api.readScanPresets(0, () -> {
                        if (++checks[0] == 5) {
                            try { tuner.emit("am", 999, 1); }
                            catch (RemoteException failure) { throw new AssertionError(failure); }
                        }
                        return true;
                    }));
            assertEquals(5, checks[0]);
            assertEquals(0, tuner.count(13));
        }
    }

    @Test public void cancellationDuringDeliveredScanDoesNotClaimSuccessOrReplay() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            boolean[] current = {true};
            tuner.afterScan = () -> current[0] = false;
            assertFalse(api.startScan(() -> current[0]));
            assertEquals(1, tuner.count(12));
            assertEquals(0, tuner.count(13));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            assertEquals(1, tuner.count(12));
        }
    }

    @Test public void missingMutationReplyStatusDoesNotClaimSuccessOrReplay() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            tuner.emptyReplyCode = 12;
            assertThrows(RemoteException.class, api::startScan);
            assertEquals(1, tuner.count(12));
            tuner.emptyReplyCode = -1;
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            assertEquals(1, tuner.count(12));
            assertEquals(0, tuner.count(13));
        }
    }

    @Test public void changedBandOrScanDuringRegistrationPreventsScanCommand() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            tuner.afterRegistration = () -> { tuner.band = "am"; tuner.frequency = 999; };
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            assertEquals(0, tuner.count(12));
        }
        Tuner scanning = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(scanning))) {
            scanning.afterRegistration = () -> { scanning.scanning = true; scanning.emit("fm", 9810, 5); };
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            assertEquals(0, scanning.count(12));
        }
    }

    @Test public void cancellationAndCloseCannotReturnCachedScanSuccessOrStartHardware() throws Exception {
        Tuner tuner = new Tuner();
        ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner));
        assertFalse(api.startScan(() -> false));
        assertEquals(0, tuner.count(1));
        assertFalse(api.startScan(() -> tuner.callback == null));
        assertEquals(0, tuner.count(12));
        api.startScan();
        tuner.scanning = false; tuner.emit("fm", 9810, 1);
        assertArrayEquals(new int[0], api.readScanPresets(0, () -> true));
        int reads = tuner.calls.size();
        assertFalse(api.stopScan(() -> false));
        assertThrows(ReglinkRadioApi.CommandRejectedException.class,
                () -> api.readScanPresets(0, () -> false));
        assertEquals(reads, tuner.calls.size());
        assertFalse(api.startScan(() -> { api.close(); return true; }));
        assertFalse(api.stopScan(() -> true));
        assertThrows(ReglinkRadioApi.CommandRejectedException.class,
                () -> api.readScanPresets(0, () -> true));
        assertEquals(1, tuner.count(12));
        assertEquals(0, tuner.count(13));
        assertEquals(1, tuner.count(2));
    }

    @Test public void failedRegistrationCleanupIsRetainedAndRetriedWithoutDuplicatingClients() throws Exception {
        Tuner tuner = new Tuner();
        ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner));
        tuner.trailingCode = 1; // Registered, then malformed reply.
        tuner.rejectCode = 2; // First removal fails too.
        assertThrows(RemoteException.class, api::startObserving);
        IBinder abandoned = tuner.callback;
        assertNotNull(abandoned);
        assertEquals(1, tuner.count(1));
        assertEquals(1, tuner.count(2));
        tuner.trailingCode = -1;
        assertThrows(RemoteException.class, api::startObserving);
        assertEquals(1, tuner.count(1));
        tuner.rejectCode = -1;
        api.close(); api.close();
        assertNull(tuner.callback);
        assertEquals(3, tuner.count(2));
        Tuner.send(abandoned, "fm", 9810, 3); // Invalidated generation stays detached.
    }

    @Test public void failedCloseUnregistrationCanBeRetriedWithoutTunerMutation() throws Exception {
        Tuner tuner = new Tuner();
        ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner));
        api.startObserving();
        tuner.rejectCode = 2;
        api.close();
        assertNotNull(tuner.callback);
        tuner.rejectCode = -1;
        api.close(); api.close();
        assertNull(tuner.callback);
        assertEquals(2, tuner.count(2));
        assertEquals(0, tuner.count(6));
        assertEquals(0, tuner.count(12));
        assertEquals(0, tuner.count(13));
    }

    @Test public void scanStopTimesOutWithoutTerminalEvidenceAndDoesNotReplay() throws Exception {
        Tuner tuner = new Tuner(); tuner.stopEmitsCallback = false;
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            api.startScan();
            long before = android.os.SystemClock.elapsedRealtime();
            assertFalse(api.stopScan(() -> true));
            long elapsed = android.os.SystemClock.elapsedRealtime() - before;
            assertTrue("Stop wait must be bounded", elapsed < 4000);
            assertEquals(1, tuner.count(13));
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startScan);
            assertThrows(ReglinkRadioApi.CommandRejectedException.class, () -> api.tuneToBand(0, 101700));
            assertFalse(api.stopScan(() -> false));
            assertEquals(1, tuner.count(13));
            tuner.emit("fm", 9810, 1);
            assertArrayEquals(new int[0], api.readScanPresets(0, () -> true));
            assertEquals(1, tuner.count(13));
        }
    }

    @Test public void audioAndUnverifiedFeaturesNeverClaimSupportOrSendCommands() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            int count = tuner.calls.size();
            assertFalse(api.requestPlayAudio());
            assertThrows(RemoteException.class, api::requestAudioFocus);
            assertThrows(RemoteException.class, api::releaseAudioFocus);
            assertThrows(RemoteException.class, api::onLocDxEvent);
            assertThrows(RemoteException.class, api::onPSEvent);
            assertThrows(RemoteException.class, api::favoriteCurrentFreq);
            assertEquals("", api.getCurrentFreqRdsPs());
            assertFalse(api.IsDxLocal()); assertFalse(api.IsPS());
            assertEquals(count, tuner.calls.size());
        }
    }

    @Test public void closeUnregistersOnceAndNeverPowersOrStopsSharedTuner() throws Exception {
        Tuner tuner = new Tuner();
        ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner));
        api.startScan();
        IBinder old = tuner.callback;
        api.close(); api.close();
        assertEquals(1, tuner.count(2));
        assertEquals(0, tuner.count(13));
        assertNull(tuner.callback);
        Tuner.send(old, "fm", 9010, 13); // Stale callbacks must be harmless.
        assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::snapshot);
        assertThrows(ReglinkRadioApi.CommandRejectedException.class, api::startObserving);
    }

    @Test public void malformedRepliesAndUnstableReadbacksFailClosed() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            tuner.rejectCode = 7;
            assertThrows(RemoteException.class, api::snapshot);
            tuner.rejectCode = -1; tuner.trailingCode = 7;
            assertThrows(RemoteException.class, api::snapshot);
            tuner.trailingCode = -1; tuner.changingFrequency = true;
            assertThrows(RemoteException.class, api::snapshot);
            assertEquals(0, tuner.count(6));
        }
    }
}
