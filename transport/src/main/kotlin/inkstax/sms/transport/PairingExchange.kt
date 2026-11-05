package inkstax.sms.transport

/**
 * Pairing exchange and device-credential definitions for the bridge
 * seam.
 *
 * Pairing (see Pairing.kt) ends with one exchange: the companion
 * presents the scanned single-use token once, and the brain returns
 * the device credential this phone will use from then on. These pure
 * definitions are the one vocabulary every binding uses for that
 * moment, so what counts as a credential, when an exchange counts
 * as having succeeded, and when a stored credential counts as this
 * device's cannot drift between bindings. There is no network call,
 * no scanner, and no storage here — a binding performs the exchange,
 * supplies the returned values and any failure, persists what these
 * definitions accept, and acts on the states.
 *
 * The credential is not the token. The token is a short-lived,
 * single-use offer that is burned by the exchange; the credential
 * is the long-lived per-device secret the exchange mints. A
 * returned value that equals the token is therefore never a
 * credential — accepting it would turn a ten-minute offer into a
 * permanent key and defeat the token's single-use rule.
 *
 * A credential belongs to exactly one endpoint and one device. A
 * credential minted for a different endpoint than the offer was
 * scanned from is not this pairing's credential, however well
 * formed it looks, because the phone scanned one brain's QR and
 * must not end up paired to another.
 *
 * A stored credential counts only on the device that exchanged for
 * it. Credential fields live in storage Android backup can restore
 * onto a different phone; the on-device token (kept in storage
 * backup never carries) names the device id, so a restored
 * credential without its matching token reads back as no
 * credential and the phone pairs afresh, instead of a backup
 * silently moving one phone's identity onto another.
 */

/**
 * The per-device credential a successful pairing exchange mints.
 * The secret is a credential — it is never logged, never shown
 * back to the user, and never sent anywhere except the endpoint
 * it was minted for.
 */
data class DeviceCredential(
    val endpointBase: String,
    val deviceId: String,
    val secret: String
) {
    override fun toString() = "DeviceCredential(endpointBase=$endpointBase, deviceId=$deviceId, secret=<redacted>)"
}

/**
 * Whether a candidate endpoint base is one a credential may belong
 * to: an HTTPS URL with a host and nothing else — no path, query,
 * fragment, or embedded user info. Endpoint bases in this seam are
 * origins, as Pairing.kt returns them from a scanned offer. Plain
 * HTTP is admitted for a loopback authority only: the local
 * binding pairs a companion and a core on the same device, where
 * the traffic never leaves the device and no TLS endpoint exists
 * to name — an HTTP name for anywhere else would put the
 * credential on a network path unprotected, so it stays refused.
 * The core's endpoint rule admits exactly the same shapes, so a
 * credential's endpoint means the same thing on both sides of
 * the seam.
 */
private fun isEndpointBase(candidate: String?): Boolean {
    val value = candidate.orEmpty()
    if (value.isEmpty() || value.any { it.isWhitespace() }) {
        return false
    }
    val uri = try {
        java.net.URI(value)
    } catch (e: java.net.URISyntaxException) {
        return false
    }
    if (uri.host.isNullOrBlank() ||
        uri.userInfo != null ||
        uri.rawQuery != null ||
        uri.rawFragment != null ||
        !(uri.path.isNullOrEmpty() || uri.path == "/")
    ) {
        return false
    }
    return if (uri.scheme.equals("https", ignoreCase = true)) {
        true
    } else if (uri.scheme.equals("http", ignoreCase = true)) {
        isLoopbackAuthority(uri.rawAuthority.orEmpty())
    } else {
        false
    }
}

/**
 * Whether an authority names this device itself: a loopback host
 * (127.0.0.1 or localhost; aligned with Android cleartext policy) with an optional all-digit
 * port. A name that merely starts like one
 * (localhost.evil.example) names somewhere else, and a port that
 * is not a port makes the whole authority another name than the
 * one the offer scanned, so both are refused whole.
 */
internal fun isLoopbackAuthority(authority: String): Boolean {
    val host: String
    val port: String?
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0) {
            return false
        }
        host = authority.substring(1, close)
        val tail = authority.substring(close + 1)
        port = when {
            tail.isEmpty() -> null
            tail.startsWith(":") -> tail.substring(1)
            else -> return false
        }
    } else {
        val split = authority.indexOf(':')
        if (split >= 0) {
            host = authority.substring(0, split)
            port = authority.substring(split + 1)
        } else {
            host = authority
            port = null
        }
    }
    if (port != null && (port.isEmpty() || !port.all { it in '0'..'9' })) {
        return false
    }
    return host.equals("localhost", ignoreCase = true) || host == "127.0.0.1"
}

/**
 * Whether a credential field is a usable opaque value: present and
 * free of whitespace, exactly as given. Credential values are
 * exact — no path in this file trims them, because trimming would
 * silently turn one key into a different key instead of rejecting
 * a malformed value, and a value carrying spaces must fail every
 * check rather than pass as its trimmed spelling.
 */
private fun isOpaqueValue(candidate: String?): Boolean {
    val value = candidate.orEmpty()
    return value.isNotEmpty() && value.none { it.isWhitespace() }
}

/**
 * The credential a pairing exchange returned, or null when the
 * exchange did not mint a usable credential for this offer. Every
 * condition must hold: the returned endpoint is exactly the
 * offer's endpoint, the device id and secret are present opaque
 * values, and the secret is not the pairing token itself. Any
 * other return — a blank secret, a credential for another
 * endpoint, the token handed back as the secret — reads back as
 * no credential, so a malformed or mismatched exchange response
 * can never leave the phone half-paired with a key that does not
 * work or should not be trusted.
 */
fun exchangedCredentialFor(
    offer: PairingOffer,
    returnedEndpointBase: String?,
    returnedDeviceId: String?,
    returnedSecret: String?
): DeviceCredential? {
    val endpoint = returnedEndpointBase.orEmpty()
    val deviceId = returnedDeviceId.orEmpty()
    val secret = returnedSecret.orEmpty()
    if (endpoint != offer.endpointBase || !isEndpointBase(endpoint)) {
        return null
    }
    if (!isOpaqueValue(deviceId) || !isOpaqueValue(secret)) {
        return null
    }
    if (secret == offer.token) {
        return null
    }
    return DeviceCredential(endpointBase = endpoint, deviceId = deviceId, secret = secret)
}

/** Why a pairing exchange did not mint a credential. */
enum class PairingExchangeFailure {
    /** The token's lifetime ran out before the exchange completed. */
    TOKEN_EXPIRED,

    /** The token was already exchanged once; it never works twice. */
    TOKEN_ALREADY_USED,

    /** The endpoint refused the exchange for another reason. */
    ENDPOINT_REJECTED,

    /** The endpoint could not be reached at all. */
    NETWORK_UNREACHABLE,

    /** The endpoint answered, but not with a usable credential. */
    MALFORMED_RESPONSE
}

/**
 * The pairing event an exchange failure maps to. A spent or lapsed
 * token ends the attempt — the recovery is a fresh scan, as
 * Pairing.kt's state table provides — so those failures map to
 * the token lapsing. Every other failure leaves the attempt open
 * inside the token's lifetime, so a phone that briefly loses its
 * network can retry the same offer instead of making the user
 * fetch a new QR for a transient fault.
 */
fun pairingEventForExchangeFailure(failure: PairingExchangeFailure): PairingEvent {
    return when (failure) {
        PairingExchangeFailure.TOKEN_EXPIRED,
        PairingExchangeFailure.TOKEN_ALREADY_USED -> PairingEvent.TOKEN_LAPSED
        PairingExchangeFailure.ENDPOINT_REJECTED,
        PairingExchangeFailure.NETWORK_UNREACHABLE,
        PairingExchangeFailure.MALFORMED_RESPONSE -> PairingEvent.EXCHANGE_FAILED
    }
}

/**
 * Reads a stored credential back as this device's credential, or
 * null when it does not count. The stored fields must form a
 * usable credential (a usable endpoint origin and opaque device
 * id and secret), and the on-device token must name the same
 * device id — a credential restored from backup without its token,
 * or with another device's token, reads back as no credential and
 * the phone pairs afresh. A corrupt stored value likewise reads
 * back as no credential rather than a credential that will fail
 * against the brain on every call.
 */
fun deviceCredentialFromStored(
    storedEndpointBase: String?,
    storedDeviceId: String?,
    storedSecret: String?,
    onDeviceTokenDeviceId: String?
): DeviceCredential? {
    val endpoint = storedEndpointBase.orEmpty()
    val deviceId = storedDeviceId.orEmpty()
    val secret = storedSecret.orEmpty()
    if (!isEndpointBase(endpoint) || !isOpaqueValue(deviceId) || !isOpaqueValue(secret)) {
        return null
    }
    if (onDeviceTokenDeviceId.orEmpty() != deviceId) {
        return null
    }
    return DeviceCredential(endpointBase = endpoint, deviceId = deviceId, secret = secret)
}

/**
 * Whether a credential may be used for an endpoint: it exists and
 * it was minted for exactly that endpoint. A binding checks this
 * before every use, so a re-pointed or restored configuration can
 * never send one brain's credential to another brain.
 */
fun isCredentialUsableFor(credential: DeviceCredential?, endpointBase: String?): Boolean {
    if (credential == null) {
        return false
    }
    return credential.endpointBase == endpointBase.orEmpty() &&
        isEndpointBase(credential.endpointBase) &&
        isOpaqueValue(credential.deviceId) &&
        isOpaqueValue(credential.secret)
}

/**
 * The next pairing state when the user disconnects this device.
 * Disconnect is the only way out of the terminal paired state:
 * it returns the phone to unpaired, and the stored credential is
 * discarded with it — a disconnected phone keeps no working key.
 * Disconnecting a phone that is not paired changes nothing.
 */
fun pairingStateAfterDisconnect(current: PairingState): PairingState {
    return if (current == PairingState.PAIRED) {
        PairingState.UNPAIRED
    } else {
        current
    }
}
