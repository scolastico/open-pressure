package org.openpressure.app.automatic;

import android.bluetooth.le.ScanFilter;
import android.Manifest;
import android.companion.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import org.openpressure.app.PressureApp;
import org.openpressure.protocol.X7Record;
import java.util.*;

/** Android association grants presence callbacks; it never registers a user on the cuff. */
@android.annotation.TargetApi(31)
public final class CompanionLinks {
    private final Context context;
    public CompanionLinks(Context context) { this.context=context.getApplicationContext(); }
    public boolean available() {
        return Build.VERSION.SDK_INT>=31 && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)
                && manager()!=null;
    }
    public boolean hasBluetoothPermissions() {
        if(Build.VERSION.SDK_INT>=31) return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private CompanionDeviceManager manager() { return context.getSystemService(CompanionDeviceManager.class); }
    public Set<String> associatedAddresses() {
        Set<String> result=new HashSet<>();
        if(!available()) return result;
        if(Build.VERSION.SDK_INT>=33) {
            for(AssociationInfo info:manager().getMyAssociations()) if(info.getDeviceMacAddress()!=null) result.add(info.getDeviceMacAddress().toString().toUpperCase(Locale.ROOT));
        } else for(String address:manager().getAssociations()) result.add(address.toUpperCase(Locale.ROOT));
        return result;
    }
    public boolean isAssociated(String address) { return address!=null && associatedAddresses().contains(address.toUpperCase(Locale.ROOT)); }
    public String addressForAssociation(int id) {
        if(!available() || Build.VERSION.SDK_INT<33) return null;
        for(AssociationInfo info:manager().getMyAssociations()) if(info.getId()==id && info.getDeviceMacAddress()!=null) return info.getDeviceMacAddress().toString();
        return null;
    }
    private AssociationInfo association(String address) {
        if(Build.VERSION.SDK_INT>=33) for(AssociationInfo info:manager().getMyAssociations())
            if(info.getDeviceMacAddress()!=null && address.equalsIgnoreCase(info.getDeviceMacAddress().toString())) return info;
        return null;
    }
    public boolean observe(String address) {
        if(!available() || !isAssociated(address)) return false;
        try {
            if(Build.VERSION.SDK_INT>=36) {
                AssociationInfo info=association(address);
                if(info==null) return false;
                manager().startObservingDevicePresence(new ObservingDevicePresenceRequest.Builder().setAssociationId(info.getId()).build());
            } else manager().startObservingDevicePresence(address);
            return true;
        } catch(DeviceNotAssociatedException | IllegalArgumentException | IllegalStateException | SecurityException failure) { return false; }
    }
    public void stopObserving(String address) {
        if(!available() || !isAssociated(address)) return;
        try {
            if(Build.VERSION.SDK_INT>=36) {
                AssociationInfo info=association(address);
                if(info!=null) manager().stopObservingDevicePresence(new ObservingDevicePresenceRequest.Builder().setAssociationId(info.getId()).build());
            } else manager().stopObservingDevicePresence(address);
        } catch(DeviceNotAssociatedException | IllegalArgumentException | IllegalStateException | SecurityException ignored) { }
    }
    /** Call when a saved monitor is forgotten, before removing its saved address. */
    public void forget(String address) {
        if(!available()) return;
        stopObserving(address);
        if(!isAssociated(address)) return;
        if(Build.VERSION.SDK_INT>=33) {
            AssociationInfo info=association(address);
            if(info!=null) manager().disassociate(info.getId());
        } else manager().disassociate(address);
    }
    public List<String[]> knownMonitors() {
        List<String[]> result=new ArrayList<>();
        for(String[] row:((PressureApp)context).store().devices()) if(X7Record.supportsModel(row[2])) result.add(row);
        return result;
    }
    public String knownAddress(String address) {
        if(address==null) return null;
        for(String[] row:knownMonitors()) if(address.equalsIgnoreCase(row[0])) return row[0];
        return null;
    }
    /** Stop old observers even when an association outlives the saved monitor. */
    public List<String> refreshObservations() {
        List<String> failures=new ArrayList<>();
        boolean enabled=AutoImportSettings.getMode(context)==AutoImportSettings.COMPANION && hasBluetoothPermissions();
        for(String address:associatedAddresses()) {
            String saved=knownAddress(address);
            if(enabled && saved!=null) { if(!observe(saved)) failures.add(saved); }
            else stopObserving(address);
        }
        return failures;
    }
    public AssociationRequest request(String address) {
        String saved=knownAddress(address);
        if(!available() || saved==null) throw new IllegalArgumentException("Select a saved, verified X7 monitor");
        // CDM checks existing bonds only for an address-based BluetoothDeviceFilter,
        // even for LE-only peripherals. A BLE-only request always needs a fresh scan.
        BluetoothDeviceFilter bonded=new BluetoothDeviceFilter.Builder().setAddress(saved).build();
        BluetoothLeDeviceFilter filter=new BluetoothLeDeviceFilter.Builder()
                .setScanFilter(new ScanFilter.Builder().setDeviceAddress(saved).build()).build();
        return new AssociationRequest.Builder().addDeviceFilter(bonded).addDeviceFilter(filter).setSingleDevice(true).build();
    }
    public CompanionDeviceManager deviceManager() { return manager(); }
}
