#!/usr/bin/env python3
"""Exercise the production SQLite schema with SQLite; Android runtime checks remain separate."""
import ast, hashlib, re, sqlite3
from pathlib import Path
root=Path(__file__).resolve().parent.parent
source=(root/'app/src/main/java/org/openpressure/app/data/ReadingStore.java').read_text()
statements=[ast.literal_eval('"'+s+'"') for s in re.findall(r'db\.execSQL\("((?:[^"\\]|\\.)*)"\)',source)]
readings=next(s for s in statements if s.startswith('CREATE TABLE readings('))
health=next(s for s in statements if s.startswith('CREATE TABLE health_exports('))
tombstone=next(s for s in statements if s.startswith('CREATE TABLE reading_tombstones('))
receipt=hashlib.sha256(b'stable').hexdigest()
outbox=next(s for s in statements if s.startswith('CREATE TABLE http_outbox('))
http_receipts=next(s for s in statements if s.startswith('CREATE TABLE http_receipts('))
for old in (1,2,3,4,5):
    db=sqlite3.connect(':memory:'); db.execute('PRAGMA foreign_keys=ON')
    db.execute(readings.replace(', afib INTEGER','') if old==1 else readings)
    db.execute("INSERT INTO readings(id,fingerprint,device,name,received,sys,dia,unit,source,raw) VALUES(7,'stable','cuff','X7',1,120,80,'mmHg','wlp-history','abc')")
    if old>=3: db.execute(health); db.execute("INSERT INTO health_exports(reading_id,client_id,state) VALUES(7,'exported',1)")
    if old<2: db.execute('ALTER TABLE readings ADD COLUMN afib INTEGER')
    if old<3: db.execute(health)
    db.execute(tombstone)
    db.execute(outbox); db.execute(http_receipts)
    assert db.execute('SELECT fingerprint FROM readings').fetchone()==('stable',)
    if old>=3: assert db.execute('SELECT client_id FROM health_exports').fetchone()==('exported',)
    db.commit()
    with db:
        db.execute('INSERT OR REPLACE INTO reading_tombstones(receipt) VALUES(?)',(receipt,))
        db.execute('DELETE FROM readings WHERE id=7')
    assert db.execute('SELECT receipt FROM reading_tombstones').fetchall()==[(receipt,)]
    assert all('stable' not in row[0] for row in db.execute('SELECT receipt FROM reading_tombstones'))
    assert db.execute('SELECT count(*) FROM health_exports').fetchone()==(0,)
    # Deleting a reading twice or explicitly restoring it then deleting again is safe.
    db.execute('DELETE FROM readings WHERE id=7')
    db.execute("INSERT INTO readings(id,fingerprint,device,name,received,sys,dia,unit,source,raw) VALUES(7,'stable','cuff','X7',1,120,80,'mmHg','wlp-history','abc')")
    db.execute('INSERT OR REPLACE INTO reading_tombstones(receipt) VALUES(?)',(receipt,))
    db.execute('DELETE FROM readings WHERE id=7')
    assert db.execute('SELECT count(*) FROM reading_tombstones').fetchone()==(1,)
    # An interrupted delete transaction must roll back both the receipt and the record deletion.
    db.commit()
    db.execute("INSERT INTO readings(id,fingerprint,device,name,received,sys,dia,unit,source,raw) VALUES(8,'rollback','cuff','X7',1,120,80,'mmHg','wlp-history','abc')")
    db.commit()
    try:
        with db:
            db.execute('INSERT OR REPLACE INTO reading_tombstones(receipt) VALUES(?)',(hashlib.sha256(b'rollback').hexdigest(),))
            db.execute('DELETE FROM readings WHERE id=8')
            raise RuntimeError('simulated interruption')
    except RuntimeError: pass
    assert db.execute('SELECT count(*) FROM reading_tombstones').fetchone()==(1,)
    assert db.execute('SELECT fingerprint FROM readings WHERE id=8').fetchone()==('rollback',)
    db.close()
# Run the actual bulk-clear query, compiled receipt SQL and deletion predicate against
# SQLite. This catches a mistaken bank predicate or overbroad cascade without Android mocks.
bulk=re.search(r'public synchronized int clearHistory\(int user\) \{(.*?)\n    \}',source,re.S)
assert bulk is not None, 'ReadingStore must provide transactional clearHistory(user)'
query=re.search(r'db\.rawQuery\("([^"]+)"',bulk[1])[1]
insert=re.search(r'db\.compileStatement\("([^"]+)"',bulk[1])[1]
where=re.search(r'db\.delete\("readings","([^"]+)"',bulk[1])[1]
count_method=re.search(r'public synchronized int historyCount\(int user\) \{(.*?)\n    \}',source,re.S)
count_query=re.search(r'rawQuery\("([^"]+)"',count_method[1])[1]
devices=next(s for s in statements if s.startswith('CREATE TABLE devices('))
packets=next(s for s in statements if s.startswith('CREATE TABLE packets('))
for user,remaining in ((0,[]),(1,[(2,),(4,),(5,)]),(2,[(1,),(3,),(4,),(5,)])):
    db=sqlite3.connect(':memory:'); db.execute('PRAGMA foreign_keys=ON')
    for sql in (readings,health,tombstone,devices,packets): db.execute(sql)
    # Two banks, multiple dates, manual and unassigned readings all share the store.
    for id,bank,time,kind in ((1,1,'2020-01-01T12:00:00','wlp-history'),(2,2,'2026-10-16T12:00:00','wlp-history'),(3,1,None,'manual'),(4,None,None,'manual'),(5,0,None,'manual')):
        db.execute('INSERT INTO readings(id,fingerprint,device,name,time,received,sys,dia,user,unit,source,raw,notes) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)',
                   (id,'fingerprint-'+str(id),'cuff','X7',time,1,120,80,bank,'mmHg',kind,'raw','private note'))
        db.execute('INSERT INTO health_exports(reading_id,client_id,state) VALUES(?,?,1)',(id,'health-'+str(id)))
    db.execute("INSERT INTO devices VALUES('cuff','X7','x7',1)")
    db.execute("INSERT INTO packets VALUES(1,1,'cuff','characteristic','payload','note')")
    prior=hashlib.sha256(b'previously deleted').hexdigest()
    db.execute('INSERT INTO reading_tombstones VALUES(?)',(prior,)); db.commit()
    assert db.execute(count_query,(str(user),str(user))).fetchone()==(5-len(remaining),), 'count must match Android string selection bindings'
    def clear(failure=None):
        with db:
            selected=db.execute(query,(str(user),str(user))).fetchall()
            for index,(fingerprint,) in enumerate(selected):
                db.execute(insert,(hashlib.sha256(fingerprint.encode()).hexdigest(),))
                if failure=='receipt' and index==0: raise RuntimeError('receipt interrupted')
            db.execute('DELETE FROM readings WHERE '+where,(str(user),str(user)))
            if failure=='delete': raise RuntimeError('commit interrupted')
    # Failure after partial receipts, and after deletion, must leave all local data intact.
    for failure in ('receipt','delete'):
        try: clear(failure)
        except RuntimeError: pass
        assert db.execute('SELECT count(*) FROM readings').fetchone()==(5,)
        assert db.execute('SELECT count(*) FROM health_exports').fetchone()==(5,)
        assert db.execute('SELECT receipt FROM reading_tombstones').fetchall()==[(prior,)]
    # Real SQLite write failures must also leave receipts, notes and export metadata intact.
    selected_fingerprints=db.execute(query,(str(user),str(user))).fetchall()
    assert len(selected_fingerprints)==5-len(remaining), 'bank scope must match Android string selection bindings'
    last_fingerprint=selected_fingerprints[-1][0]
    rejected_receipt=hashlib.sha256(last_fingerprint.encode()).hexdigest()
    triggers=(
        "CREATE TRIGGER fail_write BEFORE INSERT ON reading_tombstones WHEN NEW.receipt='"+rejected_receipt+"' BEGIN SELECT RAISE(ABORT,'receipt write failed'); END",
        "CREATE TRIGGER fail_write BEFORE DELETE ON readings BEGIN SELECT RAISE(ABORT,'delete failed'); END")
    for trigger in triggers:
        db.execute(trigger); db.commit()
        try:
            clear()
            raise AssertionError('a storage failure must propagate')
        except sqlite3.IntegrityError: pass
        assert db.execute('SELECT count(*) FROM readings WHERE notes=?',('private note',)).fetchone()==(5,)
        assert db.execute('SELECT count(*) FROM health_exports').fetchone()==(5,)
        assert db.execute('SELECT receipt FROM reading_tombstones').fetchall()==[(prior,)]
        db.execute('DROP TRIGGER fail_write'); db.commit()
    clear()
    assert db.execute('SELECT id FROM readings ORDER BY id').fetchall()==remaining
    assert db.execute('SELECT reading_id FROM health_exports ORDER BY reading_id').fetchall()==remaining
    deleted={1,2,3,4,5}-{row[0] for row in remaining}
    receipts={row[0] for row in db.execute('SELECT receipt FROM reading_tombstones')}
    assert receipts=={prior}|{hashlib.sha256(('fingerprint-'+str(id)).encode()).hexdigest() for id in deleted}
    assert db.execute('SELECT count(*) FROM devices').fetchone()==(1,)
    assert db.execute('SELECT count(*) FROM packets').fetchone()==(1,)
    clear() # Empty selection is safe and preserves previous suppression receipts.
    assert {row[0] for row in db.execute('SELECT receipt FROM reading_tombstones')}==receipts
    db.close()
# The actual v5 schema must preserve event identity and roll back receipt/payload changes together.
db=sqlite3.connect(':memory:')
db.execute(outbox); db.execute(http_receipts)
endpoint='https://example.invalid/measurements'
key=hashlib.sha256(b'synthetic reading').hexdigest()
destination=hashlib.sha256(endpoint.encode()).hexdigest()
payload='{"schema_version":1,"id":"'+key+'"}'
db.execute('INSERT INTO http_outbox(endpoint,event_key,payload) VALUES(?,?,?)',(endpoint,key,payload)); db.commit()
try:
    with db:
        db.execute('INSERT INTO http_receipts(destination,event_key) VALUES(?,?)',(destination,key))
        db.execute('DELETE FROM http_outbox WHERE endpoint=? AND event_key=?',(endpoint,key))
        raise RuntimeError('synthetic interruption')
except RuntimeError: pass
assert db.execute('SELECT payload FROM http_outbox').fetchall()==[(payload,)]
assert db.execute('SELECT COUNT(*) FROM http_receipts').fetchone()==(0,)
with db:
    db.execute('INSERT INTO http_receipts(destination,event_key) VALUES(?,?)',(destination,key))
    db.execute('DELETE FROM http_outbox WHERE endpoint=? AND event_key=?',(endpoint,key))
assert db.execute('SELECT COUNT(*) FROM http_outbox').fetchone()==(0,)
assert db.execute('SELECT destination,event_key FROM http_receipts').fetchall()==[(destination,key)]
db.close()
print('Storage SQL: migrations 1–4, fresh v5, scoped bulk clear, rollback, deletion receipts, HTTP outbox and health cascade passed')
