package org.openpressure.app.reminders;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

/** Private durable configuration and bounded, timestamp-only occurrence metadata. */
public final class ReminderStore {
    static final Object LOCK=new Object();
    private final Context context;
    public ReminderStore(Context context) { this.context=context.getApplicationContext(); }
    public List<Reminder> list() {
        synchronized(LOCK) {
            List<Reminder> result=new ArrayList<>();
            for(Entry entry:state().entries) result.add(entry.reminder);
            return Collections.unmodifiableList(result);
        }
    }
    public Reminder create(int hour,int minute,int daysMask,int skipMinutes) {
        synchronized(LOCK) {
            State state=state();
            if(state.nextId==Integer.MAX_VALUE) throw new IllegalStateException("Reminder identifiers exhausted");
            Reminder reminder=new Reminder(state.nextId++,hour,minute,daysMask,skipMinutes,true);
            Entry entry=new Entry(reminder); state.entries.add(entry);
            ReminderScheduler scheduler=new ReminderScheduler(context);
            scheduler.prepare(state,entry); write(state);
            scheduler.schedule(entry);
            return reminder;
        }
    }
    public void save(Reminder reminder) {
        if(reminder==null) throw new IllegalArgumentException("Missing reminder");
        synchronized(LOCK) {
            State state=state(); Entry old=state.find(reminder.id);
            if(old==null) throw new IllegalArgumentException("Unknown reminder");
            Entry entry=new Entry(reminder); state.entries.set(state.entries.indexOf(old),entry);
            ReminderScheduler scheduler=new ReminderScheduler(context);
            scheduler.prepare(state,entry); write(state);
            scheduler.cancel(old);
            if(reminder.enabled) scheduler.schedule(entry);
        }
    }
    public void delete(int id) {
        synchronized(LOCK) {
            State state=state(); Entry old=state.find(id);
            if(old==null) return;
            state.entries.remove(old); write(state);
            new ReminderScheduler(context).cancel(old);
            ReminderNotifications.cancel(context,id);
        }
    }
    static final class Entry {
        Reminder reminder;
        long due,token,suppressedDue;
        Entry(Reminder reminder) { this.reminder=reminder; }
    }
    static final class State {
        int nextId=1;
        long nextToken=1,latest;
        final List<Entry> entries=new ArrayList<>();
        Entry find(int id) { for(Entry entry:entries) if(entry.reminder.id==id) return entry; return null; }
    }
    private SharedPreferences preferences() { return context.getSharedPreferences("measurement_reminders",Context.MODE_PRIVATE); }
    State state() {
        synchronized(LOCK) {
            State result=new State();
            try {
                JSONObject root=new JSONObject(preferences().getString("state","{}"));
                result.nextId=root.optInt("nextId",1); result.nextToken=root.optLong("nextToken",1); result.latest=root.optLong("latest",0);
                JSONArray entries=root.optJSONArray("reminders");
                if(entries!=null) for(int i=0;i<entries.length();i++) {
                    JSONObject item=entries.getJSONObject(i);
                    Entry entry=new Entry(new Reminder(item.getInt("id"),item.getInt("hour"),item.getInt("minute"),item.getInt("days"),item.getInt("skip"),item.getBoolean("enabled")));
                    entry.due=item.optLong("due",0); entry.token=item.optLong("token",0); entry.suppressedDue=item.optLong("suppressed",0);
                    result.entries.add(entry);
                }
                return result;
            } catch(JSONException | IllegalArgumentException invalid) { throw new IllegalStateException("Could not read reminders",invalid); }
        }
    }
    void write(State state) {
        synchronized(LOCK) {
            try {
                JSONObject root=new JSONObject().put("nextId",state.nextId).put("nextToken",state.nextToken).put("latest",state.latest);
                JSONArray entries=new JSONArray();
                for(Entry entry:state.entries) {
                    Reminder r=entry.reminder;
                    entries.put(new JSONObject().put("id",r.id).put("hour",r.hour).put("minute",r.minute).put("days",r.daysMask).put("skip",r.skipMinutes).put("enabled",r.enabled).put("due",entry.due).put("token",entry.token).put("suppressed",entry.suppressedDue));
                }
                root.put("reminders",entries);
                SharedPreferences p=preferences(); String previous=p.getString("state","{}");
                if(!p.edit().putString("state",root.toString()).commit()) {
                    p.edit().putString("state",previous).commit();
                    throw new IllegalStateException("Could not save reminders");
                }
            } catch(JSONException invalid) { throw new IllegalStateException("Could not save reminders",invalid); }
        }
    }
}
