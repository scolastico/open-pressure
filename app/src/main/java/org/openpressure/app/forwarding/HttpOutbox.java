package org.openpressure.app.forwarding;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.openpressure.app.data.ReadingStore;

/** Durable temporary payloads and hash-only delivery receipts, independent of local history. */
public final class HttpOutbox {
    private final ReadingStore store;
    public HttpOutbox(ReadingStore store) { this.store=store; }
    public static final class Entry {
        public final long id;
        public final String endpoint,key,payload;
        private Entry(long id,String endpoint,String key,String payload) { this.id=id; this.endpoint=endpoint; this.key=key; this.payload=payload; }
    }
    public int count(String endpoint) {
        synchronized(store) {
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM http_outbox WHERE endpoint=?",new String[]{endpoint})) { c.moveToFirst(); return c.getInt(0); }
        }
    }
    public List<Entry> pending(String endpoint,int limit) {
        if(limit<1 || limit>500) throw new IllegalArgumentException("Choose a bounded batch");
        synchronized(store) {
            List<Entry> entries=new ArrayList<>();
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id,endpoint,event_key,payload FROM http_outbox WHERE endpoint=? ORDER BY id LIMIT ?",new String[]{endpoint,""+limit})) {
                while(c.moveToNext()) entries.add(new Entry(c.getLong(0),c.getString(1),c.getString(2),c.getString(3)));
            }
            return Collections.unmodifiableList(entries);
        }
    }
    /** Called inside ReadingStore's ingestion transaction. Existing events keep their original body. */
    public static boolean enqueue(SQLiteDatabase db,String endpoint,String key,ContentValues reading) {
        try(Cursor c=db.rawQuery("SELECT 1 FROM http_outbox WHERE endpoint=? AND event_key=? UNION ALL SELECT 1 FROM http_receipts WHERE destination=? AND event_key=? LIMIT 1",new String[]{endpoint,key,HttpPayload.sha256(endpoint),key})) {
            if(c.moveToFirst()) return false;
        }
        ContentValues row=new ContentValues(); row.put("endpoint",endpoint); row.put("event_key",key); row.put("payload",HttpPayload.create(reading,key));
        db.insertOrThrow("http_outbox",null,row); return true;
    }
    public void acknowledge(Entry entry) {
        synchronized(store) {
            SQLiteDatabase db=store.getWritableDatabase(); db.beginTransaction();
            try {
                String[] identity={""+entry.id,entry.endpoint,entry.key};
                try(Cursor c=db.rawQuery("SELECT 1 FROM http_outbox WHERE id=? AND endpoint=? AND event_key=?",identity)) {
                    if(c.moveToFirst()) {
                        ContentValues receipt=new ContentValues(); receipt.put("destination",HttpPayload.sha256(entry.endpoint)); receipt.put("event_key",entry.key);
                        db.replaceOrThrow("http_receipts",null,receipt);
                        db.delete("http_outbox","id=? AND endpoint=? AND event_key=?",identity);
                    }
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }
}
