package org.openpressure.app.data;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import java.time.LocalDateTime;
import org.json.*;
import org.openpressure.protocol.BloodPressure;
import org.openpressure.app.forwarding.*;
import org.openpressure.app.reminders.ReminderMeasurements;

public final class ReadingStore extends SQLiteOpenHelper {
    private Runnable changeListener,forwardingListener,profileListener;
    private final Context context;
    private final HttpOutbox httpOutbox=new HttpOutbox(this);
    public ReadingStore(Context context) { super(context,"pressure.db",null,8); this.context=context.getApplicationContext(); }
    public HttpOutbox httpOutbox() { return httpOutbox; }
    public synchronized void setForwardingListener(Runnable listener) { forwardingListener=listener; }
    @Override public void onConfigure(SQLiteDatabase db) { db.enableWriteAheadLogging(); db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE readings(id INTEGER PRIMARY KEY, fingerprint TEXT UNIQUE, device TEXT NOT NULL, name TEXT NOT NULL, time TEXT, received INTEGER NOT NULL, sys REAL NOT NULL, dia REAL NOT NULL, map REAL, pulse REAL, user INTEGER, status INTEGER, afib INTEGER, unit TEXT NOT NULL, source TEXT NOT NULL, raw TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX reading_time ON readings(time)");
        db.execSQL("CREATE TABLE devices(address TEXT PRIMARY KEY, name TEXT NOT NULL, model TEXT NOT NULL, user INTEGER NOT NULL)");
        addImportUsers(db);
        addReportedModel(db);
        db.execSQL("CREATE TABLE packets(id INTEGER PRIMARY KEY, received INTEGER NOT NULL, device TEXT NOT NULL, characteristic TEXT NOT NULL, payload TEXT NOT NULL, note TEXT NOT NULL)");
        createHealthExports(db);
        createDeletionReceipts(db);
        createHttpForwarding(db);
        createVirtualUsers(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<1 || newVersion!=8) throw new IllegalStateException("Unsupported database version");
        if(oldVersion<2) db.execSQL("ALTER TABLE readings ADD COLUMN afib INTEGER");
        if(oldVersion<3) createHealthExports(db);
        if(oldVersion<4) createDeletionReceipts(db);
        if(oldVersion<5) createHttpForwarding(db);
        if(oldVersion<6) createVirtualUsers(db);
        if(oldVersion<7) addImportUsers(db);
        if(oldVersion<8) addReportedModel(db);
    }
    // Which monitor user banks automatic imports read: 1, 2 or 3 (both). Existing monitors keep both.
    private void addImportUsers(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE devices ADD COLUMN users INTEGER NOT NULL DEFAULT 3");
    }
    // Model from Device Information on standard profiles: display only. The model column stays the
    // verified identity that grants automatic imports and import-user choices; existing rows keep it.
    private void addReportedModel(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE devices ADD COLUMN reported_model TEXT");
    }
    private void createVirtualUsers(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE virtual_users(id INTEGER PRIMARY KEY AUTOINCREMENT CHECK(id>0 AND id<=2147483647), name TEXT NOT NULL CHECK(length(trim(name))>0))");
        db.execSQL("ALTER TABLE readings ADD COLUMN virtual_user_id INTEGER REFERENCES virtual_users(id)");
        db.execSQL("CREATE INDEX reading_profile ON readings(virtual_user_id)");
        db.execSQL("CREATE TABLE profile_configuration(id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN (0,1)), revision INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("INSERT INTO profile_configuration(id,enabled) VALUES(1,0)");
        db.execSQL("CREATE TABLE assignment_inbox(id INTEGER PRIMARY KEY AUTOINCREMENT, event_key TEXT NOT NULL UNIQUE, payload TEXT NOT NULL, keep_local INTEGER NOT NULL CHECK(keep_local IN (0,1)), endpoint TEXT, sort_time INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE assignment_receipts(event_key TEXT PRIMARY KEY NOT NULL, profile_id INTEGER NOT NULL REFERENCES virtual_users(id))");
    }
    public synchronized void setProfileListener(Runnable listener) { profileListener=listener; }
    private void profilesChanged() { notifyCommitted(profileListener); }
    private static void notifyCommitted(Runnable listener) {
        if(listener==null) return;
        try { listener.run(); }
        catch(RuntimeException unavailable) { /* Scheduling can recover later; committed data remains accepted. */ }
    }
    public synchronized boolean virtualUsersEnabled() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT enabled FROM profile_configuration WHERE id=1",null)) { c.moveToFirst(); return c.getInt(0)!=0; }
    }
    public synchronized long virtualUsersRevision() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT revision FROM profile_configuration WHERE id=1",null)) { c.moveToFirst(); return c.getLong(0); }
    }
    public synchronized void setVirtualUsersEnabled(boolean enabled) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            if(enabled && virtualUsers().isEmpty()) throw new IllegalStateException("Create a person before enabling virtual users");
            if(virtualUsersEnabled()!=enabled) db.execSQL("UPDATE profile_configuration SET enabled=?,revision=revision+1 WHERE id=1",new Object[]{enabled?1:0});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        profilesChanged();
    }
    public synchronized List<VirtualUser> virtualUsers() {
        List<VirtualUser> users=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,name FROM virtual_users ORDER BY id",null)) { while(c.moveToNext()) users.add(new VirtualUser(c.getInt(0),c.getString(1))); }
        return Collections.unmodifiableList(users);
    }
    private static String profileName(String name) {
        if(name==null || name.trim().isEmpty() || name.trim().codePointCount(0,name.trim().length())>100) throw new IllegalArgumentException("Enter a name of 1 to 100 characters");
        return name.trim();
    }
    public synchronized VirtualUser createVirtualUser(String name) {
        String valid=profileName(name); ContentValues v=new ContentValues(); v.put("name",valid);
        int id=(int)getWritableDatabase().insertOrThrow("virtual_users",null,v); profilesChanged(); return new VirtualUser(id,valid);
    }
    public synchronized void renameVirtualUser(int id,String name) {
        ContentValues v=new ContentValues(); v.put("name",profileName(name));
        if(getWritableDatabase().update("virtual_users",v,"id=?",new String[]{""+id})!=1) throw new IllegalArgumentException("Unknown person");
        profilesChanged();
    }
    private String requireProfile(SQLiteDatabase db,int id) {
        try(Cursor c=db.rawQuery("SELECT name FROM virtual_users WHERE id=?",new String[]{""+id})) { if(c.moveToFirst()) return c.getString(0); }
        throw new IllegalArgumentException("Unknown person");
    }
    public synchronized void deleteVirtualUser(int id,int replacementId) {
        if(id==replacementId) throw new IllegalArgumentException("Choose another person");
        SQLiteDatabase db=getWritableDatabase(); int reassigned; db.beginTransaction();
        try {
            requireProfile(db,id); requireProfile(db,replacementId);
            ContentValues v=new ContentValues(); v.put("virtual_user_id",replacementId); reassigned=db.update("readings",v,"virtual_user_id=?",new String[]{""+id});
            v.clear(); v.put("profile_id",replacementId); db.update("assignment_receipts",v,"profile_id=?",new String[]{""+id});
            db.delete("virtual_users","id=?",new String[]{""+id}); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        profilesChanged(); if(reassigned>0) readingsChanged();
    }
    public synchronized int pendingAssignmentsCount() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM assignment_inbox",null)) { c.moveToFirst(); return c.getInt(0); }
    }
    public synchronized Reading nextPendingAssignment() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,payload FROM assignment_inbox ORDER BY sort_time,id LIMIT 1",null)) {
            if(!c.moveToFirst()) return null;
            ContentValues values=decode(c.getString(1)); values.put("id",c.getLong(0));
            for(String field:new String[]{"map","pulse","user","status","afib"}) if(!values.containsKey(field)) values.putNull(field);
            try(android.database.MatrixCursor row=new android.database.MatrixCursor(values.keySet().toArray(new String[0]))) {
                Object[] cells=new Object[values.size()]; int i=0; for(String key:values.keySet()) cells[i++]=values.get(key); row.addRow(cells); row.moveToFirst(); return reading(row);
            }
        }
    }
    public synchronized void assignPending(long pendingId,int profileId) {
        SQLiteDatabase db=getWritableDatabase(); boolean local=false,queued=false,assigned=false; db.beginTransaction();
        try {
            String name=requireProfile(db,profileId);
            try(Cursor c=db.rawQuery("SELECT event_key,payload,keep_local,endpoint FROM assignment_inbox WHERE id=?",new String[]{""+pendingId})) {
                if(!c.moveToFirst()) return;
                String key=c.getString(0),endpoint=c.getString(3); ContentValues values=decode(c.getString(1));
                values.put("virtual_user_id",profileId);
                if(endpoint!=null) { ContentValues wire=new ContentValues(values); wire.put("virtual_user_name",name); queued=HttpOutbox.enqueue(db,endpoint,key,wire); }
                if(c.getInt(2)!=0) { db.insertOrThrow("readings",null,values); local=true; }
                ContentValues receipt=new ContentValues(); receipt.put("event_key",key); receipt.put("profile_id",profileId); db.insertOrThrow("assignment_receipts",null,receipt);
                db.delete("assignment_inbox","id=?",new String[]{""+pendingId}); assigned=true;
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if(local) readingsChanged(); if(queued) notifyCommitted(forwardingListener); if(assigned) profilesChanged();
    }
    private static String encode(ContentValues values) {
        JSONObject json=new JSONObject();
        try { for(String key:values.keySet()) json.put(key,values.get(key)==null?JSONObject.NULL:values.get(key)); return json.toString(); }
        catch(JSONException invalid) { throw new IllegalArgumentException("Invalid pending reading",invalid); }
    }
    private static ContentValues decode(String payload) {
        try {
            JSONObject json=new JSONObject(payload); ContentValues values=new ContentValues();
            Iterator<String> keys=json.keys(); while(keys.hasNext()) {
                String key=keys.next(); Object value=json.get(key);
                if(value==JSONObject.NULL) values.putNull(key);
                else if(value instanceof Integer) values.put(key,(Integer)value);
                else if(value instanceof Long) values.put(key,(Long)value);
                else if(value instanceof Number) values.put(key,((Number)value).doubleValue());
                else values.put(key,value.toString());
            }
            return values;
        } catch(JSONException invalid) { throw new IllegalStateException("Cannot read durable pending measurement",invalid); }
    }
    private static long assignmentTime(ContentValues values) {
        try { String time=values.getAsString("time"); if(time!=null) return LocalDateTime.parse(time).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(); }
        catch(java.time.DateTimeException | ArithmeticException invalid) { /* Fall back to receipt time. */ }
        return values.getAsLong("received");
    }
    private void createHttpForwarding(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE http_outbox(id INTEGER PRIMARY KEY AUTOINCREMENT, endpoint TEXT NOT NULL, event_key TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(endpoint,event_key))");
        db.execSQL("CREATE TABLE http_receipts(destination TEXT NOT NULL, event_key TEXT NOT NULL, PRIMARY KEY(destination,event_key))");
    }
    private void createDeletionReceipts(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE reading_tombstones(receipt TEXT PRIMARY KEY NOT NULL)");
    }
    private void createHealthExports(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE health_exports(reading_id INTEGER PRIMARY KEY REFERENCES readings(id) ON DELETE CASCADE, client_id TEXT UNIQUE, record_time TEXT, offset_seconds INTEGER, state INTEGER NOT NULL DEFAULT 0 CHECK(state IN (0,1,2)), error TEXT NOT NULL DEFAULT '', exported_at INTEGER)");
    }
    public synchronized void setChangeListener(Runnable listener) { changeListener=listener; }
    private void readingsChanged() { notifyCommitted(changeListener); }
    /** The committed ingestion outcome, independent of later forwarding settings changes. */
    public static final class IngestionResult {
        public final boolean stored,queued,pending;
        private IngestionResult(boolean stored,boolean queued) { this(stored,queued,false); }
        private IngestionResult(boolean stored,boolean queued,boolean pending) { this.stored=stored; this.queued=queued; this.pending=pending; }
        public boolean accepted() { return stored || queued || pending; }
    }
    public synchronized boolean add(String device,String name,BloodPressure bp,byte[] raw,String source,Integer fallbackUser) {
        return add(device,name,bp,raw,source,fallbackUser,false);
    }
    public synchronized boolean add(String device,String name,BloodPressure bp,byte[] raw,String source,Integer fallbackUser,boolean automatic) {
        return addResult(device,name,bp,raw,source,fallbackUser,automatic).accepted();
    }
    public synchronized IngestionResult addResult(String device,String name,BloodPressure bp,byte[] raw,String source,Integer fallbackUser,boolean automatic) {
        Integer user=bp.userId==null ? fallbackUser : bp.userId;
        String timestamp=bp.time==null ? null : bp.time.toString();
        // Stored memory packets retain their raw identity even with an unset clock; live SIG values do not.
        String fingerprint=ReadingFingerprint.create(device,user,timestamp,hex(raw),source);
        ContentValues v=new ContentValues(); v.put("fingerprint",fingerprint); v.put("device",device); v.put("name",name);
        v.put("time",timestamp); v.put("received",System.currentTimeMillis()); v.put("sys",bp.systolic); v.put("dia",bp.diastolic);
        if(Double.isFinite(bp.meanArterialPressure)) v.put("map",bp.meanArterialPressure);
        v.put("pulse",bp.pulse); v.put("user",user); v.put("status",bp.status); v.put("unit",bp.kpa?"kPa":"mmHg");
        if(bp.atrialFibrillation!=null) v.put("afib",bp.atrialFibrillation?1:0);
        v.put("source",source); v.put("raw",hex(raw));
        return ingest(v,automatic);
    }
    private IngestionResult ingest(ContentValues values,boolean automatic) {
        String fingerprint=values.getAsString("fingerprint"), key=HttpPayload.sha256(fingerprint);
        HttpForwardingSettings.Config config=HttpForwardingSettings.get(context);
        boolean forwarding=config.enabled() && ExperimentalConsent.accepted(context);
        boolean local=!forwarding || config.keepLocal;
        SQLiteDatabase db=getWritableDatabase();
        boolean inserted=false,queued=false,pending=false;
        db.beginTransaction();
        try {
            try(Cursor c=db.rawQuery("SELECT 1 FROM readings WHERE fingerprint=?",new String[]{fingerprint})) {
                if(c.moveToFirst()) return new IngestionResult(false,false);
            }
            if(automatic) try(Cursor c=db.rawQuery("SELECT 1 FROM reading_tombstones WHERE receipt=?",new String[]{key})) {
                if(c.moveToFirst()) return new IngestionResult(false,false);
            }
            try(Cursor c=db.rawQuery("SELECT 1 FROM assignment_inbox WHERE event_key=?",new String[]{key})) {
                if(c.moveToFirst()) return new IngestionResult(false,false);
            }
            Integer assigned=null;
            try(Cursor c=db.rawQuery("SELECT profile_id FROM assignment_receipts WHERE event_key=?",new String[]{key})) { if(c.moveToFirst()) assigned=c.getInt(0); }
            boolean previouslyForwarded;
            try(Cursor c=db.rawQuery("SELECT 1 FROM http_outbox WHERE event_key=? UNION ALL SELECT 1 FROM http_receipts WHERE event_key=? LIMIT 1",new String[]{key,key})) { previouslyForwarded=c.moveToFirst(); }
            // Old deliveries suppress duplicate prompts only for destinations that already
            // accepted this event. A new destination still needs an explicit owner.
            boolean newDestination=false;
            if(forwarding) try(Cursor c=db.rawQuery("SELECT 1 FROM http_outbox WHERE endpoint=? AND event_key=? UNION ALL SELECT 1 FROM http_receipts WHERE destination=? AND event_key=? LIMIT 1",new String[]{config.endpoint,key,HttpPayload.sha256(config.endpoint),key})) { newDestination=!c.moveToFirst(); }
            if(virtualUsersEnabled() && assigned==null && (!previouslyForwarded || newDestination)) {
                ContentValues staged=new ContentValues(values);
                if(!local) { staged.put("fingerprint",key); staged.put("raw",""); staged.put("notes",""); }
                if(!staged.containsKey("notes")) staged.put("notes","");
                ContentValues row=new ContentValues(); row.put("event_key",key); row.put("payload",encode(staged)); row.put("keep_local",local?1:0);
                row.put("endpoint",forwarding?config.endpoint:null); row.put("sort_time",assignmentTime(values));
                db.insertOrThrow("assignment_inbox",null,row); pending=true;
            } else {
                if(assigned!=null) values.put("virtual_user_id",assigned);
                if(forwarding) {
                    ContentValues wire=new ContentValues(values); if(assigned!=null) wire.put("virtual_user_name",requireProfile(db,assigned));
                    queued=HttpOutbox.enqueue(db,config.endpoint,key,wire);
                }
                if(local) { db.insertOrThrow("readings",null,values); inserted=true; }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if(inserted || queued || pending) recordReminderMeasurement(values);
        if(inserted) readingsChanged();
        if(queued) notifyCommitted(forwardingListener);
        if(pending) profilesChanged();
        return new IngestionResult(inserted,queued,pending);
    }
    private void recordReminderMeasurement(ContentValues values) {
        try {
            String timestamp=values.getAsString("time");
            ReminderMeasurements.record(context,timestamp==null?null:LocalDateTime.parse(timestamp),
                    values.getAsLong("received"),values.getAsString("source"));
        } catch(RuntimeException unavailable) {
            // Reminder metadata is best effort; the accepted reading is already durable.
        }
    }
    /** Recover recent timing from existing dated history when upgrading from versions without reminders. */
    public synchronized void restoreReminderMeasurement() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT time,received,source FROM readings WHERE time IS NOT NULL ORDER BY time DESC",null)) {
            while(c.moveToNext()) {
                try {
                    LocalDateTime time=LocalDateTime.parse(c.getString(0));
                    long epoch=time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    if(epoch<=0 || epoch>c.getLong(1) || epoch>System.currentTimeMillis()) continue;
                    // Validate the original receipt above, but merge against today's clock so
                    // old history cannot reset newer live/passthrough timing during startup.
                    ReminderMeasurements.record(context,time,System.currentTimeMillis(),c.getString(2));
                    break;
                } catch(java.time.DateTimeException | ArithmeticException invalid) { /* Skip unusable stored clocks. */ }
            }
        } catch(RuntimeException unavailable) { /* Timing recovery must not prevent normal app startup. */ }
    }
    public synchronized void addManual(double sys,double dia,Double pulse,int user,LocalDateTime time,String notes) {
        if(!Double.isFinite(sys)||!Double.isFinite(dia)||sys<=dia||dia<=0||sys>400||dia>300||pulse!=null&&(!Double.isFinite(pulse)||pulse<=0||pulse>300)) throw new IllegalArgumentException("Check the pressure and pulse values");
        ContentValues v=new ContentValues(); v.put("fingerprint",UUID.randomUUID().toString()); v.put("device","manual"); v.put("name","Manual entry");
        v.put("time",time.toString()); v.put("received",System.currentTimeMillis()); v.put("sys",sys); v.put("dia",dia); v.put("pulse",pulse);
        v.put("user",user); v.put("unit","mmHg"); v.put("source","manual"); v.put("raw",""); v.put("notes",notes);
        ingest(v,false);
    }
    private static void validateScope(int user) { if(user>2 || user==Integer.MIN_VALUE) throw new IllegalArgumentException("Select a person or monitor bank"); }
    private static String[] scopeArgs(int user) { validateScope(user); String value=""+user; return new String[]{value,value,value,value,value}; }
    private String healthScope(String table,int user) {
        validateScope(user); if(user==0) throw new IllegalArgumentException("Choose one person");
        if(user<0) return table+".virtual_user_id=?";
        return table+".user=?"+(virtualUsersEnabled()?" AND 0":"");
    }
    public synchronized List<Reading> readings(int user) {
        List<Reading> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT r.*, (SELECT name FROM virtual_users WHERE id=r.virtual_user_id) AS virtual_user_name FROM readings r WHERE (CAST(? AS INTEGER)=0 OR (CAST(? AS INTEGER)<0 AND r.virtual_user_id=-CAST(? AS INTEGER)) OR (CAST(? AS INTEGER)>0 AND r.user=?)) ORDER BY COALESCE(time, strftime('%Y-%m-%dT%H:%M:%S', received/1000, 'unixepoch', 'localtime')) DESC, id DESC",scopeArgs(user))) {
            while(c.moveToNext()) {
                result.add(reading(c));
            }
        } return result;
    }
    public synchronized void note(long id,String note) { ContentValues v=new ContentValues(); v.put("notes",note); getWritableDatabase().update("readings",v,"id=?",new String[]{""+id}); }
    public synchronized void delete(long id) {
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            try(Cursor c=db.rawQuery("SELECT fingerprint FROM readings WHERE id=?",new String[]{""+id})) {
                if(c.moveToFirst()) {
                    ContentValues receipt=new ContentValues(); receipt.put("receipt",DeletionReceipt.key(c.getString(0)));
                    // REPLACE is safe for the single-column receipt and throws on a storage failure.
                    db.replaceOrThrow("reading_tombstones",null,receipt);
                }
            }
            db.delete("readings","id=?",new String[]{""+id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized int historyCount(int user) {
        validateScope(user);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM readings WHERE (CAST(? AS INTEGER)=0 OR (CAST(? AS INTEGER)<0 AND virtual_user_id=-CAST(? AS INTEGER)) OR (CAST(? AS INTEGER)>0 AND user=?))",scopeArgs(user))) {
            c.moveToFirst(); return c.getInt(0);
        }
    }
    public synchronized int clearHistory(int user) {
        validateScope(user);
        SQLiteDatabase db=getWritableDatabase();
        String[] selection=scopeArgs(user);
        int removed;
        db.beginTransaction();
        try {
            // Retain only hashes so automatic full-history refreshes cannot restore cleared readings.
            // Reuse one compiled statement inside the same transaction as the deletion.
            try(Cursor c=db.rawQuery("SELECT fingerprint FROM readings WHERE (CAST(? AS INTEGER)=0 OR (CAST(? AS INTEGER)<0 AND virtual_user_id=-CAST(? AS INTEGER)) OR (CAST(? AS INTEGER)>0 AND user=?))",selection);
                SQLiteStatement receipt=db.compileStatement("INSERT OR REPLACE INTO reading_tombstones(receipt) VALUES(?)")) {
                while(c.moveToNext()) {
                    receipt.bindString(1,DeletionReceipt.key(c.getString(0)));
                    receipt.executeInsert();
                }
            }
            removed=db.delete("readings","(CAST(? AS INTEGER)=0 OR (CAST(? AS INTEGER)<0 AND virtual_user_id=-CAST(? AS INTEGER)) OR (CAST(? AS INTEGER)>0 AND user=?))",selection);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if(removed>0) readingsChanged();
        return removed;
    }
    public synchronized void remember(String address,String name,String model,int user) {
        // Update-or-insert keeps the saved import users selection; SQLite UPSERT is unavailable before Android 11.
        ContentValues v=new ContentValues(); v.put("name",name); v.put("model",model); v.put("user",user);
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            if(db.update("devices",v,"address=?",new String[]{address})==0) { v.put("address",address); db.insertOrThrow("devices",null,v); }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized void setImportUsers(String address,int users) {
        if(users<1 || users>3) throw new IllegalArgumentException("Import users must be 1, 2 or 3");
        ContentValues v=new ContentValues(); v.put("users",users);
        getWritableDatabase().update("devices",v,"address=?",new String[]{address});
    }
    public synchronized int importUsers(String address) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT users FROM devices WHERE address=?",new String[]{address})) { return c.moveToFirst()?c.getInt(0):3; }
    }
    public synchronized List<String[]> devices() {
        List<String[]> rows=new ArrayList<>(); try(Cursor c=getReadableDatabase().rawQuery("SELECT address,name,model,user,users,reported_model FROM devices ORDER BY name",null)) {
            while(c.moveToNext()) rows.add(new String[]{c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5)});
        } return rows;
    }
    /** Model to name a {@link #devices()} row by: its reported Device Information model when present, else its saved model. */
    public static String displayModel(String[] device) {
        return device.length>5 && device[5]!=null && !device[5].trim().isEmpty()?device[5]:device[2];
    }
    /**
     * Stores the model a standard-profile monitor reported in Device Information, for display only;
     * the saved model, which grants capabilities, is unchanged. Unsaved addresses are left unsaved.
     */
    public synchronized void setReportedModel(String address,String value) {
        ContentValues v=new ContentValues(); v.put("reported_model",value);
        getWritableDatabase().update("devices",v,"address=?",new String[]{address});
    }
    public synchronized void forget(String address) { getWritableDatabase().delete("devices","address=?",new String[]{address}); }
    public synchronized List<Reading> healthPending(int user,int limit) {
        if(user==0 || user>2 || user==Integer.MIN_VALUE || limit<1 || limit>500) throw new IllegalArgumentException("Select one user bank and a bounded batch");
        List<Reading> pending=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT r.*, (SELECT name FROM virtual_users WHERE id=r.virtual_user_id) AS virtual_user_name, h.client_id AS health_client_id, h.record_time AS health_time, h.offset_seconds AS health_offset FROM readings r LEFT JOIN health_exports h ON h.reading_id=r.id WHERE "+healthScope("r",user)+" AND COALESCE(h.state,0)=0 ORDER BY r.id LIMIT ?",new String[]{""+(user<0?-(long)user:user),""+limit})) {
            while(c.moveToNext()) pending.add(reading(c));
        }
        return pending;
    }
    public synchronized boolean hasReadingIdentity(long id,String fingerprint) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM readings WHERE id=? AND fingerprint=?",new String[]{""+id,fingerprint})) { return c.moveToFirst(); }
    }
    public synchronized boolean hasReadingIdentity(long id,String fingerprint,int userFilter) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM readings r WHERE r.id=? AND r.fingerprint=? AND "+healthScope("r",userFilter),new String[]{""+id,fingerprint,""+(userFilter<0?-(long)userFilter:userFilter)})) { return c.moveToFirst(); }
    }
    public synchronized boolean prepareHealthExport(long id,String fingerprint,String clientId,String time,int offsetSeconds) {
        if(!hasReadingIdentity(id,fingerprint)) return false;
        ContentValues prepared=new ContentValues(); prepared.put("reading_id",id); prepared.put("client_id",clientId); prepared.put("record_time",time); prepared.put("offset_seconds",offsetSeconds);
        SQLiteDatabase db=getWritableDatabase();
        db.insertWithOnConflict("health_exports",null,prepared,SQLiteDatabase.CONFLICT_IGNORE);
        prepared.remove("reading_id");
        db.update("health_exports",prepared,"reading_id=? AND client_id IS NULL",new String[]{""+id});
        try(Cursor c=db.rawQuery("SELECT client_id,record_time,offset_seconds FROM health_exports WHERE reading_id=?",new String[]{""+id})) {
            if(!c.moveToFirst() || !clientId.equals(c.getString(0)) || !time.equals(c.getString(1)) || c.isNull(2) || offsetSeconds!=c.getInt(2))
                throw new IllegalStateException("Could not persist export identity and timestamp; retry from the local reading");
        }
        return true;
    }
    public synchronized void markHealthExported(long id,String fingerprint) {
        ContentValues v=new ContentValues(); v.put("state",1); v.put("error",""); v.put("exported_at",System.currentTimeMillis());
        getWritableDatabase().update("health_exports",v,"reading_id=? AND EXISTS(SELECT 1 FROM readings r WHERE r.id=health_exports.reading_id AND r.fingerprint=?)",new String[]{""+id,fingerprint});
    }
    public synchronized void markHealthSkipped(long id,String fingerprint,String reason) {
        if(!hasReadingIdentity(id,fingerprint)) return;
        ContentValues v=new ContentValues(); v.put("reading_id",id);
        getWritableDatabase().insertWithOnConflict("health_exports",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        v.remove("reading_id"); v.put("state",2); v.put("error",reason);
        getWritableDatabase().update("health_exports",v,"reading_id=? AND EXISTS(SELECT 1 FROM readings r WHERE r.id=health_exports.reading_id AND r.fingerprint=?)",new String[]{""+id,fingerprint});
    }
    public synchronized void retryHealthSkipped(int user) {
        ContentValues v=new ContentValues(); v.put("state",0); v.put("error","");
        getWritableDatabase().update("health_exports",v,"state=2 AND reading_id IN (SELECT id FROM readings WHERE "+healthScope("readings",user)+")",new String[]{""+(user<0?-(long)user:user)});
    }
    public synchronized int healthExportedCount(int user) { return healthCount(user,1); }
    public synchronized int healthSkippedCount(int user) { return healthCount(user,2); }
    private int healthCount(int user,int state) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM health_exports h JOIN readings r ON r.id=h.reading_id WHERE "+healthScope("r",user)+" AND h.state=?",new String[]{""+(user<0?-(long)user:user),""+state})) { c.moveToFirst(); return c.getInt(0); }
    }
    public synchronized String healthLastError(int user) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT h.error FROM health_exports h JOIN readings r ON r.id=h.reading_id WHERE "+healthScope("r",user)+" AND h.state=2 ORDER BY r.id DESC LIMIT 1",new String[]{""+(user<0?-(long)user:user)})) { return c.moveToFirst()?c.getString(0):""; }
    }
    public synchronized void packet(String device,String characteristic,byte[] bytes,String note) {
        HttpForwardingSettings.Config config=HttpForwardingSettings.get(context);
        if(config.enabled() && !config.keepLocal) return;
        ContentValues v=new ContentValues(); v.put("received",System.currentTimeMillis()); v.put("device",device); v.put("characteristic",characteristic); v.put("payload",hex(bytes)); v.put("note",note);
        SQLiteDatabase db=getWritableDatabase(); db.insertOrThrow("packets",null,v);
        db.execSQL("DELETE FROM packets WHERE id <= (SELECT COALESCE(MAX(id),0)-500 FROM packets)");
    }
    public synchronized String diagnostics() {
        StringBuilder out=new StringBuilder("received,device,characteristic,payload,note\n");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT received,device,characteristic,payload,note FROM packets ORDER BY id",null)) {
            while(c.moveToNext()) { for(int i=0;i<5;i++) { if(i>0) out.append(','); out.append(csv(c.getString(i))); } out.append('\n'); }
        } return out.toString();
    }
    public String export(int user,int days) {
        boolean profiles=virtualUsersEnabled() || user<0;
        StringBuilder out=new StringBuilder("device_time,received_at_utc,device,user,systolic,diastolic,map,unit,pulse,status,afib,source,notes,raw"+(profiles?",profile_id,profile_name":"")+"\n");
        LocalDateTime cutoff=days==0?null:LocalDateTime.now().minusDays(days);
        for(Reading r:readings(user)) {
            if(cutoff!=null && (r.time==null || r.displayTime().isBefore(cutoff))) continue;
            String[] row={r.time,java.time.Instant.ofEpochMilli(r.received).toString(),r.name,r.user==null?null:r.user.toString(),""+r.systolic,""+r.diastolic,r.map==null?null:r.map.toString(),r.unit,r.pulse==null?null:r.pulse.toString(),r.status==null?null:r.status.toString(),r.afib==null?null:r.afib.toString(),r.source,r.notes,r.raw};
            for(int i=0;i<row.length;i++) { if(i>0) out.append(','); out.append(csv(row[i])); } if(profiles) out.append(',').append(csv(r.virtualUserId==null?null:r.virtualUserId.toString())).append(',').append(csv(r.virtualUserName)); out.append('\n');
        } return out.toString();
    }
    public static String csv(String s) { if(s==null) return ""; if(!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0))>=0) s="'"+s; return '"'+s.replace("\"","\"\"")+'"'; }
    public static String hex(byte[] bytes) { StringBuilder s=new StringBuilder(); for(byte b:bytes) s.append(String.format(Locale.ROOT,"%02x",b&255)); return s.toString(); }
    private static Reading reading(Cursor c) {
        Reading r=new Reading(); r.id=number(c,"id"); r.received=number(c,"received"); r.device=text(c,"device"); r.name=text(c,"name"); r.fingerprint=text(c,"fingerprint");
        r.time=text(c,"time"); r.source=text(c,"source"); r.notes=text(c,"notes"); r.raw=text(c,"raw"); r.unit=text(c,"unit");
        r.systolic=value(c,"sys"); r.diastolic=value(c,"dia"); r.pulse=nullable(c,"pulse"); r.map=nullable(c,"map");
        Double u=nullable(c,"user"),s=nullable(c,"status"),a=nullable(c,"afib"); r.user=u==null?null:u.intValue(); r.status=s==null?null:s.intValue(); r.afib=a==null?null:a.intValue()!=0;
        if(c.getColumnIndex("virtual_user_id")>=0) { Double profile=nullable(c,"virtual_user_id"); r.virtualUserId=profile==null?null:profile.intValue(); }
        if(c.getColumnIndex("virtual_user_name")>=0) r.virtualUserName=text(c,"virtual_user_name");
        if(c.getColumnIndex("health_client_id")>=0) { r.healthClientId=text(c,"health_client_id"); r.healthTime=text(c,"health_time"); Double offset=nullable(c,"health_offset"); r.healthOffset=offset==null?null:offset.intValue(); }
        return r;
    }
    private static String text(Cursor c,String name) { return c.getString(c.getColumnIndexOrThrow(name)); }
    private static long number(Cursor c,String name) { return c.getLong(c.getColumnIndexOrThrow(name)); }
    private static double value(Cursor c,String name) { return c.getDouble(c.getColumnIndexOrThrow(name)); }
    private static Double nullable(Cursor c,String name) { int i=c.getColumnIndexOrThrow(name); return c.isNull(i)?null:c.getDouble(i); }
}
