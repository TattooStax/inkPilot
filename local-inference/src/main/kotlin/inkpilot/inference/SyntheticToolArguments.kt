package inkpilot.inference

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Strict JSON, a single string field, no duplicate/unknown keys or trailing document. */
internal object SyntheticToolArguments {
    fun contactId(arguments: String): String? = runCatching {
        JsonReader(StringReader(arguments)).use { reader ->
            reader.strictness = Strictness.STRICT
            reader.beginObject()
            require(reader.hasNext() && reader.nextName() == "contactId")
            require(reader.peek() == JsonToken.STRING)
            val value = reader.nextString()
            require(!reader.hasNext())
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
            require(value == "synthetic-a" || value == "synthetic-b")
            value
        }
    }.getOrNull()
}
