package org.openpressure.app.developer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.openpressure.app.PressureApp;
import org.openpressure.app.R;
import org.openpressure.protocol.WriteAspectIndex;
import org.openpressure.protocol.WriteAspectIndex.Aspect;

/**
 * Hidden developer options: per-aspect overrides for untested writes, switches for experimental
 * paths and a read-only view of the write-aspect index. Nothing here talks to a monitor; a switch only
 * records the user's choice for a later, explicit request.
 */
public final class DeveloperOptionsActivity extends Activity {
    private static final int TEAL=0xff176e65, INK=0xff182d2a, MUTED=0xff61726b, WARN=0xff9a3412;
    /** One stored developer choice shown as a switch; turning it on always asks for confirmation first. */
    private static final class Toggle {
        final Switch view; final BooleanSupplier read; final Consumer<Boolean> write; final int title,message;
        Toggle(Switch view,BooleanSupplier read,Consumer<Boolean> write,int title,int message) {
            this.view=view; this.read=read; this.write=write; this.title=title; this.message=message;
        }
    }
    private final List<Toggle> toggles=new ArrayList<>();
    private LinearLayout indexSection;
    private boolean binding;
    private AlertDialog dialog;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if(!DeveloperOptions.isUnlocked(this)) { finish(); return; }
        setTitle(R.string.dev_title);
        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPaddingRelative(dp(22),dp(20),dp(22),dp(28)); page.setBackgroundColor(0xfff5f7f4);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view,insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets;
        });
        setContentView(scroll);
        add(page,button(getString(R.string.settings_back),this::finish,false),0);
        add(page,text(getString(R.string.dev_title),30,INK,true),18);
        add(page,text(getString(R.string.dev_warning),16,WARN,true),14);
        add(page,untested(Aspect.CLOCK_BLOCK,"dev_allow_clock_untested",R.string.dev_allow_clock_untested,R.string.dev_allow_confirm_title,R.string.dev_allow_confirm_message),16);
        add(page,untested(Aspect.REGISTRATION,"dev_allow_registration_untested",R.string.dev_allow_registration_untested,R.string.dev_allow_registration_confirm_title,R.string.dev_allow_registration_confirm_message),8);
        add(page,text(getString(R.string.dev_experimental_title),20,INK,true),28);
        add(page,text(getString(R.string.dev_experimental_explanation),15,MUTED,false),8);
        add(page,experimental(DeveloperOptions.Experiment.FORCE_X7_ENCRYPTED,R.string.dev_experimental_force_x7_encrypted),8);
        add(page,experimental(DeveloperOptions.Experiment.CLOCK_SYNC_ENCRYPTED_X7,R.string.dev_experimental_clock_sync_encrypted_x7),8);
        indexSection=new LinearLayout(this); indexSection.setOrientation(LinearLayout.VERTICAL);
        add(page,indexSection,0);
        add(page,button(getString(R.string.dev_hide),this::confirmHide,false),24);
    }
    @Override protected void onResume() {
        super.onResume();
        if(!DeveloperOptions.isUnlocked(this)) { finish(); return; }
        if(dialog==null || !dialog.isShowing()) bindToggles();
        // Rebuilt on every resume so monitors forgotten or renamed meanwhile are current.
        index();
    }
    @Override protected void onDestroy() { if(dialog!=null) dialog.dismiss(); dialog=null; super.onDestroy(); }

    /** The override switch of one untested write aspect. */
    private Switch untested(Aspect aspect,String tag,int label,int title,int message) {
        return toggle(tag,label,() -> DeveloperOptions.allowsUntested(this,aspect),allowed -> DeveloperOptions.setAllowUntested(this,aspect,allowed),title,message);
    }
    /** The switch of one experimental path; its tag is the stored key, for example {@code experimental_force_x7_encrypted}. */
    private Switch experimental(DeveloperOptions.Experiment experiment,int label) {
        return toggle(DeveloperOptions.key(experiment),label,() -> DeveloperOptions.allowsExperimental(this,experiment),
                allowed -> DeveloperOptions.setAllowExperimental(this,experiment,allowed),R.string.dev_experimental_confirm_title,R.string.dev_experimental_confirm_message);
    }
    private Switch toggle(String tag,int label,BooleanSupplier read,Consumer<Boolean> write,int title,int message) {
        Switch view=new Switch(this); view.setText(label); view.setTextSize(16);
        view.setTextColor(INK); view.setMinHeight(dp(56)); view.setTag(tag);
        // The stored choice is the only source of truth; never restore a pending "on" from view state.
        view.setSaveEnabled(false);
        Toggle toggle=new Toggle(view,read,write,title,message);
        toggles.add(toggle);
        bind(toggle);
        view.setOnCheckedChangeListener((button,checked) -> { if(!binding) changed(toggle,checked); });
        return view;
    }
    private void bindToggles() { for(Toggle toggle:toggles) bind(toggle); }
    private void bind(Toggle toggle) {
        binding=true; toggle.view.setChecked(toggle.read.getAsBoolean()); binding=false;
    }
    private void changed(Toggle toggle,boolean checked) {
        if(!checked) { store(toggle,false); return; }
        if(dialog!=null && dialog.isShowing()) { bind(toggle); return; }
        AlertDialog confirm=new AlertDialog.Builder(this).setTitle(toggle.title)
                .setMessage(toggle.message).setNegativeButton(R.string.main_cancel,null)
                .setPositiveButton(R.string.dev_allow_confirm_accept,(d,w) -> store(toggle,true)).create();
        // Cancel, back and outside taps all end here; accept has stored the choice by then.
        confirm.setOnDismissListener(d -> { if(dialog==confirm) dialog=null; bindToggles(); });
        dialog=confirm; confirm.show();
    }
    private void store(Toggle toggle,boolean allowed) {
        try { toggle.write.accept(allowed); }
        catch(IllegalStateException failed) {
            // Locked meanwhile: nothing to change here. Otherwise the save failed: say so and show what is stored.
            if(!DeveloperOptions.isUnlocked(this)) { finish(); return; }
            saveFailed();
        }
        bindToggles();
    }
    private void confirmHide() {
        if(dialog!=null && dialog.isShowing()) return;
        dialog=new AlertDialog.Builder(this).setTitle(R.string.dev_hide).setNegativeButton(R.string.main_cancel,null)
                .setPositiveButton(R.string.dev_hide,(d,w) -> hide()).create();
        dialog.show();
    }
    private void hide() {
        // Locking clears every switch; only write when the state actually changes.
        try { if(DeveloperOptions.isUnlocked(this)) DeveloperOptions.setUnlocked(this,false); }
        catch(IllegalStateException failed) { saveFailed(); bindToggles(); index(); return; }
        Toast.makeText(this,R.string.dev_hidden,Toast.LENGTH_LONG).show();
        finish();
    }

    private void saveFailed() { Toast.makeText(this,R.string.dev_save_failed,Toast.LENGTH_LONG).show(); }

    /** One row per saved monitor and write aspect: name, aspect, status and the index's evidence note. */
    private void index() {
        if(indexSection==null) return;
        LinearLayout page=indexSection; page.removeAllViews();
        add(page,text(getString(R.string.dev_index_title),20,INK,true),28);
        List<String[]> devices=((PressureApp)getApplication()).store().devices();
        if(devices.isEmpty()) { add(page,text(getString(R.string.dev_index_no_monitors),15,MUTED,false),8); return; }
        for(String[] device:devices) {
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPaddingRelative(dp(14),dp(12),dp(14),dp(12)); card.setBackgroundColor(Color.WHITE);
            card.setTag("dev_index_"+device[0]);
            add(card,text(device[1],17,INK,true),0);
            TextView model=text(device[2],13,MUTED,false); model.setTextDirection(View.TEXT_DIRECTION_LTR); add(card,model,2);
            String identity=WriteAspectIndex.resolve(device[2]);
            for(Aspect aspect:Aspect.values()) {
                WriteAspectIndex.Entry entry=WriteAspectIndex.lookup(identity,aspect);
                add(card,text(getString(label(aspect)),15,INK,true),10);
                TextView status=text(getString(status(entry.status)),15,color(entry.status),false);
                status.setTag("dev_index_"+device[0]+"_"+aspect.name()); add(card,status,2);
                // Evidence notes are technical references from the index, shown as-is.
                TextView evidence=text(entry.evidence,12,MUTED,false); evidence.setTypeface(Typeface.MONOSPACE);
                evidence.setTextDirection(View.TEXT_DIRECTION_LTR); add(card,evidence,2);
            }
            add(page,card,10);
        }
    }
    private static int label(Aspect aspect) {
        switch(aspect) {
            case CLOCK_BLOCK: return R.string.dev_index_aspect_clock;
            case REGISTRATION: return R.string.dev_index_aspect_registration;
            default: return R.string.dev_index_aspect_unsent;
        }
    }
    /** Only a hardware-confirmed aspect uses the accent; untested ones are a warning, blocked ones muted. */
    private static int color(WriteAspectIndex.Status status) {
        switch(status) {
            case CONFIRMED: return TEAL;
            case UNTESTED: return WARN;
            default: return MUTED;
        }
    }
    private static int status(WriteAspectIndex.Status status) {
        switch(status) {
            case CONFIRMED: return R.string.dev_index_status_confirmed;
            case UNTESTED: return R.string.dev_index_status_untested;
            default: return R.string.dev_index_status_blocked;
        }
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
}
