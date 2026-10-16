package org.openpressure.app.profiles;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import org.openpressure.app.PressureApp;
import org.openpressure.app.R;
import org.openpressure.app.data.ReadingStore;
import org.openpressure.app.data.VirtualUser;
import org.openpressure.app.notifications.NewReadingNotifications;

/** Phone-local named owners; monitor memory-bank numbers never identify these profiles. */
public final class VirtualUsersActivity extends Activity {
    private static final int TEAL=0xff176e65,INK=0xff182d2a,MUTED=0xff61726b;
    private ReadingStore store;
    private LinearLayout page;
    private AlertDialog editor,removal;
    private EditText nameField;
    private int editingId=-1;
    private boolean enablingAfterCreate;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setTitle(R.string.vp_title); store=((PressureApp)getApplication()).store();
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPaddingRelative(dp(22),dp(20),dp(22),dp(28));
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xfff5f7f4); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view,insets) -> { view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
        setContentView(scroll); render();
        if(state!=null && state.getInt("editingId",-1)>=0) editName(state.getInt("editingId"),state.getBoolean("enabling"),state.getString("name",""));
    }
    @Override protected void onResume() { super.onResume(); render(); }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if(editor!=null && editor.isShowing()) { out.putInt("editingId",editingId); out.putBoolean("enabling",enablingAfterCreate); out.putString("name",nameField.getText().toString()); }
    }
    @Override protected void onDestroy() { if(editor!=null) editor.dismiss(); if(removal!=null) removal.dismiss(); super.onDestroy(); }
    private void render() {
        if(page==null) return; page.removeAllViews();
        add(page,text(getString(R.string.vp_title),29,INK,true),0);
        add(page,text(getString(R.string.vp_intro),16,MUTED,false),12);
        Switch enabled=new Switch(this); enabled.setText(R.string.vp_enabled); enabled.setTextSize(17); enabled.setMinHeight(dp(56)); enabled.setChecked(store.virtualUsersEnabled()); add(page,enabled,16);
        enabled.setOnCheckedChangeListener((button,checked) -> {
            if(checked && store.virtualUsers().isEmpty()) { render(); editName(0,true,""); return; }
            try { store.setVirtualUsersEnabled(checked); if(checked) NewReadingNotifications.requestPermissionIfNeeded(this); }
            catch(RuntimeException failed) { toast(R.string.vp_save_failed); }
            render();
        });
        add(page,text(getString(R.string.vp_mode_change_help),14,MUTED,false),8);
        add(page,text(getString(R.string.vp_pending_privacy),14,MUTED,false),10);
        if((store.virtualUsersEnabled() || store.pendingAssignmentsCount()>0) && !ProfileAssignmentNotifications.enabled(this)) {
            add(page,button(getString(R.string.settings_notice_allow),this::notificationSettings,false),10);
        }
        int pending=store.pendingAssignmentsCount();
        if(pending>0) {
            add(page,text(getString(R.string.vp_pending_count,pending),16,INK,true),16);
            add(page,button(getString(R.string.vp_assign_action),() -> startActivity(new Intent(this,AssignmentActivity.class)),true),8);
        }
        add(page,button(getString(R.string.vp_add),() -> editName(0,false,""),true),18);
        List<VirtualUser> profiles=store.virtualUsers();
        for(VirtualUser profile:profiles) {
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setTag("virtual_user_"+profile.id); card.setPadding(dp(14),dp(14),dp(14),dp(14)); card.setBackgroundColor(Color.WHITE);
            add(card,text(profile.name,21,INK,true),0);
            add(card,button(getString(R.string.vp_rename),() -> editName(profile.id,false,profile.name),false),8);
            Button delete=button(getString(R.string.vp_delete),() -> remove(profile),false); delete.setEnabled(profiles.size()>1); add(card,delete,6);
            if(profiles.size()==1) add(card,text(getString(R.string.vp_last_user),14,MUTED,false),6);
            add(page,card,14);
        }
        add(page,button(getString(R.string.main_close),this::finish,false),20);
    }
    private void editName(int id,boolean enable,String draft) {
        editingId=id; enablingAfterCreate=enable;
        nameField=new EditText(this); nameField.setHint(R.string.vp_name); nameField.setText(draft); nameField.setSingleLine(true); nameField.setSelectAllOnFocus(true); nameField.setTag("virtual_user_name");
        LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(22),dp(8),dp(22),dp(8)); add(form,nameField,0);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(enable?R.string.vp_first_user:id==0?R.string.vp_add:R.string.vp_rename).setView(form)
                .setNegativeButton(R.string.main_cancel,null).setPositiveButton(R.string.main_save,null).create();
        editor=dialog;
        dialog.setOnDismissListener(ignored -> { if(editor==dialog) { editor=null; editingId=-1; enablingAfterCreate=false; } render(); });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name=nameField.getText().toString().trim();
            if(name.isEmpty() || name.codePointCount(0,name.length())>100) { nameField.setError(getString(R.string.vp_name_invalid)); return; }
            try {
                if(editingId==0) editingId=store.createVirtualUser(name).id; else store.renameVirtualUser(editingId,name);
                if(enable) { store.setVirtualUsersEnabled(true); NewReadingNotifications.requestPermissionIfNeeded(this); }
                dialog.dismiss(); render();
            } catch(RuntimeException failed) { toast(R.string.vp_save_failed); }
        })); dialog.show();
    }
    private void remove(VirtualUser profile) {
        List<VirtualUser> replacements=new ArrayList<>(); for(VirtualUser candidate:store.virtualUsers()) if(candidate.id!=profile.id) replacements.add(candidate);
        if(replacements.isEmpty()) { toast(R.string.vp_last_user); return; }
        String[] names=new String[replacements.size()]; for(int i=0;i<names.length;i++) names[i]=replacements.get(i).name;
        int[] selected={-1};
        LinearLayout heading=new LinearLayout(this); heading.setOrientation(LinearLayout.VERTICAL); heading.setPadding(dp(22),dp(18),dp(22),dp(8));
        add(heading,text(getString(R.string.vp_delete_confirm,profile.name),21,INK,true),0);
        add(heading,text(getString(R.string.vp_delete_help),15,MUTED,false),8);
        AlertDialog dialog=new AlertDialog.Builder(this).setCustomTitle(heading)
                .setSingleChoiceItems(names,-1,(choice,index) -> { selected[0]=index; ((AlertDialog)choice).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); })
                .setNegativeButton(R.string.main_cancel,null).setPositiveButton(R.string.vp_delete,null).create();
        dialog.setOnShowListener(ignored -> {
            Button confirm=dialog.getButton(AlertDialog.BUTTON_POSITIVE); confirm.setEnabled(false);
            confirm.setOnClickListener(view -> {
                if(selected[0]<0) return;
                try { store.deleteVirtualUser(profile.id,replacements.get(selected[0]).id); dialog.dismiss(); render(); }
                catch(RuntimeException failed) { toast(R.string.vp_save_failed); }
            });
        });
        removal=dialog; dialog.setOnDismissListener(ignored -> { if(removal==dialog) removal=null; }); dialog.show();
    }
    private void notificationSettings() {
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED && !NewReadingNotifications.permissionRequested(this)) {
            NewReadingNotifications.requestPermission(this); return;
        }
        ProfileAssignmentNotifications.createChannel(this);
        Intent settings=new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,ProfileAssignmentNotifications.CHANNEL_ID);
        try { startActivity(settings); }
        catch(ActivityNotFoundException | SecurityException unavailable) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))); }
            catch(ActivityNotFoundException | SecurityException missing) { toast(R.string.vp_save_failed); }
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) { super.onRequestPermissionsResult(request,permissions,results); render(); }
    private TextView text(String value,int size,int color,boolean bold) { TextView view=new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); if(bold) view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return view; }
    private Button button(String value,Runnable action,boolean primary) { Button button=new Button(this); button.setText(value); button.setAllCaps(false); button.setTextSize(15); button.setMinHeight(dp(50)); button.setTextColor(primary?Color.WHITE:TEAL); button.setBackgroundTintList(ColorStateList.valueOf(primary?TEAL:0xffe9efea)); button.setOnClickListener(view -> action.run()); return button; }
    private void add(LinearLayout parent,View view,int top) { LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(top); parent.addView(view,params); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private void toast(int text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
}
