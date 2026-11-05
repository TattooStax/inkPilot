package org.fossify.messages.activities

import android.annotation.SuppressLint
import android.telephony.SmsManager
import android.widget.ImageView
import android.widget.TextView
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.subscriptionManagerCompat
import org.fossify.messages.messaging.ComposerSimSelection
import org.fossify.messages.models.SIMCard

fun ComposerSimSelection.render(number: TextView) {
    number.text = label
    number.contentDescription = selectedCard?.label ?: "Selected SIM unavailable"
}

@SuppressLint("MissingPermission")
fun SimpleActivity.setupThreadSimSelector(
    selection: ComposerSimSelection, icon: ImageView, number: TextView,
    numbers: List<String>, receivedSubscriptionId: Int?
) {
    val subscriptions = subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
    val cards = subscriptions.mapIndexed { index, info ->
        val label = info.displayName?.toString().orEmpty() + info.number?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
        SIMCard(index + 1, info.subscriptionId, label)
    }
    val preferredIds = listOfNotNull(numbers.firstOrNull()?.let { config.getUseSIMIdAtNumber(it) },
        receivedSubscriptionId, SmsManager.getDefaultSmsSubscriptionId())
    selection.update(cards, preferredIds.firstOrNull { id -> cards.any { it.subscriptionId == id } }
        ?: cards.firstOrNull()?.subscriptionId)
    icon.applyColorFilter(getProperTextColor())
    icon.beVisible()
    number.beVisible()
    number.setTextColor(getProperTextColor().getContrastColor())
    selection.render(number)
    icon.setOnClickListener {
        selection.selectNext()
        selection.render(number)
        selection.selectedCard?.let { card ->
            numbers.forEach { config.saveUseSIMIdAtNumber(it, card.subscriptionId) }
            toast(card.label)
        }
    }
}
