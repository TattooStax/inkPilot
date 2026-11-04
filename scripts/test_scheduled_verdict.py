#!/usr/bin/env python3
"""Host regression checks for durable SQL identity and irreversible dispatch ordering.
Android tests exercise the journal lifecycle; JUnit executes the shared part reducer.
ROOT may point to a frozen baseline to demonstrate the original regressions.
"""
import os
import re
import sqlite3
import unittest
from pathlib import Path
ROOT = Path(os.environ.get('ROOT', Path(__file__).resolve().parents[1]))
BASE = ROOT / 'app/src/main/kotlin/org/fossify/messages'

def source(name):
    p = BASE / name
    return p.read_text() if p.exists() else ''

class ScheduledRegressionTest(unittest.TestCase):
    def test_alarm_identity_uses_full_typed_key(self):
        alarm = source('messaging/ScheduledMessage.kt')
        self.assertNotIn('message.id.toInt()', alarm)
        self.assertIn('scheduledAlarmIntent(message.key(), message.threadId)', alarm)
        self.assertIn('data = Uri.parse(key.alarmIdentity())', alarm)
        self.assertIn('FLAG_NO_CREATE', alarm)

    def test_dispatch_has_durable_claim_before_handoff(self):
        receiver = source('receivers/ScheduledMessageReceiver.kt')
        self.assertNotIn('deleteScheduledMessage(', receiver)
        self.assertIn('intent.scheduledKey() ?: return', receiver)
        dispatch = source('messaging/ScheduledDispatch.kt')
        self.assertLess(dispatch.index('journal.claim(key, count)'), dispatch.index('messagingUtils.sendMmsMessage'))
        self.assertIn('journal.unknown(key)', dispatch)

    def test_expiry_does_not_delete_unresolved_sends(self):
        expiry = source('extensions/Context.kt').split('fun Context.clearExpiredScheduledMessages')[1].split('fun Context.rescheduleAllScheduledMessages')[0]
        self.assertIn('journal.state(it.key()) == "CONFIRMED"', expiry)
        self.assertNotIn('millis()', expiry)

    def test_failed_mms_resend_preserves_original(self):
        self.assertIn('originalResentMessageId != -1L && receiverResultCode == Activity.RESULT_OK', source('receivers/MmsSentReceiver.kt'))

    def test_production_journal_schema_survives_reopen_and_enforces_unique_claims(self):
        ddl = re.findall(r'db.execSQL\("(CREATE TABLE [^"\n]+)"\)', source('messaging/ScheduledVerdicts.kt'))
        self.assertEqual(2, len(ddl))
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            path = str(Path(tmp) / 'journal.db')
            db = sqlite3.connect(path)
            for sql in ddl: db.execute(sql)
            keys = ['inkpilot-scheduled://dispatch/SCHEDULED_SMS/42', 'inkpilot-scheduled://dispatch/SCHEDULED_MMS/42', 'inkpilot-scheduled://dispatch/SCHEDULED_SMS/4294967338']
            for key in keys: db.execute("INSERT OR IGNORE INTO claims VALUES (?, 'DISPATCHING', 2)", (key,))
            db.commit(); db.close()
            db = sqlite3.connect(path)
            for key in keys: db.execute("INSERT OR IGNORE INTO claims VALUES (?, 'QUEUED', 2)", (key,))
            self.assertEqual([('DISPATCHING',)] * 3, db.execute('SELECT state FROM claims').fetchall())
            db.close()

if __name__ == '__main__': unittest.main()
