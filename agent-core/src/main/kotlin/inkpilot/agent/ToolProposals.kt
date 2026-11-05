package inkpilot.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Model text can only create held proposals inside an independently issued host scope. */
data class ScopedProposalRequest(
    val name: String, val version: Int, val contactId: String, val threadId: String, val json: String,
)
enum class ProposalKind { APPOINTMENT, MEMORY_CORRECTION, DRAFT_REPLY }
sealed interface ProposalPayload {
    data class Appointment(val title: String, val staffId: Long, val startsAt: Long, val endsAt: Long,
        val zoneId: String, val room: String) : ProposalPayload
    data class MemoryCorrection(val body: String) : ProposalPayload
    data class DraftReply(val body: String) : ProposalPayload
}
sealed interface ProposalValidation {
    data class Held(val kind: ProposalKind, val payload: ProposalPayload, val canonicalJson: String) : ProposalValidation
    data class Rejected(val reason: String) : ProposalValidation
}

/** No arbitrary tool names, recipients, paths, URLs, or executable arguments. */
class ToolProposals(private val scope: DraftRequest) {
    fun parse(request: ScopedProposalRequest): ProposalValidation {
        if (request.version != 1 || request.name !in names) return ProposalValidation.Rejected("Unsupported proposal tool")
        if (request.contactId != scope.contactId || request.threadId != scope.threadId)
            return ProposalValidation.Rejected("Proposal scope changed")
        if (request.json.length !in 2..8192) return ProposalValidation.Rejected("Invalid proposal size")
        val obj = try { Json.parseToJsonElement(request.json) as? JsonObject }
            catch (_: Exception) { null } ?: return ProposalValidation.Rejected("Invalid JSON")
        if (duplicateKeys(request.json)) return ProposalValidation.Rejected("Duplicate or escaped field")
        val required = when (request.name) {
            "propose_appointment" -> setOf("title", "staffId", "startsAt", "endsAt", "zoneId", "room")
            else -> setOf("body")
        }
        if (obj.keys != required || obj.values.any { it !is JsonPrimitive })
            return ProposalValidation.Rejected("Unexpected proposal fields")
        val payload = try {
            when (request.name) {
                "propose_appointment" -> ProposalPayload.Appointment(
                    obj.text("title", 160), obj.integer("staffId"), obj.integer("startsAt"),
                    obj.integer("endsAt"), obj.text("zoneId", 80), obj.text("room", 80, allowEmpty = true))
                "propose_memory_correction" -> ProposalPayload.MemoryCorrection(obj.text("body", 8000))
                else -> ProposalPayload.DraftReply(obj.text("body", 800))
            }
        } catch (_: Exception) { return ProposalValidation.Rejected("Invalid proposal values") }
        if (payload is ProposalPayload.Appointment && (payload.staffId <= 0 || payload.startsAt < 0 ||
                payload.endsAt <= payload.startsAt || payload.endsAt - payload.startsAt !in 60_000L..86_400_000L ||
                payload.startsAt % 60_000L != 0L || payload.endsAt % 60_000L != 0L))
            return ProposalValidation.Rejected("Invalid appointment interval")
        val kind = when (payload) {
            is ProposalPayload.Appointment -> ProposalKind.APPOINTMENT
            is ProposalPayload.MemoryCorrection -> ProposalKind.MEMORY_CORRECTION
            is ProposalPayload.DraftReply -> ProposalKind.DRAFT_REPLY
        }
        return ProposalValidation.Held(kind, payload, canonical(payload))
    }

    private fun canonical(payload: ProposalPayload): String = buildJsonObject {
        when (payload) {
            is ProposalPayload.Appointment -> {
                put("title", JsonPrimitive(payload.title)); put("staffId", JsonPrimitive(payload.staffId))
                put("startsAt", JsonPrimitive(payload.startsAt)); put("endsAt", JsonPrimitive(payload.endsAt))
                put("zoneId", JsonPrimitive(payload.zoneId)); put("room", JsonPrimitive(payload.room))
            }
            is ProposalPayload.MemoryCorrection -> put("body", JsonPrimitive(payload.body))
            is ProposalPayload.DraftReply -> put("body", JsonPrimitive(payload.body))
        }
    }.toString()

    private fun JsonObject.text(key: String, max: Int, allowEmpty: Boolean = false): String {
        val primitive = getValue(key).jsonPrimitive
        require(primitive.isString)
        val text = primitive.content.trim()
        require(text.length <= max && (allowEmpty || text.isNotEmpty()) && '\u0000' !in text)
        return text
    }
    private fun JsonObject.integer(key: String): Long {
        val primitive = getValue(key).jsonPrimitive
        require(!primitive.isString)
        return requireNotNull(primitive.longOrNull)
    }

    /** Flat values only; reject duplicate keys even when a JSON map parser would overwrite one. */
    private fun duplicateKeys(raw: String): Boolean {
        val seen = mutableSetOf<String>()
        var index = 0
        while (index < raw.length) {
            if (raw[index] != '"') { index++; continue }
            val start = ++index
            var escaped = false
            while (index < raw.length) {
                if (raw[index] == '\\') { escaped = true; index += 2; continue }
                if (raw[index] == '"') break
                index++
            }
            if (index >= raw.length) return true
            val field = raw.substring(start, index)
            index++
            while (index < raw.length && raw[index].isWhitespace()) index++
            if (index < raw.length && raw[index] == ':') {
                if (escaped || !seen.add(field)) return true
            }
        }
        return false
    }
    companion object { val names = setOf("propose_appointment", "propose_memory_correction", "draft_reply") }
}
