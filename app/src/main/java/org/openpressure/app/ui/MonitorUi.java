package org.openpressure.app.ui;

import android.content.Context;
import org.openpressure.app.R;
import org.openpressure.app.automatic.AutoImportSettings;
import org.openpressure.app.ble.MonitorService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** User-facing presentation of connection state; detailed protocol messages stay in diagnostics. */
public final class MonitorUi {
    private MonitorUi() {}
    public static String title(Context context,MonitorService monitor) {
        return context.getString(titleResource(monitor));
    }
    private static int titleResource(MonitorService m) {
        String state=m.status;
        if(state.equals("Bluetooth is off")) return R.string.monitor_bluetooth_off;
        if(state.equals("Bluetooth permission needed") || state.equals("Location permission needed")) return R.string.monitor_permission_needed;
        if(state.equals("Consent required")) return R.string.monitor_consent_needed;
        if(state.equals("History transfer incomplete") || state.equals("Transfer interrupted")) return R.string.monitor_import_interrupted;
        if(state.equals("Action failed") || state.equals("EEPROM export could not be saved")) return R.string.monitor_action_failed;
        if(state.equals("User access rejected")) return R.string.monitor_profile_access_failed;
        if(state.equals("Authenticate first")) return R.string.monitor_profile_access_needed;
        if(state.equals("No monitors found") || state.equals("X7 advertisement not received")) return R.string.monitor_not_found;
        if(state.equals("Invalid EEPROM range") || state.equals("Invalid sequence") || state.equals("Check user settings")) return R.string.monitor_check_input;
        if(m.registrationPending) return R.string.monitor_choose_profile;
        if(m.registrationReading) return R.string.monitor_preparing_setup;
        if(m.registrationWriting) return R.string.monitor_finishing_setup;
        if(m.registrationDone) return R.string.monitor_setup_complete;
        if(m.dumpSaving) return R.string.monitor_saving_export;
        if(m.dumpRunning) return R.string.monitor_reading_memory;
        if(m.historyRunning) return R.string.monitor_importing;
        if(state.equals("History transfer complete")) return R.string.monitor_import_complete;
        if(m.sessionFinished) return R.string.monitor_finished;
        if(state.equals("Clock write accepted")) return R.string.monitor_time_updated;
        if(state.equals("Clock sync unavailable")) return R.string.monitor_time_not_updated;
        if(state.equals("User authenticated") || state.equals("User registered")) return R.string.monitor_profile_ready;
        if(m.ready) return R.string.monitor_connected;
        if(m.scanning) return R.string.monitor_searching;
        if(state.equals("Pair on your phone") || state.equals("Confirm fresh X7 pairing")) return R.string.monitor_confirm_pairing;
        if(state.equals("Confirm access on X7") || state.equals("Waiting for X7 confirmation…")) return R.string.monitor_confirm_access;
        if(state.equals("Disconnected")) return R.string.monitor_disconnected;
        if(state.equals("Scan finished")) return R.string.monitor_search_finished;
        if(state.equals("Disconnect before scanning") || state.equals("Action in progress")) return R.string.monitor_action_in_progress;
        if(!m.address.isEmpty() && !state.equals("Ready to connect")) return R.string.monitor_connecting;
        return R.string.monitor_ready;
    }
    public static String detail(Context context,MonitorService m) {
        int title=titleResource(m);
        if(title==R.string.monitor_bluetooth_off) return context.getString(R.string.monitor_enable_bluetooth);
        if(title==R.string.monitor_permission_needed) return context.getString(R.string.monitor_allow_bluetooth);
        if(title==R.string.monitor_consent_needed) return context.getString(R.string.monitor_accept_notice);
        if(title==R.string.monitor_import_interrupted) return importDetail(context,m,context.getString(R.string.monitor_partial_saved));
        if(title==R.string.monitor_action_failed) {
            if(m.detail.toLowerCase(Locale.ROOT).contains("registration")) return context.getString(R.string.monitor_setup_interrupted);
            return context.getString(R.string.monitor_retry_action);
        }
        if(title==R.string.monitor_profile_access_failed || title==R.string.monitor_profile_access_needed) return context.getString(R.string.monitor_check_profile);
        if(title==R.string.monitor_not_found || title==R.string.monitor_searching) return context.getString(R.string.monitor_wake_nearby);
        if(title==R.string.monitor_check_input) return context.getString(R.string.monitor_check_values);
        if(m.registrationPending) return context.getString(R.string.monitor_select_profile);
        if(m.registrationWriting || m.registrationReading) return context.getString(R.string.monitor_keep_nearby);
        if(m.dumpRunning) return context.getString(R.string.monitor_memory_progress,m.dumpReceived,m.dumpLength);
        if(m.historyRunning) return m.storedCount>=0?context.getString(R.string.monitor_import_progress,m.receivedInTransfer,m.storedCount):context.getString(R.string.monitor_keep_nearby);
        if(title==R.string.monitor_import_complete) {
            String summary=m.storedInTransfer>0 || m.queuedInTransfer==0 && m.pendingInTransfer==0?
                context.getResources().getQuantityString(R.plurals.monitor_readings_saved,m.storedInTransfer,m.storedInTransfer):"";
            if(m.queuedInTransfer>0) summary+=(summary.isEmpty()?"":"\n\n")+context.getString(R.string.http_import_count,m.queuedInTransfer);
            if(m.pendingInTransfer>0) summary+=(summary.isEmpty()?"":"\n\n")+context.getString(R.string.vp_pending_count,m.pendingInTransfer);
            String mode=importMode(context,m);
            if(!mode.isEmpty()) summary+=(summary.isEmpty()?"":"\n\n")+mode;
            return importDetail(context,m,summary);
        }
        if(m.registrationDone || m.sessionFinished) return context.getString(R.string.monitor_reconnect_next_time);
        if(title==R.string.monitor_confirm_pairing) return context.getString(R.string.monitor_accept_android_pairing);
        if(title==R.string.monitor_confirm_access) return context.getString(R.string.monitor_follow_monitor);
        if(title==R.string.monitor_time_updated) return context.getString(R.string.monitor_check_clock);
        if(title==R.string.monitor_time_not_updated) return context.getString(R.string.monitor_clock_failed)+
            (m.ready?"":"\n\n"+context.getString(R.string.monitor_reconnect_next_time));
        if(title==R.string.monitor_profile_ready || m.ready) return context.getString(R.string.monitor_choose_action);
        if(title==R.string.monitor_action_in_progress) return context.getString(R.string.monitor_wait_current_action);
        if(title==R.string.monitor_connecting) return context.getString(R.string.monitor_keep_nearby);
        if(title==R.string.monitor_disconnected) return context.getString(R.string.monitor_saved_on_phone);
        return context.getString(R.string.monitor_scan_to_connect);
    }
    /** How a vendor-memory import chose its slots, in plain language; empty for other protocols. */
    private static String importMode(Context context,MonitorService m) {
        String mode;
        switch(m.importMode) {
            case INDEX: mode=m.indexStoredCount>0?context.getResources().getQuantityString(R.plurals.monitor_import_mode_index,m.indexStoredCount,m.indexStoredCount)
                    :m.indexStoredCount==0?context.getString(R.string.monitor_import_mode_empty):""; break;
            case INDEX_FALLBACK: mode=context.getString(R.string.monitor_import_mode_fallback); break;
            case FULL_SWEEP: case FULL_READ: mode=context.getString(R.string.monitor_import_mode_sweep); break;
            default: mode=""; break;
        }
        if(m.unconfirmedBankSkipped) mode+=(mode.isEmpty()?"":"\n\n")+context.getString(R.string.monitor_unconfirmed_bank_skipped);
        return mode;
    }
    private static String importDetail(Context context,MonitorService m,String summary) {
        int clock=0;
        switch(m.clockUpdate) {
            case SKIPPED: clock=R.string.monitor_clock_skipped; break;
            case ACCEPTED: clock=R.string.monitor_check_clock; break;
            case FAILED: clock=R.string.monitor_clock_failed; break;
            default: break;
        }
        if(clock!=0) summary+="\n\n"+context.getString(clock);
        if(m.sessionEndUnconfirmed) summary+="\n\n"+context.getString(R.string.monitor_session_unconfirmed);
        return summary;
    }
    public static String automatic(Context context,MonitorService m) {
        int mode=AutoImportSettings.getMode(context);
        String status=m.automaticStatus;
        if(mode==AutoImportSettings.OFF) return context.getString(R.string.monitor_auto_off);
        if(status.contains("Bluetooth to be enabled")) return context.getString(R.string.monitor_enable_bluetooth);
        if(status.contains("grant Bluetooth")) return context.getString(R.string.monitor_allow_bluetooth);
        if(status.startsWith("Pair or connect")) return context.getString(R.string.monitor_auto_add_monitor);
        if(status.contains("failed") || status.startsWith("Could not") || status.startsWith("Android could not") || status.startsWith("Automatic import could not")) return context.getString(R.string.monitor_auto_retry);
        if(status.startsWith("Importing")) return context.getString(R.string.monitor_importing);
        if(status.contains("current operation")) return context.getString(R.string.monitor_wait_current_action);
        if(status.startsWith("Associate")) return context.getString(R.string.monitor_auto_approve);
        if(mode==AutoImportSettings.COMPANION) return context.getString(R.string.monitor_auto_companion);
        if(mode==AutoImportSettings.NOTIFICATION) return context.getString(R.string.monitor_auto_background);
        return context.getString(R.string.monitor_auto_visible);
    }
    public static List<String> readingFlags(Context context,Integer status) {
        List<String> result=new ArrayList<>();
        if(status==null) return result;
        if((status&1)!=0) result.add(context.getString(R.string.monitor_flag_movement));
        if((status&2)!=0) result.add(context.getString(R.string.monitor_flag_loose_cuff));
        if((status&4)!=0) result.add(context.getString(R.string.monitor_flag_irregular_pulse));
        switch((status>>>3)&3) {
            case 1: result.add(context.getString(R.string.monitor_flag_high_pulse)); break;
            case 2: result.add(context.getString(R.string.monitor_flag_low_pulse)); break;
            case 3: result.add(context.getString(R.string.monitor_flag_pulse_unavailable)); break;
            default: break;
        }
        if((status&32)!=0) result.add(context.getString(R.string.monitor_flag_position));
        if((status&~63)!=0) result.add(context.getString(R.string.monitor_flag_other));
        return result;
    }
}
