"""Exercise the published stable's actual DDL against this candidate's additive SQL.

Desktop SQLite only. Does not open Android data, keystores, hardware or production
databases. Android SQLiteOpenHelper, preferences and Keystore need signed-device QA.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sqlite3
from contextlib import nullcontext
import uuid
import zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--stable-source',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
prefix='app/src/main/java/io/github/strongsand/lshell/'
with zipfile.ZipFile(args.stable_source) as z:
    stable=z.read(prefix+'HistoryStore.kt').decode('utf-8')
    stable_prefs=z.read(prefix+'beacon/BeaconStorage.kt').decode('utf-8')
history=(root/prefix/'HistoryStore.kt').read_text(encoding='utf-8')
tests=(root/prefix/'TestHistory.kt').read_text(encoding='utf-8')
backup=(root/prefix/'BackupRestoreEngine.kt').read_text(encoding='utf-8')
prefs=(root/prefix/'beacon/BeaconStorage.kt').read_text(encoding='utf-8')
assert '"dish_history.db", null, 6)' in stable
assert '"dish_history.db", null, BackupDatabaseSchema.VERSION)' in history
assert 'const val VERSION = 8' in backup
assert 'if (oldVersion < 7) ObservabilitySchema.statements.forEach(db::execSQL)' in history
assert 'if (oldVersion < 8) db.execSQL(BackupDatabaseSchema.RECEIPT_SQL)' in history
ddl=re.findall(r'db.execSQL\("(CREATE [^"]+)"\)',stable.split('override fun onUpgrade')[0])
steps=re.findall(r'"(CREATE [^"]+)"',tests.split('val statements = listOf(',1)[1].split('\n    )',1)[0])
steps+=[re.search(r'const val RECEIPT_SQL = "([^"]+)"',backup)[1]]
assert len(ddl)==4 and len(steps)==6
def preference_constants(text): return dict(re.findall(r'const val (FILE|KEY_\w+) = "([^"]+)"',text))
old=preference_constants(stable_prefs); new=preference_constants(prefs)
assert all(new.get(k)==v for k,v in old.items()),'Pairing preference key/file changed'
assert 'putString(KEY_ALIAS, beacon.keyAlias)' in prefs
args.output.parent.mkdir(parents=True,exist_ok=True)
checks=[]
fixture_root=args.output.parent/('stable6-upgrade-'+uuid.uuid4().hex)
fixture_root.mkdir()
# Keep audit fixtures; avoid platform-specific restrictive tempfile ACLs.
with nullcontext(fixture_root) as folder:
    tmp=Path(folder)
    sentinel=tmp/'beacon_preferences.xml'
    sentinel.write_text('<map><string name="paired_key_alias">fixture-alias</string><long name="last_sequence" value="73"/></map>',encoding='utf-8')
    settings=tmp/'settings.xml'; settings.write_text('<map><boolean name="dark" value="true"/></map>',encoding='utf-8')
    hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [sentinel,settings]}
    def create(name,empty=False):
        db=sqlite3.connect(tmp/name)
        for sql in ddl: db.execute(sql)
        db.execute('PRAGMA user_version=6')
        if not empty:
            for n in range(120):
                source='BEACON' if n%3==0 else 'LOCAL_PHONE'
                kind='beacon_history' if source=='BEACON' else ('event' if n%3==1 else 'terminal_history')
                db.execute('INSERT INTO history (time_ms,kind,label,download,upload,latency,power_watts,source,beacon_id,beacon_sequence,source_boot,source_counter) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)',
                    (1000+n*1000,kind,'fixture',0 if n%2==0 else None,1.25,None,0 if n%2==0 else 15.75,source,
                     'fixture-beacon' if source=='BEACON' else None,n if source=='BEACON' else None,
                     11 if kind=='terminal_history' else None,n if kind=='terminal_history' else None))
        db.commit(); return db
    def rows(db): return db.execute('SELECT * FROM history ORDER BY id').fetchall()
    def schema(db): return db.execute("SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name").fetchall()
    for empty in [False,True]:
        name='empty.sqlite' if empty else 'populated.sqlite'; db=create(name,empty)
        before=rows(db)
        with db:
            for sql in steps: db.execute(sql)
            db.execute('PRAGMA user_version=8')
        db.close(); db=sqlite3.connect(tmp/name)
        assert db.execute('PRAGMA user_version').fetchone()[0]==8
        assert before==rows(db)
        assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
        if not empty:
            assert db.execute('SELECT download,latency,power_watts FROM history WHERE id=1').fetchone()==(0,None,0)
            assert db.execute("SELECT COUNT(*) FROM history WHERE source='BEACON'").fetchone()[0]==40
            try: db.execute("INSERT INTO history (time_ms,kind,label,source,beacon_id,beacon_sequence) VALUES (1,'beacon_history','duplicate','BEACON','fixture-beacon',0)")
            except sqlite3.IntegrityError: db.rollback()
            else: raise AssertionError('Lost unique Beacon identity')
        # Same idempotent SQL does not rewrite data or closed report payloads.
        db.execute("INSERT INTO connection_reports VALUES ('closed',1,2,'DAY','UTC','GOOD',3,'immutable')"); db.commit()
        for sql in steps: db.execute(sql)
        assert db.execute("SELECT payload FROM connection_reports WHERE report_key='closed'").fetchone()[0]=='immutable'
        # JSON round-trip represents row preservation, not the Android backup engine.
        assert json.loads(json.dumps(rows(db)))==[list(r) for r in before]
        db.close(); checks.append('published schema 6 -> 8 '+('empty' if empty else '120-row retained'))
    for failure_after in range(1,len(steps)+1):
        db=create('failure-'+str(failure_after)+'.sqlite'); before=rows(db); old_schema=schema(db)
        try:
            with db:
                db.execute('BEGIN IMMEDIATE')
                for index,sql in enumerate(steps,1):
                    db.execute(sql)
                    if index==failure_after: raise RuntimeError('injected DDL failure')
        except RuntimeError: pass
        assert db.execute('PRAGMA user_version').fetchone()[0]==6
        assert rows(db)==before and schema(db)==old_schema
        assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
        with db:
            for sql in steps: db.execute(sql)
            db.execute('PRAGMA user_version=8')
        assert rows(db)==before; db.close()
        checks.append('rollback + retry after migration DDL '+str(failure_after))
    assert hashes=={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [sentinel,settings]}
checks.append('pairing file/keys/alias access preserved in source; synthetic settings files unchanged')
args.output.write_text(json.dumps({'checks':checks,'passed':len(checks),'sqlite':sqlite3.sqlite_version,
    'android_instrumentation':False,'physical_upgrade':False,'stable_schema_source':'f2f7ffd (0.1.3-beta/4)',
    'candidate_version':'0.2.0-beta.2/6','schema':'6->7->8'},indent=2)+'\n',encoding='utf-8')
print('PASS:',len(checks),'published-schema migration/failure/preservation checks; Android signed-device QA pending')
