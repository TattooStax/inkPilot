"""Exercise production v6 DDL over a frozen v5 SQLite shape and interrupted migrations."""
import pathlib
import re
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/kotlin/org/fossify/messages/inkpilot/data/StudioProposalSchema.kt").read_text()
STATEMENTS = [(a or b).strip() for a, b in re.findall(
    r'db\.execSQL\((?:"""(.*?)"""|"([^"]+)")\)', SOURCE, re.S)]


def frozen_v5():
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    db.execute("CREATE TABLE studio_contacts(id TEXT PRIMARY KEY)")
    db.execute("CREATE TABLE inbound_events(event_id TEXT PRIMARY KEY)")
    db.execute("CREATE TABLE studio_staff(id INTEGER PRIMARY KEY)")
    db.execute("INSERT INTO studio_contacts VALUES('client')")
    db.execute("INSERT INTO inbound_events VALUES('event')")
    db.execute("INSERT INTO studio_staff VALUES(7)")
    db.commit()
    return db


class StudioProposalsV6MigrationTest(unittest.TestCase):
    def test_migration_preserves_sources_and_enforces_ledger(self):
        self.assertEqual(3, len(STATEMENTS))
        with frozen_v5() as db:
            db.execute("BEGIN")
            for sql in STATEMENTS:
                db.execute(sql)
            db.commit()
            row = ("proposal", "event", "APPOINTMENT", "client", "42", 1, "{}", "HELD", None, 7, 1234, None)
            db.execute("INSERT INTO action_proposals VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", row)
            self.assertEqual(("event", "client"), db.execute(
                "SELECT event_id,contact_id FROM action_proposals").fetchone())
            self.assertEqual(("client",), db.execute("SELECT id FROM studio_contacts").fetchone())
            self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("INSERT INTO action_proposals VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                           ("other", *row[1:]))
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("UPDATE action_proposals SET state='SENT' WHERE id='proposal'")
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("UPDATE action_proposals SET staff_id=99 WHERE id='proposal'")

    def test_interrupted_migration_rolls_back(self):
        for last in range(1, len(STATEMENTS) + 1):
            with self.subTest(last=last), frozen_v5() as db:
                db.execute("BEGIN")
                for sql in STATEMENTS[:last]:
                    db.execute(sql)
                db.execute("ROLLBACK")
                self.assertEqual([], db.execute(
                    "SELECT name FROM sqlite_master WHERE name='action_proposals'").fetchall())
                self.assertEqual(("event",), db.execute(
                    "SELECT event_id FROM inbound_events").fetchone())


if __name__ == "__main__":
    unittest.main()
