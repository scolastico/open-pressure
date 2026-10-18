package org.openpressure.app;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.text.NumberFormat;
import org.openpressure.app.ui.MonitorUi;
import java.util.*;
import org.openpressure.app.ble.MonitorService;
import org.openpressure.app.data.*;
import org.openpressure.app.health.HealthConnectSettingsActivity;
import org.openpressure.app.forwarding.HttpForwardingSettings;
import org.openpressure.app.forwarding.HttpForwardingSettingsActivity;
import org.openpressure.app.developer.DeveloperOptions;
import org.openpressure.app.developer.DeveloperOptionsActivity;
import org.openpressure.app.automatic.*;
import org.openpressure.app.notifications.NewReadingNotifications;
import org.openpressure.app.reminders.RemindersActivity;
import org.openpressure.app.profiles.VirtualUsersActivity;
import org.openpressure.app.profiles.AssignmentActivity;
import org.openpressure.app.pdf.PdfReportData;
import org.openpressure.app.pdf.PdfReportWriter;
import org.openpressure.protocol.DeviceReport;
import org.openpressure.app.updates.UpdateCheck;

public final class MainActivity extends Activity {
    private static final int BG=0xfff5f7f4, INK=0xff182d2a, MUTED=0xff61726b, TEAL=0xff176e65, BORDER=0xffdce5de;
    private ReadingStore store;
    private MonitorService monitor;
    private LinearLayout root,content,discoveredList;
    private final Map<String,DeviceRow> deviceRows=new LinkedHashMap<>();
    private final List<Button> navigation=new ArrayList<>();
    private TextView statusHeading,statusText,statusDetail,dumpProgress,automaticStatusText;
    private Button scanButton,dumpReadButton,dumpExportButton;
    private boolean dumpExportPreparing,pdfExportPreparing,exportWriting,historyClearing;
    private String renderedDeviceState="",pendingDumpExportPath,pendingPdfExportPath;
    private int pdfPreparationToken;
    private Button pdfExportButton;
    private AlertDialog pdfDialog;
    private Thread pdfPreparationWorker;
    private long renderedReadingsRevision;
    private boolean overviewHasStatus;
    private LocalDate renderedDay;
    private ScrollView scroll;
    private int tab,filterUser,days,historyLimit=100;
    private boolean bound,uiVisible,advancedExpanded,virtualMode;
    private final DeveloperOptions.TapCounter developerTaps=new DeveloperOptions.TapCounter();
    private Toast developerToast;
    private AlertDialog consentDialog,deviceReportDialog,updateDialog,clockSyncDialog;
    /** The current clock-sync preview has been shown; reset when the service ends the preview. */
    private boolean clockSyncPrompted;
    private String pendingReportJson;
    private static final String ISSUES="https://github.com/scolastico/open-pressure/issues";
    private int pendingExportKind, pendingExportUser, pendingExportDays;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean refreshScheduled;
    private final Runnable refresh=() -> { refreshScheduled=false; refreshMonitor(); };
    private final Runnable listener=() -> {
        if(!refreshScheduled) { refreshScheduled=true; handler.postDelayed(refresh,150); }
    };
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) { monitor=((MonitorService.LocalBinder)binder).get(); monitor.listen(listener); monitor.setUiVisible(MainActivity.this,uiVisible); render(); }
        @Override public void onServiceDisconnected(ComponentName name) { monitor=null; render(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); store=((PressureApp)getApplication()).store();
        virtualMode=store.virtualUsersEnabled();
        filterUser=getPreferences(MODE_PRIVATE).getInt(virtualMode?"selected_virtual_profile":"selected_profile",0);
        if(state!=null) {
            tab=state.getInt("tab"); if(state.getBoolean("virtualMode",false)==virtualMode) filterUser=state.getInt("user"); days=state.getInt("days");
            pendingExportKind=state.getInt("exportKind"); pendingExportUser=state.getInt("exportUser"); pendingExportDays=state.getInt("exportDays"); pendingDumpExportPath=state.getString("dumpExportPath");
            pendingPdfExportPath=state.getString("pdfExportPath"); pendingReportJson=state.getString("reportJson");
        }
        if(getIntent().getBooleanExtra("open_history",false)) { tab=1; days=0; getIntent().removeExtra("open_history"); }
        if(getIntent().getBooleanExtra("open_reminders_overview",false)) { tab=0; getIntent().removeExtra("open_reminders_overview"); }
        render();
        ensureExperimentalConsent();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if(intent.getBooleanExtra("open_reminders_overview",false)) {
            intent.removeExtra("open_reminders_overview"); tab=0;
            if(scroll!=null) scroll.scrollTo(0,0);
            render();
        } else if(intent.getBooleanExtra("open_history",false)) {
            intent.removeExtra("open_history"); tab=1; historyLimit=100;
            days=0;
            if(scroll!=null) scroll.scrollTo(0,0);
            render();
        }
    }
    @Override protected void onResume() { super.onResume(); ensureExperimentalConsent(); if(store!=null) render(); }
    @Override protected void onStart() { super.onStart(); uiVisible=true; if(monitor!=null) monitor.setUiVisible(this,true); }
    @Override protected void onStop() { uiVisible=false; if(monitor!=null) monitor.setUiVisible(this,false); super.onStop(); }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putInt("tab",tab); out.putInt("user",filterUser); out.putInt("days",days); out.putBoolean("virtualMode",virtualMode);
        out.putInt("exportKind",pendingExportKind); out.putInt("exportUser",pendingExportUser); out.putInt("exportDays",pendingExportDays); out.putString("dumpExportPath",pendingDumpExportPath);
        out.putString("pdfExportPath",pendingPdfExportPath); out.putString("reportJson",pendingReportJson);
    }
    @Override public void onDestroy() {
        pdfPreparationToken++;
        if(pdfPreparationWorker!=null) pdfPreparationWorker.interrupt();
        if(pdfDialog!=null) { pdfDialog.dismiss(); pdfDialog=null; }
        if(isFinishing()) { deleteExportSnapshot(pendingPdfExportPath); pendingPdfExportPath=null; }
        if(consentDialog!=null) { consentDialog.dismiss(); consentDialog=null; }
        if(deviceReportDialog!=null) { deviceReportDialog.dismiss(); deviceReportDialog=null; }
        if(updateDialog!=null) { updateDialog.dismiss(); updateDialog=null; }
        if(clockSyncDialog!=null) { clockSyncDialog.dismiss(); clockSyncDialog=null; }
        handler.removeCallbacks(refresh); if(monitor!=null) { monitor.unlisten(listener); monitor.setUiVisible(this,false); } if(bound) unbindService(connection); super.onDestroy();
    }
    private void ensureExperimentalConsent() {
        if(isFinishing() || isDestroyed()) return;
        if(ExperimentalConsent.accepted(this)) {
            if(!bound) bound=bindService(new Intent(this,MonitorService.class),connection,BIND_AUTO_CREATE);
            int mode=AutoImportSettings.getMode(this);
            if(mode==AutoImportSettings.NOTIFICATION || mode==AutoImportSettings.COMPANION) NewReadingNotifications.requestPermissionIfNeeded(this);
            offerUpdate(); UpdateCheck.checkIfDue(this,this::offerUpdate);
            return;
        }
        if(consentDialog!=null && consentDialog.isShowing()) return;
        consentDialog=new AlertDialog.Builder(this).setTitle(getString(R.string.main_consent_title))
                .setMessage(getString(R.string.main_consent_message))
                .setCancelable(false).setNegativeButton(getString(R.string.main_exit),(dialog,which) -> finish())
                .setPositiveButton(getString(R.string.main_consent_accept),null).create();
        consentDialog.setCanceledOnTouchOutside(false);
        consentDialog.setOnShowListener(dialog -> consentDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if(!ExperimentalConsent.accept(this)) { toast(getString(R.string.main_consent_save_failed)); return; }
            consentDialog.dismiss(); consentDialog=null;
            ensureExperimentalConsent();
        }));
        consentDialog.show();
    }
    private void render() {
        validateProfileSelection();
        int position=scroll==null?0:scroll.getScrollY();
        if(root==null) {
            root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
            root.setOnApplyWindowInsetsListener((v,insets) -> { v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
            LinearLayout header=row(); header.setPaddingRelative(dp(22),dp(18),dp(18),dp(12));
            TextView brand=text(getString(R.string.main_app_name),14,TEAL,true); brand.setLetterSpacing(0.12f); header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
            header.addView(button(getString(R.string.main_about),this::about,false)); root.addView(header);
            scroll=new ScrollView(this); scroll.setFillViewport(true);
            content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(22),dp(6),dp(22),dp(28)); scroll.addView(content);
            root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            LinearLayout nav=row(); nav.setPadding(dp(12),dp(8),dp(12),dp(8)); nav.setBackgroundColor(Color.WHITE);
            String[] labels={getString(R.string.main_overview),getString(R.string.main_history),getString(R.string.main_devices)};
            for(int i=0;i<labels.length;i++) {
                final int selected=i; Button button=button(labels[i],() -> { if(tab!=selected) { tab=selected; scroll.scrollTo(0,0); render(); } },false);
                navigation.add(button); nav.addView(button,new LinearLayout.LayoutParams(0,-2,1));
            }
            root.addView(nav); setContentView(root);
        }
        content.removeAllViews(); deviceRows.clear(); discoveredList=null; scanButton=null; dumpReadButton=null; dumpExportButton=null; pdfExportButton=null;
        statusHeading=null; statusText=null; statusDetail=null; dumpProgress=null; automaticStatusText=null;
        for(int i=0;i<navigation.size();i++) {
            navigation.get(i).setTextColor(i==tab?Color.WHITE:TEAL);
            navigation.get(i).setBackgroundTintList(android.content.res.ColorStateList.valueOf(i==tab?TEAL:0xffe9efea));
        }
        overviewHasStatus=monitor!=null && (monitor.ready || monitor.scanning);
        if(tab==0) today(); else if(tab==1) history(); else devices();
        renderedReadingsRevision=monitor==null?0:monitor.readingsRevision; renderedDay=LocalDate.now();
        renderedDeviceState=deviceState();
        scroll.post(() -> scroll.scrollTo(0,position));
        offerClockSync();
        offerDeviceReport();
    }
    // Advertisement updates must not replace buttons beneath a finger or rerun reading queries.
    private void refreshMonitor() {
        if(isDestroyed() || monitor==null) return;
        if(tab==2) {
            if(!renderedDeviceState.equals(deviceState())) { render(); return; }
            updateDiscovered();
            if(scanButton!=null) setText(scanButton,monitor.scanning?getString(R.string.main_stop_scan):getString(R.string.main_scan));
            if(dumpProgress!=null) setText(dumpProgress,dumpProgressText());
        } else if(renderedReadingsRevision!=monitor.readingsRevision || tab==0 && overviewHasStatus!=(monitor.ready||monitor.scanning)) {
            render(); return;
        }
        updateStatus();
        offerClockSync();
        offerDeviceReport();
    }
    private String deviceState() {
        if(monitor==null) return "starting";
        return Arrays.asList(monitor.ready,monitor.sessionFinished,monitor.registrationReading,monitor.registrationPending,monitor.registrationWriting,monitor.registrationDone,monitor.registrationBackupFile()!=null,monitor.clockSyncBackupFile(),monitor.address,monitor.connectedName,monitor.profile,monitor.user,
                monitor.battery,monitor.information,monitor.hasUserControl,monitor.authenticatedUser,monitor.canHistory,
                monitor.historyRunning,monitor.clockSyncRunning,monitor.clockSyncPending,monitor.importMode,monitor.importUsers,monitor.canTime,monitor.deviceTime,monitor.modelHint,monitor.canDump,
                monitor.dumpRunning,monitor.dumpSaving,monitor.dumpRevision,monitor.eepromDumpFile()!=null,monitor.dumpComplete,monitor.dumpStart,monitor.dumpLength,monitor.deviceReport()!=null,
                !monitor.address.isEmpty()&&!monitor.status.equals("Disconnected")&&!monitor.status.equals("Action failed")).toString();
    }
    private void updateStatus() {
        if(automaticStatusText!=null && monitor!=null) setText(automaticStatusText,MonitorUi.automatic(this,monitor));
        if(statusText==null || monitor==null) return;
        setText(statusHeading,getString(R.string.main_monitor));
        setText(statusText,MonitorUi.title(this,monitor)); setText(statusDetail,MonitorUi.detail(this,monitor));
    }
    private static void setText(TextView view,String value) { if(!view.getText().toString().equals(value)) view.setText(value); }
    private final class DeviceRow {
        final LinearLayout card;
        final TextView name,metadata;
        String currentName;
        DeviceRow(MonitorService.Discovered device) {
            card=card(); name=text("",20,INK,true); metadata=text("",13,MUTED,false);
            add(card,name,0); add(card,metadata,7);
            LinearLayout buttons=row();
            buttons.addView(button(getString(R.string.main_pair),() -> connect(device.address,currentName,true),true),new LinearLayout.LayoutParams(0,-2,1));
            buttons.addView(button(getString(R.string.main_connect),() -> connect(device.address,currentName,false),false),new LinearLayout.LayoutParams(0,-2,1));
            add(card,buttons,10); update(device);
        }
        void update(MonitorService.Discovered device) {
            currentName=device.name; setText(name,device.name);
            setText(metadata,device.address);
        }
    }
    private void updateDiscovered() {
        if(discoveredList==null || monitor==null) return;
        Iterator<Map.Entry<String,DeviceRow>> iterator=deviceRows.entrySet().iterator();
        while(iterator.hasNext()) {
            Map.Entry<String,DeviceRow> row=iterator.next();
            if(!monitor.discovered.containsKey(row.getKey())) { discoveredList.removeView(row.getValue().card); iterator.remove(); }
        }
        for(MonitorService.Discovered device:monitor.discovered.values()) {
            DeviceRow row=deviceRows.get(device.address);
            if(row==null) { row=new DeviceRow(device); deviceRows.put(device.address,row); discoveredList.addView(row.card); }
            else row.update(device);
        }
    }
    private void today() {
        title(getString(R.string.main_overview),getString(R.string.main_overview_subtitle));
        int pending=store.pendingAssignmentsCount();
        if(pending>0) {
            add(content,text(getString(R.string.vp_pending_count,pending),15,TEAL,true),10);
            add(content,button(getString(R.string.vp_assign_action),() -> startActivity(new Intent(this,AssignmentActivity.class)),false),6);
        }
        filters(false);
        List<Reading> readings=visibleReadings();
        LinearLayout latest=card(); latest.setBackground(background(0xffe2eee7,0xffe2eee7));
        label(latest,getString(R.string.main_latest_reading));
        if(readings.isEmpty()) {
            add(latest,text(getString(R.string.main_no_readings),29,INK,true),12);
            add(latest,text(getString(R.string.main_connect_empty),16,MUTED,false),12);
            add(latest,button(getString(R.string.main_connect_monitor),() -> { tab=2; scroll.scrollTo(0,0); render(); },true),18);
        } else {
            Reading r=readings.get(0); add(latest,text(pressureValues(r.sysMmHg(),r.diaMmHg(),false),44,INK,true),10);
            add(latest,text(getString(R.string.main_pressure_pulse,pulseLabel(r)),16,TEAL,true),4);
            add(latest,text(getString(R.string.main_reading_identity,date(r),readingName(r),userLabel(r)),14,MUTED,false),14);
            List<String> flags=MonitorUi.readingFlags(this,r.status);
            if(!flags.isEmpty()) add(latest,text(String.join(" · ",flags),14,0xff8a522d,false),10);
            add(latest,button(getString(R.string.main_view_reading),() -> readingDetail(r),false),12);
        }
        content.addView(latest);
        if(!readings.isEmpty()) {
            LocalDateTime since=LocalDateTime.now().minusDays(7); List<Reading> recent=new ArrayList<>();
            for(Reading r:readings) if(r.time!=null && !r.displayTime().isBefore(since) && !r.displayTime().isAfter(LocalDateTime.now().plusMinutes(5))) recent.add(r);
            LinearLayout summary=card(); label(summary,getString(R.string.main_past_week));
            if(recent.isEmpty()) add(summary,text(getString(R.string.main_no_week_readings),16,MUTED,false),12);
            else {
                double sys=0,dia=0; for(Reading r:recent) { sys+=r.sysMmHg(); dia+=r.diaMmHg(); }
                add(summary,text(pressureValues(sys/recent.size(),dia/recent.size(),true),27,INK,true),10);
                add(summary,text(getResources().getQuantityString(R.plurals.main_average_readings,recent.size(),number(recent.size())),14,MUTED,false),5);
                Collections.reverse(recent);
                if(virtualMode && filterUser==0) {
                    Map<Integer,List<Reading>> profiles=new LinkedHashMap<>();
                    for(Reading reading:recent) profiles.computeIfAbsent(reading.virtualUserId,ignored -> new ArrayList<>()).add(reading);
                    for(List<Reading> profile:profiles.values()) {
                        add(summary,text(userLabel(profile.get(0)),16,INK,true),14);
                        summary.addView(new TrendView(this,profile),new LinearLayout.LayoutParams(-1,dp(170)));
                    }
                } else summary.addView(new TrendView(this,recent),new LinearLayout.LayoutParams(-1,dp(170)));
            } content.addView(summary);
        }
        LinearLayout actions=card(); label(actions,getString(R.string.main_your_readings));
        add(actions,button(getString(R.string.main_add_reading),this::manualEntry,false),10);
        add(actions,button(getString(R.string.main_export_readings),() -> export(false),false),6);
        add(actions,pdfExportButton(),6);
        add(actions,button(getString(R.string.main_health_connect),() -> startActivity(new Intent(this,HealthConnectSettingsActivity.class)),false),6); content.addView(actions);
        automaticCard();
        if(monitor!=null && (monitor.ready || monitor.scanning)) statusCard();
        add(content,text(getString(R.string.main_measurement_note),13,MUTED,false),12);
        add(content,button(getString(R.string.reminder_title),() -> startActivity(new Intent(this,RemindersActivity.class)),false),8);
        add(content,button(getString(R.string.vp_title),() -> startActivity(new Intent(this,VirtualUsersActivity.class)),false),8);
    }
    private void history() {
        title(getString(R.string.main_history),getString(R.string.main_history_subtitle)); filters(true);
        LinearLayout controls=row(); controls.addView(button(getString(R.string.main_add_reading_short),this::manualEntry,false),new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(button(getString(R.string.main_export_csv),() -> export(false),true),new LinearLayout.LayoutParams(0,-2,1)); add(content,controls,8);
        add(content,pdfExportButton(),8);
        List<Reading> readings=visibleReadings(); add(content,text(getResources().getQuantityString(R.plurals.main_history_count,readings.size(),number(readings.size()),profileLabel(filterUser)),14,MUTED,false),18);
        if(readings.isEmpty()) { LinearLayout empty=card(); add(empty,text(getString(R.string.main_history_empty),23,INK,true),0); add(empty,text(getString(R.string.main_history_empty_help),16,MUTED,false),12); content.addView(empty); }
        for(int i=0;i<Math.min(historyLimit,readings.size());i++) {
            Reading r=readings.get(i); LinearLayout item=card();
            add(item,text(pressureValues(r.sysMmHg(),r.diaMmHg(),true),25,INK,true),0);
            add(item,text(getString(R.string.main_reading_date_user,date(r),userLabel(r)),14,MUTED,false),5);
            add(item,text(pulseLabel(r),15,TEAL,false),5);
            if(!r.notes.isEmpty()) add(item,text(r.notes,14,MUTED,false),6);
            item.setContentDescription(getString(R.string.main_reading_accessibility,number(r.sysMmHg()),number(r.diaMmHg()),date(r)));
            item.setOnClickListener(v -> readingDetail(r)); content.addView(item);
        }
        if(readings.size()>historyLimit) add(content,button(getString(R.string.main_show_more),() -> { historyLimit+=100; render(); },false),8);
        Button clear=button(historyClearing?getString(R.string.main_history_clearing):getString(R.string.main_clear_history),this::clearHistoryDialog,false);
        clear.setEnabled(!historyClearing && store.historyCount(0)>0); add(content,clear,8);
    }
    private void clearHistoryDialog() {
        if(historyClearing) return;
        List<Integer> scopes=profileScopes(); String[] labels=profileLabels(scopes);
        final int[] selected={0};
        new AlertDialog.Builder(this).setTitle(getString(R.string.main_clear_scope_title))
                .setSingleChoiceItems(labels,0,(dialog,which) -> selected[0]=scopes.get(which))
                .setNegativeButton(getString(R.string.main_cancel),null)
                .setPositiveButton(getString(R.string.main_continue),(dialog,which) -> confirmClearHistory(selected[0])).show();
    }
    private void confirmClearHistory(int user) {
        int count=store.historyCount(user);
        String scope=user==0?getString(R.string.main_clear_all_scope):profileLabel(user);
        if(count==0) { toast(getString(R.string.main_no_local_readings,scope)); return; }
        new AlertDialog.Builder(this).setTitle(getString(R.string.main_clear_title))
                .setMessage(getString(R.string.main_clear_confirmation,scope,number(count)))
                .setNegativeButton(getString(R.string.main_cancel),null).setPositiveButton(getResources().getQuantityString(R.plurals.main_clear_readings,count,number(count)),(dialog,which) -> clearHistory(user)).show();
    }
    private void clearHistory(int user) {
        if(historyClearing) return;
        historyClearing=true;
        // Cancel an in-progress explicit import before clearing; otherwise its remaining
        // records could immediately restore the history the user just removed.
        if(monitor!=null && monitor.historyRunning) monitor.disconnect();
        render();
        new Thread(() -> {
            try {
                int removed=store.clearHistory(user);
                handler.post(() -> {
                    historyClearing=false;
                    if(monitor!=null) monitor.localReadingsChanged();
                    if(isDestroyed() || isFinishing()) return;
                    historyLimit=100; render(); toast(getResources().getQuantityString(R.plurals.main_cleared_readings,removed,number(removed)));
                });
            } catch(Exception error) {
                handler.post(() -> {
                    historyClearing=false;
                    if(isDestroyed() || isFinishing()) return;
                    render(); toast(getString(R.string.main_clear_failed));
                });
            }
        },"clear-local-history").start();
    }
    private void devices() {
        title(getString(R.string.main_monitors),getString(R.string.main_monitor_compatibility));
        automaticCard();
        if(monitor==null) { add(content,text(ExperimentalConsent.accepted(this)?getString(R.string.main_bluetooth_starting):getString(R.string.main_consent_needed),16,MUTED,false),16); return; }
        statusCard();
        if(monitor.deviceReport()!=null) {
            LinearLayout help=card(); label(help,getString(R.string.device_report_title));
            add(help,text(getString(R.string.device_report_card),15,MUTED,false),10);
            add(help,button(getString(R.string.device_report_review),this::reviewDeviceReport,true),8); content.addView(help);
        }
        if(monitor.registrationPending) {
            LinearLayout registration=card(); label(registration,getString(R.string.main_choose_user));
            add(registration,text(getString(R.string.main_registration_user_explanation),15,MUTED,false),12);
            LinearLayout users=row();
            for(int bank=1;bank<=2;bank++) {
                final int selected=bank;
                users.addView(button(profileLabel(bank),() -> registerX7Dialog(selected),true),new LinearLayout.LayoutParams(0,-2,1));
            }
            add(registration,users,12); content.addView(registration);
        }
        if(monitor.ready) {
            LinearLayout active=card(); label(active,monitor.connectedName);
            add(active,text(profileLabel(monitor.user),14,MUTED,false),12);
            if(monitor.battery>=0) add(active,text(getString(R.string.main_battery,NumberFormat.getPercentInstance(getResources().getConfiguration().getLocales().get(0)).format(monitor.battery/100.0)),14,MUTED,false),6);
            if(monitor.hasUserControl) {
                add(active,button(monitor.authenticatedUser>0?getString(R.string.main_change_user,profileLabel(monitor.authenticatedUser)):getString(R.string.main_choose_monitor_user),this::userDialog,true),12);
            }
            if(monitor.offersImportUsers()) {
                String address=monitor.address;
                importUsersChoice(active,"import_users_",monitor.importUsers,!monitor.historyRunning&&!monitor.dumpRunning,users -> { store.setImportUsers(address,users); if(monitor.address.equals(address)) monitor.importUsers=users; monitor.refreshAutomaticImports(); render(); });
            }
            Button history=button(monitor.historyRunning?getString(R.string.main_importing):getString(R.string.main_import_history),this::historyDialog,true); history.setEnabled(monitor.canHistory&&!monitor.historyRunning&&!monitor.dumpRunning&&!monitor.clockSyncRunning); add(active,history,8);
            if(monitor.profile==org.openpressure.protocol.ProtocolProfile.VENDOR_MEMORY) {
                boolean readingAll=monitor.historyRunning&&monitor.importMode==MonitorService.ImportMode.FULL_SWEEP;
                Button readAll=button(readingAll?getString(R.string.main_reading_all):getString(R.string.main_read_all),this::readAllDialog,false);
                readAll.setEnabled(monitor.canReadAll()&&!monitor.historyRunning&&!monitor.dumpRunning); add(active,readAll,8);
            }
            if(!monitor.canHistory) add(active,text(getString(R.string.main_history_unavailable),14,MUTED,false),6);
            if(monitor.profile==org.openpressure.protocol.ProtocolProfile.VENDOR_MEMORY || monitor.profile.isX7()) {
                // Only where the clock block is confirmed or the developer switch allows it; otherwise no clock control.
                if(monitor.clockSyncRunning || monitor.clockSyncOffered()) {
                    Button sync=button(monitor.clockSyncRunning?getString(R.string.main_sync_clock_running):getString(R.string.main_sync_clock),this::startClockSync,false);
                    sync.setEnabled(monitor.canClockSync()); add(active,sync,8);
                    add(active,text(getString(R.string.main_sync_clock_explanation),14,MUTED,false),6);
                }
            } else {
                Button clock=button(getString(R.string.main_set_monitor_time),this::clockDialog,false); clock.setEnabled(monitor.canTime&&!monitor.historyRunning&&!monitor.dumpRunning); add(active,clock,8);
            }
            add(active,button(getString(R.string.main_disconnect),monitor::disconnect,false),8); content.addView(active);
        } else if(!monitor.sessionFinished && !monitor.address.isEmpty() && !monitor.status.equals("Disconnected") && !monitor.status.equals("Action failed")) add(content,button(getString(R.string.main_cancel_connection),monitor::disconnect,false),10);
        LinearLayout setup=card(); label(setup,getString(R.string.main_find_monitor));
        add(setup,text(getString(R.string.main_find_instructions),15,MUTED,false),12);
        scanButton=button(monitor.scanning?getString(R.string.main_stop_scan):getString(R.string.main_scan),() -> { if(monitor.scanning) monitor.stopScan(); else withBluetooth(monitor::scan); },true);
        scanButton.setEnabled(!monitor.registrationReading&&!monitor.registrationPending&&!monitor.registrationWriting); add(setup,scanButton,10);
        content.addView(setup);
        discoveredList=new LinearLayout(this); discoveredList.setOrientation(LinearLayout.VERTICAL); content.addView(discoveredList); updateDiscovered();
        List<String[]> saved=store.devices();
        if(!saved.isEmpty()) add(content,text(getString(R.string.main_saved_monitors),13,TEAL,true),20);
        for(String[] d:saved) {
            LinearLayout item=card(); add(item,text(d[1],20,INK,true),0); add(item,text(getString(R.string.main_saved_monitor_detail,d[0],profileLabel(Integer.parseInt(d[3]))),13,MUTED,false),6);
            // The connected monitor's card above already carries the choice; one control per monitor.
            boolean activeChoice=monitor.offersImportUsers() && monitor.address.equals(d[0]);
            if(!activeChoice && MonitorService.offersImportUsers(d[2])) importUsersChoice(item,"import_users_"+d[0]+"_",parseUsers(d[4]),true,users -> { store.setImportUsers(d[0],users); monitor.refreshAutomaticImports(); render(); });
            add(item,button(getString(R.string.main_reconnect),() -> { monitor.user=Integer.parseInt(d[3]); monitor.modelHint=d[2]; connect(d[0],d[1],false); },true),10);
            add(item,button(getString(R.string.main_forget_monitor),() -> new AlertDialog.Builder(this).setTitle(getString(R.string.main_forget_title,d[1])).setMessage(getString(R.string.main_forget_explanation)).setNegativeButton(getString(R.string.main_cancel),null).setPositiveButton(getString(R.string.main_forget),(dialog,which) -> { if(monitor.address.equals(d[0])) monitor.disconnect(); new PairingKeys(this).forget(d[0]); new CompanionLinks(this).forget(d[0]); store.forget(d[0]); monitor.refreshAutomaticImports(); render(); }).show(),false),6); content.addView(item);
        }
        add(content,button(advancedExpanded?getString(R.string.main_hide_advanced):getString(R.string.main_advanced),() -> { advancedExpanded=!advancedExpanded; render(); },false),16);
        if(advancedExpanded) advancedDeviceControls();
    }
    /** Readings to import: User 1, User 2 or both, as toggle buttons with the current choice highlighted. */
    private void importUsersChoice(LinearLayout parent,String tag,int current,boolean enabled,java.util.function.IntConsumer choose) {
        add(parent,text(getString(R.string.main_import_users),14,MUTED,false),12);
        LinearLayout choices=row();
        for(int users=1;users<=3;users++) {
            final int value=users;
            Button choice=button(users==3?getString(R.string.main_import_users_both):profileLabel(users),() -> { if(value!=current) choose.accept(value); },users==current);
            choice.setTag(tag+users); choice.setSelected(users==current); choice.setEnabled(enabled);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1); if(users>1) lp.setMarginStart(dp(6));
            choices.addView(choice,lp);
        }
        add(parent,choices,6);
    }
    private static int parseUsers(String value) {
        try { int users=Integer.parseInt(value); return users>=1 && users<=3?users:3; } catch(RuntimeException ex) { return 3; }
    }
    private void readAllDialog() {
        if(monitor==null || !monitor.canReadAll() || monitor.historyRunning || monitor.dumpRunning) return;
        new AlertDialog.Builder(this).setTitle(getString(R.string.main_read_all)).setMessage(getString(R.string.main_read_all_explanation))
                .setNegativeButton(getString(R.string.main_cancel),null)
                .setPositiveButton(getString(R.string.main_read_all),(dialog,which) -> { if(monitor!=null) monitor.readAllHistory(); }).show();
    }
    private void advancedDeviceControls() {
        add(content,button(getString(R.string.http_title),() -> startActivity(new Intent(this,HttpForwardingSettingsActivity.class)),false),8);
        updateCard();
        LinearLayout information=card(); label(information,getString(R.string.main_monitor_information));
        add(information,button(getString(R.string.main_model_hint,monitor.modelHint),this::modelDialog,false),8);
        if(!monitor.information.isEmpty()) add(information,text(monitor.information.trim(),14,MUTED,false),8);
        if(monitor.ready) {
            add(information,text(getString(R.string.main_monitor_clock,monitorClock()),14,MUTED,false),8);
            Button readClock=button(getString(R.string.main_read_clock),monitor::readClock,false); readClock.setEnabled(monitor.canTime&&!monitor.historyRunning&&!monitor.dumpRunning&&!monitor.clockSyncRunning); add(information,readClock,6);
            if(monitor.profile.isX7()) {
                dumpReadButton=button(monitor.dumpRunning?getString(R.string.main_reading_memory):getString(R.string.main_read_memory),this::dumpDialog,false);
                dumpReadButton.setEnabled(monitor.canDump&&!monitor.historyRunning&&!monitor.dumpRunning&&!monitor.dumpSaving&&!dumpExportPreparing); add(information,dumpReadButton,8);
                add(information,text(getString(R.string.main_memory_read_only),13,MUTED,false),6);
            }
        }
        add(information,button(getString(R.string.main_export_diagnostics),() -> export(true),false),8); content.addView(information);
        File dumpFile=monitor.eepromDumpFile();
        if(monitor.dumpLength>0 || dumpFile!=null && dumpFile.isFile()) {
            LinearLayout dump=card(); label(dump,getString(R.string.main_memory_export));
            dumpProgress=text(dumpProgressText(),15,MUTED,false); add(dump,dumpProgress,10);
            dumpExportButton=button(getString(R.string.main_export_memory_json),this::exportDump,false);
            dumpExportButton.setEnabled(!monitor.dumpRunning&&!monitor.dumpSaving && dumpFile!=null && dumpFile.isFile()&&!dumpExportPreparing); add(dump,dumpExportButton,8);
            if(monitor.dumpRunning) add(dump,button(getString(R.string.main_cancel_disconnect),monitor::disconnect,false),8);
            content.addView(dump);
        }
        File backup=monitor.registrationBackupFile();
        if(backup!=null && backup.isFile()) {
            LinearLayout savedBackup=card(); label(savedBackup,getString(R.string.main_registration_backup));
            add(savedBackup,text(getString(R.string.main_backup_explanation),14,MUTED,false),10);
            Button export=button(getString(R.string.main_export_settings_backup),() -> exportJsonSnapshot(monitor.registrationBackupFile(),"open-pressure-settings-backup.json"),false);
            export.setEnabled(!dumpExportPreparing); add(savedBackup,export,8); content.addView(savedBackup);
        }
        File clockBackup=monitor.clockSyncBackupFile();
        if(clockBackup!=null && clockBackup.isFile()) {
            LinearLayout savedClock=card(); label(savedClock,getString(R.string.main_clock_sync_backup));
            add(savedClock,text(getString(R.string.main_clock_sync_backup_explanation),14,MUTED,false),10);
            Button export=button(getString(R.string.main_export_settings_backup),() -> exportJsonSnapshot(monitor.clockSyncBackupFile(),"open-pressure-clock-sync-settings-backup.json"),false);
            export.setEnabled(!dumpExportPreparing); add(savedClock,export,8); content.addView(savedClock);
        }
        if(DeveloperOptions.isUnlocked(this)) add(content,button(getString(R.string.dev_open),() -> startActivity(new Intent(this,DeveloperOptionsActivity.class)),false),16);
        TextView version=text(getString(R.string.dev_version_label,BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE),13,MUTED,false);
        version.setGravity(Gravity.CENTER); version.setPadding(0,dp(8),0,dp(8)); version.setOnClickListener(v -> developerTap());
        add(content,version,16);
    }
    /** Five quick taps on the version label unlock the hidden developer options; it never contacts a monitor. */
    private void developerTap() {
        if(DeveloperOptions.isUnlocked(this)) return;
        int remaining=developerTaps.tap(android.os.SystemClock.uptimeMillis());
        if(remaining>0) { if(remaining<=2) developerToast(getResources().getQuantityString(R.plurals.dev_taps_remaining,remaining,remaining)); return; }
        try { DeveloperOptions.setUnlocked(this,true); developerToast(getString(R.string.dev_unlocked)); }
        catch(IllegalStateException failed) { developerToast(getString(R.string.dev_save_failed)); }
        render();
    }
    /** Replaces the previous hint so quick taps do not queue a backlog of toasts. */
    private void developerToast(String message) {
        if(developerToast!=null) developerToast.cancel();
        developerToast=Toast.makeText(this,message,Toast.LENGTH_SHORT); developerToast.show();
    }
    /** After a Pair attempt with an untested monitor, ask once whether the user wants to help. */
    private void offerDeviceReport() {
        if(monitor==null || !uiVisible || isFinishing() || consentDialog!=null && consentDialog.isShowing()) return;
        if(deviceReportDialog!=null && deviceReportDialog.isShowing() || updateDialog!=null && updateDialog.isShowing()) return;
        if(monitor.offerReportAfterWrite && !monitor.clockSyncRunning) {
            // A clock write relying on the untested-model developer option reached an outcome: ask once.
            monitor.offerReportAfterWrite=false;
            if(monitor.deviceReport()==null) return;
            deviceReportDialog=new AlertDialog.Builder(this).setTitle(getString(R.string.device_report_title)).setMessage(getString(R.string.clock_sync_report_prompt))
                    .setNegativeButton(getString(R.string.device_report_not_now),null)
                    .setPositiveButton(getString(R.string.device_report_review),(d,w) -> reviewDeviceReport()).show();
            return;
        }
        if(!monitor.deviceReportPromptDue()) return;
        monitor.deviceReportPrompted();
        deviceReportDialog=new AlertDialog.Builder(this).setTitle(getString(R.string.device_report_title)).setMessage(getString(R.string.device_report_prompt))
                .setNegativeButton(getString(R.string.device_report_not_now),null)
                .setPositiveButton(getString(R.string.device_report_review),(d,w) -> reviewDeviceReport()).show();
    }
    private void reviewDeviceReport() {
        DeviceReport report=monitor==null?null:monitor.deviceReport();
        if(report==null) return;
        String json=report.toJson();
        LinearLayout form=form();
        add(form,text(getString(R.string.device_report_review_explanation,getString(R.string.device_report_copy_open)),15,MUTED,false),0);
        TextView body=text(json,12,INK,false); body.setTypeface(Typeface.MONOSPACE); body.setTextIsSelectable(true);
        // Report keys and values are technical; keep them left-to-right in RTL locales.
        body.setTextDirection(View.TEXT_DIRECTION_LTR); add(form,body,12);
        if(deviceReportDialog!=null) deviceReportDialog.dismiss();
        deviceReportDialog=new AlertDialog.Builder(this).setTitle(getString(R.string.device_report_review_title)).setView(wrap(form))
                .setNegativeButton(getString(R.string.main_close),null)
                .setNeutralButton(getString(R.string.device_report_save),(d,w) -> saveDeviceReport(json))
                .setPositiveButton(getString(R.string.device_report_copy_open),(d,w) -> shareDeviceReport(report.model(),json)).show();
    }
    private void shareDeviceReport(String model,String json) {
        ClipboardManager clipboard=getSystemService(ClipboardManager.class);
        if(clipboard!=null) clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.device_report_review_title),json));
        String label=model==null||model.isEmpty()?"unknown model":model;
        String url=ISSUES+"/new?template=device_report.yml&title="+android.net.Uri.encode("Device report: "+label)+"&model="+android.net.Uri.encode(label);
        try { startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url))); toast(getString(R.string.device_report_copied)); }
        catch(ActivityNotFoundException ex) { toast(getString(R.string.device_report_browser_missing,ISSUES)); }
    }
    private void saveDeviceReport(String json) {
        if(exportBusy()) return;
        pendingExportKind=5; pendingReportJson=json; refreshPdfExportButton();
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"open-pressure-device-report.json");
        try { startActivityForResult(intent,20); } catch(ActivityNotFoundException ex) { pendingExportKind=0; pendingReportJson=null; refreshPdfExportButton(); toast(getString(R.string.main_document_provider_missing)); }
    }
    private void offerUpdate() {
        if(isFinishing() || isDestroyed() || consentDialog!=null && consentDialog.isShowing() || deviceReportDialog!=null && deviceReportDialog.isShowing() || updateDialog!=null && updateDialog.isShowing()) return;
        UpdateCheck.Release release=UpdateCheck.available(this,BuildConfig.VERSION_NAME,System.currentTimeMillis());
        if(release==null) return;
        updateDialog=new AlertDialog.Builder(this).setTitle(getString(R.string.update_title)).setMessage(getString(R.string.update_message,release.version,BuildConfig.VERSION_NAME))
                .setPositiveButton(getString(R.string.update_download),(d,w) -> {
                    // Ask again later if the download page is left without installing.
                    UpdateCheck.remindLater(this,System.currentTimeMillis());
                    try { startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(release.url))); }
                    catch(ActivityNotFoundException ex) { toast(getString(R.string.update_browser_missing)); }
                })
                .setNeutralButton(getString(R.string.update_later),(d,w) -> UpdateCheck.remindLater(this,System.currentTimeMillis()))
                .setNegativeButton(getString(R.string.update_skip),(d,w) -> UpdateCheck.skip(this,release.version))
                .setOnCancelListener(d -> UpdateCheck.remindLater(this,System.currentTimeMillis())).show();
    }
    private void updateCard() {
        LinearLayout updates=card(); label(updates,getString(R.string.update_setting_title));
        add(updates,text(getString(R.string.update_setting_description),14,MUTED,false),10);
        boolean enabled=UpdateCheck.enabled(this);
        add(updates,button(getString(enabled?R.string.update_turn_off:R.string.update_turn_on),() -> { UpdateCheck.setEnabled(this,!enabled); if(!enabled) UpdateCheck.checkIfDue(this,this::offerUpdate); render(); },false),8);
        content.addView(updates);
    }
    private void automaticCard() {
        LinearLayout card=card(); label(card,getString(R.string.main_automatic_imports));
        automaticStatusText=text(monitor==null?getString(R.string.main_automatic_description):MonitorUi.automatic(this,monitor),15,MUTED,false);
        add(card,automaticStatusText,10);
        add(card,button(getString(R.string.main_automatic_settings),() -> startActivity(new Intent(this,AutoImportSettingsActivity.class)),false),8);
        content.addView(card);
    }
    private void statusCard() {
        LinearLayout status=card(); statusHeading=text("",12,TEAL,true); statusHeading.setLetterSpacing(0.1f); status.addView(statusHeading);
        statusText=text("",21,INK,true); add(status,statusText,10);
        statusDetail=text("",15,MUTED,false); add(status,statusDetail,7); content.addView(status);
        if(monitor==null) { setText(statusHeading,getString(R.string.main_connection)); setText(statusText,getString(R.string.main_starting)); } else updateStatus();
    }
    private void validateProfileSelection() {
        boolean enabled=store.virtualUsersEnabled();
        if(enabled!=virtualMode) { virtualMode=enabled; filterUser=getPreferences(MODE_PRIVATE).getInt(virtualMode?"selected_virtual_profile":"selected_profile",0); }
        boolean valid=filterUser==0;
        if(virtualMode) { for(VirtualUser user:store.virtualUsers()) if(filterUser==-user.id) valid=true; }
        else valid=filterUser>=0 && filterUser<=2;
        if(!valid) { filterUser=0; saveProfileSelection(); }
    }
    private void saveProfileSelection() { getPreferences(MODE_PRIVATE).edit().putInt(virtualMode?"selected_virtual_profile":"selected_profile",filterUser).apply(); }
    private List<Integer> profileScopes() {
        List<Integer> scopes=new ArrayList<>(); scopes.add(0);
        if(virtualMode) { for(VirtualUser profile:store.virtualUsers()) scopes.add(-profile.id); }
        else { scopes.add(1); scopes.add(2); }
        return scopes;
    }
    private String[] profileLabels(List<Integer> scopes) { String[] labels=new String[scopes.size()]; for(int i=0;i<labels.length;i++) labels[i]=profileLabel(scopes.get(i)); return labels; }
    private void filters(boolean dates) {
        if(virtualMode) {
            Button selected=button(profileLabel(filterUser),() -> {
                List<Integer> scopes=profileScopes(); String[] labels=profileLabels(scopes);
                new AlertDialog.Builder(this).setTitle(R.string.main_choose_user).setSingleChoiceItems(labels,scopes.indexOf(filterUser),(dialog,index) -> {
                    filterUser=scopes.get(index); saveProfileSelection(); dialog.dismiss(); historyLimit=100; render();
                }).setNegativeButton(R.string.main_cancel,null).show();
            },true);
            selected.setTag("virtual_profile_filter"); add(content,selected,10);
        } else {
            LinearLayout bank=row();
            for(int i=0;i<3;i++) { final int user=i; bank.addView(button(profileLabel(i),() -> { filterUser=user; saveProfileSelection(); historyLimit=100; render(); },filterUser==i),new LinearLayout.LayoutParams(0,-2,1)); }
            add(content,bank,10);
        }
        if(dates) {
            LinearLayout range=row(); int[] options={0,7,30}; String[] labels={getString(R.string.main_all_time),getString(R.string.main_seven_days),getString(R.string.main_thirty_days)};
            for(int i=0;i<3;i++) { final int n=options[i]; range.addView(button(labels[i],() -> { days=n; render(); },days==n),new LinearLayout.LayoutParams(0,-2,1)); } add(content,range,6);
        }
    }
    private List<Reading> visibleReadings() {
        List<Reading> result=store.readings(filterUser);
        if(tab==1 && days>0) result.removeIf(r -> r.time==null || r.displayTime().isBefore(LocalDateTime.now().minusDays(days)));
        return result;
    }
    private void readingDetail(Reading r) {
        LinearLayout form=form(); add(form,text(pressureValues(r.sysMmHg(),r.diaMmHg(),true),30,INK,true),0);
        add(form,text(getString(R.string.main_reading_identity,date(r),readingName(r),userLabel(r)),14,MUTED,false),12);
        add(form,text(r.time==null?getString(R.string.main_receipt_time_note):getString(R.string.main_monitor_time_note),14,MUTED,false),6);
        add(form,text(getString(R.string.main_pulse_detail,pulseLabel(r)),14,MUTED,false),12);
        add(form,text(getString(R.string.main_afib_detail,r.afib==null?getString(R.string.main_unavailable):r.afib?getString(R.string.main_afib_flagged):getString(R.string.main_afib_not_flagged)),14,MUTED,false),6);
        List<String> flags=MonitorUi.readingFlags(this,r.status);
        if(!flags.isEmpty()) add(form,text(String.join(" · ",flags),14,MUTED,false),6);
        if(r.map!=null) add(form,text(getString(R.string.main_mean_pressure,number(r.map),r.unit),14,MUTED,false),6);
        add(form,text(getString(R.string.main_source,"manual".equals(r.source)?getString(R.string.main_source_manual):getString(R.string.main_source_monitor)),14,MUTED,false),6);
        EditText notes=input(getString(R.string.main_notes),r.notes,false); add(form,notes,12);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(getString(R.string.main_reading_details)).setView(wrap(form)).setNegativeButton(getString(R.string.main_close),null).setNeutralButton(getString(R.string.main_delete),(d,w) -> new AlertDialog.Builder(this).setTitle(getString(R.string.main_delete_title)).setMessage(getString(R.string.main_delete_explanation)).setNegativeButton(getString(R.string.main_cancel),null).setPositiveButton(getString(R.string.main_delete),(x,y) -> { store.delete(r.id); render(); }).show()).setPositiveButton(getString(R.string.main_save_notes),(d,w) -> { store.note(r.id,notes.getText().toString()); render(); }).create(); dialog.show();
    }
    private void manualEntry() {
        LinearLayout form=form(); EditText sys=input(getString(R.string.main_systolic_hint),"",true),dia=input(getString(R.string.main_diastolic_hint),"",true),pulse=input(getString(R.string.main_pulse_hint),"",true);
        EditText bank=input(getString(R.string.main_user_hint),filterUser==2?"2":"1",true),time=input(getString(R.string.main_date_time_hint),editableDateTime(LocalDateTime.now().withSecond(0).withNano(0)),false),notes=input(getString(R.string.main_notes),"",false);
        final boolean virtual=store.virtualUsersEnabled();
        for(EditText field:virtual?new EditText[]{sys,dia,pulse,time,notes}:new EditText[]{sys,dia,pulse,bank,time,notes}) add(form,field,8);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(getString(R.string.main_add_reading)).setView(wrap(form)).setNegativeButton(getString(R.string.main_cancel),null).setPositiveButton(getString(R.string.main_save),null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int user=virtual?0:Integer.parseInt(bank.getText().toString()); if(!virtual && (user<1||user>2)) throw new IllegalArgumentException("Choose user 1 or 2");
                String p=pulse.getText().toString().trim(); store.addManual(parseNumber(sys.getText().toString()),parseNumber(dia.getText().toString()),p.isEmpty()?null:parseNumber(p),user,parseDateTime(time.getText().toString().trim()),notes.getText().toString()); dialog.dismiss(); render();
                HttpForwardingSettings.Config forwarding=HttpForwardingSettings.get(this);
                if(!virtual && forwarding.enabled() && !forwarding.keepLocal) toast(getString(R.string.http_queued));
            } catch(Exception ex) { toast(getString(R.string.main_entry_invalid,editableDateTime(LocalDateTime.now()))); }
        })); dialog.show();
    }
    private void userDialog() {
        LinearLayout form=form(); add(form,text(getString(R.string.main_user_consent_explanation),15,MUTED,false),0);
        EditText bank=input(getString(virtualMode?R.string.vp_monitor_bank_hint:R.string.main_user_bank_hint),String.valueOf(monitor.user),true),code=input(getString(R.string.main_consent_code_hint),String.valueOf(monitor.consentCode),true); add(form,bank,12); add(form,code,8);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(getString(R.string.main_monitor_user)).setView(wrap(form)).setNegativeButton(getString(R.string.main_cancel),null).setNeutralButton(getString(R.string.main_register),null).setPositiveButton(getString(R.string.main_authenticate),null).create();
        dialog.setOnShowListener(x -> {
            View.OnClickListener action=v -> { try {
                int user=Integer.parseInt(bank.getText().toString()), consent=Integer.parseInt(code.getText().toString());
                if(user<1||user>2||consent<0||consent>9999) throw new IllegalArgumentException();
                boolean register=v==dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                if(register) new AlertDialog.Builder(this).setTitle(getString(R.string.main_register_title,profileLabel(user))).setMessage(getString(R.string.main_register_explanation)).setNegativeButton(getString(R.string.main_cancel),null).setPositiveButton(getString(R.string.main_register),(a,b) -> { monitor.authenticate(user,consent,true); dialog.dismiss(); }).show();
                else { monitor.authenticate(user,consent,false); dialog.dismiss(); }
            } catch(IllegalArgumentException ex) { toast(getString(R.string.main_user_invalid)); } };
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(action); dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(action);
        }); dialog.show();
    }
    private void historyDialog() {
        if(monitor==null) return;
        if(monitor.profile==org.openpressure.protocol.ProtocolProfile.VENDOR_MEMORY) {
            monitor.syncHistory(-1);
            return;
        }
        if(monitor.profile.isX7()) {
            new AlertDialog.Builder(this).setTitle(getString(R.string.main_import_x7_title)).setMessage(getString(R.string.main_import_x7_explanation))
                    .setNegativeButton(getString(R.string.main_cancel),null).setPositiveButton(getString(R.string.main_import),(dialog,which) -> { if(monitor!=null) monitor.syncHistory(-1); }).show();
            return;
        }
        LinearLayout form=form(); add(form,text(getString(R.string.main_import_standard_explanation),15,MUTED,false),0);
        EditText sequence=input(getString(R.string.main_sequence_hint),"",true); add(form,sequence,12);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(getString(R.string.main_import_standard_title)).setView(wrap(form)).setNegativeButton(getString(R.string.main_cancel),null).setPositiveButton(getString(R.string.main_import),null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { try {
            String value=sequence.getText().toString().trim(); int from=value.isEmpty()?-1:Integer.parseInt(value); if(from < -1 || from>65535) throw new IllegalArgumentException();
            monitor.syncHistory(from); dialog.dismiss();
        } catch(IllegalArgumentException ex) { toast(getString(R.string.main_sequence_invalid)); } })); dialog.show();
    }
    private String dumpProgressText() {
        if(!monitor.dumpRunning && monitor.dumpSaving) return getString(R.string.main_memory_saving);
        if(monitor.dumpLength<=0) return getString(R.string.main_memory_available);
        String address=String.format(Locale.ROOT,"0x%04X",monitor.dumpStart);
        String state=monitor.dumpRunning?getString(R.string.main_memory_reading):monitor.dumpComplete?getString(R.string.main_memory_complete):getString(R.string.main_memory_incomplete);
        return getString(R.string.main_memory_progress,address,number(monitor.dumpReceived),number(monitor.dumpLength),state);
    }
    private void dumpDialog() {
        if(monitor==null) return;
        LinearLayout form=form();
        add(form,text(getString(R.string.main_memory_read_explanation),15,MUTED,false),0);
        EditText address=input(getString(R.string.main_memory_address_hint),"0010",false),length=input(getString(R.string.main_memory_length_hint),"68",true);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        length.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        add(form,address,12); add(form,length,8);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(getString(R.string.main_memory_read_title)).setView(wrap(form)).setNegativeButton(getString(R.string.main_cancel),null).setPositiveButton(getString(R.string.main_memory_read_confirm),null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String raw=address.getText().toString().trim(); if(raw.startsWith("0x")||raw.startsWith("0X")) raw=raw.substring(2);
                if(!raw.matches("[0-9a-fA-F]{1,4}")) throw new IllegalArgumentException();
                int start=Integer.parseInt(raw,16),count=Integer.parseInt(length.getText().toString().trim());
                if(count<1 || count>65536 || start+count>65536) throw new IllegalArgumentException();
                if(monitor==null || !monitor.canDump || monitor.dumpRunning || monitor.dumpSaving || monitor.historyRunning || dumpExportPreparing) { toast(getString(R.string.main_memory_idle_required)); return; }
                monitor.dumpEeprom(start,count); dialog.dismiss();
            } catch(IllegalArgumentException ex) { toast(getString(R.string.main_memory_range_invalid)); }
        })); dialog.show();
    }
    private void exportDump() {
        if(monitor==null || monitor.dumpRunning || monitor.dumpSaving || dumpExportPreparing) return;
        File source=monitor.eepromDumpFile();
        if(source==null || !source.isFile()) { toast(getString(R.string.main_memory_missing)); return; }
        exportJsonSnapshot(source,"open-pressure-eeprom.json");
    }
    private void exportJsonSnapshot(File source,String title) {
        if(exportBusy()) return;
        if(source==null || !source.isFile() || dumpExportPreparing) { toast(getString(R.string.main_backup_missing)); return; }
        // Snapshot before opening the picker: a later transfer may replace the service's file.
        File snapshot=new File(getCacheDir(),"eeprom-export-"+UUID.randomUUID()+".json");
        dumpExportPreparing=true; refreshPdfExportButton(); if(dumpReadButton!=null) dumpReadButton.setEnabled(false); if(dumpExportButton!=null) dumpExportButton.setEnabled(false);
        new Thread(() -> {
            try {
                Files.copy(source.toPath(),snapshot.toPath());
                handler.post(() -> {
                    if(isDestroyed() || isFinishing()) { deleteExportSnapshot(snapshot.getAbsolutePath()); return; }
                    finishDumpPreparation();
                    pendingExportKind=3; pendingDumpExportPath=snapshot.getAbsolutePath();
                    refreshPdfExportButton();
                    Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,title);
                    try { startActivityForResult(intent,20); }
                    catch(ActivityNotFoundException ex) { pendingExportKind=0; pendingDumpExportPath=null; deleteExportSnapshot(snapshot.getAbsolutePath()); refreshPdfExportButton(); toast(getString(R.string.main_document_provider_missing)); }
                });
            } catch(Exception ex) { snapshot.delete(); handler.post(() -> { if(!isDestroyed()) { finishDumpPreparation(); toast(getString(R.string.main_memory_prepare_failed)); } }); }
        },"eeprom-export-snapshot").start();
    }
    private void finishDumpPreparation() {
        dumpExportPreparing=false;
        refreshPdfExportButton();
        if(monitor==null) return;
        if(dumpReadButton!=null) dumpReadButton.setEnabled(monitor.canDump&&!monitor.dumpRunning&&!monitor.dumpSaving&&!monitor.historyRunning);
        File file=monitor.eepromDumpFile();
        if(dumpExportButton!=null) dumpExportButton.setEnabled(!monitor.dumpRunning&&!monitor.dumpSaving&&file!=null&&file.isFile());
    }
    private void registerX7Dialog(int bank) {
        new AlertDialog.Builder(this).setTitle(getString(R.string.main_register_title,profileLabel(bank)))
                .setMessage(getString(R.string.main_x7_register_explanation))
                .setNegativeButton(getString(R.string.main_cancel),null).setPositiveButton(getString(R.string.main_register_user,profileLabel(bank)),(dialog,which) -> {
                    if(monitor!=null) { monitor.registerX7User(bank); render(); }
                }).show();
    }
    private void startClockSync() {
        if(monitor!=null && monitor.canClockSync()) monitor.beginClockSync();
        render();
    }
    /** Shows the clock-sync preview once while it waits; closes it when the service ends the preview. */
    private void offerClockSync() {
        if(monitor==null || !monitor.clockSyncPending) {
            clockSyncPrompted=false;
            if(clockSyncDialog!=null) { clockSyncDialog.dismiss(); clockSyncDialog=null; }
            return;
        }
        if(clockSyncPrompted || !uiVisible || isFinishing() || isDestroyed() || consentDialog!=null && consentDialog.isShowing()) return;
        clockSyncPrompted=true;
        clockSyncConfirmation();
    }
    private void clockSyncConfirmation() {
        LocalDateTime monitorClock=monitor.clockSyncMonitorClock,phone=monitor.clockSyncPhoneClock;
        String prefill=editableDateTime((phone==null?LocalDateTime.now():phone).withNano(0));
        LinearLayout form=form(); add(form,text(getString(R.string.main_sync_clock_explanation),15,MUTED,false),0);
        add(form,text(monitorClock==null?getString(R.string.clock_sync_monitor_time_unset):getString(R.string.clock_sync_monitor_time,editableDateTime(monitorClock)),15,INK,false),12);
        if(phone!=null) add(form,text(getString(R.string.clock_sync_phone_time,prefill),15,INK,false),6);
        long drift=monitor.clockSyncDriftSeconds;
        if(monitorClock!=null && drift!=Long.MAX_VALUE) {
            long difference=drift==Long.MIN_VALUE?Long.MAX_VALUE:Math.abs(drift);
            int seconds=(int)Math.min(difference,Integer.MAX_VALUE);
            add(form,text(getResources().getQuantityString(R.plurals.clock_sync_drift,seconds,seconds),15,INK,false),6);
            if(difference<=60) add(form,text(getString(R.string.clock_sync_within_threshold),14,MUTED,false),10);
        }
        EditText chosen=null;
        // The service read the untested-write developer option for this preview and checks it again on confirmation.
        if(monitor.clockSyncOverride) {
            add(form,text(getString(R.string.clock_sync_untested_warning),14,INK,true),12);
            add(form,text(getString(R.string.clock_sync_choose_time),14,MUTED,false),12);
            chosen=input(getString(R.string.main_date_time_hint),prefill,false); add(form,chosen,6);
        }
        final EditText time=chosen;
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(getString(R.string.clock_sync_title)).setView(wrap(form))
                .setNegativeButton(getString(R.string.main_cancel),(d,w) -> { if(monitor!=null) monitor.cancelClockSync(); })
                .setPositiveButton(getString(R.string.clock_sync_confirm),null).create();
        dialog.setOnCancelListener(d -> { if(monitor!=null) monitor.cancelClockSync(); });
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            LocalDateTime value=null;
            String entered=time==null?prefill:time.getText().toString().trim();
            // An untouched field means the phone time at confirmation, not the older preview time.
            if(!entered.equals(prefill)) {
                try { value=parseDateTime(entered); }
                catch(RuntimeException invalid) { toast(getString(R.string.main_date_invalid,prefill)); return; }
            }
            if(monitor==null) { dialog.dismiss(); return; }
            monitor.confirmClockSync(value);
            if(monitor.clockSyncPending) { toast(getString(R.string.main_date_invalid,prefill)); return; }
            dialog.dismiss(); if(clockSyncDialog==dialog) clockSyncDialog=null;
            render();
        }));
        clockSyncDialog=dialog; dialog.show();
    }
    private void clockDialog() {
        LinearLayout form=form(); add(form,text(getString(R.string.main_clock_explanation),15,MUTED,false),0);
        EditText time=input(getString(R.string.main_date_time_hint),editableDateTime(LocalDateTime.now().withNano(0)),false); add(form,time,12);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(getString(R.string.main_set_monitor_time)).setView(wrap(form)).setNegativeButton(getString(R.string.main_cancel),null).setNeutralButton(getString(R.string.main_use_phone_time),(d,w) -> monitor.setClock(LocalDateTime.now())).setPositiveButton(getString(R.string.main_set_time),null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { try { monitor.setClock(parseDateTime(time.getText().toString().trim())); dialog.dismiss(); } catch(Exception ex) { toast(getString(R.string.main_date_invalid,editableDateTime(LocalDateTime.now()))); } })); dialog.show();
    }
    private void modelDialog() {
        String[] models={"X7 Smart AFib · HEM-7380T1","M7 Intelli IT AFib · HEM-7380T1","X7 / M7 Smart · HEM-7361T","Evolv · HEM-7600T","M4 / X4 · HEM-7155T","HEM-7156T","M700 Intelli IT · HEM-7322T","BP7250 · HEM-7150T","BP7450 · HEM-7342T","BP5360 · HEM-7377T1","HeartGuide · HEM-6410T","RS7 · HEM-6232T","Complete · HEM-7530T",getString(R.string.main_other_monitor)};
        new AlertDialog.Builder(this).setTitle(getString(R.string.main_model_hint_title)).setItems(models,(d,w) -> { monitor.modelHint=models[w]; render(); }).setNegativeButton(getString(R.string.main_cancel),null).show();
    }
    private void connect(String address,String name,boolean pair) {
        withBluetooth(() -> {
            monitor.connect(address,name,pair);
            if(pair) {
                tab=2; scroll.scrollTo(0,0); render();
                scroll.post(() -> scroll.scrollTo(0,0));
            }
        });
    }
    private void withBluetooth(Runnable action) {
        if(!ExperimentalConsent.accepted(this)) { ensureExperimentalConsent(); return; }
        List<String> missing=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=31) { for(String p:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}) if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED) missing.add(p); }
        else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]),10); return; }
        NewReadingNotifications.requestPermissionIfNeeded(this);
        android.bluetooth.BluetoothManager manager=getSystemService(android.bluetooth.BluetoothManager.class);
        if(manager==null||manager.getAdapter()==null) { toast(getString(R.string.main_bluetooth_unavailable)); return; }
        if(!manager.getAdapter().isEnabled()) { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return; }
        action.run();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) { super.onRequestPermissionsResult(request,permissions,results); if(request==10) toast(getString(R.string.main_bluetooth_permission_granted)); }
    private void export(boolean diagnostics) {
        if(exportBusy()) return;
        if(diagnostics) { new AlertDialog.Builder(this).setTitle(getString(R.string.main_diagnostics_title)).setMessage(getString(R.string.main_diagnostics_explanation)).setNegativeButton(getString(R.string.main_cancel),null).setPositiveButton(getString(R.string.main_export),(d,w) -> createExport(true)).show(); }
        else createExport(false);
    }
    private void createExport(boolean diagnostics) {
        if(exportBusy()) return;
        pendingExportKind=diagnostics?2:1; pendingExportUser=filterUser; pendingExportDays=tab==1?days:0;
        refreshPdfExportButton();
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv").putExtra(Intent.EXTRA_TITLE,diagnostics?"open-pressure-diagnostics.csv":"open-pressure-readings.csv");
        try { startActivityForResult(intent,20); } catch(ActivityNotFoundException ex) { pendingExportKind=0; refreshPdfExportButton(); toast(getString(R.string.main_document_provider_missing)); }
    }
    private boolean exportBusy() {
        return pendingExportKind!=0 || dumpExportPreparing || pdfExportPreparing || exportWriting || (pdfDialog!=null && pdfDialog.isShowing());
    }
    private Button pdfExportButton() {
        pdfExportButton=button(getString(pdfExportPreparing?R.string.pdf_preparing:R.string.pdf_export),this::exportPdfDialog,false);
        pdfExportButton.setEnabled(!exportBusy()); return pdfExportButton;
    }
    private void refreshPdfExportButton() {
        if(pdfExportButton!=null) {
            pdfExportButton.setText(getString(pdfExportPreparing?R.string.pdf_preparing:R.string.pdf_export));
            pdfExportButton.setEnabled(!exportBusy());
        }
    }
    private void exportPdfDialog() {
        if(exportBusy()) return;
        // The report scope belongs to this dialog, even if the selected profile changes later.
        final int selectedUser=filterUser;
        LocalDate today=LocalDate.now(); final LocalDate[] range={today.minusDays(6),today};
        LinearLayout form=form();
        add(form,text(profileLabel(selectedUser),17,INK,true),0);
        add(form,text(getString(R.string.pdf_range_help),14,MUTED,false),8);
        RadioGroup modes=new RadioGroup(this); modes.setOrientation(RadioGroup.VERTICAL);
        RadioButton all=new RadioButton(this); all.setId(View.generateViewId()); all.setText(getString(R.string.pdf_range_all)); modes.addView(all);
        RadioButton custom=new RadioButton(this); custom.setId(View.generateViewId()); custom.setText(getString(R.string.pdf_range_custom)); modes.addView(custom); all.setChecked(true); add(form,modes,12);
        DateTimeFormatter dates=DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(getResources().getConfiguration().getLocales().get(0));
        Button from=button(getString(R.string.pdf_from,range[0].format(dates)),() -> {},false);
        Button to=button(getString(R.string.pdf_to,range[1].format(dates)),() -> {},false);
        Button[] buttons={from,to}; int[] labels={R.string.pdf_from,R.string.pdf_to};
        TextView error=text(getString(R.string.pdf_range_invalid),14,0xffa12828,false); error.setVisibility(View.GONE);
        for(int i=0;i<buttons.length;i++) {
            final int index=i;
            buttons[i].setEnabled(false);
            buttons[i].setOnClickListener(view -> {
                LocalDate initial=range[index];
                new DatePickerDialog(this,(picker,year,month,day) -> {
                    range[index]=LocalDate.of(year,month+1,day);
                    if(!range[0].isAfter(range[1])) error.setVisibility(View.GONE);
                    buttons[index].setText(getString(labels[index],range[index].format(dates)));
                },initial.getYear(),initial.getMonthValue()-1,initial.getDayOfMonth()).show();
            });
            add(form,buttons[i],8);
        }
        modes.setOnCheckedChangeListener((group,id) -> { boolean enabled=id==custom.getId(); from.setEnabled(enabled); to.setEnabled(enabled); if(!enabled) error.setVisibility(View.GONE); });
        add(form,error,8);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(getString(R.string.pdf_dialog_title)).setView(wrap(form))
                .setNegativeButton(getString(R.string.main_cancel),null).setPositiveButton(getString(R.string.main_export),null).create();
        pdfDialog=dialog;
        dialog.setOnDismissListener(ignored -> { if(pdfDialog==dialog) pdfDialog=null; refreshPdfExportButton(); });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            boolean customRange=custom.isChecked();
            if(customRange && range[0].isAfter(range[1])) { error.setVisibility(View.VISIBLE); return; }
            dialog.dismiss();
            preparePdfExport(selectedUser,customRange?range[0]:null,customRange?range[1]:null);
        }));
        dialog.show(); refreshPdfExportButton();
    }
    private void preparePdfExport(int selectedUser,LocalDate from,LocalDate to) {
        if(exportBusy()) return;
        pdfExportPreparing=true; final int token=++pdfPreparationToken; refreshPdfExportButton();
        // A detached configuration keeps report language stable while the picker is open.
        Context reportContext=getApplicationContext().createConfigurationContext(new android.content.res.Configuration(getResources().getConfiguration()));
        ZoneId zone=ZoneId.systemDefault();
        final boolean virtual=store.virtualUsersEnabled();
        final Map<Integer,String> profileNames=new LinkedHashMap<>();
        if(virtual) for(VirtualUser profile:store.virtualUsers()) profileNames.put(-profile.id,profile.name);
        pdfPreparationWorker=new Thread(() -> {
            File snapshot=null;
            try {
                PdfReportData report=virtual?PdfReportData.createVirtual(store.readings(selectedUser),selectedUser,from,to,zone,profileNames):PdfReportData.create(store.readings(selectedUser),selectedUser,from,to,zone);
                if(report.entries.isEmpty()) {
                    handler.post(() -> finishPdfPreparation(token,R.string.pdf_no_readings)); return;
                }
                snapshot=File.createTempFile("pdf-export-",".pdf",reportContext.getCacheDir());
                try(OutputStream out=new FileOutputStream(snapshot)) { PdfReportWriter.write(reportContext,report,out); }
                final String path=snapshot.getAbsolutePath();
                handler.post(() -> {
                    if(token!=pdfPreparationToken || isDestroyed() || isFinishing()) { deleteExportSnapshot(path); return; }
                    pdfPreparationWorker=null; pdfExportPreparing=false; pendingExportKind=4; pendingPdfExportPath=path; refreshPdfExportButton();
                    Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf").putExtra(Intent.EXTRA_TITLE,"open-pressure-readings.pdf");
                    try { startActivityForResult(intent,20); }
                    catch(ActivityNotFoundException ex) {
                        pendingExportKind=0; pendingPdfExportPath=null; deleteExportSnapshot(path); refreshPdfExportButton(); toast(getString(R.string.main_document_provider_missing));
                    } catch(RuntimeException ex) {
                        pendingExportKind=0; pendingPdfExportPath=null; deleteExportSnapshot(path); refreshPdfExportButton(); toast(getString(R.string.pdf_failed));
                    }
                });
            } catch(Exception ex) {
                if(snapshot!=null) snapshot.delete();
                handler.post(() -> finishPdfPreparation(token,R.string.pdf_failed));
            }
        },"pdf-export-snapshot");
        pdfPreparationWorker.start();
    }
    private void finishPdfPreparation(int token,int message) {
        if(token!=pdfPreparationToken || isDestroyed() || isFinishing()) return;
        pdfPreparationWorker=null; pdfExportPreparing=false; refreshPdfExportButton(); toast(getString(message));
    }
    private void deleteExportSnapshot(String path) {
        if(path!=null) new Thread(() -> new File(path).delete(),"delete-export-snapshot").start();
    }
    @Override public void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=20) return;
        int kind=pendingExportKind,exportUser=pendingExportUser,exportDays=pendingExportDays; pendingExportKind=0;
        String dumpPath=pendingDumpExportPath; pendingDumpExportPath=null;
        String pdfPath=pendingPdfExportPath; pendingPdfExportPath=null;
        String reportJson=pendingReportJson; pendingReportJson=null;
        if(result!=RESULT_OK || data==null || data.getData()==null) { deleteExportSnapshot(dumpPath); deleteExportSnapshot(pdfPath); refreshPdfExportButton(); return; }
        if(kind==0) { deleteExportSnapshot(dumpPath); deleteExportSnapshot(pdfPath); refreshPdfExportButton(); toast(getString(R.string.main_export_interrupted)); return; }
        exportWriting=true; refreshPdfExportButton();
        android.net.Uri destination=data.getData();
        new Thread(() -> {
            String message;
            try(OutputStream out=getContentResolver().openOutputStream(destination,"wt")) {
                if(out==null) throw new IOException();
                if(kind==3 || kind==4) {
                    String snapshotPath=kind==4?pdfPath:dumpPath;
                    if(snapshotPath==null) throw new IOException("Missing export snapshot");
                    try(InputStream input=new FileInputStream(snapshotPath)) {
                        byte[] buffer=new byte[8192]; int count;
                        while((count=input.read(buffer))!=-1) out.write(buffer,0,count);
                    }
                } else if(kind==5) {
                    if(reportJson==null) throw new IOException("Missing device report");
                    out.write(reportJson.getBytes(StandardCharsets.UTF_8));
                } else {
                    String payload=kind==2?store.diagnostics():store.export(exportUser,exportDays);
                    out.write(payload.getBytes(StandardCharsets.UTF_8));
                }
                message=getString(kind==5?R.string.device_report_saved:kind==4?R.string.pdf_exported:kind==3?R.string.main_memory_exported:R.string.main_csv_exported);
            } catch(Exception ex) { message=getString(kind==5?R.string.device_report_save_failed:kind==4?R.string.pdf_failed:kind==3?R.string.main_memory_write_failed:R.string.main_csv_write_failed); }
            finally { if(dumpPath!=null) new File(dumpPath).delete(); if(pdfPath!=null) new File(pdfPath).delete(); }
            String resultMessage=message; handler.post(() -> { exportWriting=false; if(!isDestroyed()) { refreshPdfExportButton(); toast(resultMessage); } });
        },"document-export").start();
    }
    private void about() {
        new AlertDialog.Builder(this).setTitle(getString(R.string.main_about_title,BuildConfig.VERSION_NAME))
                .setMessage(getString(R.string.main_about_message)+"\n\n"+getString(R.string.update_about,getString(R.string.main_devices),getString(R.string.main_advanced)))
                .setNeutralButton(getString(Build.VERSION.SDK_INT>=33?R.string.monitor_app_language:R.string.monitor_phone_language),(dialog,which) -> {
                    Intent intent=Build.VERSION.SDK_INT>=33
                            ?new Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS).setData(android.net.Uri.parse("package:"+getPackageName()))
                            :new Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS);
                    try { startActivity(intent); }
                    catch(ActivityNotFoundException unavailable) { startActivity(new Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS)); }
                }).setPositiveButton(getString(R.string.main_close),null).show();
    }
    private void title(String title,String subtitle) { add(content,text(title,34,INK,true),4); add(content,text(subtitle,15,MUTED,false),12); }
    private void label(LinearLayout parent,String value) { TextView t=text(value,12,TEAL,true); t.setLetterSpacing(0.1f); parent.addView(t); }
    private LinearLayout card() { LinearLayout c=form(); c.setBackground(background(Color.WHITE,BORDER)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.topMargin=dp(16); c.setLayoutParams(lp); return c; }
    private LinearLayout form() { LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(20),dp(20),dp(20),dp(20)); return c; }
    private LinearLayout row() { LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    private TextView text(String value,int size,int color,boolean bold) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(dp(2),1); if(bold) t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return t; }
    private Button button(String value,Runnable action,boolean primary) { Button b=new Button(this); b.setText(value); b.setTextSize(14); b.setAllCaps(false); b.setSingleLine(false); b.setMaxLines(Integer.MAX_VALUE); b.setMinWidth(0); b.setMinimumWidth(0); b.setTextColor(primary?Color.WHITE:TEAL); b.setMinHeight(dp(48)); b.setPadding(dp(12),dp(8),dp(12),dp(8)); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?TEAL:0xffe9efea)); b.setOnClickListener(v -> action.run()); return b; }
    private EditText input(String hint,String value,boolean numeric) { EditText e=new EditText(this); e.setHint(hint); e.setText(value); e.setTextSize(16); e.setSingleLine(true); e.setMinHeight(dp(52)); e.setInputType(numeric?android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL:android.text.InputType.TYPE_CLASS_TEXT); return e; }
    private ScrollView wrap(LinearLayout form) { ScrollView s=new ScrollView(this); s.addView(form); return s; }
    private GradientDrawable background(int fill,int stroke) { GradientDrawable d=new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(20)); d.setStroke(dp(1),stroke); return d; }
    private void add(LinearLayout parent,View child,int top) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(top); parent.addView(child,p); }
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
    /** Keep systolic/diastolic order in RTL text, including Arabic numeral runs. */
    private String pressureValues(double systolic,double diastolic,boolean unit) {
        String sys="\u2066"+number(systolic)+"\u2069",dia="\u2066"+number(diastolic)+"\u2069";
        return "\u2066"+getString(unit?R.string.main_pressure_unit:R.string.main_pressure_values,sys,dia)+"\u2069";
    }
    private String number(double value) { NumberFormat format=NumberFormat.getNumberInstance(getResources().getConfiguration().getLocales().get(0)); format.setMaximumFractionDigits(0); return format.format(value); }
    private String date(Reading r) { String formatted=r.displayTime().format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withLocale(getResources().getConfiguration().getLocales().get(0))); return r.time==null?getString(R.string.main_received_time,formatted):formatted; }
    private DateTimeFormatter editableDateFormatter() { return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT,FormatStyle.MEDIUM).withLocale(getResources().getConfiguration().getLocales().get(0)); }
    private String editableDateTime(LocalDateTime value) { return value.format(editableDateFormatter()); }
    private LocalDateTime parseDateTime(String value) {
        try { return LocalDateTime.parse(value,editableDateFormatter()); }
        catch(java.time.format.DateTimeParseException ignored) { return LocalDateTime.parse(value); }
    }
    private double parseNumber(String value) {
        return org.openpressure.app.ui.LocalizedInput.number(value,getResources().getConfiguration().getLocales().get(0));
    }
    private String readingName(Reading r) { return "manual".equals(r.source)?getString(R.string.main_source_manual):r.name; }
    private String monitorClock() {
        try { return editableDateTime(LocalDateTime.parse(monitor.deviceTime.replace(' ','T'))); }
        catch(java.time.format.DateTimeParseException ignored) { return getString(R.string.main_unavailable); }
    }
    private String pulseLabel(Reading r) { return r.pulse==null?getString(R.string.main_pulse_unavailable):getString(R.string.main_pulse_value,number(r.pulse)); }
    private String userLabel(Reading r) {
        if(virtualMode) return r.virtualUserName==null?getString(R.string.vp_unassigned):r.virtualUserName;
        return r.user==null?getString(R.string.main_user_unspecified):r.user==255?getString(R.string.main_guest):profileLabel(r.user);
    }
    private String profileLabel(int user) {
        if(user==0) return getString(R.string.main_all_users);
        if(user<0) { for(VirtualUser profile:store.virtualUsers()) if(user==-profile.id) return profile.name; return getString(R.string.vp_unassigned); }
        return getString(virtualMode?R.string.vp_monitor_bank:R.string.main_user_number,number(user));
    }
}
