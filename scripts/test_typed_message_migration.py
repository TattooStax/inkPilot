#!/usr/bin/env python3
"""Exercise production migration SQL with frozen v11 schema using host SQLite.
Android Room schema/DAO validation remains an instrumentation gate.
"""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/kotlin/org/fossify/messages'


def statements():
    source = (SOURCE / 'databases/TypedMessageMigration.kt').read_text()
    columns = 'id, body, type, status, participants, date, read, thread_id, is_mms, attachment, sender_phone_number, sender_name, sender_photo_uri, subscription_id, is_scheduled'
    return [next(x for x in match if x).replace('$columns', columns)
            for match in re.findall(r'execSQL\((?:"""(.*?)"""\.trimIndent\(\)|"([^"\n]*)")\)',source,re.S)]


def frozen():
    db = sqlite3.connect(':memory:')
    source = (ROOT / 'app/src/androidTest/kotlin/org/fossify/messages/inkpilot/data/FrozenMessagesV11.kt').read_text()
    for sql in [next(x for x in match if x) for match in re.findall(r'execSQL\((?:"""(.*?)"""\.trimIndent\(\)|"([^"\n]*)")\)',source,re.S)]:
        db.execute(sql)
    db.execute("INSERT INTO messages VALUES(42,'human scheduled',2,0,'[]',100,0,9,1,?, '', '', '', -1,1)", ('{"media":"unchanged"}',))
    db.execute("INSERT INTO drafts VALUES(9,'human draft',1)")
    db.execute("INSERT INTO recycle_bin_messages VALUES(42,123)")
    db.commit()
    return db


class TypedMigrationTest(unittest.TestCase):
    def test_four_way_collision_and_opaque_preservation(self):
        db = frozen()
        db.execute("BEGIN")
        with db:
            for sql in statements(): db.execute(sql)
        for mms, scheduled in [(0,0),(1,0),(0,1)]:
            db.execute("INSERT INTO messages SELECT id,body,type,status,participants,date,read,thread_id,?,attachment,sender_phone_number,sender_name,sender_photo_uri,subscription_id,? FROM messages LIMIT 1",(mms,scheduled))
        self.assertEqual(4, db.execute('SELECT COUNT(*) FROM messages').fetchone()[0])
        self.assertEqual([(42,123,4)],db.execute('SELECT * FROM recycle_bin_messages').fetchall())
        self.assertEqual('{"media":"unchanged"}',db.execute('SELECT attachment FROM messages WHERE is_mms=1 AND is_scheduled=1').fetchone()[0])
        self.assertEqual('human draft',db.execute('SELECT body FROM drafts').fetchone()[0])

    def test_real_dao_queries_keep_unknown_and_other_origins_safe(self):
        db = frozen()
        db.execute("BEGIN")
        with db:
            for sql in statements(): db.execute(sql)
        for mms,scheduled in [(0,0),(1,0),(0,1)]:
            db.execute("INSERT INTO messages SELECT id,body,type,status,participants,date,read,thread_id,?,attachment,sender_phone_number,sender_name,sender_photo_uri,subscription_id,? FROM messages LIMIT 1",(mms,scheduled))
        dao=(SOURCE/'interfaces/MessagesDao.kt').read_text()
        queries=dict((name,sql) for sql,name in re.findall(r'@Query\("([^"\n]*)"\)\s+fun (\w+)',dao))
        self.assertEqual([],db.execute(queries['getAllRecycleBinMessages']).fetchall())
        self.assertEqual([],db.execute(queries['getOldRecycleBinMessages'],{'timestamp':999}).fetchall())
        db.execute('INSERT INTO recycle_bin_messages VALUES(42,100,2)')
        recycled=db.execute(queries['getAllRecycleBinMessages']).fetchall()
        self.assertEqual(1,len(recycled))
        self.assertEqual((0,1),(recycled[0][8],recycled[0][14]))
        db.execute(queries['deleteFromMessages'],{'id':42,'isMms':0,'isScheduled':1})
        self.assertEqual([(0,0),(1,0),(1,1)],db.execute('SELECT is_mms,is_scheduled FROM messages ORDER BY is_mms,is_scheduled').fetchall())
        self.assertEqual([(42,123,4)],db.execute('SELECT * FROM recycle_bin_messages WHERE kind_code=4').fetchall())

    def test_resolution_keeps_later_typed_timestamp_for_both_origins(self):
        dao=(SOURCE/'interfaces/LegacyRecycleDao.kt').read_text()
        self.assertRegex(dao,r'@Insert\(onConflict = OnConflictStrategy.IGNORE\)\s+fun insertRecycleBinEntryIfAbsent')
        queries=dict((name,sql) for sql,name in re.findall(r'@Query\("([^"\n]*)"\)\s+fun (\w+)',dao))
        for origin in [0,1]:
            for typed_ts in [5,200]:
                db=frozen()
                db.execute("BEGIN")
                with db:
                    for sql in statements(): db.execute(sql)
                db.execute('INSERT INTO recycle_bin_messages VALUES(42,?,?)',(typed_ts,origin))
                with db:
                    db.execute('INSERT OR IGNORE INTO recycle_bin_messages VALUES(42,123,?)',(origin,))
                    db.execute(queries['retainLaterRecycleTimestamp'],{'id':42,'kindCode':origin,'deletedTs':123})
                    db.execute(queries['deleteFromRecycleBin'],{'id':42,'kindCode':4})
                self.assertEqual([(42,max(typed_ts,123),origin)],db.execute('SELECT * FROM recycle_bin_messages').fetchall())
                if typed_ts == 200:
                    self.assertEqual([],db.execute('SELECT * FROM recycle_bin_messages WHERE deleted_ts < 150').fetchall())

    def test_every_interruption_rolls_back(self):
        sqls=statements()
        self.assertEqual(9,len(sqls))
        for stop in range(1,len(sqls)+1):
            db=frozen()
            db.execute("BEGIN")
            try:
                with db:
                    for sql in sqls[:stop]: db.execute(sql)
                    raise RuntimeError('interruption')
            except RuntimeError: pass
            self.assertEqual([(42,123)],db.execute('SELECT * FROM recycle_bin_messages').fetchall())
            self.assertEqual('human scheduled',db.execute('SELECT body FROM messages').fetchone()[0])
            self.assertIsNone(db.execute("SELECT name FROM sqlite_master WHERE name='messages_typed'").fetchone())


if __name__ == '__main__': unittest.main()
