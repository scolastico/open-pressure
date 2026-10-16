package org.openpressure.app.automatic;

import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;
import android.companion.DevicePresenceEvent;
import android.content.*;
import android.os.IBinder;
import org.openpressure.app.ble.MonitorService;
import java.util.*;

/** Only Android binds this service; its bound monitor runtime needs no foreground notification. */
@android.annotation.TargetApi(31)
public final class MonitorCompanionService extends CompanionDeviceService {
    private CompanionLinks links;
    private final CompanionPresenceTracker presence=new CompanionPresenceTracker();
    private final Set<String> leases=new HashSet<>();
    private MonitorService monitor;
    private boolean bound;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener=(prefs,key) -> {
        if(AutoImportSettings.MODE_KEY.equals(key) && AutoImportSettings.getMode(this)!=AutoImportSettings.COMPANION) revokeLeases();
    };
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            monitor=((MonitorService.LocalBinder)binder).get();
            for(String address:new HashSet<>(leases)) {
                if(allowed(address)) monitor.setCompanionPresence(address,true);
                else revokeLease(address);
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) { monitor=null; }
    };
    @Override public void onCreate() {
        super.onCreate(); links=new CompanionLinks(this);
        AutoImportSettings.preferences(this).registerOnSharedPreferenceChangeListener(settingsListener);
    }
    private boolean allowed(String address) {
        return AutoImportSettings.getMode(this)==AutoImportSettings.COMPANION && links.hasBluetoothPermissions() && links.knownAddress(address)!=null && links.isAssociated(address);
    }
    private void changed(String address,int source,boolean appeared) {
        reconcileLeases();
        if(address==null) return;
        String saved=links.knownAddress(address);
        if(saved==null) for(String leased:new HashSet<>(leases)) if(leased.equalsIgnoreCase(address)) { saved=leased; break; }
        if(saved==null) return;
        boolean present=presence.update(saved,source,appeared,allowed(saved));
        if(present) {
            leases.add(saved);
            if(!bound) bound=bindService(new Intent(this,MonitorService.class),connection,BIND_AUTO_CREATE);
        } else leases.remove(saved);
        if(monitor!=null) monitor.setCompanionPresence(saved,present);
    }
    @Override public void onDeviceAppeared(String address) { changed(address,1,true); }
    @Override public void onDeviceDisappeared(String address) { changed(address,1,false); }
    @android.annotation.TargetApi(33)
    @Override public void onDeviceAppeared(AssociationInfo association) {
        reconcileLeases();
        if(association.getDeviceMacAddress()!=null && association.getDeviceMacAddress().toString().equalsIgnoreCase(links.addressForAssociation(association.getId())))
            changed(association.getDeviceMacAddress().toString(),1,true);
    }
    @android.annotation.TargetApi(33)
    @Override public void onDeviceDisappeared(AssociationInfo association) {
        reconcileLeases();
        if(association.getDeviceMacAddress()!=null && association.getDeviceMacAddress().toString().equalsIgnoreCase(links.addressForAssociation(association.getId())))
            changed(association.getDeviceMacAddress().toString(),1,false);
    }
    @android.annotation.TargetApi(36)
    @Override public void onDevicePresenceEvent(DevicePresenceEvent event) {
        // A revoked association may no longer resolve to an address; clear its old lease first.
        reconcileLeases();
        String address=links.addressForAssociation(event.getAssociationId());
        if(address==null) return;
        switch(event.getEvent()) {
            case DevicePresenceEvent.EVENT_BLE_APPEARED: changed(address,1,true); break;
            case DevicePresenceEvent.EVENT_BLE_DISAPPEARED: changed(address,1,false); break;
            case DevicePresenceEvent.EVENT_BT_CONNECTED: changed(address,2,true); break;
            case DevicePresenceEvent.EVENT_BT_DISCONNECTED: changed(address,2,false); break;
            default: break;
        }
    }
    private void reconcileLeases() {
        for(String address:new HashSet<>(leases)) if(!allowed(address)) revokeLease(address);
    }
    private void revokeLease(String address) {
        if(monitor!=null) monitor.setCompanionPresence(address,false);
        presence.update(address,3,false,false);
        leases.remove(address);
    }
    private void revokeLeases() {
        for(String address:new HashSet<>(leases)) revokeLease(address);
    }
    @Override public void onDestroy() {
        AutoImportSettings.preferences(this).unregisterOnSharedPreferenceChangeListener(settingsListener);
        revokeLeases();
        if(bound) { unbindService(connection); bound=false; }
        monitor=null; super.onDestroy();
    }
}
