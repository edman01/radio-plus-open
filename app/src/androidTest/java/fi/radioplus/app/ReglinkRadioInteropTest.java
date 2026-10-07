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

/** Synthetic protocol endpoints only; the dormant adapter never binds an OEM APK here. */
public final class ReglinkRadioInteropTest {
    private static final class Tuner extends Binder {
        final List<Integer> calls = new ArrayList<>();
        String band = "fm";
        int frequency = 9810;
        boolean supportsAm = true;
        boolean scanning;
        boolean powered = true;
        boolean deferTune;
        boolean changingFrequency;
        boolean stopEmitsCallback = true;
        int rejectCode = -1;
        int trailingCode = -1;
        IBinder callback;

        Tuner() { attachInterface(null, ReglinkRadioApi.RADIO_DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            data.enforceInterface(ReglinkRadioApi.RADIO_DESCRIPTOR);
            calls.add(code);
            assertEquals(0, flags);
            if (code == rejectCode) return false;
            switch (code) {
                case 1: callback = data.readStrongBinder(); break;
                case 2:
                    assertSame(callback, data.readStrongBinder());
                    callback = null;
                    break;
                case 6:
                    String targetBand = data.readString();
                    int target = data.readInt();
                    assertTrue("fm".equals(targetBand) || "am".equals(targetBand));
                    if (!deferTune) { band = targetBand; frequency = target; }
                    break;
                case 10: case 11: break;
                case 12:
                    scanning = true;
                    emit(band, frequency, 5);
                    break;
                case 13:
                    scanning = false;
                    if (stopEmitsCallback) emit(band, frequency, 1);
                    break;
                case 20:
                    String queried = data.readString();
                    assertTrue("fm".equals(queried) || "am".equals(queried));
                    assertEquals(0, data.dataAvail());
                    reply.writeNoException();
                    reply.writeInt("fm".equals(queried) || supportsAm ? 1 : 0);
                    return true;
                case 5: case 7: case 14: case 15: break;
                default: fail("Unsafe/unexpected radio transaction " + code);
            }
            assertEquals("Trailing input for code " + code, 0, data.dataAvail());
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

    private static final class Common extends Binder {
        final IBinder tuner;
        final List<Integer> calls = new ArrayList<>();
        Common(IBinder tuner) {
            this.tuner = tuner;
            attachInterface(null, ReglinkRadioApi.COMMON_DESCRIPTOR);
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(ReglinkRadioApi.COMMON_DESCRIPTOR);
            calls.add(code);
            assertEquals(2, code);
            assertEquals(0, flags);
            assertEquals("Radio", data.readString());
            assertEquals(0, data.dataAvail());
            reply.writeNoException();
            reply.writeStrongBinder(tuner);
            return true;
        }
    }

    @Test public void constructorAndPollingOnlyUseVerifiedReadTransactions() throws Exception {
        Tuner tuner = new Tuner();
        Common common = new Common(tuner);
        try (ReglinkRadioApi api = new ReglinkRadioApi(common)) {
            assertSame(common, api.asBinder());
            assertEquals(Arrays.asList(2), common.calls);
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

    @Test public void scansCaptureOnlyOwnedMatchingSignalConfirmedValidResults() throws Exception {
        Tuner tuner = new Tuner();
        try (ReglinkRadioApi api = new ReglinkRadioApi(new Common(tuner))) {
            api.startObserving();
            tuner.emit("fm", 8810, 13); // Not our scan.
            api.startScan();
            tuner.emit("fm", 9010, 5); // Traversal, no valid-signal bit.
            tuner.emit("am", 999, 13); // Wrong band.
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

    @Test public void stopOnlyTouchesOwnedScanAndWaitsForItsTerminalCallback() throws Exception {
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
