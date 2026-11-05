package org.fossify.messages.inkpilot.data

import inkpilot.agent.LeadQuestion

/** Stable database representation; enum declaration order must not change these bit positions. */
internal object LeadQuestionMask {
    private const val PLACEMENT = 1
    private const val SIZE = 2
    private const val STYLE = 4
    private const val REFERENCE = 8
    private const val ARTIST = 16
    private const val DATE = 32
    const val MAX_VALUE = 63

    private fun bit(question: LeadQuestion): Int = when (question) {
        LeadQuestion.PLACEMENT -> PLACEMENT
        LeadQuestion.SIZE -> SIZE
        LeadQuestion.STYLE -> STYLE
        LeadQuestion.REFERENCE -> REFERENCE
        LeadQuestion.ARTIST -> ARTIST
        LeadQuestion.DATE -> DATE
    }

    fun encode(questions: Set<LeadQuestion>): Int = questions.fold(0) { mask, question -> mask or bit(question) }

    fun decode(mask: Int): Set<LeadQuestion> = LeadQuestion.entries.filterTo(mutableSetOf()) {
        mask and bit(it) != 0
    }
}
