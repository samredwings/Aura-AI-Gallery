package ai.sakana.aura.gallery.domain.agent

import ai.sakana.aura.gallery.data.deduplication.HammingDistance
import java.util.Locale

/** Minimal metadata used to suggest continuation shots without decoding private images. */
data class ShotCandidate(
    val id: String,
    val contentUri: String,
    val capturedAtEpochMillis: Long,
    val width: Int,
    val height: Int,
    val perceptualHash: Long?,
)

data class ShotSequence(val contentUris: List<String>, val startEpochMillis: Long, val endEpochMillis: Long)

/** Conservative sequence detection: close capture times, matching aspect, and similar dHash. */
object ShotSequenceGrouper {
    fun group(
        candidates: List<ShotCandidate>,
        maxGapMillis: Long = DEFAULT_MAX_GAP_MILLIS,
        maxAspectRatioDelta: Float = DEFAULT_MAX_ASPECT_DELTA,
        maxHammingDistance: Int = HammingDistance.SIMILAR_MAX,
    ): List<ShotSequence> {
        require(maxGapMillis > 0L)
        require(maxAspectRatioDelta >= 0f)
        require(maxHammingDistance in 0..64)
        val ordered = candidates
            .filter { it.contentUri.isNotBlank() && it.width > 0 && it.height > 0 }
            .distinctBy(ShotCandidate::contentUri)
            .sortedWith(compareBy(ShotCandidate::capturedAtEpochMillis, ShotCandidate::contentUri))
        if (ordered.size < 2) return emptyList()

        val groups = mutableListOf<MutableList<ShotCandidate>>()
        var current = mutableListOf<ShotCandidate>()
        ordered.forEach { item ->
            val previous = current.lastOrNull()
            val continues = previous != null && isContinuation(previous, item, maxGapMillis, maxAspectRatioDelta, maxHammingDistance)
            if (continues) {
                current += item
            } else {
                if (current.size >= 2) groups += current
                current = mutableListOf(item)
            }
        }
        if (current.size >= 2) groups += current

        return groups.map { group ->
            ShotSequence(
                contentUris = group.map(ShotCandidate::contentUri),
                startEpochMillis = group.first().capturedAtEpochMillis,
                endEpochMillis = group.last().capturedAtEpochMillis,
            )
        }
    }

    private fun isContinuation(
        previous: ShotCandidate,
        next: ShotCandidate,
        maxGapMillis: Long,
        maxAspectRatioDelta: Float,
        maxHammingDistance: Int,
    ): Boolean {
        val gap = next.capturedAtEpochMillis - previous.capturedAtEpochMillis
        if (gap < 0L || gap > maxGapMillis) return false
        val previousRatio = previous.width.toFloat() / previous.height
        val nextRatio = next.width.toFloat() / next.height
        val ratioDelta = kotlin.math.abs(previousRatio - nextRatio) / maxOf(previousRatio, nextRatio)
        if (ratioDelta > maxAspectRatioDelta) return false
        val leftHash = previous.perceptualHash ?: return false
        val rightHash = next.perceptualHash ?: return false
        return HammingDistance.distance(leftHash, rightHash) <= maxHammingDistance
    }

    const val DEFAULT_MAX_GAP_MILLIS = 120_000L
    const val DEFAULT_MAX_ASPECT_DELTA = 0.12f
}

/** Agent actions can only use this route when the user's actual request names a trash action. */
object AgentActionPolicy {
    fun shouldMoveToTrash(userRequest: String): Boolean = authorizeTrash(userRequest)

    fun authorizeTrash(userRequest: String): Boolean {
        val normalized = userRequest.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        if (normalized.isBlank()) return false
        if (containsPhrase(normalized, "permanently delete", "delete forever", "erase permanently", "wipe permanently")) return false
        return containsPhrase(
            normalized,
            "delete", "trash", "recycle bin", "recently deleted", "move to trash", "move to recycle bin",
            "move to recently deleted", "remove from gallery",
        )
    }

    private fun containsPhrase(value: String, vararg phrases: String): Boolean =
        phrases.any { phrase -> value == phrase || value.startsWith("$phrase ") || value.endsWith(" $phrase") || value.contains(" $phrase ") }
}

/**
 * Keeps local identifiers and gallery metadata out of model-requested external searches.
 * The caller must still obtain explicit user confirmation before opening a browser.
 */
object WebSearchPolicy {
    fun validationError(query: String, privateMediaValues: Collection<String> = emptyList()): String? {
        val value = query.trim()
        if (value.isBlank()) return "Provide a public topic to search on the web."
        if (value.length > MAX_QUERY_LENGTH) return "Web searches are limited to 180 characters."
        if (value.any { it.code < 0x20 }) return "Web search queries cannot contain control characters or line breaks."

        val sensitivePatterns = listOf(
            Regex("""(?i)\b(?:content|file|android\.resource)://"""),
            Regex("""(?i)(?:^|\s)/(?:storage|data/user|data/data|sdcard|mnt)/[^\s]+"""),
            Regex("""(?i)\b[A-Z]:\\[^\s]+"""),
            Regex("""(?i)\b[^\s/\\]+\.(?:jpe?g|png|webp|gif|bmp|heic|heif|dng|raw|mp4|mov|mkv|webm|3gp)\b"""),
        )
        if (sensitivePatterns.any { it.containsMatchIn(value) }) {
            return "This query contains a local path, media URI, or photo/video filename. Search only for a public topic."
        }

        val normalized = value.lowercase(Locale.ROOT)
        val includesPrivateMedia = privateMediaValues.asSequence()
            .map(String::trim)
            .filter { it.length >= MIN_PRIVATE_VALUE_LENGTH }
            .any { normalized.contains(it.lowercase(Locale.ROOT)) }
        if (includesPrivateMedia) {
            return "This query contains information from your local gallery. Remove the private metadata and try again."
        }
        return null
    }

    const val MAX_QUERY_LENGTH = 180
    private const val MIN_PRIVATE_VALUE_LENGTH = 5
}

object MediaVersionRules {
    private val supportedOperations = setOf("AI_ERASE", "CHAT_EDIT", "TONE_EDIT", "TEXT_ERASE", "WATERMARK_ERASE")

    fun validate(
        sourceContentUri: String,
        versionContentUri: String,
        operation: String,
        prompt: String,
        modelId: String,
    ): Boolean = sourceContentUri.isNotBlank() &&
        versionContentUri.isNotBlank() &&
        sourceContentUri != versionContentUri &&
        operation in supportedOperations &&
        prompt.length <= MAX_PROMPT_LENGTH &&
        modelId.isNotBlank()

    const val MAX_PROMPT_LENGTH = 2_000
}

data class ParsedAgentSkill(val id: String, val name: String, val description: String, val instructions: String)

/**
 * Imports Markdown as prompt guidance only. It strips HTML, script/style blocks and fenced code;
 * imported content is never evaluated or allowed to register executable tools.
 */
object AgentSkillParser {
    fun parse(fileName: String, markdown: String): ParsedAgentSkill {
        require(markdown.isNotBlank()) { "The skill file is empty." }
        require(markdown.length <= MAX_SKILL_LENGTH) { "Skill files must be $MAX_SKILL_LENGTH characters or fewer." }
        val cleaned = markdown
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
            .replace(Regex("(?s)```.*?```"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace(Regex("[\\t ]+"), " ")
            .trim()
        val name = cleaned.lineSequence()
            .firstOrNull { it.trim().startsWith("#") }
            ?.trim()?.trimStart('#')?.trim()
            ?.takeIf(String::isNotBlank)
            ?: fileName.substringBeforeLast('.').replace('-', ' ').replace('_', ' ').trim().ifBlank { "Imported skill" }
        val description = cleaned.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?.take(MAX_DESCRIPTION_LENGTH)
            .orEmpty()
        require(cleaned.isNotBlank()) { "No readable Markdown instructions were found." }
        val id = fileName.substringBeforeLast('.')
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "imported-skill" }
            .take(MAX_ID_LENGTH)
        return ParsedAgentSkill(id, name.take(MAX_NAME_LENGTH), description, cleaned.take(MAX_SKILL_LENGTH))
    }

    const val MAX_SKILL_LENGTH = 20_000
    const val MAX_NAME_LENGTH = 80
    const val MAX_DESCRIPTION_LENGTH = 240
    const val MAX_ID_LENGTH = 60
}
