"""Desktop SQLite parity checks for history hot paths, using production schema/SQL.

Run with Python 3: tools/validate_history_performance.py [--large].
--large additionally checks the real 500,000-row cap at its boundary.
No user database is opened. These fixtures do not execute Android ContentValues,
SQLiteOpenHelper or Beacon transport; the Android/JVM tests complement them.
"""
import argparse
import bisect
import json
import re
import sqlite3
import statistics
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/io/github/strongsand/lshell/HistoryStore.kt").read_text(encoding="utf-8")
CREATE = re.findall(r'execSQL\("(CREATE [^"]+)"\)', SOURCE.split("override fun onUpgrade")[0])
DELETES = []
for group in re.findall(r'execSQL\(\s*((?:"[^"]*"\s*(?:\+\s*)?)+)', SOURCE):
    sql = "".join(re.findall(r'"([^"]*)"', group))
    if sql.startswith("DELETE "):
        DELETES.append(sql)
assert len(DELETES) == 6
OLD_CAP = "DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT 500000)"
BATCH = re.search(r'rawQuery\("(SELECT time_ms FROM history WHERE \$selection[^"]+)"', SOURCE).group(1)
PREDICATES = {
    "terminal": "source = 'BEACON'",
    "beacon": "source = 'LOCAL_PHONE' AND kind IN ('reading','terminal_history')",
}
assert all(predicate in SOURCE for predicate in PREDICATES.values())


def database():
    db = sqlite3.connect(":memory:")
    for sql in CREATE:
        db.execute(sql)
    return db


def insert(db, row):
    keys = list(row)
    cursor = db.execute("INSERT OR IGNORE INTO history (" + ",".join(keys) + ") VALUES (" +
                        ",".join("?" for _ in keys) + ")", [row[key] for key in keys])
    return cursor.rowcount


def sample(at, kind="terminal_history", source="LOCAL_PHONE", **extra):
    return dict(time_ms=at, kind=kind, label="fixture", source=source, **extra)


def snapshot(db):
    return db.execute("SELECT * FROM history ORDER BY id").fetchall()


def import_rows(db, rows, flavor, batch, fail=False):
    """Compare per-row SQL and cached opposite-source SQL inside the same transaction.

    Rows are already validated/bound values; Kotlin tests cover ring decoding and
    validation. Beacon fixture order is the production sequence order.
    """
    predicate = PREDICATES[flavor]
    queries = 0
    inserted = 0
    cached = None
    valid = [row for row in rows if row["time_ms"] > 0] if flavor == "beacon" else rows
    if flavor == "beacon":
        valid = sorted(valid, key=lambda row: row["beacon_sequence"])
    with db:
        for row in valid:
            if flavor == "terminal" and all(row.get(key) is None for key in
                                             ("download", "upload", "latency", "drop_rate", "power_watts")):
                continue
            at = row["time_ms"]
            if batch:
                if cached is None:
                    bounds = (min(r["time_ms"] for r in valid) - 500, max(r["time_ms"] for r in valid) + 500)
                    cached = [r[0] for r in db.execute(BATCH.replace("$selection", predicate), bounds)]
                    queries += 1
                index = bisect.bisect_left(cached, at - 500)
                overlaps = index < len(cached) and cached[index] <= at + 500
            else:
                overlaps = db.execute("SELECT 1 FROM history WHERE " + predicate +
                                      " AND time_ms BETWEEN ? AND ? LIMIT 1", (at - 500, at + 500)).fetchone() is not None
                queries += 1
            if not overlaps:
                inserted += insert(db, row)
                if fail:
                    raise RuntimeError("injected failure before commit")
    return inserted, queries


def compare_import(seed, rows, flavor):
    old, new = database(), database()
    for db in (old, new):
        with db:
            for row in seed:
                insert(db, row)
    counts = []
    for page in (rows, rows):  # page retry: identities, NULLs and IDs must stay equal
        before = import_rows(old, page, flavor, False)
        after = import_rows(new, page, flavor, True)
        assert before[0] == after[0]
        assert snapshot(old) == snapshot(new)
        counts.append((before, after))
    initial = snapshot(new)
    failing = [sample(999999, download=0, source_boot=999, source_counter=1)] if flavor == "terminal" else [
        sample(999999, "beacon_history", "BEACON", beacon_id="rollback", beacon_sequence=1, download=0)]
    for db, batch in ((old, False), (new, True)):
        try:
            import_rows(db, failing, flavor, batch, fail=True)
        except RuntimeError:
            pass
        else:
            raise AssertionError("failed import returned before rollback")
        assert snapshot(db) == initial
    old.close()
    new.close()
    return counts


def imports():
    seed = [sample(10000, "beacon_history", "BEACON", beacon_id="seed", beacon_sequence=1),
            sample(20000, "reading", download=0), sample(30000, "event"),
            sample(40000, "beacon_history", "LOCAL_PHONE", beacon_id="local", beacon_sequence=1),
            sample(50000, source_boot=1, source_counter=0, latency=20)]
    terminal = [sample(at, source_boot=1, source_counter=i, download=0, upload=None, latency=None)
                for i, at in enumerate((9500, 10500, 10501, 20000, 30000, 40000, 50000), 1)]
    terminal += [sample(10501, source_boot=1, source_counter=3, download=9),  # duplicate identity
                 sample(10501, source_boot=2, source_counter=3, download=0),  # another boot
                 sample(60000, source_boot=2, source_counter=4)]  # all metrics missing
    beacon = [sample(at, "beacon_history", "BEACON", beacon_id="page", beacon_sequence=i,
                     source_counter=i, download=0, latency=None)
              for i, at in enumerate((19500, 20500, 20501, 30000, 40000, 49500, 50501), 1)]
    beacon += [sample(20501, "beacon_history", "BEACON", beacon_id="page", beacon_sequence=3, download=9),
               sample(20501, "beacon_history", "BEACON", beacon_id="new-boot", beacon_sequence=3),
               sample(0, "beacon_history", "BEACON", beacon_id="page", beacon_sequence=90),
               sample(-1, "beacon_history", "BEACON", beacon_id="page", beacon_sequence=91)]
    result = {"terminal": compare_import(seed, terminal, "terminal"),
              "beacon": compare_import(seed, beacon, "beacon")}
    db = database()
    assert import_rows(db, [sample(60000)], "terminal", True) == (0, 0)
    assert import_rows(db, [sample(0, "beacon_history", "BEACON", beacon_sequence=1)], "beacon", True) == (0, 0)
    db.close()
    return result


def trimming():
    now = 40 * 86400000
    cutoffs = [now - 7 * 86400000, now - 86400000, now - 7 * 86400000,
               now - 86400000, now - 30 * 86400000]
    args = [(cutoffs[0],), (cutoffs[1],), (cutoffs[2],) * 2, (cutoffs[3],) * 2, (cutoffs[4],), ()]
    old, new = database(), database()
    for db in (old, new):
        with db:
            for i in range(12000):
                kind = ("reading", "terminal_history", "beacon_history", "event")[i % 4]
                insert(db, sample(now - i * 300000, kind, "BEACON" if i % 4 == 2 else "LOCAL_PHONE",
                                  id=i * 2 + 1, source_boot=1 if kind == "terminal_history" else None,
                                  source_counter=i if i % 9 else None, download=None if i % 7 else 0,
                                  drop_rate=0, beacon_id="fixture" if i % 4 == 2 else None,
                                  beacon_sequence=i if i % 4 == 2 else None))
            # Exact cutoffs, equal timestamps with different IDs, and missing counters.
            for i, at in enumerate(cutoffs):
                insert(db, sample(at, source_boot=2, source_counter=i, download=0))
                insert(db, sample(at - 1, "reading", download=None))
                insert(db, sample(at - 1, "reading", download=0))
    for i, (sql, bind) in enumerate(zip(DELETES, args)):
        old.execute(OLD_CAP if i == 5 else sql, bind)
        new.execute(sql, bind)
        assert snapshot(old) == snapshot(new), f"retention step {i} changed rows"
    rows = snapshot(new)
    result = {"rows_retained": len(rows), "oldest_ms": min(r[1] for r in rows),
              "newest_ms": max(r[1] for r in rows), "same_full_rows_after_each_delete": True}
    old.close()
    new.close()
    return result


def cap_boundaries():
    result = []
    for count in (0, 60000, 500000, 500010):
        old, new = database(), database()
        for db in (old, new):
            with db:
                db.executemany("INSERT INTO history(id,time_ms,kind,label,download,latency) VALUES (?,?, 'event','fixture',?,?)",
                               ((2 * i + 1, count - i, None if i % 2 else 0, None) for i in range(count)))
        old.execute(OLD_CAP)
        new.execute(DELETES[-1])
        assert snapshot(old) == snapshot(new)
        remaining = new.execute("SELECT COUNT(*),MIN(id),MAX(id),MIN(time_ms),MAX(time_ms) FROM history").fetchone()
        assert remaining[0] == min(count, 500000)
        result.append(dict(input=count, retained=remaining[0], oldest_id=remaining[1], newest_id=remaining[2]))
        old.close()
        new.close()
    return result


def index_probe():
    """Assess the proposed kind index on the actual batched query; no schema change."""
    db = database()
    with db:
        db.executemany("INSERT INTO history(time_ms,kind,label,source) VALUES (?,?,'fixture',?)",
                       ((i * 1000, "reading" if i % 3 else "terminal_history", "LOCAL_PHONE" if i % 2 else "BEACON")
                        for i in range(20000)))
    result = {}
    for proposed in (False, True):
        if proposed:
            db.execute("CREATE INDEX candidate_kind_time ON history(kind,time_ms)")
        entries = []
        for flavor, predicate in PREDICATES.items():
            sql = BATCH.replace("$selection", predicate)
            bounds = (5000000, 6000000)
            times = []
            for _ in range(7):
                start = time.perf_counter_ns()
                rows = db.execute(sql, bounds).fetchall()
                times.append((time.perf_counter_ns() - start) / 1e6)
            entries.append(dict(flavor=flavor, rows=len(rows), median_ms=statistics.median(times),
                                plan=db.execute("EXPLAIN QUERY PLAN " + sql, bounds).fetchall()))
        result["candidate_index" if proposed else "existing_indexes"] = dict(queries=entries,
            bytes=db.execute("PRAGMA page_count").fetchone()[0] * db.execute("PRAGMA page_size").fetchone()[0])
    db.close()
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--large", action="store_true")
    options = parser.parse_args()
    result = dict(environment="Desktop SQLite fixtures; not Android hardware", sqlite=sqlite3.sqlite_version,
                  imports=imports(), trimming=trimming(), indexes=index_probe())
    if options.large:
        result["cap_boundaries"] = cap_boundaries()
    print(json.dumps(result, indent=2))
    print("PASS: full-row parity, source/identity dedup, retry, NULL/zero, rollback and retention")
