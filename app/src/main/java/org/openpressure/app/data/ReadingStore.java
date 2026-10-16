package org.openpressure.app.data;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import java.time.LocalDateTime;
import org.openpressure.protocol.BloodPressure;
import org.openpressure.app.forwarding.*;

public final class ReadingStore extends SQLiteOpenHelper {
    private Runnable changeListener,forwardingListener;
    private final Context context;
    private final HttpOutbox httpOutbox=new HttpOutbox(this);
    public ReadingStore(Context context) { super(context,"pressure.db",null,5); this.context=context.getApplicationContext(); }
    public HttpOutbox httpOutbox() { return httpOutbox; }
    public synchronized void setForwardingListener(Runnable listener) { forwardingListener=listener; }
    @Override public void onConfigure(SQLiteDatabase db) { db.enableWriteAheadLogging(); db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE readings(id INTEGER PRIMARY KEY, fingerprint TEXT UNIQUE, device TEXT NOT NULL, name TEXT NOT NULL, time TEXT, received INTEGER NOT NULL, sys REAL NOT NULL, dia REAL NOT NULL, map REAL, pulse REAL, user INTEGER, status INTEGER, afib INTEGER, unit TEXT NOT NULL, source TEXT NOT NULL, raw TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX reading_time ON readings(time)");
        db.execSQL("CREATE TABLE devices(address TEXT PRIMARY KEY, name TEXT NOT NULL, model TEXT NOT NULL, user INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE packets(id INTEGER PRIMARY KEY, received INTEGER NOT NULL, device TEXT NOT NULL, characteristic TEXT NOT NULL, payload TEXT NOT NULL, note TEXT NOT NULL)");
        createHealthExports(db);
        createDeletionReceipts(db);
        createHttpForwarding(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<1 || newVersion!=5) throw new IllegalStateException("Unsupported database version");
        if(oldVersion<2) db.execSQL("ALTER TABLE readings ADD COLUMN afib INTEGER");
        if(oldVersion<3) createHealthExports(db);
        if(oldVersion<4) createDeletionReceipts(db);
        if(oldVersion<5) createHttpForwarding(db);
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
    private void readingsChanged() { if(changeListener!=null) changeListener.run(); }
    /** The committed ingestion outcome, independent of later forwarding settings changes. */
    public static final class IngestionResult {
        public final boolean stored,queued;
        private IngestionResult(boolean stored,boolean queued) { this.stored=stored; this.queued=queued; }
        public boolean accepted() { return stored || queued; }
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
        boolean inserted=false,queued=false;
        db.beginTransaction();
        try {
            try(Cursor c=db.rawQuery("SELECT 1 FROM readings WHERE fingerprint=?",new String[]{fingerprint})) {
                if(c.moveToFirst()) return new IngestionResult(false,false);
            }
            if(automatic) try(Cursor c=db.rawQuery("SELECT 1 FROM reading_tombstones WHERE receipt=?",new String[]{key})) {
                if(c.moveToFirst()) return new IngestionResult(false,false);
            }
            if(forwarding) queued=HttpOutbox.enqueue(db,config.endpoint,key,values);
            if(local) { db.insertOrThrow("readings",null,values); inserted=true; }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if(inserted) readingsChanged();
        if(queued && forwardingListener!=null) forwardingListener.run();
        return new IngestionResult(inserted,queued);
    }
    public synchronized void addManual(double sys,double dia,Double pulse,int user,LocalDateTime time,String notes) {
        if(!Double.isFinite(sys)||!Double.isFinite(dia)||sys<=dia||dia<=0||sys>400||dia>300||pulse!=null&&(!Double.isFinite(pulse)||pulse<=0||pulse>300)) throw new IllegalArgumentException("Check the pressure and pulse values");
        ContentValues v=new ContentValues(); v.put("fingerprint",UUID.randomUUID().toString()); v.put("device","manual"); v.put("name","Manual entry");
        v.put("time",time.toString()); v.put("received",System.currentTimeMillis()); v.put("sys",sys); v.put("dia",dia); v.put("pulse",pulse);
        v.put("user",user); v.put("unit","mmHg"); v.put("source","manual"); v.put("raw",""); v.put("notes",notes);
        ingest(v,false);
    }
    public synchronized List<Reading> readings(int user) {
        List<Reading> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("readings",null,user==0?null:"user=?",user==0?null:new String[]{""+user},null,null,"COALESCE(time, strftime('%Y-%m-%dT%H:%M:%S', received/1000, 'unixepoch', 'localtime')) DESC, id DESC")) {
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
        if(user<0 || user>2) throw new IllegalArgumentException("Select all users, user 1 or user 2");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM readings WHERE (CAST(? AS INTEGER)=0 OR user=?)",new String[]{""+user,""+user})) {
            c.moveToFirst(); return c.getInt(0);
        }
    }
    public synchronized int clearHistory(int user) {
        if(user<0 || user>2) throw new IllegalArgumentException("Select all users, user 1 or user 2");
        SQLiteDatabase db=getWritableDatabase();
        String[] selection={""+user,""+user};
        int removed;
        db.beginTransaction();
        try {
            // Retain only hashes so automatic full-history refreshes cannot restore cleared readings.
            // Reuse one compiled statement inside the same transaction as the deletion.
            try(Cursor c=db.rawQuery("SELECT fingerprint FROM readings WHERE (CAST(? AS INTEGER)=0 OR user=?)",selection);
                SQLiteStatement receipt=db.compileStatement("INSERT OR REPLACE INTO reading_tombstones(receipt) VALUES(?)")) {
                while(c.moveToNext()) {
                    receipt.bindString(1,DeletionReceipt.key(c.getString(0)));
                    receipt.executeInsert();
                }
            }
            removed=db.delete("readings","(CAST(? AS INTEGER)=0 OR user=?)",selection);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if(removed>0) readingsChanged();
        return removed;
    }
    public synchronized void remember(String address,String name,String model,int user) {
        ContentValues v=new ContentValues(); v.put("address",address); v.put("name",name); v.put("model",model); v.put("user",user);
        getWritableDatabase().insertWithOnConflict("devices",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized List<String[]> devices() {
        List<String[]> rows=new ArrayList<>(); try(Cursor c=getReadableDatabase().rawQuery("SELECT address,name,model,user FROM devices ORDER BY name",null)) {
            while(c.moveToNext()) rows.add(new String[]{c.getString(0),c.getString(1),c.getString(2),c.getString(3)});
        } return rows;
    }
    public synchronized void forget(String address) { getWritableDatabase().delete("devices","address=?",new String[]{address}); }
    public synchronized List<Reading> healthPending(int user,int limit) {
        if(user<1 || user>2 || limit<1 || limit>500) throw new IllegalArgumentException("Select one user bank and a bounded batch");
        List<Reading> pending=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT r.*, h.client_id AS health_client_id, h.record_time AS health_time, h.offset_seconds AS health_offset FROM readings r LEFT JOIN health_exports h ON h.reading_id=r.id WHERE r.user=? AND COALESCE(h.state,0)=0 ORDER BY r.id LIMIT ?",new String[]{""+user,""+limit})) {
            while(c.moveToNext()) pending.add(reading(c));
        }
        return pending;
    }
    public synchronized boolean hasReadingIdentity(long id,String fingerprint) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM readings WHERE id=? AND fingerprint=?",new String[]{""+id,fingerprint})) { return c.moveToFirst(); }
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
        getWritableDatabase().update("health_exports",v,"state=2 AND reading_id IN (SELECT id FROM readings WHERE user=?)",new String[]{""+user});
    }
    public synchronized int healthExportedCount(int user) { return healthCount(user,1); }
    public synchronized int healthSkippedCount(int user) { return healthCount(user,2); }
    private int healthCount(int user,int state) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM health_exports h JOIN readings r ON r.id=h.reading_id WHERE r.user=? AND h.state=?",new String[]{""+user,""+state})) { c.moveToFirst(); return c.getInt(0); }
    }
    public synchronized String healthLastError(int user) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT h.error FROM health_exports h JOIN readings r ON r.id=h.reading_id WHERE r.user=? AND h.state=2 ORDER BY r.id DESC LIMIT 1",new String[]{""+user})) { return c.moveToFirst()?c.getString(0):""; }
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
        StringBuilder out=new StringBuilder("device_time,received_at_utc,device,user,systolic,diastolic,map,unit,pulse,status,afib,source,notes,raw\n");
        LocalDateTime cutoff=days==0?null:LocalDateTime.now().minusDays(days);
        for(Reading r:readings(user)) {
            if(cutoff!=null && (r.time==null || r.displayTime().isBefore(cutoff))) continue;
            String[] row={r.time,java.time.Instant.ofEpochMilli(r.received).toString(),r.name,r.user==null?null:r.user.toString(),""+r.systolic,""+r.diastolic,r.map==null?null:r.map.toString(),r.unit,r.pulse==null?null:r.pulse.toString(),r.status==null?null:r.status.toString(),r.afib==null?null:r.afib.toString(),r.source,r.notes,r.raw};
            for(int i=0;i<row.length;i++) { if(i>0) out.append(','); out.append(csv(row[i])); } out.append('\n');
        } return out.toString();
    }
    public static String csv(String s) { if(s==null) return ""; if(!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0))>=0) s="'"+s; return '"'+s.replace("\"","\"\"")+'"'; }
    public static String hex(byte[] bytes) { StringBuilder s=new StringBuilder(); for(byte b:bytes) s.append(String.format(Locale.ROOT,"%02x",b&255)); return s.toString(); }
    private static Reading reading(Cursor c) {
        Reading r=new Reading(); r.id=number(c,"id"); r.received=number(c,"received"); r.device=text(c,"device"); r.name=text(c,"name"); r.fingerprint=text(c,"fingerprint");
        r.time=text(c,"time"); r.source=text(c,"source"); r.notes=text(c,"notes"); r.raw=text(c,"raw"); r.unit=text(c,"unit");
        r.systolic=value(c,"sys"); r.diastolic=value(c,"dia"); r.pulse=nullable(c,"pulse"); r.map=nullable(c,"map");
        Double u=nullable(c,"user"),s=nullable(c,"status"),a=nullable(c,"afib"); r.user=u==null?null:u.intValue(); r.status=s==null?null:s.intValue(); r.afib=a==null?null:a.intValue()!=0;
        if(c.getColumnIndex("health_client_id")>=0) { r.healthClientId=text(c,"health_client_id"); r.healthTime=text(c,"health_time"); Double offset=nullable(c,"health_offset"); r.healthOffset=offset==null?null:offset.intValue(); }
        return r;
    }
    private static String text(Cursor c,String name) { return c.getString(c.getColumnIndexOrThrow(name)); }
    private static long number(Cursor c,String name) { return c.getLong(c.getColumnIndexOrThrow(name)); }
    private static double value(Cursor c,String name) { return c.getDouble(c.getColumnIndexOrThrow(name)); }
    private static Double nullable(Cursor c,String name) { int i=c.getColumnIndexOrThrow(name); return c.isNull(i)?null:c.getDouble(i); }
}
