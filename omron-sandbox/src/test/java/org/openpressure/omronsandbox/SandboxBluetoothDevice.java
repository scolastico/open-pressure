package org.openpressure.omronsandbox;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.content.Context;
import android.os.Handler;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowBluetoothDevice;

/** Routes every {@code connectGatt} call to the fixture-backed GATT peer instead of Robolectric's default. */
@Implements(BluetoothDevice.class)
public class SandboxBluetoothDevice extends ShadowBluetoothDevice {
    /** Set by the runner for the current fixture; null means "no peer", which fails the connection. */
    static FixtureGatt peer;
    @RealObject private BluetoothDevice self;

    private BluetoothGatt connect(BluetoothGattCallback callback) {
        if(peer==null) throw new IllegalStateException("No fixture peer installed");
        return peer.connect(callback,self);
    }
    @Implementation @Override protected BluetoothGatt connectGatt(Context context,boolean auto,BluetoothGattCallback callback) { return connect(callback); }
    @Implementation @Override protected BluetoothGatt connectGatt(Context context,boolean auto,BluetoothGattCallback callback,int transport) { return connect(callback); }
    @Implementation @Override protected BluetoothGatt connectGatt(Context context,boolean auto,BluetoothGattCallback callback,int transport,int phy) { return connect(callback); }
    @Implementation @Override protected BluetoothGatt connectGatt(Context context,boolean auto,BluetoothGattCallback callback,int transport,int phy,Handler handler) { return connect(callback); }
    @Implementation @Override protected boolean createBond() {
        if(peer!=null) peer.event("createBond");
        setBondState(BluetoothDevice.BOND_BONDED);
        return true;
    }
}
