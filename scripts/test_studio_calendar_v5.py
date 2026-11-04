"""Run production v5 DDL over a frozen v4 SQLite fixture, including rollback."""
import pathlib
import re
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/kotlin/org/fossify/messages/inkpilot/studio/StudioCalendarSchema.kt").read_text()
STATEMENTS = [(m.group(1) or m.group(2)).strip() for m in
              re.finditer(r'db\.execSQL\((?:"""(.*?)"""|"([^"]+)")\)', SOURCE, re.S)]


def frozen_v4():
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    db.execute("CREATE TABLE studio_contacts(id TEXT PRIMARY KEY,number TEXT,display_name TEXT)")
    db.execute("CREATE TABLE studio_staff(id INTEGER PRIMARY KEY,name TEXT)")
    db.execute("CREATE TABLE studio_calendar(id INTEGER PRIMARY KEY,title TEXT,kind TEXT,staff_id INTEGER REFERENCES studio_staff(id),starts_at INTEGER,ends_at INTEGER,zone_id TEXT,room TEXT,status TEXT)")
    db.execute("CREATE TABLE studio_images(id INTEGER PRIMARY KEY,filename TEXT,display_name TEXT,imported_at INTEGER)")
    db.execute("CREATE TABLE image_links(image_id INTEGER REFERENCES studio_images(id),contact_id TEXT REFERENCES studio_contacts(id),appointment_id INTEGER REFERENCES studio_calendar(id),source_origin TEXT,source_id INTEGER,thread_id INTEGER)")
    db.execute("INSERT INTO studio_contacts VALUES('client','+12025550101','Synthetic')")
    db.execute("INSERT INTO studio_staff VALUES(71,'Artist')")
    db.execute("INSERT INTO studio_calendar VALUES(72,'Original','appointment',71,1800000000000,1800003600000,'America/Chicago','','scheduled')")
    db.execute("INSERT INTO studio_images VALUES(73,'original.png','Original',1)")
    db.execute("INSERT INTO image_links VALUES(73,'client',72,'MMS',74,75)")
    db.commit()
    return db


class StudioCalendarV5MigrationTest(unittest.TestCase):
    def test_upgrade_retains_old_records_and_adds_constraints(self):
        self.assertEqual(8, len(STATEMENTS))
        with frozen_v4() as db:
            db.execute("BEGIN")
            for sql in STATEMENTS:
                db.execute(sql)
            db.commit()
            self.assertEqual((72, "Original", 0, None), db.execute(
                "SELECT id,title,revision,reminder_at FROM studio_calendar").fetchone())
            self.assertEqual((73, "client", 72), db.execute(
                "SELECT image_id,contact_id,appointment_id FROM image_links").fetchone())
            self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("INSERT INTO studio_staff_hours VALUES(71,8,540,1020)")

    def test_every_interrupted_upgrade_rolls_back(self):
        for last in range(1, len(STATEMENTS) + 1):
            with self.subTest(last=last), frozen_v4() as db:
                db.execute("BEGIN")
                for sql in STATEMENTS[:last]:
                    db.execute(sql)
                db.execute("ROLLBACK")
                columns = [row[1] for row in db.execute("PRAGMA table_info(studio_calendar)")]
                self.assertEqual(["id","title","kind","staff_id","starts_at","ends_at","zone_id","room","status"], columns)
                self.assertEqual((72, "Original"), db.execute("SELECT id,title FROM studio_calendar").fetchone())
                self.assertEqual((73, "client", 72), db.execute(
                    "SELECT image_id,contact_id,appointment_id FROM image_links").fetchone())
                self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())


if __name__ == "__main__":
    unittest.main()
