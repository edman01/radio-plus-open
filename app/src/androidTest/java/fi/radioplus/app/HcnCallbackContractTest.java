package fi.radioplus.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.hcn.autoradio.IRadioCallBack;
import com.hcn.autoradio.IRadioServiceAPI;

import org.junit.Test;

import static org.junit.Assert.*;

/** Synthetic Binder coverage for the one-way callback used by both HCN contracts. */
public final class HcnCallbackContractTest {
    private static final String DESCRIPTOR = "com.hcn.autoradio.IRadioCallBack";
    private static final String[] VALUES = {null, "", "Synthetic RDS"};

    private static final class EventSink extends IRadioCallBack.Stub {
        int calls;
        int event;
        String value;

        @Override public void onEvent(int event, String value) {
            calls++;
            this.event = event;
            this.value = value;
        }
    }

    @Test public void generatedStubAcceptsManufacturerOneWayEventWithoutReply() throws Exception {
        EventSink sink = new EventSink();
        assertEquals(DESCRIPTOR, sink.asBinder().getInterfaceDescriptor());
        for (int i = 0; i < VALUES.length; i++) {
            Parcel input = eventParcel(i + 1, VALUES[i]);
            try {
                // Invoke the generated dispatch directly so a null-reply write
                // cannot be hidden by Binder's remote exception handling.
                assertTrue(sink.onTransact(1, input, null, IBinder.FLAG_ONEWAY));
                assertEquals(i + 1, sink.calls);
                assertEquals(i + 1, sink.event);
                assertEquals(VALUES[i], sink.value);
                assertEquals(0, input.dataAvail());
            } finally {
                input.recycle();
            }
        }
    }

    @Test public void generatedProxyUsesManufacturerOneWayWireLayout() throws Exception {
        CallbackEndpoint endpoint = new CallbackEndpoint();
        IRadioCallBack proxy = IRadioCallBack.Stub.asInterface(endpoint);
        assertSame(endpoint, proxy.asBinder());
        for (int i = 0; i < VALUES.length; i++) {
            proxy.onEvent(i + 1, VALUES[i]);
            assertEquals(i + 1, endpoint.calls);
            assertEquals(i + 1, endpoint.event);
            assertEquals(VALUES[i], endpoint.value);
        }
    }

    @Test public void differentInterfaceDescriptorIsRejectedBeforeCallbackDispatch() {
        EventSink sink = new EventSink();
        Parcel input = Parcel.obtain();
        try {
            input.writeInterfaceToken("com.example.unrelated.callback");
            input.writeInt(1);
            input.writeString("Synthetic RDS");
            input.setDataPosition(0);
            assertThrows(SecurityException.class,
                    () -> sink.onTransact(1, input, null, IBinder.FLAG_ONEWAY));
            assertEquals(0, sink.calls);
        } finally {
            input.recycle();
        }
    }

    @Test public void currentAndLegacyRegisterTheSameCallbackBinderAndReceiveOneWayEvents() throws Exception {
        for (RadioBackendProfile profile : new RadioBackendProfile[]{
                RadioBackendProfile.HCN_CURRENT_31, RadioBackendProfile.HCN_LEGACY_25}) {
            RegistrationEndpoint endpoint = new RegistrationEndpoint();
            IRadioServiceAPI api = RadioApiFactory.create(profile, endpoint);
            EventSink sink = new EventSink();
            api.registerRadioCallback(sink);
            assertSame(sink.asBinder(), endpoint.registered);
            Parcel input = eventParcel(7, "Synthetic station");
            try {
                assertTrue(endpoint.registered.transact(1, input, null, IBinder.FLAG_ONEWAY));
                assertEquals(1, sink.calls);
                assertEquals(7, sink.event);
                assertEquals("Synthetic station", sink.value);
            } finally {
                input.recycle();
            }
            assertEquals(1, endpoint.calls);
        }
    }

    private static Parcel eventParcel(int event, String value) {
        Parcel input = Parcel.obtain();
        input.writeInterfaceToken(DESCRIPTOR);
        input.writeInt(event);
        input.writeString(value);
        input.setDataPosition(0);
        return input;
    }

    private static final class CallbackEndpoint extends Binder {
        int calls;
        int event;
        String value;

        CallbackEndpoint() { attachInterface(null, DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            assertEquals(1, code);
            assertEquals(IBinder.FLAG_ONEWAY, flags);
            assertNull("One-way callbacks must not allocate a reply", reply);
            data.enforceInterface(DESCRIPTOR);
            event = data.readInt();
            value = data.readString();
            assertEquals(0, data.dataAvail());
            calls++;
            return true;
        }
    }

    private static final class RegistrationEndpoint extends Binder {
        IBinder registered;
        int calls;

        RegistrationEndpoint() { attachInterface(null, RadioApiFactory.DESCRIPTOR); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            assertEquals(3, code);
            assertEquals(0, flags);
            assertNotNull(reply);
            data.enforceInterface(RadioApiFactory.DESCRIPTOR);
            registered = data.readStrongBinder();
            assertEquals(0, data.dataAvail());
            calls++;
            reply.writeNoException();
            return true;
        }
    }
}
