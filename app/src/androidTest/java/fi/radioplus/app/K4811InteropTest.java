package fi.radioplus.app;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import org.junit.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Synthetic MCU IPC contracts only: no OEM code, native tuner or physical audio. */
public final class K4811InteropTest {
    static final class Audio implements NwdAudioRouting.Transport {
        volatile int source = 4;
        final List<Intent> sent = new CopyOnWriteArrayList<>();
        final NwdAudioRouting routing = new NwdAudioRouting(this, () -> 100L, false);
        @Override public int source() { return source; }
        @Override public void send(Intent intent) { sent.add(intent); }
    }

    static final class Endpoint extends Binder {
        final Audio audio = new Audio();
        final List<Integer> calls = new CopyOnWriteArrayList<>();
        final AtomicBoolean current = new AtomicBoolean(true);
        int type, band, frequency = 9810, state = 1;
        int fmMin = 8750, fmMax = 10800, fmStep = 10;
        int amMin = 522, amMax = 1620, amStep = 9;
        int lastTuneBand = -1, lastTuneFrequency = -1, lastPreset = -1;
        int readsAfterTune, readsBeforeAcknowledgement = 1;
        int frequencyReads, cancelOnFrequencyRead;
        int normalAfterStateReads, stateReads, refreshes;
        boolean fmAvailable = true, amAvailable = true, near;
        boolean pending, neverAcknowledge, rejectTune;
        boolean handoffOnTune, handoffOnBand, cancelOnTune, stuckBand;

        Endpoint() { attachInterface(null, NwdRadioApi.DESCRIPTOR); }

        NwdRadioApi api() throws RemoteException {
            return new NwdRadioApi(RadioBackendProfile.NWD_230, this, audio.routing);
        }

        void acknowledgePending() {
            if (pending) {
                band = lastTuneBand;
                frequency = lastTuneFrequency;
                pending = false;
            }
        }

        long count(int transaction) {
            return calls.stream().filter(code -> code == transaction).count();
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(NwdRadioApi.DESCRIPTOR);
            calls.add(code);
            if (code == 1) {
                assertEquals("2.3.0 frequency setter must be ONEWAY", IBinder.FLAG_ONEWAY, flags);
                assertNull("2.3.0 setter has no synchronous reply parcel", reply);
                lastTuneFrequency = data.readInt();
                lastTuneBand = data.readByte();
                lastPreset = data.readInt();
                assertEquals("Select and observe the band before tuning", band, lastTuneBand);
                assertEquals("App favorites must not save an OEM preset", 0, lastPreset);
                assertEquals(0, data.dataAvail());
                if (rejectTune) return false;
                readsAfterTune = 0;
                pending = true;
                if (handoffOnTune) audio.source = 7;
                if (cancelOnTune) current.set(false);
                return true;
            }
            assertEquals(0, flags);
            assertNotNull(reply);
            reply.writeNoException();
            switch (code) {
                case 2:
                    if (++frequencyReads == cancelOnFrequencyRead) current.set(false);
                    if (pending && ++readsAfterTune >= readsBeforeAcknowledgement && !neverAcknowledge) {
                        acknowledgePending();
                    }
                    reply.writeInt(1);
                    reply.writeByte((byte) band);
                    reply.writeString("Synthetic MCU station");
                    reply.writeInt(frequency);
                    break;
                case 5:
                    if (!stuckBand) {
                        band = (band + 1) % 6;
                        frequency = band < 3 ? 9810 : 999;
                    }
                    if (handoffOnBand) audio.source = 7;
                    break;
                case 8:
                    near = data.readInt() != 0;
                    break;
                case 9:
                    reply.writeInt(near ? 1 : 0);
                    break;
                case 10:
                    reply.writeInt(1);
                    break;
                case 22:
                    reply.writeInt(3);
                    writeGrid(reply, fmAvailable, fmMin, fmMax, fmStep);
                    writeGrid(reply, amAvailable, amMin, amMax, amStep);
                    writeGrid(reply, true, 0, 0, 0);
                    break;
                case 23:
                    stateReads++;
                    if (normalAfterStateReads > 0 && stateReads >= normalAfterStateReads) state = 1;
                    reply.writeByte((byte) state);
                    break;
                case 27:
                    // MCU's only verified read-only refresh, not an invented stop.
                    assertEquals("Do not send guessed MCU action 0", -1, data.readByte());
                    assertEquals(-1, data.readByte());
                    refreshes++;
                    break;
                case 28:
                    reply.writeString("");
                    break;
                case 29:
                    reply.writeInt(type);
                    break;
                case 30:
                    // This MCU implementation returns 0 even during search/intro.
                    reply.writeInt(0);
                    break;
                default:
                    fail("Unsupported or unverified MCU transaction " + code);
            }
            assertEquals(0, data.dataAvail());
            return true;
        }

        private static void writeGrid(Parcel reply, boolean present, int min, int max, int step) {
            reply.writeInt(present ? 1 : 0);
            if (present) {
                reply.writeInt(min);
                reply.writeInt(max);
                reply.writeInt(step);
            }
        }
    }

    private static boolean attemptTune(NwdRadioApi api, Endpoint endpoint, int band, int khz) {
        try { return api.tuneToBand(band, khz, endpoint.current::get); }
        catch (RemoteException expectedFailure) { return false; }
    }

    private static void assertNoTuningOrSearch(Endpoint endpoint) {
        for (int code : new int[]{1, 3, 4, 5, 6, 7}) {
            assertEquals("Must not mutate tuner with transaction " + code, 0L, endpoint.count(code));
        }
    }

    @Test public void cancellationDuringFinalReadCannotSendTuneOrBandCommand() throws Exception {
        for (int targetBand : new int[]{0, 3}) {
            Endpoint endpoint = new Endpoint();
            NwdRadioApi api = endpoint.api();
            endpoint.cancelOnFrequencyRead = 5;
            assertFalse(attemptTune(api, endpoint, targetBand, targetBand == 0 ? 101700 : 900));
            assertEquals(0L, endpoint.count(1));
            assertEquals(0L, endpoint.count(5));
        }
    }

    @Test public void exactInspectedPairIsRequiredAndMixedVersionsAreRejected() {
        assertEquals("0bd82481535166987f37d1d876d2d697393afcef3b46f979ec1a60209ac1fcf8",
                RadioBackendProfile.NWD_230_RADIO_SHA256);
        assertEquals("429685bf6410ae04f6410a62a5c03be2fb57466a0d5d9615f522d1000da6280b",
                RadioBackendProfile.NWD_230_KERNEL_SHA256);
        assertEquals(RadioBackendProfile.NWD_230,
                RadioBackendProfile.forApkSha256(RadioBackendProfile.NWD_230_RADIO_SHA256));
        assertTrue(RadioBackendProfile.verifiedNwdPair(RadioBackendProfile.NWD_230_RADIO_SHA256,
                RadioBackendProfile.NWD_230_KERNEL_SHA256));
        assertFalse(RadioBackendProfile.verifiedNwdPair(RadioBackendProfile.NWD_230_RADIO_SHA256,
                RadioBackendProfile.NWD_KERNEL_SHA256));
        assertFalse(RadioBackendProfile.verifiedNwdPair(RadioBackendProfile.NWD_RADIO_SHA256,
                RadioBackendProfile.NWD_230_KERNEL_SHA256));
    }

    @Test public void constructorOnlyAcceptsMcuRuntimeAndQueriesWithoutMutation() throws Exception {
        Endpoint endpoint = new Endpoint();
        assertSame(endpoint, endpoint.api().asBinder());
        assertEquals(1, endpoint.calls.size());
        assertEquals(Integer.valueOf(29), endpoint.calls.get(0));
        for (int rejectedType : new int[]{-1, 1, 2, 3, 4}) {
            Endpoint rejected = new Endpoint();
            rejected.type = rejectedType;
            assertThrows(RemoteException.class, rejected::api);
            assertEquals(1, rejected.calls.size());
            assertEquals(Integer.valueOf(29), rejected.calls.get(0));
            assertTrue(rejected.audio.sent.isEmpty());
        }
        Binder wrong = new Binder();
        wrong.attachInterface(null, "another.radio.service");
        assertThrows(RemoteException.class, () -> new NwdRadioApi(
                RadioBackendProfile.NWD_230, wrong, endpoint.audio.routing));
    }

    @Test public void onewayTuneWaitsForObservedFrequencyWithoutReplayingCommand() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.readsBeforeAcknowledgement = 4;
        NwdRadioApi api = endpoint.api();
        assertTrue(api.tuneToBand(0, 101700, endpoint.current::get));
        assertEquals(10170, endpoint.frequency);
        assertEquals(4, endpoint.readsAfterTune);
        assertEquals(1L, endpoint.count(1));
        assertEquals(0, endpoint.lastPreset);
        assertTrue(endpoint.audio.sent.isEmpty());
    }

    @Test public void unacknowledgedTuneRemainsUnresolvedAndIsNeverReplayed() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.neverAcknowledge = true;
        NwdRadioApi api = endpoint.api();
        long started = SystemClock.elapsedRealtime();
        assertFalse(attemptTune(api, endpoint, 0, 101700));
        assertTrue("A missing acknowledgement must have a bounded wait",
                SystemClock.elapsedRealtime() - started < 6000L);
        assertEquals(9810, endpoint.frequency);
        assertEquals(1L, endpoint.count(1));
        assertFalse(attemptTune(api, endpoint, 0, 102100));
        assertEquals("An unresolved previous ONEWAY write cannot be overtaken", 1L, endpoint.count(1));
        endpoint.acknowledgePending();
        endpoint.neverAcknowledge = false;
        assertTrue(api.tuneToBand(0, 102100, endpoint.current::get));
        assertEquals(10210, endpoint.frequency);
        assertEquals(2L, endpoint.count(1));
    }

    @Test public void binderRejectionDoesNotReportSuccessfulTuningOrRetry() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.rejectTune = true;
        NwdRadioApi api = endpoint.api();
        assertFalse(attemptTune(api, endpoint, 0, 101700));
        assertEquals(9810, endpoint.frequency);
        assertEquals(1L, endpoint.count(1));
    }

    @Test public void unsupportedSearchIntroAndSeekNeverSendCommands() throws Exception {
        Endpoint endpoint = new Endpoint();
        NwdRadioApi api = endpoint.api();
        endpoint.calls.clear();
        assertFalse(RadioApiFactory.supportsScanning(api));
        assertThrows(RemoteException.class, api::onASEvent);
        assertThrows(RemoteException.class, api::onPSEvent);
        assertThrows(RemoteException.class, api::onScanEvent);
        assertThrows(RemoteException.class, api::onSeekDownEvent);
        assertThrows(RemoteException.class, api::onSeekUpEvent);
        assertNoTuningOrSearch(endpoint);
        assertEquals("No guessed stop action", 0L, endpoint.count(27));
        assertTrue(endpoint.audio.sent.isEmpty());
    }

    @Test public void externalSearchAndIntroBlockTouchWithoutInventingAStop() throws Exception {
        for (int busyState : new int[]{2, 3}) {
            Endpoint endpoint = new Endpoint();
            endpoint.state = busyState;
            NwdRadioApi api = endpoint.api();
            assertFalse(attemptTune(api, endpoint, 0, 101700));
            assertNoTuningOrSearch(endpoint);
            assertEquals("MCU scanstate 0 must not authorize a guessed stop", 0L, endpoint.count(27));
            assertTrue(endpoint.audio.sent.isEmpty());
        }
    }

    @Test public void initialClosedStateHasBoundedReadinessWaitAndNeverTunesBlindly() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.state = 0;
        NwdRadioApi api = endpoint.api();
        long started = SystemClock.elapsedRealtime();
        assertFalse(attemptTune(api, endpoint, 0, 101700));
        assertTrue("MCU readiness must not wait indefinitely",
                SystemClock.elapsedRealtime() - started < 4000L);
        assertNoTuningOrSearch(endpoint);
        assertTrue(endpoint.audio.sent.isEmpty());
    }

    @Test public void delayedNormalStateCanBecomeReadyWithoutRepeatedTune() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.state = 0;
        endpoint.normalAfterStateReads = 3;
        NwdRadioApi api = endpoint.api();
        assertTrue(api.tuneToBand(0, 101700, endpoint.current::get));
        assertTrue(endpoint.stateReads >= 3);
        assertEquals(1L, endpoint.count(1));
    }

    @Test public void missingOrInvalidFmAndAmGridsFailBeforeAudioActivation() throws Exception {
        for (int scenario = 0; scenario < 4; scenario++) {
            Endpoint endpoint = new Endpoint();
            endpoint.audio.source = 0;
            if (scenario == 0) endpoint.fmAvailable = false;
            if (scenario == 1) endpoint.fmStep = 0;
            if (scenario == 2) endpoint.amAvailable = false;
            if (scenario == 3) endpoint.amStep = 0;
            int requestedBand = scenario < 2 ? 0 : 3;
            int requestedFrequency = scenario < 2 ? 101700 : 999;
            NwdRadioApi api = endpoint.api();
            assertThrows(RemoteException.class, () -> {
                api.validateTuningTarget(requestedBand, requestedFrequency);
                api.requestPlayAudio();
            });
            assertNoTuningOrSearch(endpoint);
            assertTrue("Invalid target must not initiate source routing", endpoint.audio.sent.isEmpty());
        }
    }

    @Test public void validationWhilePausedOnlyReadsAndHonorsActualGrid() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.audio.source = 0;
        endpoint.state = 0;
        endpoint.fmStep = 20;
        NwdRadioApi api = endpoint.api();
        api.validateTuningTarget(0, 101700);
        assertThrows(RemoteException.class, () -> api.validateTuningTarget(0, 101800));
        assertNoTuningOrSearch(endpoint);
        assertTrue(endpoint.audio.sent.isEmpty());
    }

    @Test public void manualFmStepsUseTwoHundredKhzGridAndWrapAtItsLastValidPoint() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.fmStep = 20;
        NwdRadioApi api = endpoint.api();
        api.onManualDownEvent();
        assertEquals(9830, endpoint.frequency);
        api.onManualUpEvent();
        assertEquals(9810, endpoint.frequency);
        endpoint.frequency = 10790;
        api.onManualDownEvent();
        assertEquals(8750, endpoint.frequency);
        api.onManualUpEvent();
        assertEquals(10790, endpoint.frequency);
        assertEquals(4L, endpoint.count(1));
        assertEquals(0L, endpoint.count(3));
        assertEquals(0L, endpoint.count(4));
    }

    @Test public void manualAmStepsUseTheObservedGridRatherThanFixedNineKhz() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.band = 3;
        endpoint.amStep = 18;
        endpoint.frequency = 1008;
        NwdRadioApi api = endpoint.api();
        api.onManualDownEvent();
        assertEquals(1026, endpoint.frequency);
        api.onManualUpEvent();
        assertEquals(1008, endpoint.frequency);
        endpoint.frequency = 1620;
        api.onManualDownEvent();
        assertEquals(522, endpoint.frequency);
        api.onManualUpEvent();
        assertEquals(1620, endpoint.frequency);
        assertEquals(4L, endpoint.count(1));
    }

    @Test public void manualAmUsesGridIntersectionWithoutExpandingTheAppRange() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.band = 5;
        endpoint.amMin = 530;
        endpoint.amMax = 1710;
        endpoint.amStep = 10;
        endpoint.frequency = 990;
        NwdRadioApi api = endpoint.api();
        api.onManualDownEvent();
        assertEquals(1080, endpoint.frequency);
        api.onManualUpEvent();
        assertEquals(990, endpoint.frequency);
        endpoint.frequency = 1620;
        api.onManualDownEvent();
        assertEquals(540, endpoint.frequency);
        api.onManualUpEvent();
        assertEquals(1620, endpoint.frequency);
        assertEquals(5, endpoint.lastTuneBand);
        assertEquals(4L, endpoint.count(1));
    }

    @Test public void manualStepRejectsDisjointAppAndOemGridsWithoutMutation() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.fmMin = 8755;
        endpoint.fmStep = 10;
        NwdRadioApi api = endpoint.api();
        assertThrows(RemoteException.class, api::onManualDownEvent);
        assertThrows(RemoteException.class, api::onManualUpEvent);
        assertNoTuningOrSearch(endpoint);
        assertTrue(endpoint.audio.sent.isEmpty());
    }

    @Test public void oemAmStationOutsideAppGridDoesNotBlockAnAllowedFmTarget() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.band = 5;
        endpoint.frequency = 1000;
        endpoint.amMin = 530;
        endpoint.amMax = 1710;
        endpoint.amStep = 10;
        NwdRadioApi api = endpoint.api();
        assertTrue(api.tuneToBand(0, 101700, endpoint.current::get));
        assertEquals(0, endpoint.lastTuneBand);
        assertEquals(10170, endpoint.frequency);
        assertEquals(1L, endpoint.count(1));
    }

    @Test public void oemAmStationOutsideAppGridCanReachAnAllowedIntersectionTarget() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.band = 3;
        endpoint.frequency = 1000;
        endpoint.amMin = 530;
        endpoint.amMax = 1710;
        endpoint.amStep = 10;
        NwdRadioApi api = endpoint.api();
        assertTrue(api.tuneToBand(3, 990, endpoint.current::get));
        assertEquals(3, endpoint.lastTuneBand);
        assertEquals(990, endpoint.frequency);
        assertEquals(1L, endpoint.count(1));
        assertEquals(0L, endpoint.count(5));
    }

    @Test public void rawAm3NormalizesToAmWithoutLosingItsSelectedRawBank() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.band = 5;
        endpoint.frequency = 999;
        NwdRadioApi api = endpoint.api();
        assertEquals(3, api.getCurrentBand());
        assertEquals(999, api.getCurrentFreq());
        assertTrue(api.tuneToBand(3, 900, endpoint.current::get));
        assertEquals(5, endpoint.lastTuneBand);
        assertEquals(900, endpoint.frequency);
        assertEquals(1L, endpoint.count(1));
        assertEquals(0L, endpoint.count(5));
    }

    @Test public void fmlBanksCannotMasqueradeAsSupportedFmOrAm() throws Exception {
        for (int unsupportedBand : new int[]{6, 7, 8}) {
            Endpoint endpoint = new Endpoint();
            endpoint.band = unsupportedBand;
            NwdRadioApi api = endpoint.api();
            assertThrows(RemoteException.class, api::frequency);
            assertFalse(attemptTune(api, endpoint, 0, 101700));
            assertNoTuningOrSearch(endpoint);
        }
    }

    @Test public void bandCyclingConfirmsFmAndAmBeforeOneFrequencyWrite() throws Exception {
        Endpoint endpoint = new Endpoint();
        NwdRadioApi api = endpoint.api();
        assertTrue(api.tuneToBand(3, 900, endpoint.current::get));
        assertEquals(3, endpoint.band);
        assertEquals(900, endpoint.frequency);
        assertEquals(3, endpoint.lastTuneBand);
        assertEquals(1L, endpoint.count(1));
        endpoint.band = 5;
        assertTrue(api.tuneToBand(0, 101700, endpoint.current::get));
        assertEquals(0, endpoint.lastTuneBand);
        assertEquals(10170, endpoint.frequency);
        assertEquals(2L, endpoint.count(1));
    }

    @Test public void bandChangeAlreadyAtRequestedFrequencyDoesNotSendAnotherTune() throws Exception {
        Endpoint endpoint = new Endpoint();
        NwdRadioApi api = endpoint.api();
        assertTrue(api.tuneToBand(3, 999, endpoint.current::get));
        assertEquals(3, endpoint.band);
        assertEquals(999, endpoint.frequency);
        assertEquals(0L, endpoint.count(1));
        assertEquals(-1, endpoint.lastTuneBand);
    }

    @Test public void sourceOwnershipLossAndCallerCancellationPreventLateTuning() throws Exception {
        Endpoint notOwned = new Endpoint();
        notOwned.audio.source = 0;
        assertFalse(attemptTune(notOwned.api(), notOwned, 0, 101700));
        assertNoTuningOrSearch(notOwned);
        assertTrue(notOwned.audio.sent.isEmpty());

        Endpoint duringBand = new Endpoint();
        duringBand.handoffOnBand = true;
        assertFalse(attemptTune(duringBand.api(), duringBand, 3, 999));
        assertEquals(0L, duringBand.count(1));
        assertTrue(duringBand.audio.sent.isEmpty());

        for (boolean handoff : new boolean[]{false, true}) {
            Endpoint afterWrite = new Endpoint();
            afterWrite.handoffOnTune = handoff;
            afterWrite.cancelOnTune = !handoff;
            assertFalse(attemptTune(afterWrite.api(), afterWrite, 0, 101700));
            assertEquals(1L, afterWrite.count(1));
            assertTrue(afterWrite.audio.sent.isEmpty());
        }
    }

    @Test public void localModeUsesInspectedCommandAndObservedAcknowledgement() throws Exception {
        Endpoint endpoint = new Endpoint();
        NwdRadioApi api = endpoint.api();
        api.onLocDxEvent();
        assertTrue(api.IsDxLocal());
        api.onLocDxEvent();
        assertFalse(api.IsDxLocal());
        assertEquals(2L, endpoint.count(8));
        assertNoTuningOrSearch(endpoint);
        assertTrue(endpoint.audio.sent.isEmpty());
    }
}
