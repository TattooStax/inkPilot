package org.fossify.messages.inkpilot.data

/** A deterministic staff choice. A held row is never a send authorization. */
sealed interface StaffQuestionResult {
    data class Held(val draftId: String) : StaffQuestionResult
    data object ConflictingHeld : StaffQuestionResult
    data object Stale : StaffQuestionResult
}
