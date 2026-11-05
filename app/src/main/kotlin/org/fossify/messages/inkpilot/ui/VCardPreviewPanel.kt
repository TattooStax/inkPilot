package org.fossify.messages.inkpilot.ui

import android.net.Uri
import android.view.View
import androidx.appcompat.app.AlertDialog
import org.fossify.messages.inkpilot.data.StudioRepository
import org.fossify.messages.inkpilot.data.VCardImportResult
import org.fossify.messages.inkpilot.data.importVCardContact

private const val CARD_TITLE_SP = 18f

internal fun isVCardAttachment(mime: String, filename: String): Boolean =
    mime.lowercase() in setOf("text/vcard", "text/x-vcard", "text/directory",
        "application/vcard", "application/x-vcard") ||
        filename.lowercase().endsWith(".vcf")

private fun StudioHomeActivity.openRouteIfCurrent(expected: StudioRoute, destination: StudioRoute) {
    if (currentRoute == expected) openRoute(destination)
}

/** The card remains in the SMS canvas; only an explicit staff choice creates a Studio contact. */
internal class VCardPreviewPanel(
    private val activity: StudioHomeActivity,
    private val repository: StudioRepository,
    private val work: StudioWork,
    private val origin: StudioRoute.Attachment,
) {
    fun view(uri: Uri): View {
        val body = StudioUi.column(activity)
        body.addView(StudioUi.text(activity, "Reading contact card…"))
        work.load({ readVCardPreview(activity, uri) }) { result ->
            body.removeAllViews()
            when (result) {
                VCardPreviewResult.Unavailable -> body.addView(StudioUi.text(activity,
                    "This contact card cannot be read here. Reopen the attachment to try again."))
                is VCardPreviewResult.Ready -> result.cards.forEach { renderCard(body, it) }
            }
        }
        return body
    }

    private fun renderCard(body: android.widget.LinearLayout, card: VCardContactPreview) {
        body.addView(StudioUi.text(activity, card.name.ifBlank { "Unnamed contact" }, CARD_TITLE_SP, true))
        if (card.phones.isEmpty())
            body.addView(StudioUi.text(activity, "No usable phone number in this card."))
        card.phones.forEach { phone ->
            body.addView(StudioUi.text(activity, phone))
            body.addView(StudioUi.button(activity, "Add to Studio contacts · $phone") {
                confirmImport(card.name, phone, body)
            })
        }
        body.addView(StudioUi.rule(activity))
    }

    private fun confirmImport(name: String, phone: String, body: android.widget.LinearLayout) {
        AlertDialog.Builder(activity).setTitle("Add Studio contact")
            .setMessage("Add ${name.ifBlank { phone }} at $phone? Existing client records will not be changed.")
            .setNegativeButton("Cancel", null).setPositiveButton("Add") { _, _ ->
                work.load({ repository.importVCardContact(phone, name) }) { result ->
                    when (result) {
                        is VCardImportResult.Added -> activity.openRouteIfCurrent(origin,
                            StudioRoute.Tab(StudioTab.CONTACTS))
                        VCardImportResult.NeedsReview -> body.addView(StudioUi.text(activity,
                            "That phone address may already belong to a client. " +
                                "Review identity in Contacts before changing it."))
                    }
                }
            }.show()
    }
}
