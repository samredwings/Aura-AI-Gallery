package ai.sakana.aura.gallery.presentation

import java.util.Locale

/** A deliberately small, deterministic command vocabulary for the on-device Aura agent. */
internal sealed interface LocalAgentCommand {
    data class OpenLibrary(val filter: LibraryFilter) : LocalAgentCommand
    data object OpenAlbums : LocalAgentCommand
    data object OpenExplore : LocalAgentCommand
    data object OpenPeople : LocalAgentCommand
    data object OpenAiSets : LocalAgentCommand
    data object OpenOptimize : LocalAgentCommand
    data object OpenSettings : LocalAgentCommand
    data object OpenRecentlyDeleted : LocalAgentCommand
    data object OpenLatestPhoto : LocalAgentCommand
    data object OpenLatestVideo : LocalAgentCommand
    data object RefreshLibrary : LocalAgentCommand
    data object AnalyzeLibrary : LocalAgentCommand
    data object ScanExactDuplicates : LocalAgentCommand
    data object ScanSimilarPhotos : LocalAgentCommand
    data object Help : LocalAgentCommand
    data object DestructiveActionBlocked : LocalAgentCommand
    data object Unsupported : LocalAgentCommand
}

internal object LocalAgentCommandParser {
    fun parse(input: String): LocalAgentCommand {
        val command = input.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        if (command.isBlank()) return LocalAgentCommand.Help

        return when {
            command in setOf("help", "what can you do", "show commands", "commands") -> LocalAgentCommand.Help
            command in setOf("recently deleted", "open recently deleted", "show recently deleted", "open trash", "show trash") ->
                LocalAgentCommand.OpenRecentlyDeleted
            containsAny(command, "delete", "erase", "remove", "wipe", "empty trash", "move to trash", "permanently delete") ->
                LocalAgentCommand.DestructiveActionBlocked
            command in setOf("favorites", "favourites", "show favorites", "show favourites", "show my favorites", "show my favourites", "open favorites", "open favourites", "my favorites", "my favourites", "favorite photos", "favourite photos") ->
                LocalAgentCommand.OpenLibrary(LibraryFilter.FAVORITES)
            command in setOf("videos", "show videos", "open videos", "video gallery", "my videos") ->
                LocalAgentCommand.OpenLibrary(LibraryFilter.VIDEOS)
            command in setOf("images", "show photos", "open photos", "photos only", "photo gallery") ->
                LocalAgentCommand.OpenLibrary(LibraryFilter.PHOTOS)
            command in setOf("photos", "open library", "open gallery", "show library", "all media", "all photos and videos", "home") ->
                LocalAgentCommand.OpenLibrary(LibraryFilter.ALL)
            command in setOf("albums", "open albums", "show albums", "go to albums") -> LocalAgentCommand.OpenAlbums
            command in setOf("explore", "open explore", "show explore") -> LocalAgentCommand.OpenExplore
            command in setOf("people", "open people", "show people", "face groups", "people groups") -> LocalAgentCommand.OpenPeople
            command in setOf("ai sets", "open ai sets", "show ai sets", "smart sets", "open smart sets", "scene groups") -> LocalAgentCommand.OpenAiSets
            command in setOf("optimize", "open optimize", "duplicates", "open duplicates", "duplicate review") -> LocalAgentCommand.OpenOptimize
            command in setOf("settings", "open settings", "app settings") -> LocalAgentCommand.OpenSettings
            command in setOf("latest photo", "open latest photo", "show latest photo", "most recent photo") -> LocalAgentCommand.OpenLatestPhoto
            command in setOf("latest video", "open latest video", "show latest video", "most recent video") -> LocalAgentCommand.OpenLatestVideo
            command in setOf("refresh", "refresh gallery", "refresh my gallery", "refresh library", "rescan library", "update gallery") -> LocalAgentCommand.RefreshLibrary
            command in setOf("analyze", "analyze library", "analyze my library", "run recognition", "start recognition", "find people and scenes") -> LocalAgentCommand.AnalyzeLibrary
            command in setOf("find exact duplicates", "scan exact duplicates", "exact duplicates", "scan for exact duplicates") -> LocalAgentCommand.ScanExactDuplicates
            command in setOf("find similar photos", "scan similar photos", "similar photos", "scan for similar photos", "find duplicate looking photos") -> LocalAgentCommand.ScanSimilarPhotos
            else -> LocalAgentCommand.Unsupported
        }
    }

    private fun containsAny(command: String, vararg phrases: String): Boolean =
        phrases.any { phrase ->
            val normalizedPhrase = phrase.lowercase(Locale.ROOT)
            command == normalizedPhrase || command.startsWith("$normalizedPhrase ") || command.endsWith(" $normalizedPhrase") || command.contains(" $normalizedPhrase ")
        }
}
