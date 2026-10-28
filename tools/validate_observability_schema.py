"""Static schema-6 -> 7 contract check using Python's built-in SQLite (no Android device).

Runs the actual additive SQL declared in TestHistory.kt, verifies preservation of
telemetry/source identities and NULL, and checks immutable snapshot identity.
Android SQLiteOpenHelper migration still requires on-device validation.
"""
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / "app/src/main/java/io/github/strongsand/lshell"
history = (source / "HistoryStore.kt").read_text(encoding="utf-8")
schema = (source / "TestHistory.kt").read_text(encoding="utf-8")
block = schema.split("val statements = listOf(", 1)[1].split("\n    )", 1)[0]
statements = re.findall(r'"(CREATE [^"]+)"', block)
assert len(statements) == 5
db = sqlite3.connect(":memory:")
for sql in re.findall(r'db.execSQL\("(CREATE [^"]+)"\)', history.split("override fun onUpgrade", 1)[0]):
    db.execute(sql)
db.execute("PRAGMA user_version = 6")
db.execute("INSERT INTO history (time_ms,kind,label,download,drop_rate,source,beacon_id,beacon_sequence) VALUES (1,'beacon_history','',NULL,0,'BEACON','synthetic',1)")
db.execute("INSERT INTO history (time_ms,kind,label,download,latency,source) VALUES (2,'reading','',0,NULL,'LOCAL_PHONE')")
db.execute("INSERT INTO history (time_ms,kind,label,latency,source_counter,source_boot,dish_state) VALUES (3,'terminal_history','',20,1,123,'CONNECTED')")
db.execute("INSERT INTO history (time_ms,kind,label) VALUES (4,'event','synthetic event')")
before = db.execute("SELECT * FROM history").fetchall()
assert db.execute("PRAGMA user_version").fetchone()[0] == 6
with db:
    for sql in statements:
        db.execute(sql)
    db.execute("PRAGMA user_version = 7")
assert before == db.execute("SELECT * FROM history").fetchall()
assert db.execute("SELECT download,drop_rate FROM history WHERE kind='beacon_history'").fetchone() == (None, 0)
assert db.execute("SELECT download,latency FROM history WHERE kind='reading'").fetchone() == (0, None)
assert db.execute("PRAGMA user_version").fetchone()[0] == 7
# Applying the new CREATE statements twice is safe; existing data is not rewritten.
for sql in statements:
    db.execute(sql)
for payload in ["original snapshot", "must not overwrite"]:
    db.execute("INSERT OR IGNORE INTO connection_reports VALUES ('week',0,1,'WEEK','UTC','GOOD',1,?)", (payload,))
assert db.execute("SELECT COUNT(*),payload FROM connection_reports").fetchone() == (1, "original snapshot")
db.execute("DELETE FROM history")
assert db.execute("SELECT payload FROM connection_reports").fetchone()[0] == "original snapshot"
for _ in range(2):
    db.execute("INSERT OR REPLACE INTO connection_tests VALUES ('id',1,'synthetic result')")
assert db.execute("SELECT COUNT(*) FROM connection_tests").fetchone()[0] == 1
try:
    db.execute("INSERT INTO history (time_ms,kind,label,source,beacon_id,beacon_sequence) VALUES (1,'beacon_history','','BEACON','synthetic',1)")
    db.execute("INSERT INTO history (time_ms,kind,label,source,beacon_id,beacon_sequence) VALUES (2,'beacon_history','','BEACON','synthetic',1)")
except sqlite3.IntegrityError:
    pass
else:
    raise AssertionError("Beacon identity index lost")
print("PASS: additive schema, original rows, NULL/zero, identities, idempotence and immutable snapshots")
