package ai.sakana.aura.gallery.domain.agent

import com.google.gson.JsonParser

/** Structured responses from the local LLM are parsed as data; no generated code is evaluated. */
data class AgentAction(val tool: String, val arguments: Map<String, String>)
data class AgentResponse(val message: String, val actions: List<AgentAction>)

object AgentResponseParser {
    private val allowedTools = setOf(
        "navigate", "search_media", "open_latest_media", "refresh_library", "analyze_library",
        "scan_duplicates", "open_ai_erase", "open_tone_editor", "find_continuation_shots", "save_continuation_set",
        "move_media_to_trash", "restore_from_trash", "open_settings", "search_web",
    )

    fun parse(raw: String): AgentResponse {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return AgentResponse(cleaned.take(MAX_MESSAGE_LENGTH), emptyList())
        return runCatching {
            val root = JsonParser.parseString(cleaned.substring(start, end + 1)).asJsonObject
            val message = root.get("message")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.take(MAX_MESSAGE_LENGTH).orEmpty()
            val actions = mutableListOf<AgentAction>()
            val source = root.get("actions")?.takeIf { it.isJsonArray }?.asJsonArray
            source?.take(MAX_ACTIONS)?.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val item = element.asJsonObject
                val tool = item.get("tool")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    ?: return@forEach
                if (tool !in allowedTools) return@forEach
                val args = item.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject
                val values = LinkedHashMap<String, String>()
                args?.entrySet()?.forEach { (key, value) ->
                    if (values.size >= MAX_ARGUMENTS) return@forEach
                    if (key.length !in 1..MAX_ARGUMENT_LENGTH || !key.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*"))) return@forEach
                    if (!value.isJsonPrimitive) return@forEach
                    val primitive = value.asJsonPrimitive
                    if (primitive.isString) {
                        if (primitive.asString.length <= MAX_VALUE_LENGTH) values[key] = primitive.asString
                    } else if (primitive.isNumber || primitive.isBoolean) {
                        values[key] = primitive.toString()
                    }
                }
                actions += AgentAction(tool, values)
            }
            AgentResponse(message.ifBlank { "Aura completed the request." }, actions)
        }.getOrElse { AgentResponse(cleaned.take(MAX_MESSAGE_LENGTH), emptyList()) }
    }

    const val MAX_MESSAGE_LENGTH = 1_200
    const val MAX_ACTIONS = 4
    private const val MAX_ARGUMENTS = 8
    private const val MAX_ARGUMENT_LENGTH = 48
    private const val MAX_VALUE_LENGTH = 240
}
