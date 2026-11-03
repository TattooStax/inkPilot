package inkstax.sms.transport

/**
 * Outbound pickup wire definitions for the bridge seam.
 *
 * FailureHonesty.kt defines what the outbound half of dual ack
 * means — a reply the brain dispatched stays
 * [OutboundAckState.QUEUED] until the phone acks it actually
 * sent — and OutboundAckWire.kt defines how the phone reports
 * that verdict back. This file defines the missing first half:
 * the one wire shape every binding uses to pick the queued
 * work up from the brain, so the request a binding sends and
 * the reading of the answer it gets back cannot drift between
 * bindings. There is no network call and no send here — a
 * binding supplies the stored credential and the pickup time,
 * performs the HTTPS POST, and acts on the outcome: work it
 * receives is sent through the platform SMS API under the
 * default-SMS role, and the phone's verdict on that send is
 * reported through the outbound ack wire, never invented here.
 *
 * The pickup is a POST to the outbound pickup path on the
 * credential's own endpoint, authenticated by the credential
 * itself: the device id travels in the JSON body and the
 * secret travels only in the authorization value, in its
 * base64url-encoded spelling, never in the body — the same
 * shape HeartbeatWire.kt, MessageDeliveryWire.kt, and
 * OutboundAckWire.kt use, for the same reason: a logged
 * request body must never leak the device's key, and every
 * opaque secret the exchange can mint must spell a valid
 * header value. The body also carries the phone's pickup
 * time, so the brain can tell a pickup that was delayed in
 * transit from one that never arrived.
 *
 * The brain answers No Content when no outbound work is
 * queued for this device: that answer carries no body and no
 * work item, so an idle phone can never be mistaken for a
 * phone holding a reply it has not sent. When work is
 * queued, the brain answers OK with one flat JSON object of
 * string values carrying exactly one work item: the reply
 * under the source id the brain filed it against, the
 * recipient it goes to, the message body itself, and the
 * time the brain queued it. One item per pickup keeps the
 * dual-ack bookkeeping unambiguous — the phone sends what it
 * picked up, acks that source id, and picks up again — and
 * a drained queue reads as No Content on the next pickup.
 * Extra string fields are ignored: the brain may add
 * instance identity to the same answer without breaking
 * companions already in the field. Anything structurally
 * other than that flat object — a nested value, a number,
 * a repeated key — is malformed.
 *
 * A work item is accepted only when every field could name
 * a real send. The source id follows the identity rule the
 * whole seam renders: not blank, carrying no whitespace —
 * it is the identity the outbound ack report will file the
 * phone's verdict against, so an identity the seam could
 * not have minted is no identity to send under. The
 * recipient likewise must be non-blank and carry no
 * whitespace: an address the platform could dial is one
 * token, and an address with whitespace in it is a
 * rendering, not a destination. The message body must not
 * be blank — a blank body is not a text anyone dispatched.
 * The queue time must be a non-negative count of
 * milliseconds spelled in ASCII digits: it orders the work
 * and feeds the received-late style delay bookkeeping, and
 * a spelling the phone cannot read exactly is not a time
 * the brain queued anything at. A work item that fails any
 * of these reads as a malformed response, never as partial
 * work to send anyway: sending to a guessed recipient under
 * a guessed identity would put a real text in the wrong
 * place and file its verdict where the brain cannot match
 * it.
 *
 * A credential the seam does not accept builds no request
 * at all, in the same terms [isCredentialUsableFor] defines:
 * a corrupt or backup-restored stored value must never be
 * sent to an endpoint as if it were this device's key.
 */

/** Path of the outbound pickup on the brain's endpoint. */
const val OUTBOUND_PICKUP_PATH: String = "/companion/outbound-pickup"

/** JSON field that carries the device id in the pickup request. */
const val OUTBOUND_PICKUP_DEVICE_FIELD: String = "device_id"

/** JSON field that carries the phone's pickup time in the pickup request. */
const val OUTBOUND_PICKUP_REQUESTED_AT_FIELD: String = "requested_at_millis"

/** JSON field that carries the reply source id in the pickup answer. */
const val OUTBOUND_PICKUP_SOURCE_FIELD: String = "source_id"

/** JSON field that carries the recipient in the pickup answer. */
const val OUTBOUND_PICKUP_RECIPIENT_FIELD: String = "recipient"

/** JSON field that carries the message body in the pickup answer. */
const val OUTBOUND_PICKUP_BODY_FIELD: String = "body"

/** JSON field that carries the queue time in the pickup answer. */
const val OUTBOUND_PICKUP_QUEUED_AT_FIELD: String = "queued_at_millis"

/** Authorization scheme that carries the device secret on the pickup request. */
const val OUTBOUND_PICKUP_AUTHORIZATION_SCHEME: String = "Bearer"

/**
 * One outbound work item the brain queued for this device,
 * read from a pickup answer: the reply's [sourceId] (the
 * identity its ack report is filed against), the
 * [recipient] it goes to, the message [body] itself, and
 * the time the brain queued it. A work item exists only
 * when every field passed the acceptance rules above, so a
 * binding that holds one holds a send it may perform.
 */
data class OutboundWork(
    val sourceId: String,
    val recipient: String,
    val body: String,
    val queuedAtMillis: Long
)

/**
 * One outbound pickup request, ready for a binding to POST:
 * the URL on the credential's endpoint, the JSON body
 * carrying the device id and the pickup time, and the
 * authorization value carrying the secret. The secret
 * appears only in [authorization], in its encoded spelling
 * — it is a credential, never logged and never part of the
 * body. The generated data-class rendering would print that
 * value verbatim, so [toString] redacts it: a request that
 * lands in a log line shows where the pickup was going,
 * never the key it carried.
 */
data class OutboundPickupRequest(
    val url: String,
    val body: String,
    val authorization: String
) {
    override fun toString(): String {
        return "OutboundPickupRequest(url=" + url + ", body=" + body +
            ", authorization=" + OUTBOUND_PICKUP_AUTHORIZATION_SCHEME + " <redacted>)"
    }
}

/**
 * The pickup request for a stored [credential] picking up at
 * [requestedAtMillis], or null when no pickup may be sent:
 * the pickup time is negative, or the credential is not one
 * the seam accepts for its own endpoint. Building no
 * request is the honest outcome for a credential that does
 * not count — the binding surfaces the broken pairing
 * instead of presenting a key the brain never minted for
 * this device.
 */
fun outboundPickupRequestFor(credential: DeviceCredential, requestedAtMillis: Long): OutboundPickupRequest? {
    if (requestedAtMillis < 0L) {
        return null
    }
    if (!isCredentialUsableFor(credential, credential.endpointBase)) {
        return null
    }
    return OutboundPickupRequest(
        url = credential.endpointBase.trimEnd('/') + OUTBOUND_PICKUP_PATH,
        body = "{\"$OUTBOUND_PICKUP_DEVICE_FIELD\":${jsonString(credential.deviceId)}," +
            "\"$OUTBOUND_PICKUP_REQUESTED_AT_FIELD\":${jsonString(requestedAtMillis.toString())}}",
        authorization = OUTBOUND_PICKUP_AUTHORIZATION_SCHEME + " " + base64Url(credential.secret)
    )
}

/**
 * The base64url spelling of a value, without padding. The
 * alphabet is exactly the characters a header value may
 * carry, so any opaque secret encodes to a valid
 * authorization value, and decoding the spelling recovers
 * the value itself.
 */
private fun base64Url(value: String): String {
    return java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))
}

/** Why an outbound pickup did not hand over work. */
enum class OutboundPickupFailure {
    /** The endpoint does not recognise this device credential. */
    CREDENTIAL_REJECTED,

    /** The endpoint refused the pickup for another reason. */
    ENDPOINT_REJECTED,

    /** The endpoint could not be reached at all. */
    NETWORK_UNREACHABLE,

    /** The endpoint answered, but not with a usable work item. */
    MALFORMED_RESPONSE
}

/** The outcome of one outbound pickup attempt, as the seam reads it. */
sealed interface OutboundPickupOutcome {
    /** The brain handed over this work item for the phone to send. */
    data class WorkAvailable(val work: OutboundWork) : OutboundPickupOutcome

    /** The brain holds no outbound work for this device right now. */
    data object NoWork : OutboundPickupOutcome

    /** The pickup handed over no work; the failure says why. */
    data class Failed(val failure: OutboundPickupFailure) : OutboundPickupOutcome
}

/**
 * Reads one pickup attempt's answer as an
 * [OutboundPickupOutcome]. A null [statusCode] means no
 * answer arrived at all — the endpoint could not be
 * reached, and any work the brain holds stays queued there
 * for a later pickup. Only No Content is the empty queue:
 * that answer is defined to carry no body, so the status
 * alone decides and a body is never read into evidence.
 * On an OK status the body must still parse as the flat
 * answer shape and pass the work-item acceptance rules; a
 * well-formed answer whose fields name no real send is a
 * malformed response, so the phone never sends a text to a
 * guessed recipient under a guessed identity. A credential
 * refusal is its own failure, not a transient one —
 * retrying the same key cannot succeed, and the phone
 * reads it as a broken pairing whose recovery is
 * re-pairing, never as a link that merely went quiet.
 */
fun outboundPickupOutcomeFor(statusCode: Int?, responseBody: String?): OutboundPickupOutcome {
    if (statusCode == null) {
        return OutboundPickupOutcome.Failed(OutboundPickupFailure.NETWORK_UNREACHABLE)
    }
    return when {
        statusCode == HTTP_NO_CONTENT -> OutboundPickupOutcome.NoWork
        statusCode == HTTP_OK -> {
            val work = outboundWorkFromFields(flatJsonStringObject(responseBody))
            if (work != null) {
                OutboundPickupOutcome.WorkAvailable(work)
            } else {
                OutboundPickupOutcome.Failed(OutboundPickupFailure.MALFORMED_RESPONSE)
            }
        }
        statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN ->
            OutboundPickupOutcome.Failed(OutboundPickupFailure.CREDENTIAL_REJECTED)
        statusCode in HTTP_CLIENT_ERROR_RANGE ->
            OutboundPickupOutcome.Failed(OutboundPickupFailure.ENDPOINT_REJECTED)
        else -> OutboundPickupOutcome.Failed(OutboundPickupFailure.MALFORMED_RESPONSE)
    }
}

/**
 * The work item a flat pickup answer names, or null when
 * the answer names no real send: a field is missing, the
 * source id or recipient is blank or carries whitespace,
 * the body is blank, or the queue time is not a
 * non-negative count of milliseconds in ASCII digits.
 */
private fun outboundWorkFromFields(fields: Map<String, String>?): OutboundWork? {
    if (fields == null) {
        return null
    }
    val sourceId = fields[OUTBOUND_PICKUP_SOURCE_FIELD] ?: return null
    if (sourceId.isBlank() || sourceId.any { it.isWhitespace() }) {
        return null
    }
    val recipient = fields[OUTBOUND_PICKUP_RECIPIENT_FIELD] ?: return null
    if (recipient.isBlank() || recipient.any { it.isWhitespace() }) {
        return null
    }
    val body = fields[OUTBOUND_PICKUP_BODY_FIELD] ?: return null
    if (body.isBlank()) {
        return null
    }
    val queuedAtMillis = nonNegativeMillis(fields[OUTBOUND_PICKUP_QUEUED_AT_FIELD]) ?: return null
    return OutboundWork(
        sourceId = sourceId,
        recipient = recipient,
        body = body,
        queuedAtMillis = queuedAtMillis
    )
}

/**
 * A non-negative count of milliseconds spelled in ASCII
 * digits, or null for any other spelling. A strict digit
 * check decides, in the seam's established shape: a broader
 * digit test would also accept non-ASCII digits, and a time
 * spelled in those is not a time this seam reads. A
 * spelling too large for a Long is no time either.
 */
private fun nonNegativeMillis(spelling: String?): Long? {
    if (spelling.isNullOrEmpty()) {
        return null
    }
    for (char in spelling) {
        if (char !in '0'..'9') {
            return null
        }
    }
    return spelling.toLongOrNull()
}

private const val HTTP_OK: Int = 200
private const val HTTP_NO_CONTENT: Int = 204
private const val HTTP_UNAUTHORIZED: Int = 401
private const val HTTP_FORBIDDEN: Int = 403
private val HTTP_CLIENT_ERROR_RANGE: IntRange = 400..499

/**
 * Renders a value as a JSON string literal, escaping exactly
 * the characters JSON requires: the quote, the backslash,
 * and the control characters. Everything else is carried
 * as-is — the value a reader recovers from the literal is
 * the value itself.
 */
private fun jsonString(value: String): String {
    val rendered = StringBuilder(value.length + 2)
    rendered.append('"')
    for (char in value) {
        when (char) {
            '"' -> rendered.append("\\\"")
            '\\' -> rendered.append("\\\\")
            '\b' -> rendered.append("\\b")
            '\u000C' -> rendered.append("\\f")
            '\n' -> rendered.append("\\n")
            '\r' -> rendered.append("\\r")
            '\t' -> rendered.append("\\t")
            else -> if (char.code < 0x20) {
                rendered.append("\\u")
                rendered.append(char.code.toString(16).padStart(4, '0'))
            } else {
                rendered.append(char)
            }
        }
    }
    rendered.append('"')
    return rendered.toString()
}

/**
 * Reads a flat JSON object whose values are all strings, or
 * returns null when the text is anything else. Strict on
 * purpose: keys and values must be JSON strings, a key may
 * appear only once, and nothing may follow the closing
 * brace — a response read loosely here would be trusted
 * with a real send two lines later.
 */
private fun flatJsonStringObject(raw: String?): Map<String, String>? {
    val reader = PickupFlatJsonReader(raw.orEmpty())
    val fields = reader.readObject() ?: return null
    return if (reader.isAtEnd()) fields else null
}

private class PickupFlatJsonReader(private val text: String) {
    private var index: Int = 0

    fun isAtEnd(): Boolean {
        skipWhitespace()
        return index >= text.length
    }

    fun readObject(): Map<String, String>? {
        skipWhitespace()
        if (!consume('{')) {
            return null
        }
        val fields = LinkedHashMap<String, String>()
        skipWhitespace()
        if (consume('}')) {
            return fields
        }
        while (true) {
            skipWhitespace()
            val key = readString() ?: return null
            skipWhitespace()
            if (!consume(':')) {
                return null
            }
            skipWhitespace()
            val value = readString() ?: return null
            if (fields.put(key, value) != null) {
                return null
            }
            skipWhitespace()
            if (consume('}')) {
                return fields
            }
            if (!consume(',')) {
                return null
            }
        }
    }

    private fun readString(): String? {
        if (!consume('"')) {
            return null
        }
        val value = StringBuilder()
        while (index < text.length) {
            val char = text[index]
            index++
            when (char) {
                '"' -> return value.toString()
                '\\' -> {
                    val escaped = readEscape() ?: return null
                    value.append(escaped)
                }
                else -> {
                    if (char.code < 0x20) {
                        return null
                    }
                    value.append(char)
                }
            }
        }
        return null
    }

    private fun readEscape(): Char? {
        if (index >= text.length) {
            return null
        }
        val escape = text[index]
        index++
        return when (escape) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicodeEscape()
            else -> null
        }
    }

    private fun readUnicodeEscape(): Char? {
        if (index + 4 > text.length) {
            return null
        }
        var code = 0
        repeat(4) {
            val digit = hexDigit(text[index])
            if (digit < 0) {
                return null
            }
            code = code * 16 + digit
            index++
        }
        return code.toChar()
    }

    /**
     * The value of one ASCII hex digit, or -1 for anything
     * else. A broader digit test would also accept
     * non-ASCII digits and letters, and an escape spelled
     * with those is not JSON — it must read as malformed,
     * never decode into a value.
     */
    private fun hexDigit(char: Char): Int {
        return when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> -1
        }
    }

    private fun consume(expected: Char): Boolean {
        if (index < text.length && text[index] == expected) {
            index++
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index] in " \t\n\r") {
            index++
        }
    }
}
