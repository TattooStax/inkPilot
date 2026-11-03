package org.fossify.messages.transport

/**
 * The one lock for every read-modify-write of the stored
 * outbound send progress: a multipart send's parts report
 * through separate broadcasts, each reading the progress the
 * earlier parts left, folding its own part's result in, and
 * storing the result — and without one order between them,
 * two parts reporting together could each store a progress
 * missing the other's result, and the send's one verdict
 * would never complete. It is process-wide so it holds across
 * the sender that opens a send's progress and every
 * sent-result broadcast that folds into it.
 *
 * Only the storage operations run under this lock: the
 * verdict a completed progress produces is filed after the
 * lock is released, under the credential guard's own order.
 */
internal object OutboundSendProgressGuard {
    val lock = Any()
}
