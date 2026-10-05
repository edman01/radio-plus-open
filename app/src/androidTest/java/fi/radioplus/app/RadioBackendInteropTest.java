package fi.radioplus.app;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.test.platform.app.InstrumentationRegistry;
import com.hcn.autoradio.IRadioCallBack;
import com.hcn.autoradio.IRadioServiceAPI;

import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Only synthetic Binder endpoints are used; no OEM APK/code is included in these tests. */
public final class RadioBackendInteropTest {
    private static final class Tuner extends Binder {
        final List<Integer> calls = new ArrayList<>();
        final boolean legacy;
        int frequency = 98_100;
        int intArgument;
        String stringArgument;
        IBinder binderArgument;
        boolean reject;
        boolean throwError;

        Tuner(boolean legacy) {
            this.legacy = legacy;
            attachInterface(null, RadioApiFactory.DESCRIPTOR);
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(RadioApiFactory.DESCRIPTOR);
            calls.add(code);
            assertEquals(0, flags);
            if (reject) return false;
            if (throwError) {
                reply.writeException(new SecurityException("test permission refusal"));
                return true;
            }
            if (code == 1 || code == 3 || code == 4) binderArgument = data.readStrongBinder();
            if (code == 14) frequency = intArgument = data.readInt();
            if (code == 15) stringArgument = data.readString();
            assertEquals("Unexpected arguments for transaction " + code, 0, data.dataAvail());
            reply.writeNoException();
            if (legacy) {
                if (code == 17) reply.writeInt(0);
                if (code == 18) reply.writeInt(frequency);
                if (code >= 19 && code <= 25) reply.writeInt(code >= 23 ? 1 : 0);
            } else {
                if (code == 18) reply.writeInt(0);
                if (code == 19) reply.writeInt(frequency);
                if (code == 20) reply.writeString("QA station");
                if (code >= 21 && code <= 29) reply.writeInt(1);
            }
            return true;
        }
    }

    @Test public void legacyUsesVerifiedTransactionMapAndParcelTypes() throws Exception {
        Tuner tuner = new Tuner(true);
        IRadioServiceAPI api = RadioApiFactory.create(RadioBackendProfile.HCN_LEGACY_25, tuner);
        assertSame(tuner, api.asBinder());
        Binder client = new Binder();
        api.registerRadioClientBinder(client);
        assertSame(client, tuner.binderArgument);
        api.unRegisterRadioClientBinder();
        IRadioCallBack callback = new IRadioCallBack.Stub() {
            @Override public void onEvent(int event, String value) { }
        };
        api.registerRadioCallback(callback);
        assertSame(callback.asBinder(), tuner.binderArgument);
        api.unRegisterRadioCallback(null);
        assertNull(tuner.binderArgument);
        api.onBandEvent(); api.onASEvent(); api.onPSEvent(); api.onLocDxEvent();
        api.onSeekDownEvent(); api.onSeekUpEvent(); api.onManualUpEvent(); api.onManualDownEvent();
        api.onScanEvent(); api.gotoFreq(101_700); api.gotoFreq2("101700");
        assertEquals(101_700, tuner.intArgument);
        assertEquals("101700", tuner.stringArgument);
        assertEquals(0, api.getCurrentBand());
        assertEquals(101_700, api.getCurrentFreq());
        assertFalse(api.IsAS()); assertFalse(api.IsPS()); assertFalse(api.IsScan()); assertFalse(api.IsSeek());
        assertTrue(api.IsStereo()); assertTrue(api.IsDxLocal()); assertTrue(api.requestPlayAudio());
        assertEquals(Arrays.asList(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,17,18,19,20,21,22,23,24,25), tuner.calls);
    }

    @Test public void legacyStatePollingCannotAccidentallyPlayAudio() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Tuner tuner = new Tuner(true);
        List<RadioServiceClient.RadioState> states = new ArrayList<>();
        CountDownLatch dispatched = new CountDownLatch(1);
        RadioServiceClient client = new RadioServiceClient(context, new RadioServiceClient.Listener() {
            @Override public void onConnectionChanged(boolean connected, String message) { }
            @Override public void onStateChanged(RadioServiceClient.RadioState state) {
                states.add(state); dispatched.countDown();
            }
            @Override public void onRadioError(String message) { fail(message); }
        });
        try {
            Field service = RadioServiceClient.class.getDeclaredField("service");
            service.setAccessible(true);
            service.set(client, RadioApiFactory.create(RadioBackendProfile.HCN_LEGACY_25, tuner));
            Method poll = RadioServiceClient.class.getDeclaredMethod("pollNow");
            poll.setAccessible(true);
            poll.invoke(client);
            assertTrue(dispatched.await(5, TimeUnit.SECONDS));
            assertEquals(98_100, states.get(0).frequency);
            assertFalse(states.get(0).oemFavorite);
            assertFalse(states.get(0).scanning);
            assertTrue(states.get(0).stereo);
            assertEquals(Arrays.asList(17,18,19,20,21,23,24,22), tuner.calls);
            assertFalse("25 is PlayAudio, not IsScan", tuner.calls.contains(25));
        } finally { client.close(); }
    }

    @Test public void legacyOptionalFeaturesNeverCallMissingOrWrongTransactions() throws Exception {
        Tuner tuner = new Tuner(true);
        IRadioServiceAPI api = RadioApiFactory.create(RadioBackendProfile.HCN_LEGACY_25, tuner);
        assertFalse(RadioApiFactory.supportsOemFavorites(api));
        assertFalse(RadioApiFactory.supportsSeparateAudioFocus(api));
        assertEquals("", api.getCurrentFreqRdsPs());
        assertFalse(api.currentFreqIsFavorite());
        assertFalse(api.getFreqIsFavorite(0, 98_100));
        assertThrows(RemoteException.class, api::favoriteCurrentFreq);
        assertThrows(RemoteException.class, api::requestAudioFocus);
        assertThrows(RemoteException.class, api::releaseAudioFocus);
        assertThrows(RemoteException.class, () -> api.gotoFreqIndex(0));
        assertTrue(tuner.calls.isEmpty());
    }

    @Test public void currentV7ContractRetainsItsOriginalTransactionNumbers() throws Exception {
        Tuner tuner = new Tuner(false);
        IRadioServiceAPI api = RadioApiFactory.create(RadioBackendProfile.HCN_CURRENT_31, tuner);
        assertTrue(RadioApiFactory.supportsSeparateAudioFocus(api));
        assertTrue(RadioApiFactory.supportsOemFavorites(api));
        api.favoriteCurrentFreq();
        assertEquals(0, api.getCurrentBand());
        assertEquals(98_100, api.getCurrentFreq());
        assertEquals("QA station", api.getCurrentFreqRdsPs());
        assertTrue(api.IsAS()); assertTrue(api.IsScan());
        assertTrue(api.requestPlayAudio()); api.requestAudioFocus(); api.releaseAudioFocus();
        assertEquals(Arrays.asList(17,18,19,20,23,25,29,30,31), tuner.calls);
    }

    @Test public void unknownAndMismatchingBindersFailClosed() throws Exception {
        Tuner tuner = new Tuner(true);
        assertThrows(RemoteException.class, () -> RadioApiFactory.create(RadioBackendProfile.UNKNOWN, tuner));
        Binder other = new Binder(); other.attachInterface(null, "another.service");
        assertThrows(RemoteException.class, () -> RadioApiFactory.create(RadioBackendProfile.HCN_CURRENT_31, other));
        assertThrows(RemoteException.class, () -> RadioApiFactory.create(RadioBackendProfile.HCN_LEGACY_25, null));
        assertTrue(tuner.calls.isEmpty());
    }

    @Test public void legacyFailuresAreNotSilentlyReportedAsSuccess() throws Exception {
        Tuner tuner = new Tuner(true);
        IRadioServiceAPI api = RadioApiFactory.create(RadioBackendProfile.HCN_LEGACY_25, tuner);
        tuner.reject = true;
        assertThrows(RemoteException.class, api::getCurrentFreq);
        tuner.reject = false; tuner.throwError = true;
        assertThrows(SecurityException.class, api::requestPlayAudio);
    }

    @Test public void absentStockApkDetectionIsAsynchronousAndUnknown() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            context.getPackageManager().getPackageInfo(RadioBackendContract.PACKAGE_NAME, 0);
            org.junit.Assume.assumeTrue("Emulator without OEM radio required", false);
        } catch (android.content.pm.PackageManager.NameNotFoundException expected) { }
        CountDownLatch done = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> RadioApiFactory.detect(context, result -> {
            assertSame(Looper.getMainLooper(), Looper.myLooper());
            assertEquals(RadioBackendProfile.UNKNOWN, result.profile);
            assertFalse(result.problem.isEmpty());
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }
}
