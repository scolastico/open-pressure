package org.openpressure.app.profiles;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import org.openpressure.app.PressureApp;
import org.openpressure.app.R;
import org.openpressure.app.data.Reading;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.data.VirtualUser;

/** Explicit choices drain the durable inbox one measurement at a time. */
public final class AssignmentActivity extends Activity {
    private static final int TEAL=0xff176e65,INK=0xff182d2a,MUTED=0xff61726b;
    private ReadingStore store;
    private LinearLayout page;
    private boolean assigning;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setTitle(R.string.vp_assign_title); store=((PressureApp)getApplication()).store();
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPaddingRelative(dp(22),dp(20),dp(22),dp(28));
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xfff5f7f4); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view,insets) -> { view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
        setContentView(scroll); refreshPending();
    }
    @Override protected void onResume() { super.onResume(); refreshPending(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); refreshPending(); }
    /** Called on the main thread by the foreground coordinator after committed changes. */
    public void refreshPending() {
        if(page==null || assigning || isFinishing() || isDestroyed()) return;
        Reading reading=store.nextPendingAssignment();
        if(reading==null) { finish(); return; }
        page.removeAllViews();
        add(text(getString(R.string.vp_assign_title),29,INK,true),0);
        add(text(getString(R.string.vp_assign_help),16,MUTED,false),12);
        add(text(getString(R.string.vp_pending_count,store.pendingAssignmentsCount()),15,TEAL,true),12);
        NumberFormat number=NumberFormat.getNumberInstance(locale()); number.setMaximumFractionDigits(1);
        String systolic="\u2066"+number.format(reading.systolic)+"\u2069",diastolic="\u2066"+number.format(reading.diastolic)+"\u2069";
        String pressure="\u2066"+getString(R.string.main_pressure_values,systolic,diastolic)+" "+reading.unit+"\u2069";
        TextView pressureView=text(pressure,32,INK,true); pressureView.setTag("assignment_pressure"); add(pressureView,22);
        DateTimeFormatter date=DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withLocale(locale());
        add(text(reading.displayTime().format(date),18,INK,true),8);
        if(reading.time==null) add(text(getString(R.string.main_receipt_time_note),14,MUTED,false),6);
        add(text("manual".equals(reading.source)?getString(R.string.main_source_manual):reading.name,15,MUTED,false),6);
        if(reading.pulse!=null) add(text(getString(R.string.main_pulse_detail,getString(R.string.main_pulse_value,number.format(reading.pulse))),15,MUTED,false),6);
        for(VirtualUser profile:store.virtualUsers()) add(button(profile.name,() -> assign(reading.id,profile.id),true),12);
        add(button(getString(R.string.vp_title),() -> startActivity(new Intent(this,VirtualUsersActivity.class)),false),18);
        add(button(getString(R.string.vp_later),this::later,false),8);
    }
    private void assign(long displayedId,int profileId) {
        if(assigning || isFinishing()) return;
        // A new older measurement or another activity's choice invalidates this displayed action.
        Reading oldest=store.nextPendingAssignment();
        if(oldest==null || oldest.id!=displayedId) { refreshPending(); return; }
        assigning=true;
        try { store.assignPending(displayedId,profileId); }
        catch(RuntimeException failed) { Toast.makeText(this,R.string.vp_save_failed,Toast.LENGTH_LONG).show(); }
        finally { assigning=false; }
        refreshPending();
    }
    private void later() { ((PressureApp)getApplication()).assignments().deferUntilNextForeground(); finish(); }
    @Override public void onBackPressed() { later(); }
    private Locale locale() { return getResources().getConfiguration().getLocales().get(0); }
    private TextView text(String value,int size,int color,boolean bold) { TextView view=new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); if(bold) view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return view; }
    private Button button(String value,Runnable action,boolean primary) { Button button=new Button(this); button.setText(value); button.setAllCaps(false); button.setTextSize(16); button.setMinHeight(dp(54)); button.setTextColor(primary?Color.WHITE:TEAL); button.setBackgroundTintList(ColorStateList.valueOf(primary?TEAL:0xffe9efea)); button.setOnClickListener(view -> action.run()); return button; }
    private void add(View view,int top) { LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(top); page.addView(view,params); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
