package fi.radioplus.app;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.test.platform.app.InstrumentationRegistry;
import com.hcn.autoradio.IRadioServiceAPI;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Synthetic endpoints only: no stock APK, JNI library or vendor implementation. */
public final class TsRadioInteropTest {
    static final class Endpoint extends Binder {
        final List<Integer> commonCalls = new CopyOnWriteArrayList<>();
        final List<Integer> radioCalls = new CopyOnWriteArrayList<>();
        volatile int rawBand;
        volatile int rawFrequency = 9810;
        volatile int mode = 1;
        volatile int seekDirection = -1;
        int flags = 5;
        boolean reject;
        boolean denied;
        boolean stuckBand;
        int fmMinimum = 8750;
        int fmSpacing = 10;
        int fmCount = 206;
        int lastTuningIndex = -1;
        boolean wrongRoundTrip;
        boolean changeBandDuringGridRead;
        IBinder nested;

        Endpoint() {
            attachInterface(null, TsRadioApi.COMMON_DESCRIPTOR);
            nested = new Binder() {
                { attachInterface(null, TsRadioApi.RADIO_DESCRIPTOR); }
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int f) {
                    data.enforceInterface(TsRadioApi.RADIO_DESCRIPTOR);
                    assertEquals(0, f);
                    radioCalls.add(code);
                    if (reject) return false;
                    if (denied) { reply.writeException(new SecurityException("denied")); return true; }
                    reply.writeNoException();
                    switch (code) {
                        case 3: reply.writeInt(rawBand); break;
                        case 8: reply.writeInt(rawBand < 4 ? fmCount : 123); break;
                        case 9:
                            int index = data.readInt();
                            reply.writeInt(rawBand < 4
                                    ? fmMinimum + index * fmSpacing + (wrongRoundTrip && index > 1 ? 1 : 0)
                                    : 522 + index * 9);
                            if (changeBandDuringGridRead && index > 1) rawBand = 4;
                            break;
                        case 14: if (!stuckBand) rawBand = 0; break;
                        case 15: if (!stuckBand) rawBand = 4; break;
                        case 19: if (!stuckBand) rawBand = rawBand == 2 ? 4 : rawBand >= 5 ? 0 : rawBand + 1; break;
                        case 20: seekDirection = data.readInt(); break;
                        case 25: assertEquals(1, data.readInt()); reply.writeInt(rawFrequency); break;
                        case 27: reply.writeInt(flags); break;
                        case 29: mode = data.readInt(); break;
                        case 30: reply.writeString("QA\u0000\u0000  "); break;
                        case 34:
                            lastTuningIndex = data.readInt();
                            assertTrue("TuneFset takes a zero-based grid index", lastTuningIndex >= 0
                                    && lastTuningIndex < (rawBand < 4 ? fmCount : 123));
                            rawFrequency = rawBand < 4 ? fmMinimum + lastTuningIndex * fmSpacing
                                    : 522 + lastTuningIndex * 9;
                            break;
                        default: fail("Unverified TS radio transaction " + code);
                    }
                    assertEquals(0, data.dataAvail());
                    return true;
                }
            };
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int f) {
            data.enforceInterface(TsRadioApi.COMMON_DESCRIPTOR);
            assertEquals(0, f);
            commonCalls.add(code);
            reply.writeNoException();
            if (code == 55) {
                assertEquals("Radio", data.readString()); reply.writeStrongBinder(nested);
            } else if (code == 10) reply.writeInt(mode);
            else fail("Unverified TS common transaction " + code);
            assertEquals(0, data.dataAvail());
            return true;
        }
        TsRadioApi api() throws Exception {
            return (TsRadioApi) RadioApiFactory.create(RadioBackendProfile.TS_AC8259_V115, this);
        }
    }

    @Test public void nestedDescriptorAndEndpointAreVerifiedBeforeCommands() throws Exception {
        Endpoint endpoint = new Endpoint();
        assertSame(endpoint, endpoint.api().asBinder());
        assertEquals(Arrays.asList(55), endpoint.commonCalls);
        assertTrue(endpoint.radioCalls.isEmpty());
        endpoint.nested = null;
        assertThrows(RemoteException.class, endpoint::api);
        Binder bad = new Binder(); bad.attachInterface(null, "wrong.radio");
        endpoint.nested = bad;
        assertThrows(RemoteException.class, endpoint::api);
        assertThrows(RemoteException.class, () -> RadioApiFactory.create(RadioBackendProfile.UNKNOWN, endpoint));
        assertThrows(RemoteException.class, () -> RadioApiFactory.create(RadioBackendProfile.HCN_CURRENT_31, endpoint));
        assertTrue(endpoint.radioCalls.isEmpty());
        for (RadioBackendProfile profile : new RadioBackendProfile[]{RadioBackendProfile.TS_AC8259_V115,
                RadioBackendProfile.TS_825X_V27, RadioBackendProfile.TS_8667Q_V23}) {
            Endpoint recognized = new Endpoint();
            assertTrue(RadioApiFactory.create(profile, recognized) instanceof TsRadioApi);
            assertEquals(Arrays.asList(55), recognized.commonCalls);
            assertTrue(recognized.radioCalls.isEmpty());
            assertEquals("com.ts.main.common.MainUI", RadioBackendContract.serviceIntent(profile).getComponent().getClassName());
        }
        assertThrows(IllegalArgumentException.class,
                () -> RadioBackendContract.serviceIntent(RadioBackendProfile.UNKNOWN));
    }

    @Test public void fmAndAmFrequencyUnitsAndBanksAreConverted() throws Exception {
        Endpoint endpoint = new Endpoint(); TsRadioApi api = endpoint.api();
        for (int band = 0; band < 3; band++) {
            endpoint.rawBand = band;
            assertEquals(band, api.getCurrentBand());
            assertEquals(98100, api.getCurrentFreq());
            api.gotoFreq(101700); assertEquals(10170, endpoint.rawFrequency);
            endpoint.rawFrequency = 9810;
        }
        endpoint.rawBand = 3; assertEquals(0, api.getCurrentBand());
        for (int band : new int[]{4, 5}) {
            endpoint.rawBand = band; endpoint.rawFrequency = 999;
            assertEquals(3, api.getCurrentBand()); assertEquals(999, api.getCurrentFreq());
            api.gotoFreq(900); assertEquals(900, endpoint.rawFrequency);
        }
        endpoint.rawBand = 7;
        assertThrows(RemoteException.class, api::getCurrentBand);
        assertThrows(RemoteException.class, () -> api.gotoFreq(98100));
    }

    @Test public void directTuningStepsAndSeekUseOnlyInspectedCommands() throws Exception {
        Endpoint endpoint = new Endpoint(); TsRadioApi api = endpoint.api();
        api.onManualUpEvent(); assertEquals(9800, endpoint.rawFrequency);
        api.onManualDownEvent(); assertEquals(9810, endpoint.rawFrequency);
        api.onSeekDownEvent(); assertEquals(1, endpoint.seekDirection);
        api.onSeekUpEvent(); assertEquals(0, endpoint.seekDirection);
        assertTrue(api.tuneToBand(3, 999)); assertEquals(4, endpoint.rawBand); assertEquals(999, endpoint.rawFrequency);
        assertTrue(api.tuneToBand(2, 98100)); assertEquals(2, endpoint.rawBand); assertEquals(9810, endpoint.rawFrequency);
        assertTrue("AM1 and AM2 aliases must not stop the OEM bank cycle", api.tuneToBand(0, 101700));
        assertEquals(0, endpoint.rawBand); assertEquals(10170, endpoint.rawFrequency);
        assertThrows(RemoteException.class, () -> api.gotoFreq(98101));
        assertThrows(RemoteException.class, () -> api.gotoFreq2("not a frequency"));
        assertFalse("OEM TuneStep ignores direction", endpoint.radioCalls.contains(21));
        assertFalse(endpoint.radioCalls.contains(36)); assertFalse(endpoint.radioCalls.contains(38));
    }

    @Test public void failedBandSelectionDoesNotTuneTheWrongBand() throws Exception {
        Endpoint endpoint = new Endpoint(); TsRadioApi api = endpoint.api(); endpoint.stuckBand = true;
        assertFalse(api.tuneToBand(3, 999));
        assertFalse(endpoint.radioCalls.contains(34));
        assertFalse(endpoint.radioCalls.contains(29));
    }

    @Test public void allTsProfilesUseOemStepIndicesAndVerifyTheRoundTrip() throws Exception {
        for (RadioBackendProfile profile : new RadioBackendProfile[]{RadioBackendProfile.TS_AC8259_V115,
                RadioBackendProfile.TS_825X_V27, RadioBackendProfile.TS_8667Q_V23}) {
            Endpoint endpoint = new Endpoint();
            TsRadioApi api = (TsRadioApi) RadioApiFactory.create(profile, endpoint);
            api.gotoFreq(98100);
            assertEquals(106, endpoint.lastTuningIndex);
            assertEquals(Arrays.asList(3,8,9,9,9,3,34), endpoint.radioCalls);
            endpoint.fmSpacing = 5; endpoint.fmCount = 411;
            api.gotoFreq(98100); assertEquals(212, endpoint.lastTuningIndex);
            assertEquals(9810, endpoint.rawFrequency);
            api.gotoFreq(87500); assertEquals(0, endpoint.lastTuningIndex);
            api.gotoFreq(108000); assertEquals(410, endpoint.lastTuningIndex);
        }
    }

    @Test public void invalidChangingAndUnsupportedGridsDoNotWriteAnything() throws Exception {
        for (int scenario = 0; scenario < 5; scenario++) {
            Endpoint endpoint = new Endpoint(); TsRadioApi api = endpoint.api();
            if (scenario == 0) endpoint.fmSpacing = 0;
            if (scenario == 1) endpoint.fmCount = 0;
            if (scenario == 2) endpoint.wrongRoundTrip = true;
            if (scenario == 3) endpoint.changeBandDuringGridRead = true;
            if (scenario == 4) { endpoint.fmMinimum = 7600; endpoint.fmCount = 141; }
            assertThrows(RemoteException.class, () -> api.gotoFreq(98100));
            assertFalse(endpoint.radioCalls.contains(34));
            assertFalse(endpoint.radioCalls.contains(29));
        }
    }

    @Test public void canceledPlaybackCannotTuneAfterABandTransition() throws Exception {
        Endpoint endpoint = new Endpoint(); TsRadioApi api = endpoint.api();
        assertFalse(api.tuneToBand(3, 999, () -> endpoint.rawBand == 0));
        assertEquals(4, endpoint.rawBand);
        assertFalse(endpoint.radioCalls.contains(34));
        assertFalse(endpoint.radioCalls.contains(29));
        endpoint.radioCalls.clear();
        assertFalse(api.tuneToBand(0, 98100, () -> false));
        assertTrue(endpoint.radioCalls.isEmpty());
    }

    @Test public void playPauseUsesSourceSelectionNotGlobalMuteOrOemUi() throws Exception {
        Endpoint endpoint = new Endpoint(); TsRadioApi api = endpoint.api();
        endpoint.mode = 0;
        assertTrue(api.requestPlayAudio()); assertEquals(1, endpoint.mode);
        assertTrue(api.readHealth().radioOwnsSource());
        endpoint.flags = 8; assertTrue(api.readHealth().muted);
        assertTrue(api.pauseRadioSource()); assertEquals(0, endpoint.mode);
        assertFalse(api.readHealth().radioOwnsSource());
        endpoint.mode = 7;
        int before = endpoint.radioCalls.size();
        assertFalse(api.pauseRadioSource()); assertEquals(7, endpoint.mode);
        assertEquals(before, endpoint.radioCalls.size());
        assertEquals(Arrays.asList(55,10,10,10,10,10), endpoint.commonCalls);
        assertFalse(endpoint.commonCalls.contains(1)); assertFalse(endpoint.commonCalls.contains(2));
        assertFalse(endpoint.commonCalls.contains(63)); assertFalse(endpoint.commonCalls.contains(64));
    }

    @Test public void unsupportedCapabilitiesNeverInvokeAnOemCommand() throws Exception {
        Endpoint endpoint = new Endpoint(); TsRadioApi api = endpoint.api();
        assertFalse(RadioApiFactory.supportsOemFavorites(api));
        assertFalse(RadioApiFactory.supportsSeparateAudioFocus(api));
        assertFalse(RadioApiFactory.supportsScanning(api));
        assertFalse(RadioApiFactory.supportsLocalMode(api));
        assertThrows(RemoteException.class, api::onASEvent); assertThrows(RemoteException.class, api::onScanEvent);
        assertThrows(RemoteException.class, api::onLocDxEvent); assertThrows(RemoteException.class, api::favoriteCurrentFreq);
        assertThrows(RemoteException.class, api::requestAudioFocus); assertThrows(RemoteException.class, api::releaseAudioFocus);
        assertFalse(api.IsAS()); assertFalse(api.IsPS()); assertFalse(api.IsScan()); assertFalse(api.IsSeek());
        assertTrue(endpoint.radioCalls.isEmpty());
    }

    @Test public void everyRecognizedTsProfileDisablesUnverifiedUiFeatures() throws Exception {
        Field latest = RadioApiFactory.class.getDeclaredField("latest"); latest.setAccessible(true);
        Object before = latest.get(null);
        try {
            for (RadioBackendProfile profile : new RadioBackendProfile[]{RadioBackendProfile.TS_AC8259_V115,
                    RadioBackendProfile.TS_825X_V27, RadioBackendProfile.TS_8667Q_V23}) {
                latest.set(null, new RadioApiFactory.Detection(profile, "1.1", "test", ""));
                assertFalse(RadioApiFactory.selectedSupportsScanning());
                assertFalse(RadioApiFactory.selectedSupportsLocalMode());
            }
            latest.set(null, new RadioApiFactory.Detection(RadioBackendProfile.HCN_CURRENT_31, "", "", ""));
            assertTrue(RadioApiFactory.selectedSupportsScanning());
            assertTrue(RadioApiFactory.selectedSupportsLocalMode());
            latest.set(null, new RadioApiFactory.Detection(RadioBackendProfile.UNKNOWN, "", "", ""));
            assertFalse(RadioApiFactory.selectedSupportsScanning());
            assertFalse(RadioApiFactory.selectedSupportsLocalMode());
            latest.set(null, null);
            assertFalse(RadioApiFactory.selectedSupportsScanning());
            assertFalse(RadioApiFactory.selectedSupportsLocalMode());
        } finally { latest.set(null, before); }
    }

    @Test public void statePollingOnlyReadsAndNeverChangesSource() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Endpoint endpoint = new Endpoint();
        CountDownLatch done = new CountDownLatch(1);
        List<RadioServiceClient.RadioState> results = new ArrayList<>();
        RadioServiceClient client = new RadioServiceClient(context, new RadioServiceClient.Listener() {
            @Override public void onConnectionChanged(boolean connected, String message) { }
            @Override public void onRadioError(String message) { fail(message); }
            @Override public void onStateChanged(RadioServiceClient.RadioState state) { results.add(state); done.countDown(); }
        });
        try {
            Field field = RadioServiceClient.class.getDeclaredField("service"); field.setAccessible(true);
            field.set(client, endpoint.api());
            Method poll = RadioServiceClient.class.getDeclaredMethod("pollNow"); poll.setAccessible(true); poll.invoke(client);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(98100, results.get(0).frequency); assertEquals("QA", results.get(0).rdsName);
            assertTrue(results.get(0).stereo); assertTrue(results.get(0).localMode);
            assertEquals(Arrays.asList(3,3,25,30,27,27), endpoint.radioCalls);
            assertEquals(Arrays.asList(55), endpoint.commonCalls);
        } finally { client.close(); }
    }

    @Test public void transactionAndPermissionFailuresPropagate() throws Exception {
        Endpoint endpoint = new Endpoint(); TsRadioApi api = endpoint.api();
        endpoint.reject = true; assertThrows(RemoteException.class, api::getCurrentBand);
        endpoint.reject = false; endpoint.denied = true;
        assertThrows(SecurityException.class, api::requestPlayAudio);
    }
}
