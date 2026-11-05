package org.fossify.messages.inkpilot.data

import android.content.Context
import android.os.Build
import org.fossify.messages.messaging.MessageKey
import org.fossify.messages.messaging.MessageOrigin
import org.fossify.messages.messaging.ScheduledVerdicts
import org.fossify.messages.messaging.alarmIdentity

/** Remove only synthetic claims owned by one test method; never reset the app-wide journal. */
internal class OwnedScheduledVerdictFixture(private val context: Context) {
    private val identities = linkedSetOf<String>()

    init { check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only." } }

    fun track(key: MessageKey): MessageKey {
        require(key.origin == MessageOrigin.SCHEDULED_SMS || key.origin == MessageOrigin.SCHEDULED_MMS)
        val identity = key.alarmIdentity()
        check(identity !in identities)
        ScheduledVerdicts(context).use { check(it.state(key) == "QUEUED") { "Synthetic verdict key already exists" } }
        identities += identity
        return key
    }

    fun cleanup() {
        if (identities.isEmpty()) return
        ScheduledVerdicts(context).use { journal ->
            val db = journal.writableDatabase
            db.beginTransaction()
            try {
                identities.forEach { identity ->
                    db.delete("parts", "identity = ?", arrayOf(identity))
                    db.delete("claims", "identity = ?", arrayOf(identity))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        identities.clear()
    }
}
