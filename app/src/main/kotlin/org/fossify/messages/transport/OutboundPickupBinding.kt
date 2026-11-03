package org.fossify.messages.transport

import android.content.Context
import inkstax.sms.transport.DeviceCredential
import inkstax.sms.transport.OutboundPickupOutcome
import inkstax.sms.transport.outboundPickupOutcomeFor
import inkstax.sms.transport.outboundPickupRequestFor
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val PICKUP_CONNECT_TIMEOUT_MILLIS = 10_000
private const val PICKUP_READ_TIMEOUT_MILLIS = 15_000

/**
 * The largest pickup answer this binding will read. A work answer
 * is a small flat JSON object; a larger answer is not one,
 * however it is labelled — and the endpoint is configuration, so
 * an unbounded read would let any endpoint exhaust the app's
 * memory before the answer is even judged.
 */
private const val PICKUP_MAX_RESPONSE_BYTES = 16_384

/** The result of one outbound pickup run by this binding. */
sealed interface OutboundPickupBindingResult {
    /**
     * No pickup could be built: this device holds no credential
     * the seam accepts for its own endpoint, so there is no key to
     * pick up with. That is a broken or absent pairing whose
     * recovery is pairing (again), never a link that merely went
     * quiet — the caller must not read it as an unreachable
     * endpoint, and any work the brain holds stays queued there.
     */
    data object NoUsableCredential : OutboundPickupBindingResult

    /** A pickup was sent and its answer was read the one way. */
    data class Completed(val outcome: OutboundPickupOutcome) : OutboundPickupBindingResult
}

/**
 * Picks up one outbound work item from the brain for this
 * device's stored credential, picking up at [requestedAtMillis].
 * This is the binding that performs the pickup the :transport
 * definitions describe: the request is the one
 * [outboundPickupRequestFor] builds from the stored credential,
 * and the answer is read the one way [outboundPickupOutcomeFor]
 * reads it. Work the answer hands over is the caller's to send
 * through the platform SMS API under the default-SMS role; this
 * binding sends nothing itself, and the phone's verdict on a
 * send is reported through the outbound ack binding, never
 * through this one. An empty queue is its own outcome, distinct
 * from every failure, so an idle phone never reads as a broken
 * link.
 *
 * The credential's endpoint is HTTPS — or loopback HTTP on this
 * device itself, the local binding's case, where the traffic
 * never leaves the phone — so the secret travels only over TLS
 * or not over a network at all, and only in the authorization
 * value the wire shape defines — never in the body, never
 * logged here.
 *
 * A negative [requestedAtMillis] is a caller error and throws:
 * an argument fault must never be reported as a missing or
 * broken credential, whose documented recovery is re-pairing.
 *
 * This call blocks on the network, so it belongs on a background
 * thread. A transport fault while asking reads as no answer at
 * all, which the outcome reader names unreachable, and any work
 * the brain holds stays queued for a later pickup. Redirects are
 * never followed: the brain answers on the credential's own
 * endpoint or not at all, and a redirect status is read by the
 * outcome reader as an answer that hands over no work. The body
 * is read only on an OK status, and only up to a bounded size —
 * every other outcome is decided by the status alone, because
 * the empty-queue answer is defined to carry no body, so a
 * credential refusal is reported as such even when its error
 * body cannot be read, never retried as if the endpoint were
 * merely unreachable. A body read that fails or runs past the
 * bound leaves the body unread, which the outcome reader names
 * malformed on an OK status: an answer that cannot be read
 * exactly names no send. The body is decoded as strict UTF-8 —
 * malformed bytes fail the decode instead of being replaced,
 * because a replacement character inside a work item would
 * hand the caller altered work (a changed message body, or a
 * changed source id its verdict is filed against) that the
 * outcome reader would accept as the work the brain queued.
 */
fun Context.performOutboundPickup(requestedAtMillis: Long): OutboundPickupBindingResult {
    require(requestedAtMillis >= 0L) { "requestedAtMillis must not be negative" }
    val credential = storedDeviceCredential()
        ?: return OutboundPickupBindingResult.NoUsableCredential
    return performOutboundPickup(credential, requestedAtMillis)
}

/**
 * The same pickup performed with a credential the caller
 * already holds: a caller that acts under a pairing — a sender
 * that picked work up under the stored credential and reports
 * its verdicts under that same pairing — picks up with that
 * pairing's credential, never one a disconnect and re-pair may
 * have stored since, so work taken from one pairing's endpoint
 * is only ever sent and acked under that pairing.
 */
@Suppress("SwallowedException")
internal fun Context.performOutboundPickup(
    credential: DeviceCredential,
    requestedAtMillis: Long
): OutboundPickupBindingResult {
    require(requestedAtMillis >= 0L) { "requestedAtMillis must not be negative" }
    val request = outboundPickupRequestFor(credential, requestedAtMillis)
        ?: return OutboundPickupBindingResult.NoUsableCredential
    var connection: HttpURLConnection? = null
    var statusCode: Int? = null
    var responseBody: String? = null
    try {
        connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = PICKUP_CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = PICKUP_READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", request.authorization)
        connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
        statusCode = connection.responseCode
        if (statusCode == HttpURLConnection.HTTP_OK) {
            responseBody = runCatching { readPickupResponseBody(connection) }.getOrNull()
        }
    } catch (e: IOException) {
        statusCode = null
        responseBody = null
    } finally {
        connection?.disconnect()
    }
    return OutboundPickupBindingResult.Completed(outboundPickupOutcomeFor(statusCode, responseBody))
}

/**
 * Reads a work answer's body, or returns null when it runs past
 * [PICKUP_MAX_RESPONSE_BYTES] — an oversized answer is no work
 * answer, and the outcome reader names a missing body on an OK
 * status malformed. Bytes past the limit are never buffered, so
 * the read stops instead of filling memory first. The buffered
 * bytes are decoded as strict UTF-8: a decoder that reports
 * malformed and unmappable input throws instead of substituting
 * a replacement character, and the caller's guarded read turns
 * that failure into a missing body.
 */
private fun readPickupResponseBody(connection: HttpURLConnection): String? {
    val bytes = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    connection.inputStream.use { stream ->
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) {
                break
            }
            if (bytes.size() + count > PICKUP_MAX_RESPONSE_BYTES) {
                return null
            }
            bytes.write(buffer, 0, count)
        }
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
}
