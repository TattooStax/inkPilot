package org.fossify.messages.messaging

import android.telephony.SmsManager
import com.klinker.android.send_message.Settings

/** Explicit subscription failures propagate; never reuse a manager for another SIM. */
@Suppress("DEPRECATION")
fun getSmsManager(subId: Int): SmsManager = selectSmsManager(
    subId, SmsManager::getSmsManagerForSubscriptionId, SmsManager::getDefault
)

internal fun selectSmsManager(
    subId: Int, forSubscription: (Int) -> SmsManager, defaultManager: () -> SmsManager
): SmsManager = if (subId == Settings.DEFAULT_SUBSCRIPTION_ID) defaultManager() else forSubscription(subId)
