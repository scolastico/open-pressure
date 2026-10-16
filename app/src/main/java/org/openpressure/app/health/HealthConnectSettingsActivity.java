package org.openpressure.app.health;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.Context;
import org.openpressure.app.data.ReadingStore;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.health.connect.client.HealthConnectClient;
import org.openpressure.app.PressureApp;
import org.openpressure.app.R;
import org.openpressure.app.data.ExperimentalConsent;

/** Explicit opt-in and user-bank selection, independent of Bluetooth service state. */
public final class HealthConnectSettingsActivity extends Activity {
    private static final int PERMISSION_REQUEST=30;
    private static final int TEAL=0xff176e65, INK=0xff182d2a, MUTED=0xff61726b;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable listener=() -> handler.post(this::updateViews);
    private HealthConnectSync sync;
    private TextView status,selection;
    private RadioGroup banks;
    private RadioButton bankOne,bankTwo;
    private Button enable,disable,syncNow,manage,install;
    private AlertDialog consentDialog;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        sync=((PressureApp)getApplication()).healthConnect();
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPaddingRelative(dp(22),dp(20),dp(22),dp(28));
        page.setBackgroundColor(0xfff5f7f4);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((v,insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        add(page,button(getString(R.string.settings_back),this::finish,false),0);
        add(page,text(getString(R.string.health_title),30,INK,true),18);
        add(page,text(getString(R.string.health_subtitle),17,TEAL,true),8);
        add(page,text(getString(R.string.health_intro),16,INK,false),16);
        status=text("",16,INK,true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(page,status,20);
        selection=text("",15,MUTED,false);
        add(page,selection,8);
        banks=new RadioGroup(this);
        bankOne=bank(getString(R.string.health_user_one),R.id.health_bank_one); bankTwo=bank(getString(R.string.health_user_two),R.id.health_bank_two);
        banks.addView(bankOne); banks.addView(bankTwo);
        add(page,banks,12);
        int chosen=state==null?(sync.enabled()?sync.selectedUser():0):state.getInt("bank",0);
        if(chosen==1 || chosen==2) banks.check(chosen==1?R.id.health_bank_one:R.id.health_bank_two);
        banks.setOnCheckedChangeListener((group,id) -> updateViews());
        enable=button(getString(R.string.health_enable),this::enableExport,true);
        disable=button(getString(R.string.health_disable),() -> { sync.disable(); updateViews(); },false);
        syncNow=button(getString(R.string.health_export_now),() -> sync.syncNow(),false);
        install=button(getString(R.string.health_install),() -> open(sync.installIntent()),false);
        manage=button(getString(R.string.health_manage),() -> open(sync.manageIntent()),false);
        add(page,enable,12); add(page,disable,8); add(page,syncNow,8); add(page,install,8); add(page,manage,8);
        add(page,text(getString(R.string.health_history_help),15,MUTED,false),20);
        add(page,text(getString(R.string.health_shared_help),15,MUTED,false),14);
        add(page,text(getString(R.string.health_copies_help),15,MUTED,false),14);
        add(page,button(getString(R.string.health_privacy_action),() -> startActivity(new Intent(this,HealthConnectPrivacyActivity.class)),false),14);
        updateViews();
    }

    @Override protected void onStart() { super.onStart(); sync.listen(listener); }
    @Override protected void onResume() { super.onResume(); sync.refresh(); updateViews(); }
    @Override protected void onStop() { sync.unlisten(listener); handler.removeCallbacksAndMessages(null); super.onStop(); }
    @Override protected void onDestroy() {
        if(consentDialog!=null) consentDialog.dismiss();
        super.onDestroy();
    }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putInt("bank",selectedBank());
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        // The backend checks actual granted permissions; the result code alone is insufficient.
        if(requestCode==PERMISSION_REQUEST) { sync.completePermissionRequest(); updateViews(); }
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==PERMISSION_REQUEST) { sync.completePermissionRequest(); updateViews(); }
    }

    private void enableExport() {
        int bank=selectedBank();
        if(bank!=1 && bank!=2) { toast(getString(R.string.health_choose_user)); return; }
        if(!ExperimentalConsent.accepted(this)) { showExperimentalConsent(); return; }
        if(sync.availability()!=HealthConnectClient.SDK_AVAILABLE) { sync.refresh(); updateViews(); return; }
        sync.prepareEnable(bank);
        try {
            // Android 14+ uses native health permissions. The SDK contract's intent on
            // these versions is an ActivityResultRegistry command, not a launchable activity.
            if(Build.VERSION.SDK_INT>=34) {
                requestPermissions(new String[]{"android.permission.health.WRITE_BLOOD_PRESSURE"},PERMISSION_REQUEST);
            } else {
                Intent permission=sync.permissionIntent();
                if(permission==null) { sync.disable(); toast(getString(R.string.health_permission_unavailable)); updateViews(); return; }
                startActivityForResult(permission,PERMISSION_REQUEST);
            }
        }
        catch(ActivityNotFoundException | SecurityException failure) {
            sync.disable(); toast(getString(R.string.health_permission_open_failed));
        }
        updateViews();
    }

    private void showExperimentalConsent() {
        if(consentDialog!=null && consentDialog.isShowing()) return;
        consentDialog=new AlertDialog.Builder(this).setTitle(getString(R.string.settings_experimental_title))
                .setMessage(getString(R.string.settings_experimental_message))
                .setNegativeButton(getString(R.string.settings_cancel),null).setPositiveButton(getString(R.string.settings_agree),null).create();
        consentDialog.setOnShowListener(dialog -> consentDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if(!ExperimentalConsent.accept(this)) { toast(getString(R.string.settings_agreement_failed)); return; }
            consentDialog.dismiss(); consentDialog=null;
            enableExport();
        }));
        consentDialog.show();
    }

    private void updateViews() {
        if(isFinishing() || isDestroyed() || enable==null) return;
        boolean active=sync.enabled();
        int available=sync.availability();
        if(available==HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            status.setText(getString(R.string.health_update_required));
        } else if(available==HealthConnectClient.SDK_UNAVAILABLE) {
            status.setText(Build.VERSION.SDK_INT<28?getString(R.string.health_android_required):getString(R.string.health_unavailable_local));
        } else status.setText(localizedStatus(this,sync));
        selection.setText(active?getString(R.string.health_selection_active,sync.selectedUser()):getString(R.string.health_selection_off));
        if(active && (sync.selectedUser()==1 || sync.selectedUser()==2)) banks.check(sync.selectedUser()==1?R.id.health_bank_one:R.id.health_bank_two);
        bankOne.setEnabled(!active); bankTwo.setEnabled(!active);
        enable.setVisibility(active?View.GONE:View.VISIBLE);
        enable.setEnabled(available==HealthConnectClient.SDK_AVAILABLE && (selectedBank()==1 || selectedBank()==2));
        disable.setVisibility(active?View.VISIBLE:View.GONE);
        syncNow.setVisibility(active?View.VISIBLE:View.GONE);
        syncNow.setEnabled(available==HealthConnectClient.SDK_AVAILABLE);
        manage.setVisibility(available==HealthConnectClient.SDK_AVAILABLE?View.VISIBLE:View.GONE);
        install.setVisibility(available==HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED?View.VISIBLE:View.GONE);
    }

    /** Translate backend state without exposing stored errors or implementation details. */
    public static String localizedStatus(Context context,HealthConnectSync sync) {
        String state=sync.status();
        int message;
        if(state.startsWith("Checking")) message=R.string.health_status_checking;
        else if(state.startsWith("Waiting for")) message=R.string.health_status_permission_pending;
        else if(state.contains("was not granted")) message=R.string.health_status_permission_missing;
        else if(state.startsWith("Could not check")) message=R.string.health_status_permission_check_failed;
        else if(state.startsWith("Export paused")) message=R.string.health_status_paused;
        else if(state.startsWith("Automatic export is off. Existing")) message=R.string.health_status_off_copies;
        else if(state.startsWith("Automatic export is off")) message=R.string.health_status_off;
        else if(state.startsWith("Install or update")) message=R.string.health_update_required;
        else if(state.startsWith("Health Connect is unavailable")) message=R.string.health_unavailable_local;
        else if(state.startsWith("Health Connect is temporarily") || state.startsWith("Health Connect export failed")) message=R.string.health_status_retry;
        else if(state.startsWith("Export queued")) message=R.string.health_status_queued;
        else if(sync.enabled()) {
            ReadingStore store=((PressureApp)context.getApplicationContext()).store();
            int skipped=store.healthSkippedCount(sync.selectedUser());
            String summary=context.getString(R.string.health_status_summary,sync.selectedUser(),store.healthExportedCount(sync.selectedUser()),skipped);
            return skipped>0?summary+"\n"+context.getString(R.string.health_status_skipped):summary;
        } else message=R.string.health_status_off;
        return context.getString(message);
    }

    private void open(Intent intent) {
        if(intent==null) { toast(getString(R.string.health_unavailable)); return; }
        try { startActivity(intent); }
        catch(ActivityNotFoundException | SecurityException failure) { toast(getString(R.string.health_open_failed)); }
    }
    private int selectedBank() {
        int id=banks.getCheckedRadioButtonId();
        return id==R.id.health_bank_one?1:id==R.id.health_bank_two?2:0;
    }
    private RadioButton bank(String label,int id) {
        RadioButton b=new RadioButton(this); b.setId(id); b.setText(label); b.setTextSize(16); b.setTextColor(INK); b.setMinHeight(dp(52)); return b;
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(dp(3),1);
        if(bold) t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return t;
    }
    private Button button(String label,Runnable action,boolean primary) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setMinHeight(dp(50));
        b.setTextColor(primary?Color.WHITE:TEAL); b.setBackgroundTintList(ColorStateList.valueOf(primary?TEAL:0xffe9efea));
        b.setOnClickListener(v -> action.run()); return b;
    }
    private void add(LinearLayout parent,View view,int top) {
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(top); parent.addView(view,params);
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_LONG).show(); }
}
