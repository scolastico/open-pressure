package org.openpressure.app.reminders;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;
import java.text.DateFormatSymbols;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import org.openpressure.app.R;

/** Phone-local measurement reminders, available independently of Bluetooth setup. */
public final class RemindersActivity extends Activity {
    private static final int TEAL=0xff176e65, INK=0xff182d2a, MUTED=0xff61726b, NOTIFICATION_REQUEST=42;
    private ReminderStore store;
    private ReminderScheduler scheduler;
    private LinearLayout page,reminders;
    private TextView exactHelp,notificationHelp;
    private Button exactAllow,notificationAllow;
    private AlertDialog editor,deleteDialog;
    private TimePicker time;
    private EditText skip;
    private final List<CheckBox> weekdays=new ArrayList<>();
    private int editingId=-1,deletingId=-1;
    private boolean editingEnabled=true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setTitle(R.string.reminder_title);
        store=new ReminderStore(this); scheduler=new ReminderScheduler(this); ReminderNotifications.createChannel(this);
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPaddingRelative(dp(22),dp(20),dp(22),dp(28)); page.setBackgroundColor(0xfff5f7f4);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view,insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets;
        }); setContentView(scroll);
        add(page,button(R.string.settings_back,this::finish,false),0);
        add(page,text(getString(R.string.reminder_title),30,INK,true),18);
        add(page,text(getString(R.string.reminder_intro),16,INK,false),14);
        exactHelp=text(getString(R.string.reminder_exact_help),15,MUTED,false); add(page,exactHelp,14);
        exactAllow=button(R.string.reminder_exact_allow,this::allowExact,false); add(page,exactAllow,6);
        notificationHelp=text(getString(R.string.reminder_notifications_help),15,MUTED,false); add(page,notificationHelp,14);
        notificationAllow=button(R.string.reminder_notifications_allow,this::allowNotifications,false); add(page,notificationAllow,6);
        add(page,button(R.string.reminder_add,() -> edit(null,null),true),18);
        reminders=new LinearLayout(this); reminders.setOrientation(LinearLayout.VERTICAL); add(page,reminders,14);
        renderReminders(); updateStatus();
        if(state!=null) {
            if(state.getBoolean("editor")) {
                editingId=state.getInt("editing_id",-1); editingEnabled=state.getBoolean("editing_enabled",true);
                edit(null,state);
            }
            int pending=state.getInt("deleting_id",-1); if(pending>=0) confirmDelete(pending);
        }
    }
    @Override protected void onResume() {
        super.onResume();
        try { scheduler.refresh(); } catch(IllegalStateException failed) { toast(R.string.reminder_save_failed); }
        renderReminders(); updateStatus();
    }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if(editor!=null && editor.isShowing()) {
            // Commit text typed into the native picker before recording its hour/minute.
            time.clearFocus(); out.putBoolean("editor",true); out.putInt("editing_id",editingId); out.putBoolean("editing_enabled",editingEnabled);
            out.putInt("hour",time.getHour()); out.putInt("minute",time.getMinute()); out.putInt("days",selectedDays()); out.putString("skip",skip.getText().toString());
        }
        if(deleteDialog!=null && deleteDialog.isShowing()) out.putInt("deleting_id",deletingId);
    }
    @Override protected void onDestroy() {
        if(editor!=null) editor.dismiss(); if(deleteDialog!=null) deleteDialog.dismiss(); super.onDestroy();
    }
    private void renderReminders() {
        reminders.removeAllViews(); List<Reminder> saved;
        try { saved=store.list(); }
        catch(IllegalStateException failed) { add(reminders,text(getString(R.string.reminder_save_failed),16,MUTED,false),0); return; }
        if(saved.isEmpty()) add(reminders,text(getString(R.string.reminder_empty),16,MUTED,false),0);
        for(Reminder reminder:saved) {
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPaddingRelative(dp(16),dp(12),dp(16),dp(16)); card.setBackgroundColor(Color.WHITE);
            Calendar clock=Calendar.getInstance(); clock.set(Calendar.HOUR_OF_DAY,reminder.hour); clock.set(Calendar.MINUTE,reminder.minute);
            add(card,text(DateFormat.getTimeFormat(this).format(clock.getTime()),26,INK,true),0);
            add(card,text(dayDescription(reminder.daysMask),16,MUTED,false),6);
            add(card,text(getString(R.string.reminder_skip_minutes)+": "+NumberFormat.getIntegerInstance(locale()).format(reminder.skipMinutes),15,MUTED,false),6);
            Switch enabled=new Switch(this); enabled.setText(R.string.reminder_enabled); enabled.setTextSize(16); enabled.setMinHeight(dp(52)); enabled.setChecked(reminder.enabled);
            enabled.setOnCheckedChangeListener((view,checked) -> {
                try { store.save(new Reminder(reminder.id,reminder.hour,reminder.minute,reminder.daysMask,reminder.skipMinutes,checked)); scheduler.refresh(); renderReminders(); }
                catch(IllegalStateException failed) { enabled.setOnCheckedChangeListener(null); enabled.setChecked(reminder.enabled); toast(R.string.reminder_save_failed); renderReminders(); }
            }); add(card,enabled,8);
            // Full-width actions remain readable with large fonts and longer translations.
            add(card,button(R.string.reminder_edit,() -> edit(reminder,null),false),4);
            add(card,button(R.string.reminder_delete,() -> confirmDelete(reminder.id),false),4);
            add(reminders,card,12);
        }
    }
    private void edit(Reminder reminder,Bundle draft) {
        if(editor!=null && editor.isShowing()) return;
        Calendar now=Calendar.getInstance();
        if(draft==null) { editingId=reminder==null?-1:reminder.id; editingEnabled=reminder==null || reminder.enabled; }
        int hour=draft!=null?draft.getInt("hour"):reminder==null?now.get(Calendar.HOUR_OF_DAY):reminder.hour;
        int minute=draft!=null?draft.getInt("minute"):reminder==null?now.get(Calendar.MINUTE):reminder.minute;
        int days=draft!=null?draft.getInt("days"):reminder==null?0:reminder.daysMask;
        LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPaddingRelative(dp(20),dp(8),dp(20),dp(20));
        TextView timeLabel=text(getString(R.string.reminder_time),16,INK,true); add(form,timeLabel,6);
        time=new TimePicker(this);
        time.setId(View.generateViewId()); time.setSaveEnabled(false); time.setIs24HourView(DateFormat.is24HourFormat(this)); time.setHour(hour); time.setMinute(minute);
        timeLabel.setLabelFor(time.getId()); add(form,time,4);
        add(form,text(getString(R.string.reminder_days),16,INK,true),14);
        weekdays.clear(); String[] dayNames=new DateFormatSymbols(locale()).getWeekdays();
        for(int calendarDay:orderedDays()) {
            int bit=(calendarDay+5)%7;
            CheckBox day=new CheckBox(this); day.setText(dayNames[calendarDay]); day.setTextSize(16); day.setTextColor(INK); day.setMinHeight(dp(48));
            day.setTag(bit); day.setSaveEnabled(false); day.setChecked((days&(1<<bit))!=0); weekdays.add(day); add(form,day,0);
        }
        add(form,text(getString(R.string.reminder_once),15,MUTED,false),4);
        TextView label=text(getString(R.string.reminder_skip_minutes),16,INK,true); add(form,label,14);
        skip=new EditText(this); skip.setTag("reminder_skip"); skip.setId(View.generateViewId()); skip.setSaveEnabled(false); skip.setInputType(InputType.TYPE_CLASS_NUMBER); skip.setSingleLine(true); skip.setMinHeight(dp(52));
        skip.setText(draft!=null?draft.getString("skip"):editableMinutes(reminder==null?120:reminder.skipMinutes));
        label.setLabelFor(skip.getId()); add(form,skip,4); add(form,text(getString(R.string.reminder_skip_help),15,MUTED,false),8);
        ScrollView scroll=new ScrollView(this); scroll.addView(form);
        editor=new AlertDialog.Builder(this).setTitle(editingId<0?R.string.reminder_add:R.string.reminder_edit).setView(scroll)
                .setNegativeButton(R.string.main_cancel,null).setPositiveButton(R.string.main_save,null).create();
        AlertDialog current=editor;
        current.setOnShowListener(dialog -> current.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> saveEditor()));
        current.setOnDismissListener(dialog -> { if(editor==current) { editor=null; weekdays.clear(); } }); current.show();
    }
    private void saveEditor() {
        time.clearFocus(); int minutes;
        try { minutes=parseMinutes(skip.getText().toString()); }
        catch(IllegalArgumentException invalid) { skip.setError(getString(R.string.reminder_invalid)); return; }
        try {
            if(editingId<0) store.create(time.getHour(),time.getMinute(),selectedDays(),minutes);
            else store.save(new Reminder(editingId,time.getHour(),time.getMinute(),selectedDays(),minutes,editingEnabled));
        } catch(IllegalArgumentException invalid) { skip.setError(getString(R.string.reminder_invalid)); return; }
        catch(IllegalStateException failed) { toast(R.string.reminder_save_failed); return; }
        scheduler.refresh(); editor.dismiss(); renderReminders(); updateStatus();
    }
    private int parseMinutes(String value) {
        String digits=value.trim(); if(digits.isEmpty()) throw new IllegalArgumentException();
        // Android number keyboards may enter locale digits; values must remain whole minutes.
        long number=0;
        for(int i=0;i<digits.length();i++) {
            int digit=Character.digit(digits.charAt(i),10); if(digit<0) throw new IllegalArgumentException();
            number=number*10+digit; if(number>Integer.MAX_VALUE) throw new IllegalArgumentException();
        }
        return (int)number;
    }
    private String editableMinutes(int minutes) {
        NumberFormat format=NumberFormat.getIntegerInstance(locale()); format.setGroupingUsed(false); return format.format(minutes);
    }
    private int selectedDays() { int days=0; for(CheckBox day:weekdays) if(day.isChecked()) days|=1<<(Integer)day.getTag(); return days; }
    private void confirmDelete(int id) {
        deletingId=id; deleteDialog=new AlertDialog.Builder(this).setTitle(R.string.reminder_delete).setMessage(R.string.reminder_delete_confirm)
                .setNegativeButton(R.string.main_cancel,null).setPositiveButton(R.string.reminder_delete,null).create();
        AlertDialog current=deleteDialog;
        current.setOnShowListener(dialog -> current.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try { store.delete(id); }
            catch(IllegalStateException failed) { toast(R.string.reminder_save_failed); return; }
            scheduler.refresh(); current.dismiss(); renderReminders();
        }));
        current.setOnDismissListener(dialog -> { if(deleteDialog==current) { deleteDialog=null; deletingId=-1; } }); current.show();
    }
    private void updateStatus() {
        boolean exact=ReminderScheduler.exactAllowed(this),notifications=ReminderNotifications.enabled(this);
        exactHelp.setVisibility(exact?View.GONE:View.VISIBLE); exactAllow.setVisibility(exact?View.GONE:View.VISIBLE);
        notificationHelp.setVisibility(notifications?View.GONE:View.VISIBLE); notificationAllow.setVisibility(notifications?View.GONE:View.VISIBLE);
    }
    private void allowExact() {
        if(Build.VERSION.SDK_INT>=31) openSettings(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())),R.string.reminder_exact_help);
    }
    private void allowNotifications() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notification_permission_asked",false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_permission_asked",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_REQUEST); return;
        }
        NotificationManager manager=getSystemService(NotificationManager.class);
        boolean permitted=Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        Intent settings=new Intent(permitted && manager!=null && manager.areNotificationsEnabled()?Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS:Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,ReminderNotifications.CHANNEL_ID);
        openSettings(settings,R.string.reminder_notifications_help);
    }
    private void openSettings(Intent intent,int unavailableMessage) {
        try { startActivity(intent); }
        catch(ActivityNotFoundException | SecurityException unavailable) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))); }
            catch(ActivityNotFoundException | SecurityException missing) { toast(unavailableMessage); }
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results); if(request==NOTIFICATION_REQUEST) updateStatus();
    }
    private Locale locale() { return getResources().getConfiguration().getLocales().get(0); }
    private int[] orderedDays() {
        int first=Calendar.getInstance(locale()).getFirstDayOfWeek(); int[] days=new int[7];
        for(int i=0;i<7;i++) days[i]=(first-1+i)%7+1; return days;
    }
    private String dayDescription(int mask) {
        if(mask==0) return getString(R.string.reminder_once);
        String[] names=new DateFormatSymbols(locale()).getWeekdays(); List<String> selected=new ArrayList<>();
        for(int day:orderedDays()) if((mask&(1<<((day+5)%7)))!=0) selected.add(names[day]);
        return String.join(" · ",selected);
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView text=new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setLineSpacing(dp(3),1);
        if(bold) text.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return text;
    }
    private Button button(int label,Runnable action,boolean primary) {
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
