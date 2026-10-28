"""Desktop SQLite checks for actual additive SQL and logical-merge invariants.

No device/app data is touched. Android SQLiteOpenHelper/SAF still need physical
validation; this is not an instrumented Android test.
"""
import hashlib
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / "app/src/main/java/io/github/strongsand/lshell"
history = (source / "HistoryStore.kt").read_text(encoding="utf-8")
observability = (source / "TestHistory.kt").read_text(encoding="utf-8")
restore = (source / "BackupRestoreEngine.kt").read_text(encoding="utf-8")
adapter = (source / "BackupDatabaseAdapter.kt").read_text(encoding="utf-8")
codecs = (source / "BackupCodecs.kt").read_text(encoding="utf-8")
receipt_sql = re.search(r'const val RECEIPT_SQL = "([^"]+)"', restore)[1]
assert 'null, BackupDatabaseSchema.VERSION' in history
assert 'if (oldVersion < 8) db.execSQL(BackupDatabaseSchema.RECEIPT_SQL)' in history
columns = re.findall(r'"([a-z_]+)"', codecs.split('val historyFields = listOf(', 1)[1].split('val historyIntegers', 1)[0])
assert len(columns) == 19
create_history = re.findall(r'db.execSQL\("(CREATE [^"]+)"\)', history.split("override fun onUpgrade", 1)[0])
health_sql = re.findall(r'"(CREATE [^"]+)"', observability.split("val statements = listOf(", 1)[1].split("\n    )", 1)[0])
temp_sql = re.search(r'db.execSQL\("(CREATE TEMP [^"]+)"\)', adapter)[1]

def create(version):
    db = sqlite3.connect(":memory:")
    for sql in create_history:
        db.execute(sql)
    if version >= 7:
        for sql in health_sql:
            db.execute(sql)
    if version == 8:
        db.execute(receipt_sql)
    db.execute(f"PRAGMA user_version={version}")
    return db

def insert(db, row):
    return db.execute(f"INSERT INTO history ({','.join(columns)}) VALUES ({','.join('?' for _ in columns)})", [row.get(c) for c in columns])

rows = [dict(time_ms=1000,kind="reading",label="Synthetic",source="LOCAL_PHONE",download=0),
        dict(time_ms=2000,kind="terminal_history",label="Synthetic",source="LOCAL_PHONE",source_boot=10,source_counter=20,latency=25),
        dict(time_ms=3000,kind="beacon_history",label="Synthetic",source="BEACON",beacon_id="synthetic",beacon_sequence=7,power_watts=50),
        dict(time_ms=4000,kind="event",label="Synthetic event",source="LOCAL_PHONE")]
for version in (6, 7):
    db = create(version)
    for row in rows:
        insert(db, row)
    before = db.execute("SELECT * FROM history").fetchall()
    db.commit()
    with db:
        if version == 6:
            for sql in health_sql:
                db.execute(sql)
        db.execute(receipt_sql)
        db.execute("PRAGMA user_version=8")
    assert before == db.execute("SELECT * FROM history").fetchall()
    assert db.execute("PRAGMA user_version").fetchone()[0] == 8
    assert db.execute("SELECT download,latency FROM history WHERE kind='reading'").fetchone() == (0, None)
    db.close()

def identity(row):
    if row["source"] == "BEACON":
        return f"beacon:{row['beacon_id']}:{row['beacon_sequence']}"
    if row["kind"] == "terminal_history" and row.get("source_boot") is not None and row.get("source_counter") is not None:
        return f"terminal:{row['source_boot']}:{row['source_counter']}"
    # Synthetic integral values; Kotlin canonical codec separately tests numeric spellings.
    logical = {c: row.get(c) for c in columns}
    canonical = json.dumps(logical,sort_keys=True,separators=(",",":"),ensure_ascii=False)
    return "local:" + hashlib.sha256(canonical.encode()).hexdigest()

def merge(db, incoming, restore_id, fail=False):
    added = existing = 0
    try:
        db.execute("BEGIN IMMEDIATE")
        db.execute(temp_sql)
        db.execute("DELETE FROM backup_history_keys")
        for values in db.execute(f"SELECT {','.join(columns)} FROM history").fetchall():
            row = dict(zip(columns,values))
            # Normalize integral REAL values as Kotlin's canonical codec does.
            row = {k: int(v) if isinstance(v,float) and v.is_integer() else v for k,v in row.items()}
            db.execute("INSERT OR IGNORE INTO backup_history_keys VALUES (?)",(identity(row),))
        for row in incoming:
            key = identity(row)
            changed = db.execute("INSERT OR IGNORE INTO backup_history_keys VALUES (?)",(key,)).rowcount
            if changed:
                insert(db,row); added += 1
            else:
                existing += 1
            if fail:
                raise RuntimeError("injected before commit")
        db.execute("INSERT INTO backup_restore_receipts VALUES (?,?,?)",(restore_id,"synthetic-backup",5000))
        db.execute("DROP TABLE backup_history_keys")
        db.commit()
        return added, existing
    except Exception:
        db.rollback()
        raise

db = create(8); db.commit()
assert merge(db, rows, "restore1") == (4, 0)
assert merge(db, rows + [rows[0]], "restore2") == (0, 5)
assert db.execute("SELECT COUNT(*) FROM history").fetchone()[0] == 4
assert merge(db, rows + [dict(rows[0],time_ms=9000)], "restore3") == (1, 4)
assert db.execute("SELECT COUNT(*) FROM history").fetchone()[0] == 5
before = db.execute("SELECT * FROM history").fetchall()
try:
    merge(db,[dict(rows[0],time_ms=10000)],"must-rollback",True)
except RuntimeError:
    pass
else:
    raise AssertionError("failure injection missing")
assert before == db.execute("SELECT * FROM history").fetchall()
assert db.execute("SELECT 1 FROM backup_restore_receipts WHERE restore_id='must-rollback'").fetchone() is None
assert db.execute("SELECT download,latency FROM history WHERE time_ms=1000").fetchone() == (0,None)
for table, key, fields in [
    ("connection_tests","test_id",("test_id","started_at","payload")),
    ("connection_reports","report_key",("report_key","start_ms","end_ms","kind","zone","grade","generated_ms","payload"))]:
    values = ("original",1000,"payload-original") if table == "connection_tests" else ("original",1000,2000,"WEEK","UTC","GOOD",3000,"payload-original")
    for _ in range(2):
        db.execute(f"INSERT OR IGNORE INTO {table} ({','.join(fields)}) VALUES ({','.join('?' for _ in fields)})",values)
    assert db.execute(f"SELECT COUNT(*),payload FROM {table} WHERE {key}='original'").fetchone() == (1,"payload-original")
print("PASS: actual schema 6/7 -> 8 SQL, row preservation, empty/partial/repeated merge, NULL/zero, source IDs, rollback + receipt atomicity, immutable snapshots")
