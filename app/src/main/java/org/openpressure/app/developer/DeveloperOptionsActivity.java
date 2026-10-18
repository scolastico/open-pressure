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
import java.util.List;
import org.openpressure.app.PressureApp;
import org.openpressure.app.R;
import org.openpressure.protocol.VendorMemoryModel;
import org.openpressure.protocol.WriteAspectIndex;
import org.openpressure.protocol.WriteAspectIndex.Aspect;

/**
 * Hidden developer options: per-aspect overrides for untested writes and a read-only
 * view of the write-aspect index. Nothing here talks to a monitor; a switch only
 * records the user's choice for a later, explicit request.
 */
public final class DeveloperOptionsActivity extends Activity {
    private static final int TEAL=0xff176e65, INK=0xff182d2a, MUTED=0xff61726b, WARN=0xff9a3412;
    private Switch clock;
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
        clock=new Switch(this); clock.setText(R.string.dev_allow_clock_untested); clock.setTextSize(16);
        clock.setTextColor(INK); clock.setMinHeight(dp(56)); clock.setTag("dev_allow_clock_untested");
        // The stored choice is the only source of truth; never restore a pending "on" from view state.
        clock.setSaveEnabled(false);
        bindClock();
        clock.setOnCheckedChangeListener((view,checked) -> { if(!binding) clockChanged(checked); });
        add(page,clock,16);
        index(page);
        add(page,button(getString(R.string.dev_hide),this::confirmHide,false),24);
    }
    @Override protected void onResume() {
        super.onResume();
        if(!DeveloperOptions.isUnlocked(this)) { finish(); return; }
        if(dialog==null || !dialog.isShowing()) bindClock();
    }
    @Override protected void onDestroy() { if(dialog!=null) dialog.dismiss(); dialog=null; super.onDestroy(); }

    private void bindClock() {
        if(clock==null) return;
        binding=true; clock.setChecked(DeveloperOptions.allowsUntested(this,Aspect.CLOCK_BLOCK)); binding=false;
    }
    private void clockChanged(boolean checked) {
        if(!checked) { store(false); return; }
        if(dialog!=null && dialog.isShowing()) return;
        AlertDialog confirm=new AlertDialog.Builder(this).setTitle(R.string.dev_allow_confirm_title)
                .setMessage(R.string.dev_allow_confirm_message).setNegativeButton(R.string.main_cancel,null)
                .setPositiveButton(R.string.dev_allow_confirm_accept,(d,w) -> store(true)).create();
        // Cancel, back and outside taps all end here; accept has stored the choice by then.
        confirm.setOnDismissListener(d -> { if(dialog==confirm) dialog=null; bindClock(); });
        dialog=confirm; confirm.show();
    }
    private void store(boolean allowed) {
        try { DeveloperOptions.setAllowUntested(this,Aspect.CLOCK_BLOCK,allowed); }
        catch(IllegalStateException locked) { finish(); return; }
        bindClock();
    }
    private void confirmHide() {
        if(dialog!=null && dialog.isShowing()) return;
        dialog=new AlertDialog.Builder(this).setTitle(R.string.dev_hide).setNegativeButton(R.string.main_cancel,null)
                .setPositiveButton(R.string.dev_hide,(d,w) -> hide()).create();
        dialog.show();
    }
    private void hide() {
        // Locking clears every switch; only write when the state actually changes.
        if(DeveloperOptions.isUnlocked(this)) DeveloperOptions.setUnlocked(this,false);
        Toast.makeText(this,R.string.dev_hidden,Toast.LENGTH_LONG).show();
        finish();
    }

    /** One row per saved monitor and write aspect: name, aspect, status and the index's evidence note. */
    private void index(LinearLayout page) {
        add(page,text(getString(R.string.dev_index_title),20,INK,true),28);
        List<String[]> devices=((PressureApp)getApplication()).store().devices();
        if(devices.isEmpty()) { add(page,text(getString(R.string.dev_index_no_monitors),15,MUTED,false),8); return; }
        for(String[] device:devices) {
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPaddingRelative(dp(14),dp(12),dp(14),dp(12)); card.setBackgroundColor(Color.WHITE);
            card.setTag("dev_index_"+device[0]);
            add(card,text(device[1],17,INK,true),0);
            TextView model=text(device[2],13,MUTED,false); model.setTextDirection(View.TEXT_DIRECTION_LTR); add(card,model,2);
            String identity=identity(device[2]);
            for(Aspect aspect:Aspect.values()) {
                WriteAspectIndex.Entry entry=WriteAspectIndex.lookup(identity,aspect);
                add(card,text(getString(aspect==Aspect.CLOCK_BLOCK?R.string.dev_index_aspect_clock:R.string.dev_index_aspect_unsent),15,INK,true),10);
                TextView status=text(getString(status(entry.status)),15,entry.status==WriteAspectIndex.Status.BLOCKED?MUTED:TEAL,false);
                status.setTag("dev_index_"+device[0]+"_"+aspect.name()); add(card,status,2);
                // Evidence notes are technical references from the index, shown as-is.
                TextView evidence=text(entry.evidence,12,MUTED,false); evidence.setTypeface(Typeface.MONOSPACE);
                evidence.setTextDirection(View.TEXT_DIRECTION_LTR); add(card,evidence,2);
            }
            add(page,card,10);
        }
    }
    /** The index keys verified identities: vendor-memory models by their table id, X7 identities as reported. */
    static String identity(String model) {
        VendorMemoryModel known=VendorMemoryModel.identify(model);
        return known!=null?known.id:model;
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
