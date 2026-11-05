package org.fossify.messages.inkpilot.phone

import android.Manifest
import android.content.pm.PackageManager
import android.provider.CallLog
import android.text.format.DateFormat
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.fossify.messages.inkpilot.ui.StudioUi
import org.fossify.messages.inkpilot.ui.StudioWork

private const val HISTORY_TITLE_SP = 24f
private const val HISTORY_ROW_SP = 14f

/** A read-only recent call list inside the Phone tab; a row only fills the dialer. */
internal class PhoneHistoryPanel(
    private val activity: AppCompatActivity,
    private val work: StudioWork,
    private val openDial: (String) -> Unit,
    private val back: () -> Unit,
) {
    fun view(): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.button(activity, "‹ Phone", back))
        body.addView(StudioUi.text(activity, "Call history", HISTORY_TITLE_SP, true))
        if (!PhoneRoleSetup.held(activity)) {
            body.addView(StudioUi.text(activity, "Choose inkPilot as your Phone app to see recent calls here."))
            body.addView(StudioUi.button(activity, "Choose inkPilot as Phone app") { PhoneRoleSetup.request(activity) })
        } else if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED) {
            body.addView(StudioUi.text(activity, "Allow call history access to show your recent calls."))
            body.addView(StudioUi.button(activity, "Allow call history") {
                ActivityCompat.requestPermissions(activity,
                    arrayOf(Manifest.permission.READ_CALL_LOG), CALL_LOG_REQUEST)
            })
        } else {
            val list = StudioUi.column(activity, 0)
            list.addView(StudioUi.text(activity, "Loading recent calls…"))
            body.addView(list)
            work.load(::recentCalls) { calls ->
                list.removeAllViews()
                if (calls.isEmpty()) list.addView(StudioUi.text(activity, "No recent calls."))
                calls.forEach { call ->
                    val label = "${call.direction} · ${DateFormat.format("MMM d, h:mm a", call.date)}"
                    list.addView(StudioUi.text(activity, label, HISTORY_ROW_SP))
                    if (call.number.isBlank()) list.addView(StudioUi.text(activity, "Private or unavailable number"))
                    else list.addView(StudioUi.button(activity, call.number) { openDial(call.number) })
                }
            }
        }
        return StudioUi.scroll(activity, body)
    }

    private fun recentCalls(): List<RecentCall> {
        val columns = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE)
        return activity.contentResolver.query(CallLog.Calls.CONTENT_URI, columns,
            null, null, "${CallLog.Calls.DATE} DESC")
            ?.use { cursor ->
                val calls = mutableListOf<RecentCall>()
                while (cursor.moveToNext() && calls.size < MAX_CALLS) {
                    val type = when (cursor.getInt(1)) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        else -> "Call"
                    }
                    calls += RecentCall(cursor.getString(0).orEmpty(), type, cursor.getLong(2))
                }
                calls
            }.orEmpty()
    }

    private data class RecentCall(val number: String, val direction: String, val date: Long)
    companion object {
        private const val CALL_LOG_REQUEST = 721
        private const val MAX_CALLS = 40
    }
}
