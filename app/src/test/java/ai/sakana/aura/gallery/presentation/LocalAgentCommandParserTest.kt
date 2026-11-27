package ai.sakana.aura.gallery.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAgentCommandParserTest {
    @Test
    fun recognizes_library_filters_and_navigation() {
        assertEquals(LocalAgentCommand.OpenLibrary(LibraryFilter.FAVORITES), LocalAgentCommandParser.parse("Show my favourites"))
        assertEquals(LocalAgentCommand.OpenLibrary(LibraryFilter.VIDEOS), LocalAgentCommandParser.parse("open videos"))
        assertEquals(LocalAgentCommand.OpenAlbums, LocalAgentCommandParser.parse("Go to albums"))
        assertEquals(LocalAgentCommand.OpenPeople, LocalAgentCommandParser.parse("show people"))
        assertEquals(LocalAgentCommand.OpenAiSets, LocalAgentCommandParser.parse("open smart sets"))
        assertEquals(LocalAgentCommand.OpenSettings, LocalAgentCommandParser.parse("settings"))
    }

    @Test
    fun recognizes_local_operations() {
        assertEquals(LocalAgentCommand.RefreshLibrary, LocalAgentCommandParser.parse("refresh my gallery"))
        assertEquals(LocalAgentCommand.AnalyzeLibrary, LocalAgentCommandParser.parse("analyze my library"))
        assertEquals(LocalAgentCommand.ScanExactDuplicates, LocalAgentCommandParser.parse("find exact duplicates"))
        assertEquals(LocalAgentCommand.ScanSimilarPhotos, LocalAgentCommandParser.parse("scan similar photos"))
        assertEquals(LocalAgentCommand.OpenRecentlyDeleted, LocalAgentCommandParser.parse("recently deleted"))
    }

    @Test
    fun recognizes_latest_media_and_normalizes_case_and_punctuation() {
        assertEquals(LocalAgentCommand.OpenLatestPhoto, LocalAgentCommandParser.parse("OPEN LATEST PHOTO!!"))
        assertEquals(LocalAgentCommand.OpenLatestVideo, LocalAgentCommandParser.parse("show latest video"))
    }

    @Test
    fun returns_help_for_blank_input_and_unknown_for_unrecognized_commands() {
        assertEquals(LocalAgentCommand.Help, LocalAgentCommandParser.parse("  "))
        assertEquals(LocalAgentCommand.Unsupported, LocalAgentCommandParser.parse("send my photos to a random server"))
    }

    @Test
    fun refuses_destructive_free_text_commands() {
        assertEquals(LocalAgentCommand.DestructiveActionBlocked, LocalAgentCommandParser.parse("delete all duplicates"))
        assertEquals(LocalAgentCommand.DestructiveActionBlocked, LocalAgentCommandParser.parse("erase my photos"))
    }
}
