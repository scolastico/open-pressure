package org.openpressure.app.automatic;

import android.app.Activity;
import org.openpressure.app.R;
import android.Manifest;
import android.content.pm.PackageManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanResult;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceManager;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.os.*;
import android.view.View;
import android.widget.*;
import org.openpressure.app.ble.MonitorService;
import org.openpressure.app.PressureApp;
import org.openpressure.app.notifications.NewReadingNotifications;

/** Explicit choice between visible-app, foreground-notification, and Android companion listening. */
public final class AutoImportSettingsActivity extends Activity {
    private static final int ASSOCIATION_REQUEST=71, BLUETOOTH_PERMISSION_REQUEST=72, ENABLE_BLUETOOTH_REQUEST=73, RADIO_BASE=50000;
    private static final int TEAL=0xff176e65, INK=0xff182d2a, MUTED=0xff61726b;
    private CompanionLinks links;
    private MonitorService monitor;
    private boolean visible,bound,rendering,associationBusy,enablePending;
    private String pendingAddress,enableResumeAddress;
    private RadioGroup modes;
    private LinearLayout devices;
    private TextView status,permissionStatus,locationStatus,bluetoothStatus,notificationStatus;
    private Button grantPermissions,managePermissions,locationSettings,enableBluetooth,notificationPermissions;
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            monitor=((MonitorService.LocalBinder)binder).get();
            monitor.setUiVisible(AutoImportSettingsActivity.this,visible);
            if(visible) monitor.refreshAutomaticImports();
        }
        @Override public void onServiceDisconnected(ComponentName name) { monitor=null; }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); links=new CompanionLinks(this);
        if(saved!=null) {
            pendingAddress=saved.getString("pendingAddress"); associationBusy=saved.getBoolean("associationBusy");
            enablePending=saved.getBoolean("enablePending"); enableResumeAddress=saved.getString("enableResumeAddress");
        }
        NewReadingNotifications.createChannel(this);
        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPaddingRelative(dp(22),dp(20),dp(22),dp(28)); page.setBackgroundColor(0xfff5f7f4);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view,insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets;
        });
        setContentView(scroll);
        add(page,button(getString(R.string.settings_back),this::finish),0);
        add(page,text(getString(R.string.settings_auto_title),30,INK,true),18);
        add(page,text(getString(R.string.settings_auto_intro),16,INK,false),14);
        modes=new RadioGroup(this);
        mode(getString(R.string.settings_auto_off),AutoImportSettings.OFF,true);
        mode(getString(R.string.settings_auto_visible),AutoImportSettings.VISIBLE,true);
        boolean notificationSupported=Build.VERSION.SDK_INT<29 || Build.VERSION.SDK_INT>=31;
        mode(notificationSupported?getString(R.string.settings_auto_notification):getString(R.string.settings_auto_notification_unavailable),AutoImportSettings.NOTIFICATION,notificationSupported);
        mode(links.available()?getString(R.string.settings_auto_companion):getString(R.string.settings_auto_companion_unavailable),AutoImportSettings.COMPANION,links.available());
        add(page,modes,16);
        permissionStatus=text("",15,MUTED,false);
        permissionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(page,permissionStatus,12);
        grantPermissions=button(getString(R.string.settings_bluetooth_permission),this::requestBluetoothPermissions);
        add(page,grantPermissions,6);
        managePermissions=button(getString(R.string.settings_app_permissions),() -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName()))));
        add(page,managePermissions,6);
        bluetoothStatus=text(getString(R.string.settings_bluetooth_off),16,0xff8a522d,true);
        bluetoothStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(page,bluetoothStatus,12);
        enableBluetooth=button(getString(R.string.settings_bluetooth_enable),() -> requestEnableBluetooth(null)); add(page,enableBluetooth,6);
        notificationStatus=text(getString(R.string.settings_notice_explanation),15,MUTED,false);
        add(page,notificationStatus,12);
        notificationPermissions=button(getString(R.string.settings_notice_allow),this::requestReadingNotifications); add(page,notificationPermissions,6);
        add(page,text(getString(R.string.settings_auto_choices),15,MUTED,false),12);
        if(!notificationSupported) add(page,text(getString(R.string.settings_auto_background_unavailable),15,MUTED,false),12);
        add(page,text(getString(R.string.settings_auto_companion_help),15,MUTED,false),12);
        locationStatus=text(getString(R.string.settings_auto_location_help),15,MUTED,false);
        add(page,locationStatus,12);
        locationSettings=button(getString(R.string.settings_location_settings),() -> startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        add(page,locationSettings,6);
        status=text("",16,INK,true); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(page,status,18);
        devices=new LinearLayout(this); devices.setOrientation(LinearLayout.VERTICAL); add(page,devices,8);
        add(page,text(getString(R.string.settings_auto_data_help),15,MUTED,false),20);
        modes.setOnCheckedChangeListener((group,id) -> {
            if(rendering) return;
            int mode=id-RADIO_BASE;
            try {
                AutoImportSettings.setMode(this,mode); refreshObservers(); if(monitor!=null && visible) monitor.refreshAutomaticImports();
                if(backgroundMode(mode) && Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                        && !NewReadingNotifications.permissionRequested(this))
                    NewReadingNotifications.requestPermissionIfNeeded(this);
            }
            catch(IllegalArgumentException | IllegalStateException failure) { toast(getString(R.string.settings_auto_change_failed)); }
            updateViews();
        });
        updateViews();
    }
    @Override protected void onStart() {
        super.onStart(); visible=true;
        bound=bindService(new Intent(this,MonitorService.class),connection,BIND_AUTO_CREATE);
    }
    @Override protected void onResume() {
        super.onResume();
        if(pendingAddress!=null && links.isAssociated(pendingAddress)) completeAssociation(pendingAddress);
        refreshObservers(); updateViews();
        if(backgroundMode(AutoImportSettings.getMode(this))) NewReadingNotifications.requestPermissionIfNeeded(this);
    }
    @Override protected void onStop() {
        visible=false;
        if(monitor!=null) monitor.setUiVisible(this,false);
        if(bound) { unbindService(connection); bound=false; }
        monitor=null; super.onStop();
    }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putString("pendingAddress",pendingAddress); out.putBoolean("associationBusy",associationBusy);
        out.putBoolean("enablePending",enablePending); out.putString("enableResumeAddress",enableResumeAddress);
    }
    private void mode(String label,int mode,boolean enabled) {
        RadioButton radio=new RadioButton(this); radio.setId(RADIO_BASE+mode); radio.setText(label); radio.setTextColor(INK); radio.setTextSize(16); radio.setMinHeight(dp(54)); radio.setEnabled(enabled); modes.addView(radio);
    }
    private void refreshObservers() {
        if(AutoImportSettings.getMode(this)==AutoImportSettings.COMPANION && !bluetoothEnabled()) return;
        if(!links.refreshObservations().isEmpty()) toast(getString(R.string.settings_companion_retry));
    }
    private void updateViews() {
        if(isDestroyed()) return;
        int mode=AutoImportSettings.getMode(this);
        LocationManager location=getSystemService(LocationManager.class);
        boolean locationHint=mode==AutoImportSettings.COMPANION && Build.VERSION.SDK_INT>=31 && location!=null && !location.isLocationEnabled();
        locationStatus.setVisibility(locationHint?View.VISIBLE:View.GONE);
        locationSettings.setVisibility(locationHint?View.VISIBLE:View.GONE);
        boolean permissionGranted=links.hasBluetoothPermissions();
        boolean bluetoothOff=mode!=AutoImportSettings.OFF && permissionGranted && !bluetoothEnabled();
        bluetoothStatus.setVisibility(bluetoothOff?View.VISIBLE:View.GONE);
        enableBluetooth.setVisibility(bluetoothOff?View.VISIBLE:View.GONE); enableBluetooth.setEnabled(!enablePending && !associationBusy);
        boolean notificationsMissing=backgroundMode(mode) && !NewReadingNotifications.enabled(this);
        notificationStatus.setVisibility(notificationsMissing?View.VISIBLE:View.GONE);
        notificationPermissions.setVisibility(notificationsMissing?View.VISIBLE:View.GONE);
        permissionStatus.setVisibility(permissionGranted?View.GONE:View.VISIBLE);
        permissionStatus.setText(Build.VERSION.SDK_INT>=31?getString(R.string.settings_nearby_help):getString(R.string.settings_location_permission_help));
        grantPermissions.setVisibility(permissionGranted?View.GONE:View.VISIBLE);
        managePermissions.setVisibility(permissionGranted?View.GONE:View.VISIBLE);
        rendering=true; modes.check(RADIO_BASE+mode); rendering=false;
        devices.removeAllViews();
        if(mode!=AutoImportSettings.COMPANION) {
            if(mode==AutoImportSettings.NOTIFICATION && Build.VERSION.SDK_INT>=29 && Build.VERSION.SDK_INT<=30) { status.setText(getString(R.string.settings_background_unavailable_status)); return; }
            status.setText(mode==AutoImportSettings.OFF?getString(R.string.settings_auto_off_status):mode==AutoImportSettings.VISIBLE?getString(R.string.settings_auto_visible_status):getString(R.string.settings_auto_notification_status));
            return;
        }
        if(!links.available()) { status.setText(getString(R.string.settings_companion_unavailable_status)); return; }
        status.setText(getString(R.string.settings_companion_approve_help));
        if(links.knownMonitors().isEmpty()) { add(devices,text(getString(R.string.settings_companion_no_monitors),15,MUTED,false),8); return; }
        for(String[] row:links.knownMonitors()) {
            boolean associated=links.isAssociated(row[0]);
            add(devices,text(getString(R.string.settings_companion_monitor,row[1],row[0],getString(associated?R.string.settings_companion_approved:R.string.settings_companion_approval_needed)),15,INK,true),14);
            Button setup=button(associated?getString(R.string.settings_companion_restart):getString(R.string.settings_companion_approve),() -> setupCompanion(row[0]));
            setup.setEnabled(!associationBusy && !enablePending && permissionGranted); add(devices,setup,6);
        }
    }
    private static boolean backgroundMode(int mode) { return mode==AutoImportSettings.NOTIFICATION || mode==AutoImportSettings.COMPANION; }
    private boolean bluetoothEnabled() {
        if(!links.hasBluetoothPermissions()) return false;
        BluetoothManager manager=getSystemService(BluetoothManager.class);
        try { return manager!=null && manager.getAdapter()!=null && manager.getAdapter().isEnabled(); }
        catch(SecurityException revoked) { return false; }
    }
    private void requestEnableBluetooth(String resumeAddress) {
        if(enablePending || associationBusy) return;
        if(!links.hasBluetoothPermissions()) { toast(getString(R.string.settings_bluetooth_permission_first)); return; }
        BluetoothManager manager=getSystemService(BluetoothManager.class);
        if(manager==null || manager.getAdapter()==null) { toast(getString(R.string.settings_bluetooth_unavailable)); return; }
        enablePending=true; enableResumeAddress=resumeAddress; updateViews();
        try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BLUETOOTH_REQUEST); }
        catch(ActivityNotFoundException | SecurityException failure) {
            enablePending=false; enableResumeAddress=null; updateViews(); toast(getString(R.string.settings_bluetooth_open_failed));
        }
    }
    private void setupCompanion(String address) {
        if(AutoImportSettings.getMode(this)!=AutoImportSettings.COMPANION || !links.available() || links.knownAddress(address)==null || associationBusy || enablePending) return;
        if(!links.hasBluetoothPermissions()) { toast(getString(R.string.settings_companion_permission_first)); return; }
        if(!bluetoothEnabled()) { toast(getString(R.string.settings_bluetooth_enable_first)); requestEnableBluetooth(address); return; }
        if(links.isAssociated(address)) toast(links.observe(address)?getString(R.string.settings_companion_enabled):getString(R.string.settings_companion_start_failed));
        else associate(address);
    }
    private void requestReadingNotifications() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                && (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) || !NewReadingNotifications.permissionRequested(this))) {
            NewReadingNotifications.requestPermission(this);
        } else {
            android.app.NotificationManager manager=getSystemService(android.app.NotificationManager.class);
            boolean appBlocked=manager==null || !manager.areNotificationsEnabled()
                    || Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED;
            Intent settings=new Intent(appBlocked?android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS:android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName());
            if(!appBlocked) settings.putExtra(android.provider.Settings.EXTRA_CHANNEL_ID,NewReadingNotifications.CHANNEL_ID);
            startActivity(settings);
        }
    }
    private void requestBluetoothPermissions() {
        String[] required=Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        requestPermissions(required,BLUETOOTH_PERMISSION_REQUEST);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==NewReadingNotifications.PERMISSION_REQUEST) { updateViews(); return; }
        if(request!=BLUETOOTH_PERMISSION_REQUEST) return;
        refreshObservers();
        if(monitor!=null && visible) monitor.refreshAutomaticImports();
        updateViews();
        if(!links.hasBluetoothPermissions()) toast(getString(R.string.settings_bluetooth_permission_missing));
    }
    private void associate(String address) {
        if(!links.hasBluetoothPermissions()) { toast(getString(R.string.settings_companion_permission_first)); return; }
        if(!links.available() || links.knownAddress(address)==null || associationBusy) return;
        if(!bluetoothEnabled()) { requestEnableBluetooth(address); return; }
        pendingAddress=address; associationBusy=true; updateViews();
        associationEvent("Request existing-bond and BLE saved-address approval · Android API "+Build.VERSION.SDK_INT);
        CompanionDeviceManager.Callback callback=new CompanionDeviceManager.Callback() {
            private boolean launched;
            private void launch(IntentSender sender) {
                if(launched || isDestroyed()) return; launched=true;
                associationEvent("Opening Android approval dialog");
                try { startIntentSenderForResult(sender,ASSOCIATION_REQUEST,null,0,0,0); }
                catch(IntentSender.SendIntentException | SecurityException failure) { failedAssociation(getString(R.string.settings_companion_open_failed)); }
            }
            @Override public void onDeviceFound(IntentSender sender) { launch(sender); }
            @Override public void onAssociationPending(IntentSender sender) { launch(sender); }
            @android.annotation.TargetApi(33)
            @Override public void onAssociationCreated(AssociationInfo association) {
                if(association.getDeviceMacAddress()!=null) completeAssociation(association.getDeviceMacAddress().toString());
                else failedAssociation(getString(R.string.settings_companion_not_confirmed));
            }
            @Override public void onFailure(CharSequence message) { failedAssociation(getString(R.string.settings_companion_approval_failed)); }
        };
        try {
            if(Build.VERSION.SDK_INT>=33) links.deviceManager().associate(links.request(address),getMainExecutor(),callback);
            else links.deviceManager().associate(links.request(address),callback,new Handler(Looper.getMainLooper()));
        } catch(IllegalArgumentException | IllegalStateException | SecurityException failure) { failedAssociation(getString(R.string.settings_companion_request_failed)); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==ENABLE_BLUETOOTH_REQUEST) {
            if(!enablePending) return;
            String resume=enableResumeAddress; enableResumeAddress=null; enablePending=false;
            refreshObservers(); if(monitor!=null && visible) monitor.refreshAutomaticImports(); updateViews();
            if(result==RESULT_OK && bluetoothEnabled()) { if(resume!=null) setupCompanion(resume); }
            else toast(getString(R.string.settings_bluetooth_cancelled));
            return;
        }
        if(request!=ASSOCIATION_REQUEST) return;
        associationEvent("Android approval result="+result);
        if(result!=RESULT_OK) { failedAssociation(getString(R.string.settings_companion_cancelled)); return; }
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
            failedAssociation(getString(R.string.settings_companion_nearby_needed)); return;
        }
        String address=null;
        if(data!=null) {
            if(Build.VERSION.SDK_INT>=33) {
                AssociationInfo info=data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION,AssociationInfo.class);
                if(info!=null && info.getDeviceMacAddress()!=null) address=info.getDeviceMacAddress().toString();
            }
            if(address==null) {
                Object device=data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE);
                if(device instanceof ScanResult) address=((ScanResult)device).getDevice().getAddress();
                else if(device instanceof BluetoothDevice) address=((BluetoothDevice)device).getAddress();
            }
        }
        // New Android versions also report through onAssociationCreated; never trust RESULT_OK alone.
        if(address!=null) completeAssociation(address);
        else if(pendingAddress!=null && links.isAssociated(pendingAddress)) completeAssociation(pendingAddress);
        else if(Build.VERSION.SDK_INT<33) failedAssociation(getString(R.string.settings_companion_unapproved));
    }
    private void completeAssociation(String address) {
        if(isDestroyed() || pendingAddress==null) return;
        if(!pendingAddress.equalsIgnoreCase(address) || links.knownAddress(address)==null || !links.isAssociated(address)) {
            failedAssociation(getString(R.string.settings_companion_wrong_monitor)); return;
        }
        associationEvent("Android association verified for saved monitor");
        associationBusy=false; pendingAddress=null;
        refreshObservers(); if(monitor!=null && visible) monitor.refreshAutomaticImports(); updateViews();
    }
    private void failedAssociation(String message) {
        if(isDestroyed()) return;
        associationEvent("Approval failed: "+message);
        associationBusy=false; pendingAddress=null; toast(message); updateViews();
    }
    private void associationEvent(String event) {
        if(pendingAddress!=null) ((PressureApp)getApplication()).store().packet(pendingAddress,"companion-association",new byte[0],event);
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView text=new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setLineSpacing(dp(3),1);
        if(bold) text.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return text;
    }
    private Button button(String label,Runnable action) {
        Button button=new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(14); button.setMinHeight(dp(50)); button.setTextColor(TEAL); button.setBackgroundTintList(ColorStateList.valueOf(0xffe9efea)); button.setOnClickListener(view -> action.run()); return button;
    }
    private void add(LinearLayout page,View view,int top) {
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(top); page.addView(view,params);
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_LONG).show(); }
}
