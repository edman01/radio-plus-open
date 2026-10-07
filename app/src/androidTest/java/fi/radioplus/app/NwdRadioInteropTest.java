package fi.radioplus.app;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Synthetic contracts only; these tests do not install or execute vendor code. */
public final class NwdRadioInteropTest {
    static final class Audio implements NwdAudioRouting.Transport {
        volatile int source = 0;
        long now = 100;
        final List<Intent> sent = new CopyOnWriteArrayList<>();
        final NwdAudioRouting routing = new NwdAudioRouting(this, () -> now);
        @Override public int source() { return source; }
        @Override public void send(Intent intent) { sent.add(intent); }
    }

    static final class Endpoint extends Binder {
        final List<Integer> calls = new CopyOnWriteArrayList<>();
        final Audio audio = new Audio();
        volatile int band, freq = 9810, type = 2, state = 1;
        int direction = -1, lastPreset = -1, gridCount = 3, gridStep = 10;
        boolean near, reject, denied, truncated, extra, stuck, mutateBandOnGrid;
        boolean cancelOnBand, missingFrequency;
        boolean enforceBandCooldown, amSupported = true, handoffOnBand, malformedPresets, pendingScan;
        long lastBandAt;
        final AtomicBoolean current = new AtomicBoolean(true);
        Endpoint() { attachInterface(null, NwdRadioApi.DESCRIPTOR); }
        NwdRadioApi api() throws Exception { return new NwdRadioApi(this, audio.routing); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(NwdRadioApi.DESCRIPTOR);
            assertEquals(0, flags);
            calls.add(code);
            if (reject) return false;
            if (denied) { reply.writeException(new SecurityException("denied")); return true; }
            reply.writeNoException();
            if (truncated) return true;
            switch (code) {
                case 1:
                    freq = data.readInt();
                    assertEquals("Band must already be selected", band, data.readByte());
                    lastPreset = data.readInt(); assertEquals(0, lastPreset);
                    break;
                case 2:
                    reply.writeInt(missingFrequency ? 0 : 1);
                    if (!missingFrequency) { reply.writeByte((byte) band); reply.writeString("QA\u0000 "); reply.writeInt(freq); }
                    break;
                case 4: direction = data.readInt(); break;
                case 5:
                    if (cancelOnBand) current.set(false);
                    if (handoffOnBand) audio.source = 7;
                    if (!stuck && (!enforceBandCooldown || SystemClock.elapsedRealtime() - lastBandAt >= 500)) {
                        lastBandAt = SystemClock.elapsedRealtime();
                        band = (band + 1) % (amSupported ? 5 : 3); freq = band < 3 ? 9810 : 999;
                    }
                    break;
                case 6: if (!pendingScan) state = 2; break;
                case 7: state = state == 3 ? 1 : 3; break;
                case 8: near = data.readInt() != 0; break;
                case 9: reply.writeInt(near ? 1 : 0); break;
                case 10: reply.writeInt(1); break;
                case 21:
                    reply.writeInt(malformedPresets ? -1 : 6);
                    if (!malformedPresets) for (int i = 0; i < 6; i++) {
                        reply.writeInt(1); reply.writeByte((byte) band); reply.writeString("");
                        reply.writeInt(band < 3 ? 9000 + band * 600 + i * 10 : 522 + (band - 3) * 54 + i * 9);
                    }
                    break;
                case 22:
                    reply.writeInt(gridCount);
                    for (int i = 0; i < Math.max(0, Math.min(gridCount, 3)); i++) {
                        reply.writeInt(1);
                        reply.writeInt(i == 0 ? 8750 : i == 1 ? 522 : 0);
                        reply.writeInt(i == 0 ? 10800 : i == 1 ? 1620 : 0);
                        reply.writeInt(i == 0 ? gridStep : i == 1 ? 9 : 0);
                    }
                    if (mutateBandOnGrid && calls.stream().filter(c -> c == 22).count() >= 2) band = 3;
                    break;
                case 23: reply.writeByte((byte) state); break;
                case 27: assertEquals(0, data.readByte()); assertEquals(0, data.readByte()); state = 1; break;
                case 28: reply.writeString("Radio text"); break;
                case 29: reply.writeInt(type); break;
                default: fail("Unverified NWD transaction " + code);
            }
            assertEquals(0, data.dataAvail());
            if (extra) reply.writeInt(999);
            return true;
        }
    }

    @Test public void descriptorTypeAndServiceComponentAreChecked() throws Exception {
        Endpoint e = new Endpoint(); assertSame(e, e.api().asBinder());
        assertEquals(Arrays.asList(29), e.calls);
        Binder wrong = new Binder(); wrong.attachInterface(null, "other.radio");
        assertThrows(RemoteException.class, () -> new NwdRadioApi(wrong, e.audio.routing));
        for (int type : new int[]{-1,0,1,3,4}) {
            e.type = type; assertThrows(RemoteException.class, e::api);
        }
        assertThrows(RemoteException.class, () -> RadioApiFactory.create(RadioBackendProfile.NWD_222, e));
        Intent intent = RadioBackendContract.serviceIntent(RadioBackendProfile.NWD_222);
        assertEquals("com.nwd.radio.service", intent.getComponent().getPackageName());
        assertEquals("com.nwd.radio.service.RadioService", intent.getComponent().getClassName());
        assertEquals("com.nwd.radio.service.ACTION_RADIO_SERVICE", intent.getAction());
    }

    @Test public void fmAndAmParcelsUseTheirCorrectUnits() throws Exception {
        Endpoint e = new Endpoint(); NwdRadioApi api = e.api();
        for (int band = 0; band < 3; band++) {
            e.band = band; e.freq = 9810;
            assertEquals(band, api.getCurrentBand()); assertEquals(98100, api.getCurrentFreq());
            api.gotoFreq(101700); assertEquals(10170, e.freq); assertEquals(0, e.lastPreset);
        }
        for (int band : new int[]{3,4}) {
            e.band = band; e.freq = 999;
            assertEquals(3, api.getCurrentBand()); assertEquals(999, api.getCurrentFreq());
            api.gotoFreq(900); assertEquals(900, e.freq);
        }
        e.band = 6; assertThrows(RemoteException.class, api::frequency);
        e.band = 0; e.freq = Integer.MAX_VALUE; assertThrows(RemoteException.class, api::frequency);
        e.missingFrequency = true; assertThrows(RemoteException.class, api::frequency);
    }

    @Test public void bandIsConfirmedBeforeTuningAndAmAliasesWrap() throws Exception {
        Endpoint e = new Endpoint(); NwdRadioApi api = e.api();
        assertTrue(api.tuneToBand(3, 999, e.current::get)); assertEquals(3, e.band); assertEquals(999, e.freq);
        assertTrue(api.tuneToBand(2, 101700, e.current::get)); assertEquals(2, e.band); assertEquals(10170, e.freq);
        assertEquals(0, e.lastPreset);
    }

    @Test public void canceledOrStuckBandNeverSendsAFrequency() throws Exception {
        Endpoint e = new Endpoint(); NwdRadioApi api = e.api();
        e.cancelOnBand = true;
        assertFalse(api.tuneToBand(3, 999, e.current::get)); assertFalse(e.calls.contains(1));
        e = new Endpoint(); api = e.api(); e.stuck = true;
        assertFalse(api.tuneToBand(3, 999, e.current::get)); assertFalse(e.calls.contains(1));
        e.calls.clear(); e.current.set(false);
        assertFalse(api.tuneToBand(3, 999, e.current::get)); assertTrue(e.calls.isEmpty());
    }

    @Test public void invalidUnavailableAndChangedGridsNeverTune() throws Exception {
        for (int scenario = 0; scenario < 5; scenario++) {
            Endpoint e = new Endpoint(); NwdRadioApi api = e.api();
            if (scenario == 0) e.gridStep = 0;
            if (scenario == 1) e.gridStep = 20;
            if (scenario == 2) e.gridCount = -1;
            if (scenario == 3) e.gridCount = 4;
            if (scenario == 4) e.mutateBandOnGrid = true;
            if (scenario == 4) assertFalse(api.tuneToBand(0, 98200, e.current::get));
            else assertThrows(RemoteException.class, () -> api.tuneToBand(0, 98200, e.current::get));
            assertFalse(e.calls.contains(1));
        }
    }

    @Test public void stepsSeekLocalAndStatusUseInspectedTransactions() throws Exception {
        Endpoint e = new Endpoint(); NwdRadioApi api = e.api();
        api.onManualDownEvent(); assertEquals(9820, e.freq);
        api.onManualUpEvent(); assertEquals(9810, e.freq);
        api.onSeekDownEvent(); assertEquals(1, e.direction);
        api.onSeekUpEvent(); assertEquals(0, e.direction);
        api.onLocDxEvent(); assertTrue(api.IsDxLocal());
        api.onLocDxEvent(); assertFalse(api.IsDxLocal());
        assertTrue(api.IsStereo()); assertFalse(api.IsScan());
        e.state = 2; assertTrue(api.IsScan()); assertTrue(api.IsSeek());
        e.state = 3; assertTrue(api.IsPS());
        assertEquals("Radio text", api.radioText());
        assertFalse(e.calls.contains(3)); assertFalse(e.calls.contains(13));
        assertThrows(RemoteException.class, () -> api.gotoFreq(98101));
        assertThrows(RemoteException.class, () -> api.gotoFreq2("bad"));
    }

    @Test public void unavailableFeaturesNeverSendGuessedTransactions() throws Exception {
        Endpoint e = new Endpoint(); NwdRadioApi api = e.api(); e.calls.clear();
        assertThrows(RemoteException.class, api::requestAudioFocus);
        assertThrows(RemoteException.class, api::favoriteCurrentFreq);
        assertTrue(e.calls.isEmpty());
        assertTrue(RadioApiFactory.supportsScanning(api));
        assertFalse(RadioApiFactory.supportsSeparateAudioFocus(api));
        assertFalse(RadioApiFactory.supportsOemFavorites(api));
        assertFalse(RadioApiFactory.usesHcnFramework(api));
        assertTrue(RadioApiFactory.supportsLocalMode(api));
    }

    @Test public void autoScanStartStopAndIntroUseConfirmedCommands() throws Exception {
        Endpoint e = new Endpoint(); e.audio.source = 4; NwdRadioApi api = e.api();
        api.onASEvent(); assertTrue(api.IsAS()); assertEquals(2, e.state);
        api.onASEvent(); assertFalse(api.IsAS()); assertEquals(1, e.state);
        assertEquals(1L, e.calls.stream().filter(c -> c == 6).count());
        assertEquals(1L, e.calls.stream().filter(c -> c == 27).count());
        api.onPSEvent(); assertTrue(api.IsPS());
        api.onScanEvent(); assertFalse(api.IsPS());
        e.state = 2; assertThrows(RemoteException.class, api::onASEvent);
        assertEquals("Busy seek must not start or toggle AMS", 1L, e.calls.stream().filter(c -> c == 6).count());
        assertTrue(e.audio.sent.isEmpty());
    }

    @Test public void pendingScanIsReportedWithoutSendingASecondStart() throws Exception {
        Endpoint e = new Endpoint(); e.audio.source = 4; e.pendingScan = true; NwdRadioApi api = e.api();
        api.onASEvent(); assertTrue(api.IsAS());
        api.onASEvent(); assertFalse(api.IsAS());
        assertEquals(1L, e.calls.stream().filter(c -> c == 6).count());
    }

    @Test public void finishedScanReadsEveryFmBankAndRestoresOriginalStation() throws Exception {
        Endpoint e = new Endpoint(); e.audio.source = 4; e.enforceBandCooldown = true; NwdRadioApi api = e.api();
        api.onASEvent(); assertTrue(api.IsAS()); e.state = 1; assertFalse(api.IsAS());
        int[] results = api.readScanPresets(0, e.current::get);
        assertEquals(18, results.length); assertEquals(90000, results[0]); assertEquals(102500, results[17]);
        assertEquals(0, e.band); assertEquals(9810, e.freq);
        assertEquals(3L, e.calls.stream().filter(c -> c == 21).count());
        assertEquals(1L, e.calls.stream().filter(c -> c == 1).count());
    }

    @Test public void fmOnlyAndAmBanksAreCollectedWithoutMixingBands() throws Exception {
        Endpoint fm = new Endpoint(); fm.audio.source = 4; fm.amSupported = false; fm.enforceBandCooldown = true;
        assertEquals(18, fm.api().readScanPresets(0, fm.current::get).length);
        assertEquals(0, fm.band); assertEquals(9810, fm.freq);
        Endpoint am = new Endpoint(); am.audio.source = 4; am.band = 4; am.freq = 999; am.enforceBandCooldown = true;
        int[] result = am.api().readScanPresets(3, am.current::get);
        assertEquals(12, result.length); for (int khz : result) assertTrue(khz >= 522 && khz <= 1620);
        assertEquals(4, am.band); assertEquals(999, am.freq);
    }

    @Test public void scanReadCancellationOrAudioHandoffNeverRestoresOverAnotherSource() throws Exception {
        for (boolean handoff : new boolean[]{false, true}) {
            Endpoint e = new Endpoint(); e.audio.source = 4;
            e.handoffOnBand = handoff; e.cancelOnBand = !handoff;
            assertThrows(RemoteException.class, () -> e.api().readScanPresets(0, e.current::get));
            assertFalse(e.calls.contains(1));
        }
    }

    @Test public void malformedPresetBankCannotBeReturnedAsComplete() throws Exception {
        Endpoint e = new Endpoint(); e.audio.source = 4; e.malformedPresets = true;
        assertThrows(RemoteException.class, () -> e.api().readScanPresets(0, e.current::get));
        assertEquals(0, e.band); assertEquals(9810, e.freq);
    }

    @Test public void bandCyclingRespectsVendorCooldown() throws Exception {
        Endpoint e = new Endpoint(); e.enforceBandCooldown = true;
        assertTrue(e.api().tuneToBand(3, 999, e.current::get));
        assertEquals(3, e.band); assertEquals(999, e.freq);
    }

    @Test public void tuningStopsAnActiveSearchBeforeSendingTheRequestedFrequency() throws Exception {
        Endpoint e = new Endpoint(); e.state = 2;
        assertTrue(e.api().tuneToBand(0, 101700, e.current::get));
        assertEquals(1, e.state); assertEquals(10170, e.freq);
        assertTrue(e.calls.indexOf(27) < e.calls.indexOf(1));
        assertFalse(e.calls.contains(6));
    }

    @Test public void rejectedDeniedAndMalformedRepliesFailClosed() throws Exception {
        Endpoint e = new Endpoint(); NwdRadioApi api = e.api();
        e.reject = true; assertThrows(RemoteException.class, api::frequency);
        e.reject = false; e.denied = true; assertThrows(SecurityException.class, api::frequency);
        e.denied = false; e.truncated = true; assertThrows(RemoteException.class, api::frequency);
        e.truncated = false; e.extra = true; assertThrows(RemoteException.class, api::frequency);
        assertFalse(e.calls.contains(1)); assertTrue(e.audio.sent.isEmpty());
    }

    @Test public void audioRequestsAreExplicitTypedAndCoalescedUntilAcknowledged() {
        Audio a = new Audio(); assertTrue(a.routing.play());
        assertEquals(2, a.sent.size());
        Intent source = a.sent.get(0), init = a.sent.get(1);
        assertEquals(NwdAudioRouting.CHANGE_SOURCE, source.getAction());
        assertEquals(NwdAudioRouting.KERNEL_PACKAGE, source.getPackage());
        assertTrue(source.getExtras().get("extra_source_id") instanceof Byte);
        assertEquals(4, source.getByteExtra("extra_source_id", (byte)-1));
        assertEquals(NwdAudioRouting.APP_IN, init.getAction());
        assertEquals(NwdAudioRouting.RADIO_PACKAGE, init.getPackage());
        assertEquals(8, init.getIntExtra("extra_app_id", -1));
        assertEquals(1, init.getIntExtra("extra_app_operation", -1));
        assertEquals(0, init.getIntExtra("extra_app_event", -1));
        a.routing.play(); a.now += 1000; a.routing.play(); assertEquals(2, a.sent.size());
        a.source = 4; a.now += 10000; a.routing.play(); assertEquals(2, a.sent.size());
        assertTrue(a.routing.readHealth().radioOwnsSource()); assertFalse(a.routing.readHealth().muteKnown);
    }

    @Test public void audioReattemptIsBoundedAndAnotherSourceCanBeSelectedExplicitly() {
        Audio a = new Audio(); a.routing.play(); a.now += NwdAudioRouting.PENDING_ROUTE_MS;
        a.routing.play(); assertEquals(4, a.sent.size());
        a.source = 7; a.routing.play(); assertEquals(6, a.sent.size());
        assertFalse(a.routing.readHealth().radioOwnsSource());
    }

    @Test public void pauseOnlyLeavesRadioNeverMutesOtherSources() {
        Audio a = new Audio();
        for (int source : new int[]{-1,0,7}) { a.source = source; assertFalse(a.routing.pause()); }
        assertTrue(a.sent.isEmpty());
        a.source = -1; assertFalse(a.routing.readHealth().sourceKnown);
        a.source = 4; assertTrue(a.routing.pause());
        assertEquals(2, a.sent.size());
        assertEquals(0, a.sent.get(0).getByteExtra("extra_source_id", (byte)-1));
        assertEquals(NwdAudioRouting.EXIT_RADIO, a.sent.get(1).getAction());
        assertEquals(NwdAudioRouting.RADIO_PACKAGE, a.sent.get(1).getPackage());
        a.source = 0; a.routing.play(); assertEquals(4, a.sent.size());
    }

    @Test public void alreadyPlayingStockRadioIsNotReinitialized() {
        Audio a = new Audio(); a.source = 4; assertTrue(a.routing.play()); assertTrue(a.sent.isEmpty());
    }

    @Test public void immediatePauseCancelsPendingArmToRadioButNotAnotherSource() {
        Audio a = new Audio(); a.routing.play();
        assertTrue(a.routing.pause()); assertEquals(4, a.sent.size());
        assertEquals(0, a.sent.get(2).getByteExtra("extra_source_id", (byte)-1));
        assertFalse(a.routing.pause()); assertEquals(4, a.sent.size());
        a.routing.play(); a.source = 7;
        assertFalse(a.routing.pause()); assertEquals(6, a.sent.size());
    }

    @Test public void statePollingDoesNotInitializeOrRetuneRadio() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Endpoint e = new Endpoint(); CountDownLatch done = new CountDownLatch(1);
        List<RadioServiceClient.RadioState> states = new ArrayList<>();
        RadioServiceClient client = new RadioServiceClient(context, new RadioServiceClient.Listener() {
            @Override public void onConnectionChanged(boolean connected, String message) { }
            @Override public void onRadioError(String message) { fail(message); }
            @Override public void onStateChanged(RadioServiceClient.RadioState state) { states.add(state); done.countDown(); }
        });
        try {
            Field field = RadioServiceClient.class.getDeclaredField("service"); field.setAccessible(true); field.set(client, e.api());
            Method poll = RadioServiceClient.class.getDeclaredMethod("pollNow"); poll.setAccessible(true); poll.invoke(client);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(98100, states.get(0).frequency); assertEquals("QA", states.get(0).rdsName);
            assertTrue(states.get(0).stereo); assertTrue(e.audio.sent.isEmpty());
            for (int call : e.calls) assertTrue(Arrays.asList(2,9,10,23,28,29).contains(call));
        } finally { client.close(); }
    }
}
