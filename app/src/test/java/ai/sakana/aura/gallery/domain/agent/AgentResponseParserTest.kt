package ai.sakana.aura.gallery.domain.agent

import org.junit.Assert.*
import org.junit.Test

class AgentResponseParserTest {
    @Test
    fun parses_an_allowlisted_navigation_action_and_message() {
        val response = AgentResponseParser.parse(
            """{"message":"Opening your favorites.","actions":[{"tool":"navigate","arguments":{"screen":"favorites"}}]}""",
        )
        assertEquals("Opening your favorites.", response.message)
        assertEquals("navigate", response.actions.single().tool)
        assertEquals("favorites", response.actions.single().arguments["screen"])
    }

    @Test
    fun rejects_unknown_tools_and_invalid_json() {
        assertTrue(AgentResponseParser.parse("""{"message":"No","actions":[{"tool":"run_shell","arguments":{"cmd":"rm -rf /"}}]}""").actions.isEmpty())
        assertFalse(AgentResponseParser.parse("not json").message.isBlank())
    }

    @Test
    fun bounds_actions_and_arguments() {
        val many = (1..8).joinToString(",") { """{"tool":"navigate","arguments":{"screen":"photos"}}""" }
        val response = AgentResponseParser.parse("""{"message":"OK","actions":[$many]}""")
        assertEquals(AgentResponseParser.MAX_ACTIONS, response.actions.size)
        assertTrue(AgentResponseParser.parse("""{"message":"OK","actions":[{"tool":"navigate","arguments":{"screen":"${"x".repeat(300)}}}]}""").actions.isEmpty())
    }

    @Test
    fun allows_explicit_trash_tools_only_with_actual_trash_intent() {
        assertTrue(AgentActionPolicy.authorizeTrash("Move my selected photos to Recently Deleted"))
        assertFalse(AgentActionPolicy.authorizeTrash("Remove the watermark and text from this photo"))
        assertFalse(AgentActionPolicy.authorizeTrash("Permanently delete every original"))
    }
}
