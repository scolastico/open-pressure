package org.openpressure.app.ble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import org.openpressure.app.*;
import org.openpressure.app.data.*;
import org.openpressure.app.automatic.*;
import org.openpressure.app.notifications.*;
import org.openpressure.protocol.*;
import java.util.*;
import java.time.LocalDateTime;
import java.util.function.Consumer;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

@SuppressLint("MissingPermission")
public final class MonitorService extends Service {
    public final class LocalBinder extends Binder { public MonitorService get() { return MonitorService.this; } }
    public static final class Discovered {
        public String address,name,metadata; public int rssi;
        public byte[] manufacturerData;
    }
    private static final class PendingConnection {
        final String address,name; final boolean pair;
        PendingConnection(String address,String name,boolean pair) { this.address=address; this.name=name; this.pair=pair; }
    }
    public String status="Ready to connect", detail="Choose your monitor and scan nearby devices.", address="", connectedName="", modelHint="X7 Smart AFib · HEM-7380T1", deviceTime="Not read", information="";
    public enum ClockUpdate { NONE, SKIPPED, ACCEPTED, FAILED }
    public ClockUpdate clockUpdate=ClockUpdate.NONE;
    public boolean sessionEndUnconfirmed;
    public boolean scanning,ready,historyRunning,canHistory,canTime,hasUserControl,canDump,dumpRunning,dumpComplete,dumpSaving,sessionFinished,registrationReading,registrationPending,registrationWriting,registrationDone;
    public int dumpStart,dumpReceived,dumpLength;
    public long readingsRevision;
    public long dumpRevision;
    public int user=1,consentCode=526,authenticatedUser=0,battery=-1,storedCount=-1,receivedInTransfer,addedInTransfer;
    public int storedInTransfer,queuedInTransfer,pendingInTransfer;
    public ProtocolProfile profile=ProtocolProfile.UNSUPPORTED;
    public final LinkedHashMap<String,Discovered> discovered=new LinkedHashMap<>();
    private final Set<Runnable> listeners=new HashSet<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final LocalBinder binder=new LocalBinder();
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private ReadingStore store;
    private PairingKeys keys;
    private OperationQueue operations;
    private SecureSession secure;
    private BluetoothGattCharacteristic handshake,racp,userControl,timeCharacteristic,wlpTx,wlpRx;
    private WlpReadSession wlpRead;
    private VendorMemoryModel vendorModel;
    private VendorWlpSession vendorRead;
    private LegacyUnlock vendorUnlock;
    private VendorPairingKeys vendorKeys;
    private List<byte[]> vendorKeyCandidates=Collections.emptyList();
    private int vendorKeyCandidate,vendorParsed;
    private boolean vendorWaitingBond,vendorSessionEnded;
    private boolean vendorClockAttempted,vendorClockPending;
    private String vendorClockResult="";
    private final List<BluetoothGattCharacteristic> vendorTx=new ArrayList<>(),vendorRx=new ArrayList<>();
    private WlpConnection wlpConnection;
    private X7Registration x7Registration;
    private byte[] registrationSettings;
    private File registrationBackup;
    private long registrationBackupGeneration=-1;
    private X7Bonding x7Bonding;
    private boolean x7FreshBondEstablished;
    private X7Transaction x7Transaction;
    private X7Authentication x7Authentication;
    private X7MemoryIndex x7HistoryIndex;
    private List<X7MemoryIndex.Range> x7HistoryRanges=Collections.emptyList();
    private int x7HistoryRange,x7HistoryRangeOffset;
    private long x7AuthenticationDeadline;
    private byte[] connectionManufacturer;
    private PendingConnection pendingConnection;
    private int wlpParsed,wlpRejected,negotiatedMtu=23;
    private String verifiedModel="";
    private long lastWlpUpdate;
    private final ExecutorService dumpIo=Executors.newSingleThreadExecutor();
    private volatile File dumpFile;
    private long dumpStartedAt;
    private long dumpSerial;
    private long dumpSaveVersion;
    private boolean scanUpdatePending;
    private final Runnable scanUpdate=() -> { scanUpdatePending=false; if(scanning) changed(); };
    private boolean pairingRequested,waitingBond;
    private int pendingUserOperation,pendingUser,minimumSequence;
    private Runnable operationTimeout,phaseTimeout;
    private long generation;
    private long responseGeneration;
    private final HistoryTransfer transfer=new HistoryTransfer();
    public boolean autoWatching;
    public String automaticStatus="Automatic imports wait while the app is open.";
    private final Set<Object> visibleOwners=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> companionPresent=new HashSet<>();
    private final Map<String,String[]> automaticDevices=new LinkedHashMap<>();
    private final AutoImportTracker automaticTracker=new AutoImportTracker();
    private final AutomaticReadingNotificationPolicy readingNotifications=new AutomaticReadingNotificationPolicy();
    private AutoImportTracker.Attempt automaticAttempt;
    private DeviceReport report;
    private String reportAddress;
    private boolean reportPrompted;
    private ScanCallback automaticScan;
    private String automaticFilterKey="";
    private boolean automaticConnection,foregroundActive,destroying;
    private long automaticRetryAt;
    private final Runnable automaticRefresh=() -> { if(!destroying) refreshAutomaticImports(); };

    private final Runnable scanTimeout=() -> {
        boolean awaitingAdvertisement=pendingConnection!=null;
        stopScan();
        if(awaitingAdvertisement) state("X7 advertisement not received","Wake the cuff in pairing or transfer mode and scan again so the app can determine its security protocol.");
    };
    @Override public void onCreate() {
        super.onCreate(); store=((PressureApp)getApplication()).store(); keys=new PairingKeys(this);
        File previousDump=new File(getFilesDir(),"eeprom-dump.json"); if(previousDump.isFile()) dumpFile=previousDump;
        File[] backups=new File(getFilesDir(),"settings-backups").listFiles((directory,name) -> name.startsWith("x7-settings-") && name.endsWith(".json"));
        if(backups!=null) for(File backup:backups) if(backup.isFile() && (registrationBackup==null || backup.lastModified()>registrationBackup.lastModified())) registrationBackup=backup;
        BluetoothManager manager=getSystemService(BluetoothManager.class); adapter=manager==null?null:manager.getAdapter();
        operations=new OperationQueue(this::fail);
        registerReceiver(bondReceiver,new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        registerReceiver(adapterReceiver,new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        NotificationChannel channel=new NotificationChannel("connection",getString(org.openpressure.app.R.string.monitor_channel_connection),NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        NewReadingNotifications.createChannel(this);
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && "disconnect".equals(intent.getAction())) disconnect();
        else if(intent!=null && "stop-automatic".equals(intent.getAction())) {
            AutoImportSettings.setMode(this,AutoImportSettings.OFF);
            if(automaticConnection) disconnect();
            refreshAutomaticImports();
        }
        return START_NOT_STICKY;
    }
    public void listen(Runnable listener) { listeners.add(listener); }
    public void unlisten(Runnable listener) { listeners.remove(listener); }
    public void localReadingsChanged() { readingsRevision++; changed(); }
    private void changed() { for(Runnable l:new ArrayList<>(listeners)) l.run(); }
    private void state(String title,String message) {
        status=title; detail=message;
        if(report!=null && !automaticConnection && !title.equals("Disconnected")) report.outcome(title,title.equals("Action failed")?message:null);
        if(store!=null && !address.isEmpty() && !scanning) store.packet(address,"event",new byte[0],title+": "+message);
        changed();
    }
    private boolean available() {
        if(!ExperimentalConsent.accepted(this)) { state("Consent required","Open the app and accept the experimental-use notice first."); return false; }
        if(Build.VERSION.SDK_INT>=31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)) {
            state("Bluetooth permission needed","Allow Nearby devices to use your monitor."); return false;
        }
        if(Build.VERSION.SDK_INT<31 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            state("Location permission needed","Android 8–11 require location permission for Bluetooth scanning."); return false;
        }
        if(adapter==null || !adapter.isEnabled()) { state("Bluetooth is off","Enable Bluetooth on your phone, then try again."); return false; }
        return true;
    }
    private void foreground() {
        if(automaticConnection && AutoImportSettings.getMode(this)==AutoImportSettings.COMPANION && companionPresent.contains(address)) return;
        promoteForeground(false);
    }
    private void promoteForeground(boolean waiting) {
        if(!foregroundActive) startForegroundService(new Intent(this,MonitorService.class));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,waiting?2:1,new Intent(this,MonitorService.class).setAction(waiting?"stop-automatic":"disconnect"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(1,new Notification.Builder(this,"connection").setSmallIcon(org.openpressure.app.R.drawable.ic_pressure).setContentTitle(getString(org.openpressure.app.R.string.main_app_name)).setContentText(getString(waiting?org.openpressure.app.R.string.monitor_notification_waiting:org.openpressure.app.R.string.monitor_notification_connected)).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,getString(waiting?org.openpressure.app.R.string.monitor_notification_stop:org.openpressure.app.R.string.monitor_notification_disconnect),stop).build()).build());
        foregroundActive=true;
    }
    public void setUiVisible(Object owner,boolean visible) {
        if(visible) visibleOwners.add(owner); else visibleOwners.remove(owner);
        refreshAutomaticImports();
    }
    public void setCompanionPresence(String target,boolean present) {
        if(present) companionPresent.add(target); else companionPresent.remove(target);
        refreshAutomaticImports();
    }
    private void scheduleAutomaticRefresh() {
        if(destroying) return;
        handler.removeCallbacks(automaticRefresh); handler.post(automaticRefresh);
    }
    private void automaticStatus(String value) {
        if(!automaticStatus.equals(value)) { automaticStatus=value; changed(); }
    }
    private boolean automaticPermissions() {
        if(!ExperimentalConsent.accepted(this) || adapter==null) return false;
        if(Build.VERSION.SDK_INT>=31) return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    public void refreshAutomaticImports() {
        if(destroying) return;
        int mode=AutoImportSettings.getMode(this);
        Map<String,String[]> saved=new LinkedHashMap<>();
        for(String[] device:store.devices()) if(AutoImportTracker.supportsModel(device[2])) saved.put(device[0],device);
        for(String removed:new HashSet<>(automaticDevices.keySet())) if(!saved.containsKey(removed)) {
            automaticTracker.restore(removed,null); companionPresent.remove(removed);
            getSharedPreferences("automatic-checkpoints",MODE_PRIVATE).edit().remove(removed).apply();
        }
        automaticDevices.clear(); automaticDevices.putAll(saved);
        boolean visible=!visibleOwners.isEmpty();
        boolean notificationAllowed=mode==AutoImportSettings.NOTIFICATION && (Build.VERSION.SDK_INT<29 || Build.VERSION.SDK_INT>=31);
        boolean watch=mode!=AutoImportSettings.OFF && (visible || notificationAllowed && foregroundActive || mode==AutoImportSettings.COMPANION && !companionPresent.isEmpty());
        if(mode==AutoImportSettings.OFF) {
            stopAutomaticScan(); automaticStatus("Automatic imports are off.");
            if(automaticConnection) disconnect(); stopIdleService(); return;
        }
        if(saved.isEmpty()) { stopAutomaticScan(); automaticStatus("Pair or connect a supported monitor first."); stopIdleService(); return; }
        if(!automaticPermissions()) { stopAutomaticScan(); automaticStatus("Open the app and grant Bluetooth access to enable automatic imports."); stopIdleService(); return; }
        if(!adapter.isEnabled()) { stopAutomaticScan(); automaticStatus("Waiting for Bluetooth to be enabled."); return; }
        if(notificationAllowed && (visible || foregroundActive)) {
            try { promoteForeground(gatt==null && x7Bonding==null && !scanning); watch=true; }
            catch(RuntimeException failure) {
                stopAutomaticScan(); automaticStatus("Android could not start background listening. Open the app and try again, or choose visible app mode."); return;
            }
        }
        else if(gatt==null && foregroundActive && !scanning) { stopForeground(STOP_FOREGROUND_REMOVE); foregroundActive=false; stopSelf(); }
        if(!watch) { stopAutomaticScan(); automaticStatus(mode==AutoImportSettings.COMPANION?"Waiting for an Android companion-device presence notification.":"Automatic imports resume when the app is visible."); stopIdleService(); return; }
        if(gatt!=null || scanning || x7Bonding!=null || pendingConnection!=null || dumpSaving) {
            stopAutomaticScan(); automaticStatus(automaticConnection?"Importing history automatically…":"Automatic imports wait for the current operation to finish."); return;
        }
        if(SystemClock.elapsedRealtime()<automaticRetryAt) {
            automaticStatus("Bluetooth scan failed; automatic listening will retry shortly.");
            handler.removeCallbacks(automaticRefresh); handler.postDelayed(automaticRefresh,Math.max(1,automaticRetryAt-SystemClock.elapsedRealtime())); return;
        }
        List<ScanFilter> filters=new ArrayList<>(); List<String> addresses=new ArrayList<>();
        for(String target:saved.keySet()) {
            if(mode==AutoImportSettings.COMPANION && !visible && !companionPresent.contains(target)) continue;
            filters.add(new ScanFilter.Builder().setDeviceAddress(target).build()); addresses.add(target);
            if(automaticTracker.completedRevision(target)==null) automaticTracker.restore(target,getSharedPreferences("automatic-checkpoints",MODE_PRIVATE).getString(target,null));
        }
        if(filters.isEmpty()) { stopAutomaticScan(); automaticStatus("Associate a saved monitor in Automatic imports settings for background access."); return; }
        Collections.sort(addresses); String filterKey=mode+":"+visible+":"+addresses;
        if(autoWatching && filterKey.equals(automaticFilterKey)) return;
        stopAutomaticScan();
        BluetoothLeScanner scanner=adapter.getBluetoothLeScanner();
        if(scanner==null) { automaticStatus("Bluetooth scanner is unavailable."); return; }
        ScanCallback callback=new ScanCallback() {
            @Override public void onScanResult(int type,ScanResult result) { handler.post(() -> acceptAutomaticAdvertisement(this,result)); }
            @Override public void onBatchScanResults(List<ScanResult> results) { handler.post(() -> { for(ScanResult result:results) acceptAutomaticAdvertisement(this,result); }); }
            @Override public void onScanFailed(int code) { handler.post(() -> {
                if(this!=automaticScan) return;
                stopAutomaticScan(); automaticRetryAt=SystemClock.elapsedRealtime()+60000;
                automaticStatus("Automatic Bluetooth scan failed ("+code+"); retrying in one minute.");
                handler.removeCallbacks(automaticRefresh); handler.postDelayed(automaticRefresh,60000);
            }); }
        };
        try {
            automaticScan=callback; automaticFilterKey=filterKey; autoWatching=true;
            scanner.startScan(filters,new ScanSettings.Builder().setScanMode(visible?ScanSettings.SCAN_MODE_BALANCED:ScanSettings.SCAN_MODE_LOW_POWER).build(),callback);
            automaticStatus("Listening for new measurement advertisements."); changed();
        } catch(RuntimeException ex) {
            stopAutomaticScan(); automaticRetryAt=SystemClock.elapsedRealtime()+60000;
            automaticStatus("Could not start automatic Bluetooth listening. Check Bluetooth permissions.");
            handler.removeCallbacks(automaticRefresh); handler.postDelayed(automaticRefresh,60000);
        }
    }
    private void stopAutomaticScan() {
        ScanCallback previous=automaticScan; automaticScan=null; autoWatching=false; automaticFilterKey="";
        if(previous!=null && adapter!=null) try {
            BluetoothLeScanner scanner=adapter.getBluetoothLeScanner(); if(scanner!=null) scanner.stopScan(previous);
        } catch(RuntimeException ignored) {}
    }
    private void acceptAutomaticAdvertisement(ScanCallback source,ScanResult result) {
        if(source!=automaticScan || !autoWatching || gatt!=null || scanning || !automaticPermissions()) return;
        int mode=AutoImportSettings.getMode(this);
        if(mode==AutoImportSettings.OFF || mode==AutoImportSettings.VISIBLE && visibleOwners.isEmpty()) return;
        try {
        String target=result.getDevice().getAddress(); String[] saved=automaticDevices.get(target);
        if(saved==null || mode==AutoImportSettings.COMPANION && visibleOwners.isEmpty() && !companionPresent.contains(target)) return;
        ScanRecord record=result.getScanRecord(); if(record==null) return;
        byte[] manufacturer=record.getManufacturerSpecificData(0x020e);
        if(X7Security.fromManufacturerPayload(manufacturer)!=X7Security.Mode.PLAIN || result.getDevice().getBondState()!=BluetoothDevice.BOND_BONDED) return;
        // Automatic Connect may only unlock with a key saved by an explicit Pair.
        VendorMemoryModel vendor=VendorMemoryModel.identify(saved[2]);
        if(vendor!=null && vendor.legacyUnlock && !savedVendorKey(target,vendor)) return;
        OmronAdvertisement advertisement;
        try { advertisement=OmronAdvertisement.parseManufacturerPayload(manufacturer); } catch(IllegalArgumentException ignored) { return; }
        AutoImportTracker.Attempt attempt=automaticTracker.offer(target,advertisement,SystemClock.elapsedRealtime());
        if(attempt==null) return;
        store.packet(target,"automatic-advertisement",manufacturer,"New advertised record revision · "+attempt.revision);
        user=Integer.parseInt(saved[3]);
        connect(target,saved[1],false,false,manufacturer,attempt);
        } catch(RuntimeException failure) {
            if(automaticAttempt!=null) disconnect();
            else automaticTracker.cancel(SystemClock.elapsedRealtime());
            automaticStatus("Automatic import could not start. Check Bluetooth access and reconnect manually if needed.");
        }
    }
    private boolean savedVendorKey(String target,VendorMemoryModel vendor) {
        try { return new VendorPairingKeys(keys,target,vendor.id).hasCandidate(); }
        catch(Exception ex) { return false; }
    }
    private final BroadcastReceiver adapterReceiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) { refreshAutomaticImports(); }
    };
    public void scan() {
        if(!available()) return;
        if(automaticConnection) disconnect();
        if(gatt!=null || x7Bonding!=null) { state("Disconnect before scanning","Finish the active monitor session first."); return; }
        stopAutomaticScan(); stopScan(); discovered.clear(); foreground(); scanning=true;
        state("Looking for monitors…","Put the monitor in Bluetooth pairing or transfer mode. Scan lasts 60 seconds.");
        BluetoothLeScanner scanner=adapter.getBluetoothLeScanner();
        if(scanner==null) { scanning=false; fail("Bluetooth scanner is unavailable"); return; }
        try { scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback); handler.postDelayed(scanTimeout,60000); }
        catch(RuntimeException ex) { scanning=false; fail("Could not scan: " + ex.getMessage()); }
    }
    public void stopScan() {
        pendingConnection=null;
        handler.removeCallbacks(scanTimeout);
        handler.removeCallbacks(scanUpdate); scanUpdatePending=false;
        if(scanning) {
            scanning=false;
            try { BluetoothLeScanner scanner=adapter.getBluetoothLeScanner(); if(scanner!=null) scanner.stopScan(scanCallback); } catch(RuntimeException ignored) {}
            if(gatt==null) { state(discovered.isEmpty()?"No monitors found":"Scan finished","Move the monitor closer and activate Bluetooth transfer if it is missing."); stopIdleService(); }
            changed();
            scheduleAutomaticRefresh();
        }
    }
    private final ScanCallback scanCallback=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult result) { handler.post(() -> addScan(result)); }
        @Override public void onBatchScanResults(List<ScanResult> results) { handler.post(() -> { for(ScanResult r:results) addScan(r); }); }
        @Override public void onScanFailed(int code) { handler.post(() -> { stopScan(); fail("Bluetooth scan failed ("+code+")"); }); }
    };
    private void addScan(ScanResult result) {
        if(!scanning) return;
        ScanRecord record=result.getScanRecord(); if(record==null) return;
        byte[] manufacturer=record.getManufacturerSpecificData(0x020e);
        List<ParcelUuid> services=record.getServiceUuids();
        boolean candidate=OmronAdvertisement.hasKnownName(record.getDeviceName()) || manufacturer!=null || services!=null && (services.contains(new ParcelUuid(Uuids.BP)) || services.contains(new ParcelUuid(Uuids.VENDOR)) || services.contains(new ParcelUuid(Uuids.VENDOR_ALIAS)) || services.contains(new ParcelUuid(Uuids.OPTIONAL)));
        if(!candidate) return;
        Discovered d=new Discovered(); d.address=result.getDevice().getAddress(); d.name=record.getDeviceName(); if(d.name==null) d.name="Bluetooth health monitor";
        d.manufacturerData=manufacturer==null?null:manufacturer.clone();
        d.rssi=result.getRssi(); d.metadata="Model is verified after connecting";
        if(manufacturer!=null) try {
            OmronAdvertisement a=OmronAdvertisement.parseManufacturerPayload(manufacturer);
            int count=0; for(OmronAdvertisement.User u:a.users) count+=u.recordCount;
            d.metadata=a.hasRecordCounts()?a.users.size()+" users · "+count+" advertised records":a.users.isEmpty()?"Omron device · No record summary":a.users.size()+" users";
            d.metadata+=(a.pairingMode?" · Pairing mode":"")+(a.clockUnset?" · Clock unset":"");
        } catch(IllegalArgumentException ignored) { d.metadata="Omron device · Advertisement variant"; }
        X7Security.Mode advertised=X7Security.fromManufacturerPayload(manufacturer);
        if(advertised!=X7Security.Mode.UNKNOWN) {
            d.metadata+=" · "+(advertised==X7Security.Mode.ENCRYPTED?"Application encryption":"Plain WLP advertisement");
            if(pendingConnection!=null && pendingConnection.address.equals(d.address)) {
                PendingConnection pending=pendingConnection;
                discovered.put(d.address,d);
                // Defer past this scan callback; cancellation clears the token before connecting.
                handler.post(() -> { if(scanning && pendingConnection==pending) connect(pending.address,pending.name,pending.pair); });
                return;
            }
        }
        Discovered previous=discovered.get(d.address);
        if(previous!=null && previous.name.equals(d.name) && previous.metadata.equals(d.metadata) && Arrays.equals(previous.manufacturerData,d.manufacturerData) && Math.abs(previous.rssi-d.rssi)<5) return;
        discovered.put(d.address,d);
        if(!scanUpdatePending) { scanUpdatePending=true; handler.postDelayed(scanUpdate,500); }
    }
    public void connect(String target,String name,boolean pair) {
        connect(target,name,pair,false,null);
    }
    private void connect(String target,String name,boolean pair,boolean freshBond,byte[] manufacturer) {
        connect(target,name,pair,freshBond,manufacturer,null);
    }
    private void connect(String target,String name,boolean pair,boolean freshBond,byte[] manufacturer,AutoImportTracker.Attempt attempt) {
        if(!available()) { if(attempt!=null) automaticTracker.finish(attempt,false,SystemClock.elapsedRealtime()); return; }
        disconnect(); stopScan(); stopAutomaticScan();
        automaticConnection=attempt!=null; automaticAttempt=attempt;
        readingNotifications.begin(automaticConnection);
        receivedInTransfer=0; addedInTransfer=0; storedInTransfer=0; queuedInTransfer=0; pendingInTransfer=0;
        x7FreshBondEstablished=freshBond;
        address=target; connectedName=name; pairingRequested=pair; authenticatedUser=0; storedCount=-1; deviceTime="Not read"; information="";
        clockUpdate=ClockUpdate.NONE; sessionEndUnconfirmed=false;
        store.packet(address,"session",new byte[0],"OpenPressure "+BuildConfig.VERSION_NAME+" · Android API "+Build.VERSION.SDK_INT+" · "+(pair?"Pair":"Connect"));
        Discovered selected=discovered.get(target);
        connectionManufacturer=manufacturer!=null?manufacturer.clone():selected==null || selected.manufacturerData==null?null:selected.manufacturerData.clone();
        if(pair) discovered.clear();
        if(connectionManufacturer!=null) store.packet(address,"manufacturer-020e",connectionManufacturer,"Omron manufacturer data · company prefix removed");
        // Automatic sessions keep the last user-started report; a fresh X7 bond continues the same Pair.
        if(attempt==null) {
            report=new DeviceReport(pair?"Pair":"Connect",BuildConfig.VERSION_NAME,Build.VERSION.SDK_INT,Build.MANUFACTURER+" "+Build.MODEL);
            report.advertisement(selected==null?name:selected.name,connectionManufacturer);
            reportAddress=target; reportPrompted=false;
        }
        canHistory=false; canTime=false; hasUserControl=false; canDump=false; verifiedModel=""; negotiatedMtu=23; battery=-1; profile=ProtocolProfile.UNSUPPORTED;
        try { foreground(); state("Connecting…",name+" · Keep the monitor in transfer mode."); gatt=adapter.getRemoteDevice(target).connectGatt(this,false,callback,BluetoothDevice.TRANSPORT_LE); armPhase(30000,"Connection timed out"); }
        catch(RuntimeException ex) { fail("Connection failed: "+ex.getMessage()); }
    }
    public void disconnect() {
        boolean interruptedRegistration=registrationWriting;
        notifyAutomaticReadings();
        if(automaticAttempt!=null) automaticTracker.finish(automaticAttempt,false,SystemClock.elapsedRealtime());
        automaticAttempt=null; automaticConnection=false;
        stopAutomaticScan();
        if(x7Registration!=null) x7Registration.cancel();
        x7Registration=null; registrationReading=false; registrationPending=false; registrationWriting=false; registrationDone=false; registrationBackupGeneration=-1;
        if(registrationSettings!=null) Arrays.fill(registrationSettings,(byte)0);
        registrationSettings=null;
        if(x7Bonding!=null) x7Bonding.cancel();
        x7Bonding=null; x7FreshBondEstablished=false;
        if(dumpRunning && wlpRead!=null) saveDump(wlpRead.data(),false,"Disconnected before the complete read and end acknowledgement");
        dumpRunning=false; wlpRead=null; if(wlpConnection!=null) wlpConnection.close(); wlpConnection=null; sessionFinished=false; x7Transaction=null; x7Authentication=null; x7HistoryIndex=null; x7HistoryRanges=Collections.emptyList(); connectionManufacturer=null; canDump=false; canHistory=false; canTime=false; hasUserControl=false;
        if(vendorUnlock!=null) vendorUnlock.close();
        vendorUnlock=null; vendorRead=null; vendorModel=null; vendorKeys=null;
        clearVendorKeyCandidates(); vendorTx.clear(); vendorRx.clear(); vendorWaitingBond=false; vendorSessionEnded=false;
        vendorClockAttempted=false; vendorClockPending=false; vendorClockResult="";
        generation++; cancelPhase(); cancelOperationTimeout(); operations.clear(); stopScan();
        ready=false; historyRunning=false; waitingBond=false; pendingUserOperation=0; authenticatedUser=0;
        if(secure!=null) { secure.close(); secure=null; }
        BluetoothGatt previous=gatt; gatt=null;
        if(previous!=null) { try { previous.disconnect(); } catch(RuntimeException ignored) {} previous.close(); }
        handshake=null; racp=null; userControl=null; timeCharacteristic=null; wlpTx=null; wlpRx=null;
        stopIdleService();
        if(interruptedRegistration) state("Action failed","Registration was interrupted before its acknowledged END. The cuff's settings state is unverified; the pre-registration backup is retained. No further settings write or automatic retry was sent.");
        else state("Disconnected","Your saved readings remain on this phone.");
        scheduleAutomaticRefresh();
    }
    private void reportProtocol(String memoryMap) {
        if(report!=null && !automaticConnection) report.protocol(profile.name(),memoryMap);
    }
    /** A shareable report for the last user-started session with an untested monitor, or null. */
    public DeviceReport deviceReport() { return report!=null && report.useful()?report:null; }
    /** Ask once per monitor, after a Pair attempt has ended rather than between its reconnects. */
    public boolean deviceReportPromptDue() {
        return deviceReport()!=null && "Pair".equals(report.operation) && !reportPrompted && gatt==null && x7Bonding==null && pendingConnection==null && !scanning
                && !getSharedPreferences("device-report",MODE_PRIVATE).getBoolean("prompted:"+reportAddress,false);
    }
    public void deviceReportPrompted() {
        reportPrompted=true;
        if(reportAddress!=null) getSharedPreferences("device-report",MODE_PRIVATE).edit().putBoolean("prompted:"+reportAddress,true).apply();
    }
    private void notifyAutomaticReadings() {
        if(readingNotifications.finish(visibleOwners.isEmpty())>0)
            NewReadingNotifications.show(this,storedInTransfer,queuedInTransfer);
    }
    private boolean acceptReading(BloodPressure reading,byte[] raw,String source,Integer bank,boolean automatic) {
        ReadingStore.IngestionResult result=store.addResult(address,connectedName,reading,raw,source,bank,automatic);
        if(result.stored) { storedInTransfer++; readingsRevision++; }
        if(result.queued) queuedInTransfer++;
        if(result.pending) pendingInTransfer++;
        return result.accepted();
    }
    private void stopIdleService() {
        // Keep the single persistence owner alive across Activity recreation until its last save settles.
        if(gatt==null && x7Bonding==null && !scanning && !dumpSaving && !autoWatching) {
            if(!destroying && AutoImportSettings.getMode(this)==AutoImportSettings.NOTIFICATION && foregroundActive && !automaticDevices.isEmpty()) return;
            stopForeground(STOP_FOREGROUND_REMOVE); foregroundActive=false; stopSelf();
        }
    }
    private void fail(String message) {
        if(vendorRead!=null && vendorRead.historyComplete() && !vendorRead.complete() && vendorClockAttempted) {
            // Android may still be busy after an optional clock timeout and reject END.
            // All history is already validated and stored; finalization cannot undo the import.
            if(vendorClockPending) { clockUpdate=ClockUpdate.FAILED; vendorClockResult="Clock update could not be confirmed; imported readings were retained."; }
            store.packet(address,"vendor-finalization",new byte[0],message);
            finishVendorHistory(false);
            closeFinishedWlp(" Session finalization warning: "+message);
            return;
        }
        if(registrationWriting) message+=" Profile registration is incomplete; the cuff's settings state is unverified. The pre-registration backup is retained. No retry or further settings write was sent.";
        if(message.contains("(257)") || message.contains("error 257")) message+=" Android reported a generic GATT failure, not an EEPROM status. The cuff may have left transfer mode; reconnect and export Bluetooth diagnostics if it recurs.";
        if(dumpRunning && wlpRead!=null) { saveDump(wlpRead.data(),false,message); dumpRunning=false; }
        disconnect(); state("Action failed",message);
    }
    private BluetoothGattCharacteristic characteristic(UUID service,UUID characteristic) {
        if(gatt==null) return null; BluetoothGattService s=gatt.getService(service); return s==null?null:s.getCharacteristic(characteristic);
    }
    private void discoverProfile() {
        cancelPhase();
        boolean bp=characteristic(Uuids.BP,Uuids.MEASUREMENT)!=null;
        BluetoothGattService vendor=gatt.getService(Uuids.VENDOR); if(vendor==null) vendor=gatt.getService(Uuids.VENDOR_ALIAS);
        handshake=vendor==null?null:vendor.getCharacteristic(Uuids.HANDSHAKE);
        boolean handshakeCapable=handshake!=null && writable(handshake) && subscribable(handshake);
        boolean wlpChannels=false;
        StringBuilder services=new StringBuilder();
        for(BluetoothGattService s:gatt.getServices()) {
            services.append(s.getUuid()).append('\n');
            List<String> layout=new ArrayList<>();
            for(BluetoothGattCharacteristic c:s.getCharacteristics()) layout.add(c.getUuid()+" properties=0x"+Integer.toHexString(c.getProperties()));
            if(report!=null && !automaticConnection) report.service(s.getUuid().toString(),layout);
            for(BluetoothGattCharacteristic c:s.getCharacteristics()) {
                services.append("  ").append(c.getUuid()).append(" properties=0x").append(Integer.toHexString(c.getProperties())).append('\n');
                if((s.getUuid().equals(Uuids.VENDOR)||s.getUuid().equals(Uuids.VENDOR_ALIAS)) && DeviceWritePolicy.isWlpDataCharacteristic(c.getUuid())) wlpChannels=true;
            }
        }
        profile=ProtocolProfile.select(bp,gatt.getService(Uuids.OPTIONAL)!=null,vendor!=null,handshakeCapable,wlpChannels);
        reportProtocol(null);
        store.packet(address,"services",new byte[0],services.toString());
        if(profile==ProtocolProfile.LEGACY_WLP) { identifyWlp(); return; }
        if(automaticConnection) { fail("Automatic imports require a verified Omron WLP monitor. Connect manually to use this monitor."); return; }
        if(profile==ProtocolProfile.UNSUPPORTED) { fail("The device exposes no supported blood pressure profile. Export diagnostics to inspect its services."); return; }
        if(profile==ProtocolProfile.STP) {
            state("Establishing secure session…","Pairing protocol implemented independently in Java.");
            subscribe(handshake,() -> {
                try {
                    if(pairingRequested) keys.forget(address);
                    secure=new SecureSession(keys.get(address)); responseWrite(handshake,secure.begin(),20000,"Secure pairing response timed out");
                }
                catch(Exception ex) { fail("Secure pairing could not start: "+ex.getMessage()); }
            });
        } else if(pairingRequested && gatt.getDevice().getBondState()!=BluetoothDevice.BOND_BONDED) {
            waitingBond=true; state("Pair on your phone","Confirm the Android Bluetooth pairing prompt, if shown.");
            if(!gatt.getDevice().createBond()) { fail("Android could not start Bluetooth pairing. Try Connect if the monitor is already paired."); return; }
            armPhase(45000,"Bluetooth pairing timed out");
        } else configure();
    }
    private final BroadcastReceiver bondReceiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            BluetoothDevice device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if(device==null || !device.getAddress().equals(address)) return;
            int bond=intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,-1);
            store.packet(address,"android-bond",new byte[0],"Bond state="+bond+" · fresh registration="+(x7Bonding!=null));
            if(x7Bonding!=null) {
                if(bond!=device.getBondState()) {
                    store.packet(address,"android-bond",new byte[0],"Ignoring stale broadcast; live bond state="+device.getBondState());
                    return;
                }
                // Android may finish before the queued BONDING broadcast reaches this receiver.
                if(bond==BluetoothDevice.BOND_BONDED && intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,-1)==BluetoothDevice.BOND_BONDING)
                    x7Bonding.changed(X7Bonding.Bond.BONDING);
                if(bond==BluetoothDevice.BOND_NONE || bond==BluetoothDevice.BOND_BONDING || bond==BluetoothDevice.BOND_BONDED)
                    advanceFreshX7Bond(device,x7Bonding.changed(bondState(bond)));
                return;
            }
            if(vendorWaitingBond) {
                if(bond!=device.getBondState()) return;
                if(bond==BluetoothDevice.BOND_BONDED) {
                    vendorWaitingBond=false; cancelPhase(); subscribeVendorChannels(1);
                } else if(bond==BluetoothDevice.BOND_NONE) fail("Bluetooth pairing was cancelled or rejected");
                return;
            }
            if(!waitingBond) return;
            if(bond==BluetoothDevice.BOND_BONDED) { waitingBond=false; cancelPhase(); configure(); }
            else if(bond==BluetoothDevice.BOND_NONE) fail("Bluetooth pairing was cancelled or rejected");
        }
    };
    private void identifyWlp() {
        BluetoothGattCharacteristic model=characteristic(Uuids.sig(0x180a),Uuids.sig(0x2a24));
        if(model==null || (model.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)==0) {
            fail("Vendor memory access requires a readable Device Information model identifier. No memory commands were sent."); return;
        }
        state("Checking monitor model…","Reading the model identifier before selecting its memory map.");
        enqueue("read:"+key(model),() -> gatt.readCharacteristic(model),() -> {
            verifiedModel=new String(readResult,StandardCharsets.UTF_8).replace("\u0000","").trim();
            store.packet(address,"model",readResult,"DIS model identity");
            if(report!=null && !automaticConnection) report.model(verifiedModel);
            if(X7Record.supportsModel(verifiedModel)) { identifyX7(); return; }
            vendorModel=VendorMemoryModel.identify(verifiedModel);
            if(vendorModel==null) { fail("WLP model is not enabled: "+verifiedModel+". No memory commands were sent."); return; }
            configureVendorMemory();
        });
    }
    private void identifyX7() {
        wlpTx=characteristic(Uuids.VENDOR_ALIAS,Uuids.WLP_TX); wlpRx=characteristic(Uuids.VENDOR_ALIAS,Uuids.WLP_RX);
        handshake=characteristic(Uuids.VENDOR_ALIAS,Uuids.HANDSHAKE);
        if(wlpTx==null || (wlpTx.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)==0 || wlpRx==null || (wlpRx.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0 || handshake==null || (handshake.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)==0 || (handshake.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0) {
            fail("Unsupported X7 WLP layout. No memory commands were sent."); return;
        }
        X7Security.Mode advertised=X7Security.fromManufacturerPayload(connectionManufacturer);
        boolean savedKey;
        try {
            byte[] ltk=keys.get(address); savedKey=ltk!=null; if(ltk!=null) Arrays.fill(ltk,(byte)0);
        } catch(Exception ex) { fail("Could not read this monitor's pairing key: "+ex.getMessage()); return; }
        if(advertised==X7Security.Mode.UNKNOWN && !savedKey) {
            String target=address,name=connectedName; boolean pair=pairingRequested;
            disconnect(); scan();
            if(scanning) {
                pendingConnection=new PendingConnection(target,name,pair);
                state("Checking X7 advertisement…","Wake the cuff in pairing or transfer mode. Its advertisement determines whether application encryption is required; connecting will resume automatically.");
            }
            return;
        }
        if(automaticConnection && (advertised!=X7Security.Mode.PLAIN || savedKey || gatt.getDevice().getBondState()!=BluetoothDevice.BOND_BONDED)) {
            fail("Automatic X7 import requires the existing plain-WLP Android bond. Pair manually first; no registration or key exchange was started."); return;
        }
        profile=advertised==X7Security.Mode.ENCRYPTED || savedKey?ProtocolProfile.X7_SECURE_WLP:ProtocolProfile.X7_WLP;
        reportProtocol("HEM-7380T1");
        store.packet(address,"protocol",new byte[0],"Verified model "+verifiedModel+" · "+profile+" · advertisement="+advertised+" · saved application key="+savedKey);
        if(profile==ProtocolProfile.X7_SECURE_WLP) {
            if((handshake.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)==0 || negotiatedMtu<92) {
                fail("X7 application pairing requires acknowledged system writes and an ATT MTU of at least 92. No pairing or memory commands were sent."); return;
            }
            configureWlp(); return;
        }
        if(pairingRequested && !x7FreshBondEstablished) { beginFreshX7Bond(); return; }
        int bond=gatt.getDevice().getBondState();
        if(bond!=BluetoothDevice.BOND_BONDED) {
            waitingBond=true; state("Pair on your phone","Confirm Android's pairing prompt for the X7. No legacy key-programming commands are used.");
            if(bond!=BluetoothDevice.BOND_BONDING && !gatt.getDevice().createBond()) { fail("Android could not start X7 bonding. Wake the cuff in pairing mode and reconnect."); return; }
            armPhase(45000,"X7 Bluetooth pairing timed out");
        } else configureWlp();
    }
    private void configureVendorMemory() {
        profile=ProtocolProfile.VENDOR_MEMORY;
        reportProtocol(vendorModel.id);
        UUID service=VendorWlpSession.service(vendorModel);
        for(UUID id:VendorWlpSession.txChannels(vendorModel)) {
            BluetoothGattCharacteristic c=characteristic(service,id);
            if(c==null || !writable(c) || !vendorModel.legacyUnlock && (c.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)==0) {
                fail("The verified model has an unsupported vendor TX layout. No memory commands were sent."); return;
            }
            vendorTx.add(c);
        }
        for(UUID id:VendorWlpSession.rxChannels(vendorModel)) {
            BluetoothGattCharacteristic c=characteristic(service,id);
            if(c==null || (c.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0 || c.getDescriptor(Uuids.CCCD)==null) {
                fail("The verified model has an unsupported vendor RX layout. No memory commands were sent."); return;
            }
            vendorRx.add(c);
        }
        handshake=vendorModel.legacyUnlock?characteristic(service,VendorWlpSession.unlockCharacteristic()):null;
        if(vendorModel.legacyUnlock && (handshake==null || (handshake.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)==0 || (handshake.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0 || handshake.getDescriptor(Uuids.CCCD)==null)) {
            fail("The verified model has no supported legacy unlock characteristic. No key or memory commands were sent."); return;
        }
        if(!vendorModel.legacyUnlock && negotiatedMtu<27) {
            fail("This single-channel vendor reader requires ATT MTU 27 or larger. No memory commands were sent."); return;
        }
        if(user>vendorModel.userCount) user=1;
        store.packet(address,"protocol",new byte[0],"Verified "+vendorModel.id+" · vendor memory · "+vendorRx.size()+" RX channels · settings writes disabled");
        state("Configuring monitor…","Enabling the verified model's history notifications.");
        subscribeVendorChannels(0);
    }
    private void subscribeVendorChannels(int index) {
        if(index>=vendorRx.size()) {
            if(vendorModel.legacyUnlock) subscribe(handshake,this::beginLegacyUnlock);
            else vendorMemoryReady();
            return;
        }
        subscribe(vendorRx.get(index),() -> {
            if(index==0) {
                int bond=gatt.getDevice().getBondState();
                if(bond!=BluetoothDevice.BOND_BONDED && automaticConnection) {
                    fail("Automatic import requires the existing Android bond. Pair manually first; no key or memory commands were sent."); return;
                }
                if(bond!=BluetoothDevice.BOND_BONDED) {
                    vendorWaitingBond=true;
                    state("Pair on your phone","Confirm Android's Bluetooth request. Monitor history and settings have not been changed.");
                    if(bond!=BluetoothDevice.BOND_BONDING && !gatt.getDevice().createBond()) {
                        fail("Android could not start Bluetooth bonding. Put the monitor in pairing mode and retry."); return;
                    }
                    armPhase(60000,"Vendor monitor Bluetooth bonding timed out; no memory commands were sent.");
                    return;
                }
            }
            subscribeVendorChannels(index+1);
        });
    }
    private void clearVendorKeyCandidates() {
        for(byte[] candidate:vendorKeyCandidates) Arrays.fill(candidate,(byte)0);
        vendorKeyCandidates=Collections.emptyList(); vendorKeyCandidate=0;
    }
    private void beginLegacyUnlock() {
        try {
            vendorKeys=new VendorPairingKeys(keys,address,vendorModel.id);
            if(pairingRequested) {
                byte[] fresh=vendorKeys.provisioningKey();
                try {
                    // A lost ACK must not make the only key already sent to the cuff unrecoverable.
                    vendorUnlock=LegacyUnlock.pair(fresh);
                } finally { Arrays.fill(fresh,(byte)0); }
                state("Confirm Bluetooth pairing","Keep the monitor in pairing mode while its application key is registered.");
            } else {
                vendorKeyCandidates=vendorKeys.candidates(); vendorKeyCandidate=0;
                if(vendorKeyCandidates.isEmpty()) { fail("This monitor has no saved application key. Put it in pairing mode and choose Pair first."); return; }
                vendorUnlock=LegacyUnlock.connect(vendorKeyCandidates.get(0));
                state("Connecting…","Unlocking the verified model with its saved application key. No key programming is started by Connect.");
            }
            sendLegacyUnlock();
        } catch(Exception ex) { fail("Could not prepare this monitor's application key. No further key or memory command was sent."); }
    }
    private void sendLegacyUnlock() {
        store.packet(address,"legacy-unlock",new byte[0],pairingRequested?"Application key provisioning · body omitted":"Saved application key access · body omitted");
        responseWrite(handshake,vendorUnlock.command(),20000,"Vendor application key exchange timed out. Reconnect, or use Pair while the monitor is in pairing mode.");
    }
    private void vendorMemoryReady() {
        clearVendorKeyCandidates();
        if(vendorUnlock!=null) { vendorUnlock.close(); vendorUnlock=null; }
        timeCharacteristic=characteristic(Uuids.CTS,Uuids.CURRENT_TIME);
        if(timeCharacteristic==null || !writable(timeCharacteristic)) timeCharacteristic=characteristic(Uuids.COMMAND,Uuids.DATE_TIME);
        ready=true; canHistory=true; canTime=timeCharacteristic!=null && writable(timeCharacteristic); canDump=false; hasUserControl=false;
        store.remember(address,connectedName,verifiedModel,user);
        // Do not finish a pairing-only transmission: some cuffs power down on the first END.
        if(pairingRequested || automaticConnection) { beginVendorHistory(); return; }
        state("Vendor history available","Ready to import history. A supported clock update will be attempted before the session ends; unread counters and calibration data are retained.");
        readInformation();
    }
    private void beginVendorHistory() {
        if(vendorModel==null || vendorRead!=null || vendorSessionEnded) return;
        try {
            vendorRead=new VendorWlpSession(vendorModel);
            vendorParsed=0; wlpRejected=0; storedCount=-1; receivedInTransfer=0; addedInTransfer=0; historyRunning=true;
            storedInTransfer=0; queuedInTransfer=0; pendingInTransfer=0;
            vendorClockAttempted=false; vendorClockPending=false; vendorClockResult="";
            clockUpdate=ClockUpdate.NONE; sessionEndUnconfirmed=false;
            state("Reading history…","Reading the verified model's history banks before an optional clock update and a single session end.");
            sendVendorRead();
        } catch(IllegalArgumentException ex) { fail("Could not start vendor history: "+ex.getMessage()); }
    }
    private void sendVendorRead() {
        byte[] command=vendorRead.command();
        // All read/session commands fit TX0. Secret key commands use their separate control characteristic.
        store.packet(address,vendorTx.get(0).getUuid().toString(),command,"Vendor memory TX · read/session control only");
        responseWrite(vendorTx.get(0),command,15000,"Vendor history response timed out; validated readings already received were retained.");
    }
    private void vendorNotification(BluetoothGattCharacteristic c,byte[] bytes) {
        try {
            if(c.equals(handshake) && vendorUnlock!=null) {
                cancelPhase();
                if(report!=null && !automaticConnection) report.keyAcknowledgment(bytes);
                // Only a matching negative unlock response may try the other durable candidate.
                // Connect never changes to key programming, even when both candidates fail.
                if(!pairingRequested && bytes.length==2 && (bytes[0]&255)==0x81 && bytes[1]!=0 && vendorKeyCandidate+1<vendorKeyCandidates.size()) {
                    vendorUnlock.close(); vendorUnlock=LegacyUnlock.connect(vendorKeyCandidates.get(++vendorKeyCandidate));
                    sendLegacyUnlock(); return;
                }
                vendorUnlock.accept(bytes);
                if(!vendorUnlock.complete()) { sendLegacyUnlock(); return; }
                byte[] accepted=vendorUnlock.key();
                try { vendorKeys.confirm(accepted); } finally { Arrays.fill(accepted,(byte)0); }
                vendorMemoryReady(); return;
            }
            int channel=vendorRx.indexOf(c);
            if(channel<0 || vendorRead==null || !historyRunning) return;
            store.packet(address,c.getUuid().toString(),bytes,"Vendor history RX · channel "+channel);
            if(!vendorRead.accept(channel,bytes)) return;
            cancelPhase();
            byte[] data=vendorRead.data(); int offset=0;
            for(VendorMemoryModel.Range range:vendorModel.historyRanges()) {
                while(vendorParsed>=offset && vendorParsed<offset+range.length && vendorParsed+vendorModel.recordBytes<=data.length) {
                    byte[] raw=Arrays.copyOfRange(data,vendorParsed,vendorParsed+vendorModel.recordBytes);
                    vendorParsed+=vendorModel.recordBytes;
                    try {
                        BloodPressure reading=vendorModel.parse(raw,range.user);
                        if(reading!=null) {
                            receivedInTransfer++;
                            if(acceptReading(reading,raw,"vendor-history",range.user,automaticConnection)) { addedInTransfer++; readingNotifications.readingInserted(); }
                        }
                    } catch(IllegalArgumentException ex) {
                        wlpRejected++; store.packet(address,"vendor-record",raw,"Rejected "+vendorModel.id+" bank "+range.user+": "+ex.getMessage());
                    }
                }
                offset+=range.length;
            }
            if(vendorRead.complete()) {
                finishVendorHistory(true);
                return;
            }
            if(vendorRead.historyComplete() && !vendorClockAttempted) { syncVendorClock(); return; }
            long now=SystemClock.elapsedRealtime();
            if(now-lastWlpUpdate>=500) { lastWlpUpdate=now; detail=vendorRead.received()+" history bytes read · "+addedInTransfer+" new readings"; changed(); }
            sendVendorRead();
        } catch(Exception ex) { fail("Vendor response rejected: "+ex.getMessage()); }
    }
    private void syncVendorClock() {
        vendorClockAttempted=true;
        // Automatic imports are read-only, so the optional clock write belongs to explicit sessions.
        if(automaticConnection) { sendVendorRead(); return; }
        if(!canTime) {
            clockUpdate=ClockUpdate.SKIPPED;
            vendorClockResult="Clock sync skipped: no supported time characteristic.";
            sendVendorRead(); return;
        }
        vendorClockPending=true;
        LocalDateTime time=LocalDateTime.now();
        state("Finishing history import…","Readings saved. Attempting a clock update before ending the session.");
        writeClock(time,() -> {
            vendorClockPending=false; vendorClockResult="Clock write accepted.";
            sendVendorRead();
        },error -> {
            vendorClockPending=false; vendorClockResult="Clock sync unavailable; imported readings were retained.";
            store.packet(address,"clock",new byte[0],"Optional clock write failed: "+error);
            sendVendorRead();
        });
    }
    private void finishVendorHistory(boolean ended) {
        sessionEndUnconfirmed=!ended;
        vendorSessionEnded=ended; sessionFinished=true; ready=false; canHistory=false; canTime=false; historyRunning=false;
        storedCount=receivedInTransfer;
        if(report!=null && !automaticConnection) report.transfer(receivedInTransfer,wlpRejected,ended);
        finishAutomaticAttempt(ended && wlpRejected==0);
        state(wlpRejected==0?"History transfer complete":"History transfer incomplete",
            addedInTransfer+" new readings accepted · "+receivedInTransfer+" decoded · "+wlpRejected+" rejected slots. Erased slots were skipped. "+vendorClockResult+
            (ended?" The monitor acknowledged END.":" Session END was not acknowledged.")+" Reconnect for another transfer.");
        if(automaticConnection && ended) {
            // Close an automatic link if the monitor keeps it open, so listening can resume.
            long session=generation;
            handler.postDelayed(() -> { if(session==generation && automaticConnection && vendorSessionEnded) closeFinishedWlp(); },4000);
        }
    }
    /** Only a complete validated transfer checkpoints its advertised revision; the alert is posted once. */
    private void finishAutomaticAttempt(boolean completed) {
        if(automaticConnection && automaticAttempt!=null) {
            if(automaticTracker.finish(automaticAttempt,completed,SystemClock.elapsedRealtime()) && completed)
                getSharedPreferences("automatic-checkpoints",MODE_PRIVATE).edit().putString(automaticAttempt.address,automaticAttempt.revision).apply();
            automaticAttempt=null;
        }
        notifyAutomaticReadings();
    }

    private static X7Bonding.Bond bondState(int state) {
        if(state==BluetoothDevice.BOND_NONE) return X7Bonding.Bond.NONE;
        if(state==BluetoothDevice.BOND_BONDING) return X7Bonding.Bond.BONDING;
        if(state==BluetoothDevice.BOND_BONDED) return X7Bonding.Bond.BONDED;
        throw new IllegalArgumentException("Unknown Android bond state");
    }
    private void beginFreshX7Bond() {
        BluetoothDevice device=gatt.getDevice();
        String model=verifiedModel;
        byte[] manufacturer=connectionManufacturer==null?null:connectionManufacturer.clone();
        // The identity and plain-WLP branch were verified before reaching this operation.
        // Close the probe connection before replacing the Android bond, as fresh registration does.
        disconnect();
        connectionManufacturer=manufacturer; verifiedModel=model; profile=ProtocolProfile.X7_WLP;
        x7Bonding=new X7Bonding(bondState(device.getBondState()));
        foreground();
        advanceFreshX7Bond(device,x7Bonding.action());
    }
    private void advanceFreshX7Bond(BluetoothDevice device,X7Bonding.Action action) {
        if(x7Bonding==null) return;
        try {
            if(action==X7Bonding.Action.REMOVE_BOND) {
                state("Renewing X7 Bluetooth pairing…","Pair replaces this cuff's existing Android bond. Keep the cuff in pairing mode. Device memory is not written.");
                boolean accepted=Boolean.TRUE.equals(BluetoothDevice.class.getMethod("removeBond").invoke(device));
                if(!accepted) { failFreshX7Bond(); return; }
                armPhase(15000,"Android did not remove the old X7 bond. Forget this cuff in Android Bluetooth settings, then choose Pair again.");
            } else if(action==X7Bonding.Action.CREATE_BOND) {
                cancelPhase();
                state("Confirm fresh X7 pairing","Confirm Android's Bluetooth prompt. Follow the cuff's physical confirmation prompt if shown. Waiting for a newly completed bond; profile association is not yet verified.");
                if(!device.createBond()) { fail("Android could not start fresh X7 bonding. Keep the cuff in pairing mode and choose Pair again."); return; }
                if(device.getBondState()==BluetoothDevice.BOND_BONDING) x7Bonding.changed(X7Bonding.Bond.BONDING);
                armPhase(60000,"Fresh X7 bonding timed out. The cuff has not completed the new Android bond. No EEPROM commands were sent.");
            } else if(action==X7Bonding.Action.CONNECT) {
                String target=address,name=connectedName;
                byte[] manufacturer=connectionManufacturer==null?null:connectionManufacturer.clone();
                store.packet(address,"android-bond",new byte[0],"Fresh plain-X7 bond completed; reconnecting for system verification");
                connect(target,name,true,true,manufacturer);
            } else if(action==X7Bonding.Action.FAILED) {
                fail("Fresh X7 Bluetooth pairing was cancelled or rejected. No EEPROM commands were sent.");
            }
        } catch(ReflectiveOperationException | RuntimeException ex) { failFreshX7Bond(); }
    }
    private void failFreshX7Bond() {
        fail("Android could not remove this cuff's previous bond. Forget this cuff in Android Bluetooth settings, put it in pairing mode, then choose Pair again. The old bond is not accepted as fresh pairing.");
    }
    private void configureWlp() {
        ready=false; canHistory=false; canDump=false;
        state("Configuring X7…","Enabling data and system notifications for "+(profile==ProtocolProfile.X7_SECURE_WLP?"application pairing.":"a plain WLP session."));
        if(profile==ProtocolProfile.X7_SECURE_WLP) subscribe(handshake,() -> subscribe(wlpRx,this::beginX7Transaction));
        else subscribe(wlpRx,() -> subscribe(handshake,this::beginX7Transaction));
    }
    private void beginX7Transaction() {
        long session=generation;
        handler.postDelayed(() -> {
            if(session!=generation || gatt==null) return;
            byte[] nonce=new byte[4]; new java.security.SecureRandom().nextBytes(nonce);
            x7Transaction=new X7Transaction(nonce);
            state("Waiting for X7…","Checking the cuff's system channel before authentication.");
            byte[] command=x7Transaction.request();
            store.packet(address,Uuids.HANDSHAKE.toString(),command,"X7 system TX · transaction check");
            responseWrite(handshake,command,20000,"X7 did not acknowledge its system transaction. Wake the cuff and reconnect. No memory commands were sent.");
        },profile==ProtocolProfile.X7_SECURE_WLP?0:500);
    }
    private void beginX7Encryption() {
        try {
            if(pairingRequested) keys.forget(address);
            byte[] savedKey=keys.get(address);
            try { secure=new SecureSession(savedKey); } finally { if(savedKey!=null) Arrays.fill(savedKey,(byte)0); }
            state("Pairing X7 application…","Establishing the cuff's encrypted application session. Keep the cuff in Bluetooth mode.");
            responseWrite(handshake,secure.begin(),20000,"X7 application pairing response timed out. No memory commands were sent.");
        } catch(Exception ex) { fail("X7 application pairing could not start: "+ex.getMessage()); }
    }
    private void pollX7Authentication() {
        if(x7Authentication==null || x7Authentication.complete()) return;
        if(SystemClock.elapsedRealtime()>=x7AuthenticationDeadline) { fail("X7 authentication timed out. The cuff has not confirmed access. No memory commands were sent."); return; }
        store.packet(address,"authentication",new byte[0],"X7 authentication poll · encrypted body omitted");
        responseWrite(handshake,x7Authentication.command(),10000,"X7 authentication response timed out. No memory commands were sent.");
    }
    private void x7Ready() {
        wlpConnection=new WlpConnection();
        ready=true; canHistory=true; canDump=true; canTime=false; hasUserControl=false;
        if(pairingRequested && profile==ProtocolProfile.X7_WLP && (verifiedModel.equals("X7 Smart AFib") || verifiedModel.equals("HEM-7380T1-EOSL"))) {
            ready=false; canHistory=false; canDump=false; registrationReading=true;
            if(negotiatedMtu<35) { fail("X7 registration requires an ATT MTU of at least 35. No EEPROM commands were sent. Connect remains available for read-only access."); return; }
            beginWlpRead(X7MemoryIndex.SETTINGS_START,X7MemoryIndex.SETTINGS_BYTES);
            return;
        }
        store.remember(address,connectedName,verifiedModel,user);
        state(profile==ProtocolProfile.X7_SECURE_WLP?"X7 authenticated for reads":"X7 read mode available",
                (profile==ProtocolProfile.X7_SECURE_WLP?"The cuff confirmed application authentication. ":"Bluetooth transport is ready. ")+"On-device profile pairing has not been completed by this app. History and settings backup can be read without registering a profile. The cuff may show OK and switch off after the transfer ends; settings writes remain disabled.");
        if(automaticConnection) { syncHistory(-1); return; }
        readInformation();
    }
    private boolean x7ReadReady() {
        return profile.isX7() && wlpConnection!=null && !wlpConnection.shutdownExpected() && x7Transaction!=null && x7Transaction.ready() &&
                (profile!=ProtocolProfile.X7_SECURE_WLP || secure!=null && secure.established() && x7Authentication!=null && x7Authentication.complete());
    }
    public File eepromDumpFile() { return dumpFile; }
    public void dumpEeprom(int start,int length) {
        if(!ready || !canDump || !x7ReadReady() || historyRunning || dumpRunning || dumpSaving || pendingUserOperation!=0) return;
        try { new WlpReadSession(start,length,8); }
        catch(IllegalArgumentException ex) { state("Invalid EEPROM range",ex.getMessage()); return; }
        dumpSerial++; dumpFile=null; dumpStart=start; dumpLength=length; dumpReceived=0; dumpComplete=false; dumpRunning=true; dumpStartedAt=System.currentTimeMillis();
        saveDump(new byte[0],false,"Read in progress");
        beginWlpRead(start,length);
    }
    private void beginWlpRead(int start,int length) {
        wlpRead=wlpConnection.begin(start,length,negotiatedMtu>=27?16:8); lastWlpUpdate=0;
        state(registrationReading?"Backing up X7 settings…":dumpRunning?"Reading EEPROM…":"Reading X7 history…","Read requests only. Device settings and unread counters are retained.");
        sendWlpCommand();
    }
    private void sendWlpCommand() {
        byte[] command=wlpRead.command();
        store.packet(address,Uuids.WLP_TX.toString(),command,"WLP TX · read/session control only");
        responseWrite(wlpTx,command,10000,"X7 read response timed out; received data was retained");
    }
    private void handleWlp(byte[] fragment) {
        if(wlpRead==null) return;
        try {
            if(registrationWriting && x7Registration!=null && !x7Registration.complete()) {
                if(!x7Registration.accept(fragment)) return;
                cancelPhase();
                if(x7Registration.complete()) sendWlpCommand(); else sendRegistrationCommand();
                return;
            }
            if(registrationPending || registrationReading && wlpRead.rangeComplete())
                throw new IllegalArgumentException("Unexpected WLP reply while saving settings or waiting for user selection");
            if(!wlpRead.accept(fragment)) return;
            cancelPhase();
            byte[] bytes=wlpRead.data();
            if(registrationReading && wlpRead.rangeComplete()) {
                X7MemoryIndex.parse(bytes);
                registrationSettings=bytes.clone();
                saveRegistrationBackup(bytes);
                return;
            }
            if(dumpRunning) {
                dumpReceived=bytes.length;
                if(bytes.length>0 && bytes.length%512==0 && !wlpRead.complete()) saveDump(bytes,false,"Read in progress");
            } else if(historyRunning) {
                if(x7HistoryIndex==null && wlpRead.rangeComplete()) {
                    x7HistoryIndex=X7MemoryIndex.parse(bytes);
                    x7HistoryRanges=x7HistoryIndex.historyRanges(); x7HistoryRange=0;
                    wlpParsed=X7MemoryIndex.SETTINGS_BYTES; x7HistoryRangeOffset=wlpParsed;
                    storedCount=x7HistoryIndex.count(1)+x7HistoryIndex.count(2);
                    store.packet(address,"x7-history-index",new byte[0],"Populated BP slots: user 1="+x7HistoryIndex.count(1)+", user 2="+x7HistoryIndex.count(2)+"; unread counters retained");
                    if(!x7HistoryRanges.isEmpty()) {
                        X7MemoryIndex.Range range=x7HistoryRanges.get(0); wlpRead.appendRange(range.start,range.length);
                    }
                }
                while(x7HistoryIndex!=null && !x7HistoryRanges.isEmpty() && wlpParsed+X7Record.RECORD_BYTES<=bytes.length) {
                    X7MemoryIndex.Range range=x7HistoryRanges.get(x7HistoryRange);
                    int slot=(wlpParsed-x7HistoryRangeOffset)/X7Record.RECORD_BYTES, bank=range.user;
                    byte[] raw=Arrays.copyOfRange(bytes,wlpParsed,wlpParsed+X7Record.RECORD_BYTES); wlpParsed+=X7Record.RECORD_BYTES;
                    try {
                        X7Record record=X7Record.parse(raw,bank);
                        if(record!=null) {
                            receivedInTransfer++;
                            if(acceptReading(record.measurement,raw,"wlp-history",bank,automaticConnection)) { addedInTransfer++; readingNotifications.readingInserted(); }
                        } else { wlpRejected++; store.packet(address,"wlp-record",raw,"Erased slot inside populated bank "+bank+" slot "+slot); }
                    } catch(IllegalArgumentException ex) { wlpRejected++; store.packet(address,"wlp-record",raw,"Rejected bank "+bank+" slot "+slot+": "+ex.getMessage()); }
                }
                if(x7HistoryIndex!=null && wlpRead.rangeComplete() && x7HistoryRange+1<x7HistoryRanges.size()) {
                    x7HistoryRangeOffset=bytes.length; x7HistoryRange++;
                    X7MemoryIndex.Range range=x7HistoryRanges.get(x7HistoryRange); wlpRead.appendRange(range.start,range.length);
                }
            }
            if(wlpRead.complete()) {
                wlpConnection.finishTransfer(); sessionFinished=true; ready=false; canHistory=false; canDump=false;
                wlpRead=null;
                if(registrationWriting) {
                    registrationWriting=false; registrationDone=true;
                    if(x7Registration!=null) x7Registration.cancel(); x7Registration=null;
                    if(registrationSettings!=null) Arrays.fill(registrationSettings,(byte)0); registrationSettings=null;
                    store.remember(address,connectedName,verifiedModel,user);
                    state("User "+user+" registration acknowledged","The cuff accepted the selected-user settings, clock and session end. Follow any remaining cuff indication; it may show OK and turn off. Wake it and use Connect for a read-only history transfer. Physical behavior still needs verification.");
                } else if(dumpRunning) {
                    dumpRunning=false; saveDump(bytes,true,"");
                    state("EEPROM read complete",bytes.length+" verified bytes received and the cuff acknowledged END. Saving the local JSON export… Wake and reconnect the cuff for another transfer.");
                } else {
                    historyRunning=false;
                    if(report!=null && !automaticConnection) report.transfer(receivedInTransfer,wlpRejected,true);
                    finishAutomaticAttempt(wlpRejected==0 && receivedInTransfer==storedCount);
                    state(wlpRejected==0 && receivedInTransfer==storedCount?"History transfer complete":"History transfer incomplete",addedInTransfer+" new readings accepted · "+receivedInTransfer+" / "+storedCount+" decoded · "+wlpRejected+" rejected slots. Device counters were retained. The cuff acknowledged END; wake and reconnect it for another transfer.");
                }
                if(automaticConnection) {
                    long session=generation;
                    handler.postDelayed(() -> { if(session==generation && automaticConnection && wlpConnection!=null && wlpConnection.shutdownExpected()) closeFinishedWlp(); },4000);
                }
                return;
            }
            long now=SystemClock.elapsedRealtime();
            if(now-lastWlpUpdate>=500) {
                lastWlpUpdate=now;
                detail=registrationReading?"Reading the 68-byte settings snapshot before user selection…":dumpRunning?dumpReceived+" / "+dumpLength+" bytes read":x7HistoryIndex==null?"Reading the monitor's current history index…":((wlpParsed-X7MemoryIndex.SETTINGS_BYTES)/16)+" / "+storedCount+" populated slots read · "+addedInTransfer+" new readings";
                changed();
            }
            sendWlpCommand();
        } catch(IllegalArgumentException ex) { fail("X7 response rejected: "+ex.getMessage()); }
    }
    private void closeFinishedWlp() {
        closeFinishedWlp(" The monitor closed its Bluetooth connection.");
    }
    private void closeFinishedWlp(String note) {
        String completedStatus=status,completedDetail=detail; boolean registered=registrationDone;
        disconnect(); sessionFinished=true; registrationDone=registered;
        state(completedStatus,completedDetail+note);
    }
    public File registrationBackupFile() { return registrationBackup; }
    private void saveRegistrationBackup(byte[] bytes) {
        final long session=generation;
        final WlpReadSession paused=wlpRead;
        final String device=address,model=verifiedModel;
        armPhase(15000,"The settings backup could not finish in time. No settings writes were sent; reconnect and retry Pair.");
        state("Saving settings backup…","Saving the fresh 68-byte settings/index before enabling user registration. This is not a full EEPROM or calibration image.");
        dumpIo.execute(() -> {
            try {
                JSONObject json=new JSONObject();
                json.put("format","open-pressure-x7-settings-backup-v1"); json.put("app_version",BuildConfig.VERSION_NAME);
                json.put("device",device); json.put("model",model); json.put("read_only",true);
                json.put("scope","Pre-registration X7 settings/index snapshot only; not a full EEPROM or calibration backup");
                json.put("address",X7MemoryIndex.SETTINGS_START); json.put("length",X7MemoryIndex.SETTINGS_BYTES); json.put("received",bytes.length);
                json.put("read_complete",true); json.put("settings_checksum_valid",true); json.put("session_ended",false);
                json.put("saved_at_utc",java.time.Instant.now().toString()); json.put("encoding","hex"); json.put("data",ReadingStore.hex(bytes));
                json.put("sha256",ReadingStore.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
                File file=SettingsBackupStore.save(new File(getFilesDir(),"settings-backups"),json.toString(2).getBytes(StandardCharsets.UTF_8),MonitorService::syncBackupDirectory);
                handler.post(() -> {
                    // Always retain a completed local snapshot, even if the user cancelled the link.
                    registrationBackup=file;
                    if(session!=generation || !registrationReading || gatt==null || wlpRead!=paused || !paused.rangeComplete() || paused.complete()) { changed(); return; }
                    cancelPhase(); registrationReading=false; registrationPending=true; registrationBackupGeneration=session;
                    armPhase(90000,"X7 user selection timed out. No settings writes were sent. Wake the cuff in pairing mode and retry.");
                    state("Choose your X7 user","Settings backup saved. Select user 1 or 2 in this app to finish profile registration. Only continue if the cuff label is HEM-7380T1-EOSL. Connect is available for read-only access instead.");
                });
            } catch(Exception ex) {
                handler.post(() -> { if(session==generation && registrationReading) fail("Could not save the pre-registration settings backup. No settings writes were sent: "+ex.getMessage()); });
            }
        });
    }
    public void registerX7User(int bank) {
        if(!registrationPending || registrationBackupGeneration!=generation || registrationBackup==null || !registrationBackup.isFile() ||
                registrationSettings==null || profile!=ProtocolProfile.X7_WLP || !x7ReadReady()) return;
        try {
            X7Registration registration=new X7Registration(verifiedModel,registrationSettings,bank,LocalDateTime.now());
            cancelPhase(); user=bank; x7Registration=registration; registrationPending=false; registrationWriting=true;
            registration.authorize();
            state("Registering X7 user "+bank+"…","Applying the selected-user setting and phone clock to the model's settings staging area. Birthdays, revision values, history pointers and all other settings bytes are preserved. Pre-registration backup retained.");
            sendRegistrationCommand();
        } catch(IllegalArgumentException ex) { fail("Registration rejected before sending settings: "+ex.getMessage()); }
    }
    private static void syncBackupDirectory(File directory) throws java.io.IOException {
        try {
            java.io.FileDescriptor descriptor=android.system.Os.open(directory.getAbsolutePath(),android.system.OsConstants.O_RDONLY,0);
            try { android.system.Os.fsync(descriptor); } finally { android.system.Os.close(descriptor); }
        } catch(android.system.ErrnoException ex) { throw new java.io.IOException("Could not sync the settings backup directory",ex); }
    }
    private void sendRegistrationCommand() {
        byte[] command=x7Registration.command();
        store.packet(address,Uuids.WLP_TX.toString(),command,"X7 registration TX · bounded settings staging only · user "+user);
        responseWrite(wlpTx,command,10000,"X7 registration acknowledgment timed out");
    }
    private void saveDump(byte[] bytes,boolean complete,String error) {
        final long serial=dumpSerial, started=dumpStartedAt;
        final long saveVersion=++dumpSaveVersion;
        dumpSaving=true;
        final int start=dumpStart, length=dumpLength;
        final String device=address, model=verifiedModel;
        final long receivedAt=System.currentTimeMillis();
        dumpIo.execute(() -> {
            try {
                JSONObject json=new JSONObject();
                json.put("format","open-pressure-eeprom-v1"); json.put("app_version",getPackageManager().getPackageInfo(getPackageName(),0).versionName); json.put("model",model); json.put("device",device);
                json.put("read_only",true); json.put("address",start); json.put("address_hex",String.format(Locale.ROOT,"0x%04X",start));
                json.put("scope",start==X7MemoryIndex.SETTINGS_START && length==X7MemoryIndex.SETTINGS_BYTES?"X7 settings/index region only; not a full EEPROM or calibration backup":"Requested WLP address range; not a full physical EEPROM image");
                json.put("length",length); json.put("received",bytes.length); json.put("complete",complete); json.put("error",error);
                json.put("started_at_utc",java.time.Instant.ofEpochMilli(started).toString()); json.put("saved_at_utc",java.time.Instant.ofEpochMilli(receivedAt).toString());
                json.put("encoding","hex"); json.put("data",ReadingStore.hex(bytes));
                json.put("sha256",ReadingStore.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
                File destination=new File(getFilesDir(),"eeprom-dump.json"), temporary=new File(getFilesDir(),"eeprom-dump.json.tmp");
                Files.write(temporary.toPath(),json.toString(2).getBytes(StandardCharsets.UTF_8));
                Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                handler.post(() -> {
                    if(serial!=dumpSerial || saveVersion!=dumpSaveVersion) return;
                    dumpFile=destination; dumpComplete=complete; dumpSaving=false; dumpRevision++;
                    if(complete && status.equals("EEPROM read complete")) state("EEPROM dump saved",bytes.length+" verified bytes saved. The read session is complete; wake and reconnect the cuff for another transfer. Export the JSON before starting another dump.");
                    else changed();
                    stopIdleService();
                });
            } catch(Exception ex) {
                handler.post(() -> { if(serial==dumpSerial && saveVersion==dumpSaveVersion) { dumpSaving=false; dumpFile=null; dumpComplete=false; dumpRevision++; state("EEPROM export could not be saved",ex.getMessage()==null?"Local file write failed. Retry the read.":ex.getMessage()); stopIdleService(); } });
            }
        });
    }
    private void configure() {
        if(profile.isX7()) { configureWlp(); return; }
        state("Configuring monitor…","Enabling readings and available controls.");
        racp=characteristic(Uuids.OPTIONAL,Uuids.RACP); if(racp==null) racp=characteristic(Uuids.BP,Uuids.RACP);
        userControl=characteristic(Uuids.UDS,Uuids.USER_CONTROL);
        timeCharacteristic=characteristic(Uuids.CTS,Uuids.CURRENT_TIME);
        if(timeCharacteristic==null) timeCharacteristic=characteristic(Uuids.COMMAND,Uuids.DATE_TIME);
        canHistory=racp!=null && writable(racp) && subscribable(racp);
        canTime=timeCharacteristic!=null && writable(timeCharacteristic);
        hasUserControl=userControl!=null && writable(userControl) && subscribable(userControl);
        List<BluetoothGattCharacteristic> subscriptions=new ArrayList<>();
        if(hasUserControl) subscriptions.add(userControl);
        if(canHistory) subscriptions.add(racp);
        subscriptions.add(characteristic(Uuids.BP,Uuids.MEASUREMENT));
        configureNext(subscriptions,0);
    }
    private void configureNext(List<BluetoothGattCharacteristic> list,int index) {
        if(index<list.size()) { subscribe(list.get(index),() -> configureNext(list,index+1)); return; }
        ready=true; store.remember(address,connectedName,modelHint,user);
        state("Connected",hasUserControl?"Authenticate user "+user+" to access that user's history.":"Ready for readings. Start a measurement on the monitor.");
        readInformation();
    }
    private void readInformation() {
        for(int uuid:new int[]{0x2a24,0x2a25,0x2a26,0x2a29}) {
            BluetoothGattCharacteristic c=characteristic(Uuids.sig(0x180a),Uuids.sig(uuid));
            if(c!=null && (c.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)!=0) read(c,bytes -> {
                String value=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("\u0000","");
                information+=value+"\n";
                // The serial number (2A25) is shown locally but never added to the shareable report.
                if(report!=null && !automaticConnection && uuid==0x2a26) report.firmware(value);
                if(report!=null && !automaticConnection && uuid==0x2a29) report.manufacturer(value);
                changed();
            });
        }
        BluetoothGattCharacteristic b=characteristic(Uuids.sig(0x180f),Uuids.sig(0x2a19));
        if(b!=null && (b.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)!=0) read(b,bytes -> { if(bytes.length==1 && (bytes[0]&255)<=100) battery=bytes[0]&255; changed(); });
        BluetoothGattCharacteristic f=characteristic(Uuids.BP,Uuids.FEATURE);
        if(f!=null && (f.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)!=0) read(f,bytes -> { information+="BP feature bits: "+ReadingStore.hex(bytes)+"\n"; changed(); });
        if(timeCharacteristic!=null && (timeCharacteristic.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)!=0) readClock();
    }
    public void authenticate(int bank,int code,boolean register) {
        if(!ready || !hasUserControl) return;
        if(historyRunning || pendingUserOperation!=0) { state("Action in progress","Wait for the current user or history operation."); return; }
        byte[] command;
        try { command=register?Commands.registerUser(bank,code):Commands.consent(bank,code); }
        catch(IllegalArgumentException ex) { state("Check user settings",ex.getMessage()); return; }
        authenticatedUser=0; pendingUser=bank; pendingUserOperation=register?0x40:2; user=bank; consentCode=code;
        state(register?"Registering user…":"Authenticating user…","User "+bank);
        responseWrite(userControl,command,20000,"User-control response timed out");
    }
    public void syncHistory(int firstSequence) {
        if(!ready || !canHistory) return;
        if(historyRunning || dumpRunning || pendingUserOperation!=0) return;
        if(profile==ProtocolProfile.VENDOR_MEMORY) {
            if(firstSequence!=-1) { state("Invalid sequence","Vendor memory imports read all mapped history slots; sequence filters are not supported."); return; }
            beginVendorHistory(); return;
        }
        if(profile.isX7()) {
            if(!x7ReadReady()) return;
            if(firstSequence!=-1) { state("Read populated records","X7 imports use the current index for both users without changing counters."); return; }
            historyRunning=true; receivedInTransfer=0; addedInTransfer=0; storedCount=-1; wlpParsed=0; wlpRejected=0;
            storedInTransfer=0; queuedInTransfer=0; pendingInTransfer=0;
            x7HistoryIndex=null; x7HistoryRanges=Collections.emptyList(); x7HistoryRange=0; x7HistoryRangeOffset=0;
            beginWlpRead(X7MemoryIndex.SETTINGS_START,X7MemoryIndex.SETTINGS_BYTES);
            return;
        }
        if(hasUserControl && authenticatedUser!=user) { state("Authenticate first","Authenticate the selected monitor user before reading history."); return; }
        if(firstSequence < -1 || firstSequence>65535) { state("Invalid sequence","Use All records or a number from 0 to 65535."); return; }
        minimumSequence=firstSequence; historyRunning=true; receivedInTransfer=0; addedInTransfer=0; storedCount=-1;
        storedInTransfer=0; queuedInTransfer=0; pendingInTransfer=0;
        transfer.begin();
        state("Counting stored readings…","Readings are saved as they arrive; device memory is retained.");
        responseWrite(racp,firstSequence<0?Commands.countAll():Commands.countFrom(firstSequence),45000,"History count timed out; transfer incomplete");
    }
    public void readClock() {
        if(!ready || timeCharacteristic==null || (timeCharacteristic.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)==0) return;
        read(timeCharacteristic,bytes -> { try { LocalDateTime time=Commands.readDateTime(bytes); deviceTime=time==null?"Device clock unset":time.toString().replace('T',' '); changed(); } catch(IllegalArgumentException ex) { deviceTime="Clock format not recognized"; changed(); } });
    }
    public void setClock(LocalDateTime time) {
        if(!ready || !canTime || historyRunning || pendingUserOperation!=0) return;
        writeClock(time,() -> state("Clock write accepted","Monitor accepted the time write. Read the clock to verify, if available."),
            error -> {
                // A missing callback can leave Android's ATT slot busy even after our queue times out.
                if(error.startsWith("Bluetooth operation timed out: ")) {
                    disconnect(); state("Clock sync unavailable","The clock update timed out. Reconnect before importing history.");
                } else state("Clock sync unavailable","Monitor time was not confirmed. You can still import history.");
            });
    }
    private void writeClock(LocalDateTime time,Runnable success,Consumer<String> failure) {
        boolean current=timeCharacteristic.getUuid().equals(Uuids.CURRENT_TIME);
        write(timeCharacteristic,current?Commands.currentTime(time):Commands.dateTime(time),() -> {
            clockUpdate=ClockUpdate.ACCEPTED; deviceTime=time.toString().replace('T',' '); success.run();
        },error -> { clockUpdate=ClockUpdate.FAILED; failure.accept(error); });
    }
    private void notification(BluetoothGattCharacteristic c,byte[] bytes) {
        if(profile==ProtocolProfile.VENDOR_MEMORY) { vendorNotification(c,bytes); return; }
        if(profile.isX7() && (c.equals(handshake) || c.equals(wlpRx))) {
            x7Notification(c,bytes); return;
        }
        if(c.equals(handshake) && secure!=null && !secure.established()) {
            cancelPhase();
            try {
                byte[] next=secure.accept(bytes);
                if(next!=null) responseWrite(handshake,next,20000,"Secure pairing response timed out");
                else {
                    byte[] key=secure.longTermKey(); try { keys.put(address,key); } finally { Arrays.fill(key,(byte)0); }
                    store.remember(address,connectedName,modelHint,user); configure();
                }
            } catch(Exception ex) { fail(ex.getMessage()); }
            return;
        }
        if(vendorCharacteristic(c) && secure!=null && secure.established()) {
            try { bytes=secure.decrypt(bytes); } catch(IllegalArgumentException ex) { fail("Secure packet rejected: "+ex.getMessage()); return; }
        }
        if(c.equals(userControl)) {
            if(bytes.length<3 || (bytes[0]&255)!=0x20 || (bytes[1]&255)!=pendingUserOperation || pendingUserOperation==0) return;
            cancelPhase(); int op=pendingUserOperation; pendingUserOperation=0;
            if(bytes[2]!=1) { state("User access rejected","Monitor returned code "+(bytes[2]&255)+". Check user bank and consent code; register only in pairing mode."); return; }
            if(op==2) { authenticatedUser=pendingUser; state("User authenticated","User "+authenticatedUser+" · You can now read history."); }
            else state("User registered","User "+pendingUser+" registered. Authenticate this user to read history.");
            store.remember(address,connectedName,modelHint,user); return;
        }
        store.packet(address,c.getUuid().toString(),bytes,"RX");
        if(c.getUuid().equals(Uuids.MEASUREMENT) && c.getService().getUuid().equals(Uuids.BP)) {
            try {
                BloodPressure bp=BloodPressure.parse(bytes);
                Integer fallback=authenticatedUser>0?authenticatedUser:null;
                boolean added=acceptReading(bp,bytes,historyRunning?"history":"bluetooth",fallback,false);
                if(historyRunning) { transfer.measurement(true,added); updateTransferCounters(); armPhase(45000,"History stalled; partial readings were retained"); }
                changed();
            } catch(IllegalArgumentException ex) {
                if(historyRunning) { transfer.measurement(false,false); updateTransferCounters(); armPhase(45000,"History stalled; partial readings were retained"); }
                state("Unrecognized reading","Packet saved in diagnostics: "+ex.getMessage());
            } catch(android.database.sqlite.SQLiteException ex) {
                fail("Could not retain the reading in local history or the upload queue. Free storage and reconnect to retry.");
            }
        } else if(c.equals(racp) && historyRunning) handleHistory(bytes);
    }
    private void x7Notification(BluetoothGattCharacteristic c,byte[] bytes) {
        try {
            if(c.equals(handshake) && x7Transaction!=null && !x7Transaction.ready()) {
                if(bytes.length>0 && (bytes[0]&255)==0x91) store.packet(address,c.getUuid().toString(),bytes,"X7 system RX · transaction check");
                if(!x7Transaction.accept(bytes)) return;
                cancelPhase();
                if(profile==ProtocolProfile.X7_SECURE_WLP) beginX7Encryption(); else x7Ready();
                return;
            }
            if(profile==ProtocolProfile.X7_SECURE_WLP) {
                if(secure==null) return;
                if(!secure.established()) {
                    if(!c.equals(handshake) || bytes.length==0 || ((bytes[0]&255)!=0xf0 && (bytes[0]&255)!=0xff)) return;
                    cancelPhase();
                    // Pairing bodies, challenges and LTKs are excluded from diagnostic storage.
                    store.packet(address,"pairing",new byte[0],"X7 application handshake reply · body omitted");
                    byte[] next=secure.accept(bytes);
                    if(next!=null) responseWrite(handshake,next,20000,"X7 application pairing response timed out. No memory commands were sent.");
                    else {
                        byte[] key=secure.longTermKey(); try { keys.put(address,key); } finally { Arrays.fill(key,(byte)0); }
                        x7Authentication=new X7Authentication(); x7AuthenticationDeadline=SystemClock.elapsedRealtime()+60000;
                        state("Confirm access on X7","Encryption established. Waiting for the cuff to authorize this app. Follow its confirmation prompt; select your physical user/profile button only when the cuff requests it.");
                        pollX7Authentication();
                    }
                    return;
                }
                bytes=secure.decrypt(bytes);
                if(c.equals(handshake)) {
                    if(x7Authentication==null || x7Authentication.complete()) return;
                    X7Authentication.Result result=x7Authentication.accept(bytes);
                    if(result==X7Authentication.Result.IGNORED) return;
                    cancelPhase();
                    store.packet(address,"authentication",new byte[0],"X7 cuff authorization: "+result);
                    if(result==X7Authentication.Result.REJECTED) { fail("The X7 rejected application access. Restart pairing on the cuff and choose Pair again. No memory commands were sent."); return; }
                    if(result==X7Authentication.Result.COMPLETE) x7Ready();
                    else {
                        state("Waiting for X7 confirmation…","The cuff reports authorization is still pending. Follow its confirmation prompt; a blinking pairing screen is not successful pairing.");
                        long session=generation; X7Authentication pending=x7Authentication;
                        handler.postDelayed(() -> { if(session==generation && x7Authentication==pending && !pending.complete()) pollX7Authentication(); },1000);
                    }
                    return;
                }
            }
            if(c.equals(wlpRx) && x7ReadReady()) {
                store.packet(address,c.getUuid().toString(),bytes,"WLP RX · "+(profile==ProtocolProfile.X7_SECURE_WLP?"authenticated plaintext":"plain"));
                handleWlp(bytes);
            }
        } catch(Exception ex) { fail("X7 session failed: "+ex.getMessage()); }
    }
    private void handleHistory(byte[] bytes) {
        try {
            Commands.RacpResponse response=Commands.racp(bytes);
            HistoryTransfer.Action action=transfer.response(response);
            storedCount=transfer.expected;
            if(action==HistoryTransfer.Action.REPORT) {
                cancelPhase();
                state("Reading history…",storedCount+" readings reported by monitor.");
                responseWrite(racp,minimumSequence<0?Commands.reportAll():Commands.reportFrom(minimumSequence),45000,"History transfer timed out; partial readings were retained");
            } else if(action==HistoryTransfer.Action.COMPLETE) finishHistory(storedCount==0?"No stored readings for this user.":addedInTransfer+" new readings saved · "+receivedInTransfer+" received.");
            else if(action==HistoryTransfer.Action.FAILED) { historyRunning=false; cancelPhase(); state("History transfer incomplete",transfer.failure); }
        } catch(IllegalArgumentException ex) { fail("Invalid history response: "+ex.getMessage()); }
    }
    private void updateTransferCounters() { receivedInTransfer=transfer.received; addedInTransfer=transfer.added; }
    private void finishHistory(String message) { historyRunning=false; cancelPhase(); state("History transfer complete",message); }
    private static boolean writable(BluetoothGattCharacteristic c) { return (c.getProperties()&(BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))!=0; }
    private static boolean subscribable(BluetoothGattCharacteristic c) { return (c.getProperties()&(BluetoothGattCharacteristic.PROPERTY_NOTIFY|BluetoothGattCharacteristic.PROPERTY_INDICATE))!=0; }
    private static String key(BluetoothGattCharacteristic c) { return c.getService().getUuid()+"/"+c.getUuid()+"/"+c.getInstanceId(); }
    private boolean vendorCharacteristic(BluetoothGattCharacteristic c) { UUID s=c.getService().getUuid(); return s.equals(Uuids.VENDOR)||s.equals(Uuids.VENDOR_ALIAS); }
    private void enqueue(String key,java.util.function.BooleanSupplier start,Runnable success) {
        enqueue(key,start,success,null);
    }
    private void enqueue(String key,java.util.function.BooleanSupplier start,Runnable success,Consumer<String> failure) {
        long session=generation;
        operations.add(key,() -> { armOperation(key,session); return start.getAsBoolean(); },() -> { cancelOperationTimeout(); success.run(); },
            failure==null?null:error -> { cancelOperationTimeout(); failure.accept(error); });
    }
    private void subscribe(BluetoothGattCharacteristic c,Runnable success) {
        if(c==null || !subscribable(c)) { fail("Required reading characteristic cannot notify or indicate"); return; }
        BluetoothGattDescriptor d=c.getDescriptor(Uuids.CCCD); if(d==null) { fail("Required notification descriptor is missing"); return; }
        enqueue("descriptor:"+key(c),() -> {
            byte[] value=!profile.isX7() && profile!=ProtocolProfile.VENDOR_MEMORY && (c.getProperties()&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0?BluetoothGattDescriptor.ENABLE_INDICATION_VALUE:BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if(profile==ProtocolProfile.VENDOR_MEMORY) DeviceWritePolicy.validateVendorDescriptor(vendorModel,c.getService().getUuid(),c.getUuid(),d.getUuid(),value);
            else DeviceWritePolicy.validateDescriptor(profile,c.getService().getUuid(),c.getUuid(),d.getUuid(),value);
            if(!gatt.setCharacteristicNotification(c,true)) return false;
            if(Build.VERSION.SDK_INT>=33) return gatt.writeDescriptor(d,value)==BluetoothStatusCodes.SUCCESS;
            d.setValue(value); return gatt.writeDescriptor(d);
        },success);
    }
    private void write(BluetoothGattCharacteristic c,byte[] bytes,Runnable success) {
        write(c,bytes,success,null);
    }
    private void write(BluetoothGattCharacteristic c,byte[] bytes,Runnable success,Consumer<String> failure) {
        byte[] requested=bytes.clone();
        enqueue("write:"+key(c),() -> {
            if(profile==ProtocolProfile.VENDOR_MEMORY) {
                if(c.equals(timeCharacteristic)) DeviceWritePolicy.validateVendorClock(vendorModel,c.getService().getUuid(),c.getUuid(),requested);
                else if(c.equals(handshake)) DeviceWritePolicy.validateVendorUnlock(vendorModel,c.getService().getUuid(),c.getUuid(),requested,vendorUnlock);
                else DeviceWritePolicy.validateVendorCharacteristic(vendorModel,c.getService().getUuid(),c.getUuid(),requested);
            }
            else if(registrationWriting && x7Registration!=null && c.equals(wlpTx) && requested.length>8)
                DeviceWritePolicy.validateX7Registration(profile,c.getService().getUuid(),c.getUuid(),requested,x7Registration);
            else DeviceWritePolicy.validateCharacteristic(profile,c.getService().getUuid(),c.getUuid(),requested);
            byte[] value=requested;
            if(vendorCharacteristic(c) && secure!=null && secure.established()) {
                // Validate the complete plaintext first. No API accepts arbitrary ciphertext to bypass this gate.
                if(profile!=ProtocolProfile.X7_SECURE_WLP) throw new IllegalArgumentException("Vendor writes after pairing are not enabled");
                if(c.equals(handshake) && requested[0]==0x70) throw new IllegalArgumentException("Pairing command after session establishment");
                value=secure.encrypt(requested);
            } else if(profile==ProtocolProfile.X7_SECURE_WLP &&
                    (c.equals(wlpTx) || c.equals(handshake) && requested[0]==0x20)) {
                throw new IllegalArgumentException("X7 access requires an established encrypted session");
            }
            // SYS transaction and the single data TX use NO_RESPONSE. Crypto and queued SYS commands use DEFAULT.
            int type=(profile==ProtocolProfile.VENDOR_MEMORY && !vendorModel.legacyUnlock && vendorTx.contains(c)) || profile.isX7() && (c.equals(wlpTx) || c.equals(handshake) && requested[0]==0x11)?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:
                    (c.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0?BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT:BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            if(value.length>negotiatedMtu-3) throw new IllegalArgumentException("Command exceeds negotiated ATT payload");
            if(Build.VERSION.SDK_INT>=33) return gatt.writeCharacteristic(c,value,type)==BluetoothStatusCodes.SUCCESS;
            c.setWriteType(type); c.setValue(value); return gatt.writeCharacteristic(c);
        },success,failure);
    }
    private byte[] readResult;
    private void responseWrite(BluetoothGattCharacteristic c,byte[] bytes,long timeout,String reason) {
        cancelPhase(); long token=responseGeneration;
        // An indication may arrive before Android's write-complete callback.
        write(c,bytes,() -> { if(token==responseGeneration) armPhase(timeout,reason); });
    }
    private void read(BluetoothGattCharacteristic c,Consumer<byte[]> success) {
        String operationKey="read:"+key(c); long session=generation;
        operations.add(operationKey,() -> { armOperation(operationKey,session); return gatt.readCharacteristic(c); },
            () -> { cancelOperationTimeout(); success.accept(readResult); },
            error -> { cancelOperationTimeout(); information+="Optional read unavailable: "+c.getUuid()+"\n"; changed(); });
    }
    private void armOperation(String key,long session) {
        cancelOperationTimeout(); operationTimeout=() -> { if(session==generation) operations.timeout(key); };
        handler.postDelayed(operationTimeout,15000);
    }
    private void cancelOperationTimeout() { if(operationTimeout!=null) handler.removeCallbacks(operationTimeout); operationTimeout=null; }
    private void armPhase(long millis,String reason) { cancelPhase(); long session=generation; phaseTimeout=() -> { if(session==generation) fail(reason); }; handler.postDelayed(phaseTimeout,millis); }
    private void cancelPhase() { responseGeneration++; if(phaseTimeout!=null) handler.removeCallbacks(phaseTimeout); phaseTimeout=null; }
    private final BluetoothGattCallback callback=new BluetoothGattCallback() {
        private void current(BluetoothGatt source,Runnable action) { handler.post(() -> { if(source==gatt) action.run(); }); }
        @Override public void onConnectionStateChange(BluetoothGatt source,int result,int state) { current(source,() -> {
            if(state==BluetoothProfile.STATE_DISCONNECTED && vendorRead!=null && vendorRead.historyComplete() && vendorClockAttempted && !vendorSessionEnded) {
                if(vendorClockPending) { clockUpdate=ClockUpdate.FAILED; vendorClockResult="Clock update could not be confirmed; imported readings were retained."; }
                finishVendorHistory(false); closeFinishedWlp(); return;
            }
            if(state==BluetoothProfile.STATE_DISCONNECTED && vendorSessionEnded) { closeFinishedWlp(); return; }
            if(state==BluetoothProfile.STATE_DISCONNECTED && wlpConnection!=null && wlpConnection.shutdownExpected()) { closeFinishedWlp(); return; }
            if(state==BluetoothProfile.STATE_DISCONNECTED && registrationWriting) { fail("X7 disconnected before the registration END acknowledgment."); return; }
            if(state==BluetoothProfile.STATE_DISCONNECTED && (registrationReading || registrationPending)) { fail("X7 left pairing before profile registration. No settings writes were sent; wake the cuff and retry Pair."); return; }
            if(result!=BluetoothGatt.GATT_SUCCESS) { fail("Bluetooth connection error "+result+". Wake the monitor and reconnect."); return; }
            if(state==BluetoothProfile.STATE_CONNECTED) { MonitorService.this.state("Discovering monitor…","Negotiating Bluetooth MTU."); if(!source.requestMtu(243)) discover(source); }
            else if(state==BluetoothProfile.STATE_DISCONNECTED) { boolean partial=historyRunning; disconnect(); if(partial) MonitorService.this.state("Transfer interrupted","Partial readings were saved. Reconnect to retry history."); }
        }); }
        @Override public void onMtuChanged(BluetoothGatt source,int mtu,int result) { current(source,() -> { if(result==0) negotiatedMtu=mtu; if(report!=null && !automaticConnection) report.mtu(negotiatedMtu); store.packet(address,"mtu",new byte[0],"ATT MTU="+negotiatedMtu+" · callback status="+result); discover(source); }); }
        private void discover(BluetoothGatt source) { if(!source.discoverServices()) fail("Android rejected service discovery"); else armPhase(20000,"Service discovery timed out"); }
        @Override public void onServicesDiscovered(BluetoothGatt source,int result) { current(source,() -> { if(result==0) discoverProfile(); else fail("Service discovery failed ("+result+")"); }); }
        @Override public void onDescriptorWrite(BluetoothGatt source,BluetoothGattDescriptor d,int result) { current(source,() -> operations.complete("descriptor:"+key(d.getCharacteristic()),result)); }
        @Override public void onCharacteristicWrite(BluetoothGatt source,BluetoothGattCharacteristic c,int result) { current(source,() -> {
            if(result!=BluetoothGatt.GATT_SUCCESS && vendorSessionEnded && vendorTx.contains(c)) { closeFinishedWlp(); return; }
            if(result!=BluetoothGatt.GATT_SUCCESS && c.equals(wlpTx) && wlpConnection!=null && wlpConnection.shutdownExpected()) {
                store.packet(address,"android-write",new byte[0],"Late WLP TX completion status="+result+" after validated END acknowledgment");
                closeFinishedWlp(); return;
            }
            operations.complete("write:"+key(c),result);
        }); }
        @Override public void onCharacteristicRead(BluetoothGatt source,BluetoothGattCharacteristic c,int result) { byte[] bytes=c.getValue(); readDone(source,c,bytes,result); }
        @Override public void onCharacteristicRead(BluetoothGatt source,BluetoothGattCharacteristic c,byte[] bytes,int result) { readDone(source,c,bytes,result); }
        private void readDone(BluetoothGatt source,BluetoothGattCharacteristic c,byte[] bytes,int result) { byte[] value=bytes==null?new byte[0]:bytes.clone(); current(source,() -> { readResult=value; operations.complete("read:"+key(c),result); }); }
        @Override public void onCharacteristicChanged(BluetoothGatt source,BluetoothGattCharacteristic c) { byte[] bytes=c.getValue(); notifyDone(source,c,bytes); }
        @Override public void onCharacteristicChanged(BluetoothGatt source,BluetoothGattCharacteristic c,byte[] bytes) { notifyDone(source,c,bytes); }
        private void notifyDone(BluetoothGatt source,BluetoothGattCharacteristic c,byte[] bytes) { byte[] value=bytes==null?new byte[0]:bytes.clone(); current(source,() -> notification(c,value)); }
    };
    @Override public void onDestroy() {
        destroying=true; handler.removeCallbacks(automaticRefresh); stopAutomaticScan(); disconnect();
        dumpIo.shutdown(); unregisterReceiver(bondReceiver); unregisterReceiver(adapterReceiver); super.onDestroy();
    }
}
