package dev.saseq;

import dev.saseq.services.ArchivedThreadService;
import dev.saseq.services.StructuredHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ContractTest {
    @Test
    void archiveCursorsAreTypedAndScopeBound() {
        var cursor = new ArchivedThreadService.ArchiveCursor("1", "parent", "public", "2026-09-30T00:00:00Z");
        assertEquals(cursor, ArchivedThreadService.ArchiveCursor.decode(cursor.encode(), "parent", "public"));
        assertThrows(IllegalArgumentException.class, () -> ArchivedThreadService.ArchiveCursor.decode(cursor.encode(), "other", "public"));
        assertThrows(IllegalArgumentException.class, () -> ArchivedThreadService.ArchiveCursor.decode(cursor.encode(), "parent", "joined_private"));
        assertThrows(IllegalArgumentException.class, () -> ArchivedThreadService.ArchiveCursor.decode(Base64.getUrlEncoder().encodeToString("1|parent|public|x|y".getBytes()), "parent", "public"));
    }

    @Test
    void structuredContractsAdvertiseNativeOutputSchemas() throws Exception {
        var history = StructuredHistoryService.class.getDeclaredMethod("readStructuredMessages", String.class, Integer.class, String.class, String.class, String.class);
        var reactions = StructuredHistoryService.class.getDeclaredMethod("listReactionUsers", String.class, String.class, String.class, String.class, Integer.class, String.class);
        assertTrue(history.isAnnotationPresent(McpTool.class));
        assertTrue(history.getAnnotation(McpTool.class).generateOutputSchema());
        assertTrue(reactions.getAnnotation(McpTool.class).generateOutputSchema());
        assertEquals("1", new StructuredHistoryService.HistoryPage("1", "c", java.util.List.of(), "oldest_first", null, null, null, null, false, java.time.OffsetDateTime.now()).schemaVersion());
    }

    @Test
    void reactionContractPreservesIdentityAndCursor() {
        var user = new StructuredHistoryService.ReactionUser("42", "same-name", true);
        var page = new StructuredHistoryService.ReactionUsersPage("1", "c", "m", "👍", "NORMAL", java.util.List.of(user), "42", true);
        assertEquals("42", page.users().get(0).id());
        assertTrue(page.users().get(0).bot());
        assertEquals("42", page.nextUserId());
    }
}
