package org.openpressure.app.forwarding;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.openpressure.app.PressureApp;
import org.openpressure.app.R;
import org.openpressure.app.data.ExperimentalConsent;

/** Explicit opt-in destination and local-history choice for new measurements. */
public final class HttpForwardingSettingsActivity extends Activity {
    private static final int TEAL=0xff176e65, INK=0xff182d2a, MUTED=0xff61726b;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refreshStatus=new Runnable() {
        @Override public void run() { updateStatus(); handler.postDelayed(this,2000); }
    };
    private EditText endpoint,authorization;
    private Switch keepLocal;
    private TextView status,failure;
    private Button retry;
    private AlertDialog consentDialog;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle(R.string.http_title);
        HttpForwardingSettings.Config config=HttpForwardingSettings.get(this);
        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPaddingRelative(dp(22),dp(20),dp(22),dp(28)); page.setBackgroundColor(0xfff5f7f4);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view,insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets;
        });
        setContentView(scroll);
        add(page,button(getString(R.string.settings_back),this::finish,false),0);
        add(page,text(getString(R.string.http_title),30,INK,true),18);
        add(page,text(getString(R.string.http_intro),16,INK,false),14);
        endpoint=input(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setText(saved==null?config.endpoint:saved.getString("endpoint",config.endpoint));
        label(page,R.string.http_url,endpoint);
        authorization=input(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        authorization.setTransformationMethod(PasswordTransformationMethod.getInstance());
        authorization.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        // Secrets may be reloaded from private preferences, never from Android screen state.
        authorization.setSaveEnabled(false); authorization.setText(config.authorization);
        label(page,R.string.http_authorization,authorization);
        keepLocal=new Switch(this); keepLocal.setText(R.string.http_keep_local); keepLocal.setTextSize(16);
        keepLocal.setTextColor(INK); keepLocal.setMinHeight(dp(56));
        keepLocal.setChecked(saved==null?config.keepLocal:saved.getBoolean("keep_local",config.keepLocal));
        add(page,keepLocal,16);
        add(page,text(getString(R.string.http_passthrough_help),15,MUTED,false),12);
        add(page,text(getString(R.string.http_destination_help),15,MUTED,false),12);
        add(page,text(getString(R.string.http_privacy),15,MUTED,false),12);
        add(page,button(getString(R.string.main_save),this::save,true),18);
        status=text("",16,INK,true); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(page,status,18);
        failure=text(getString(R.string.http_failed),15,MUTED,false);
        failure.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(page,failure,8);
        retry=button(getString(R.string.http_retry),this::retry,false); add(page,retry,8);
        endpoint.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { updateLocalChoice(); }
            @Override public void afterTextChanged(Editable value) {}
        });
        updateLocalChoice(); updateStatus();
    }
    @Override protected void onResume() { super.onResume(); handler.post(refreshStatus); }
    @Override protected void onPause() { handler.removeCallbacks(refreshStatus); super.onPause(); }
    @Override protected void onDestroy() {
        handler.removeCallbacks(refreshStatus); if(consentDialog!=null) consentDialog.dismiss(); super.onDestroy();
    }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("endpoint",endpoint.getText().toString()); out.putBoolean("keep_local",keepLocal.isChecked());
    }
    private void updateLocalChoice() {
        String value=endpoint.getText().toString(); boolean present=!value.trim().isEmpty();
        keepLocal.setVisibility(present?View.VISIBLE:View.GONE);
        keepLocal.setEnabled(present && HttpForwardingSettings.validEndpoint(value.trim()));
        if(!present) keepLocal.setChecked(true);
    }
    private void save() {
        String url=endpoint.getText().toString(),header=authorization.getText().toString();
        if(!url.trim().isEmpty() && !HttpForwardingSettings.validEndpoint(url.trim())) { toast(R.string.http_invalid); return; }
        for(int i=0;i<header.length();i++) if(header.charAt(i)<32 || header.charAt(i)>126) { toast(R.string.http_invalid); return; }
        if(!url.trim().isEmpty() && !ExperimentalConsent.accepted(this)) { consent(this::save); return; }
        try { HttpForwardingSettings.save(this,url,header,keepLocal.isChecked()); }
        catch(IllegalArgumentException invalid) { toast(R.string.http_invalid); return; }
        catch(IllegalStateException failed) { toast(R.string.http_save_failed); return; }
        ((PressureApp)getApplication()).httpForwarding().refresh();
        updateLocalChoice(); updateStatus(); toast(R.string.http_saved);
    }
    private void retry() {
        // Unsaved form edits must never change the destination or credentials used for uploads.
        if(!HttpForwardingSettings.get(this).enabled()) return;
        if(!ExperimentalConsent.accepted(this)) { consent(this::retry); return; }
        ((PressureApp)getApplication()).httpForwarding().retryNow(); updateStatus();
    }
    private void consent(Runnable accepted) {
        if(consentDialog!=null && consentDialog.isShowing()) return;
        consentDialog=new AlertDialog.Builder(this).setTitle(R.string.settings_experimental_title)
                .setMessage(R.string.settings_experimental_message).setNegativeButton(R.string.main_cancel,null)
                .setPositiveButton(R.string.settings_agree,null).create();
        consentDialog.setOnShowListener(dialog -> consentDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if(!ExperimentalConsent.accept(this)) { toast(R.string.settings_agreement_failed); return; }
            consentDialog.dismiss(); consentDialog=null; accepted.run();
        }));
        consentDialog.show();
    }
    private void updateStatus() {
        if(status==null || isFinishing() || isDestroyed()) return;
        HttpForwardingSettings.Config config=HttpForwardingSettings.get(this);
        int pending=config.enabled()?((PressureApp)getApplication()).store().httpOutbox().count(config.endpoint):0;
        status.setVisibility(config.enabled()?View.VISIBLE:View.GONE); status.setText(getString(R.string.http_status,pending));
        failure.setVisibility(pending>0 && HttpForwardingSync.lastAttemptFailed(this)?View.VISIBLE:View.GONE);
        retry.setVisibility(config.enabled()?View.VISIBLE:View.GONE); retry.setEnabled(pending>0);
    }
    private EditText input(int type) {
        EditText input=new EditText(this); input.setId(View.generateViewId()); input.setInputType(type);
        input.setSingleLine(true); input.setTextSize(16); input.setTextColor(INK); input.setMinHeight(dp(52));
        input.setSaveEnabled(false); return input;
    }
    private void label(LinearLayout page,int resource,EditText input) {
        TextView label=text(getString(resource),16,INK,true); label.setLabelFor(input.getId()); add(page,label,16); add(page,input,4);
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView text=new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setLineSpacing(dp(3),1);
        if(bold) text.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return text;
    }
    private Button button(String label,Runnable action,boolean primary) {
        Button button=new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(14); button.setMinHeight(dp(50));
        button.setTextColor(primary?Color.WHITE:TEAL); button.setBackgroundTintList(ColorStateList.valueOf(primary?TEAL:0xffe9efea));
        button.setOnClickListener(view -> action.run()); return button;
    }
    private void add(LinearLayout parent,View view,int top) {
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(top); parent.addView(view,params);
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private void toast(int message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
}
