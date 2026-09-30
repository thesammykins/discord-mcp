package dev.saseq.services;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/** REST-backed archived thread contract. JDA 6.4.1 has no public uncached thread resolver. */
@Service
public class ArchivedThreadService {
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final String token;
    private final String apiBase;

    public ArchivedThreadService(@Value("${DISCORD_TOKEN:}") String token,
                                 @Value("${DISCORD_API_BASE:https://discord.com/api/v10}") String apiBase) {
        this.token = token;
        this.apiBase = apiBase;
    }

    public record ArchiveCursor(String version, String parentChannelId, String mode, String value) {
        public ArchiveCursor {
            if (version == null || parentChannelId == null || mode == null || value == null || value.contains("|")) {
                throw new IllegalArgumentException("archive cursor fields are invalid");
            }
        }
        public String encode() {
            String raw = version + "|" + parentChannelId + "|" + mode + "|" + value;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
        public static ArchiveCursor decode(String encoded, String parent, String mode) {
            try {
                String[] p = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", 4);
                if (p.length != 4 || !"1".equals(p[0]) || !parent.equals(p[1]) || !mode.equals(p[2])) throw new IllegalArgumentException("cursor scope mismatch");
                return new ArchiveCursor(p[0], p[1], p[2], p[3]);
            } catch (IllegalArgumentException e) { throw new IllegalArgumentException("invalid or mismatched archive cursor", e); }
        }
    }
    public record ArchivedThread(String id, String parentChannelId, String guildId, String name, boolean archived, boolean locked, String archiveTimestamp) {}
    public record ArchivedPage(String schemaVersion, String parentChannelId, String mode, List<ArchivedThread> threads, String nextCursor, boolean hasMore, boolean complete) {}

    @McpTool(name = "list_archived_threads", description = "List archived public, private, or joined-private threads under an authorized parent", generateOutputSchema = true)
    public ArchivedPage listArchivedThreads(
            @McpToolParam(description = "Parent channel ID") String parentChannelId,
            @McpToolParam(description = "public, private, or joined_private") String mode,
            @McpToolParam(description = "Maximum results (1-100)", required = false) Integer limit,
            @McpToolParam(description = "Opaque typed cursor", required = false) String cursor) {
        if (parentChannelId == null || parentChannelId.isBlank()) throw new IllegalArgumentException("parentChannelId is required");
        if (!List.of("public", "private", "joined_private").contains(mode)) throw new IllegalArgumentException("unsupported archive mode");
        int n = limit == null ? 100 : limit;
        if (n < 1 || n > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        ArchiveCursor c = cursor == null || cursor.isBlank() ? null : ArchiveCursor.decode(cursor, parentChannelId, mode);
        String path = mode.equals("joined_private")
                ? "/channels/" + parentChannelId + "/users/@me/threads/archived/private"
                : "/channels/" + parentChannelId + "/threads/archived/" + mode;
        String query = "?limit=" + n + (c == null ? "" : "&before=" + c.value());
        JsonNode root = request(path + query);
        List<ArchivedThread> threads = root.path("threads").valueStream().map(this::map).filter(t -> parentChannelId.equals(t.parentChannelId())).toList();
        boolean more = root.path("has_more").asBoolean(false);
        String next = more && !threads.isEmpty() ? new ArchiveCursor("1", parentChannelId, mode,
                mode.equals("public") || mode.equals("private") ? threads.get(threads.size() - 1).archiveTimestamp() : threads.get(threads.size() - 1).id()).encode() : null;
        return new ArchivedPage("1", parentChannelId, mode, threads, next, more, true);
    }

    /** Resolves an uncached thread and rejects a thread returned for another parent. */
    public JsonNode resolveThread(String threadId, String expectedParentId) {
        JsonNode node = request("/channels/" + threadId);
        if (!expectedParentId.equals(node.path("parent_id").asText())) throw new IllegalArgumentException("thread parent does not match requested scope");
        return node;
    }

    private ArchivedThread map(JsonNode n) { return new ArchivedThread(n.path("id").asText(), n.path("parent_id").asText(), n.path("guild_id").asText(null), n.path("name").asText(), n.path("thread_metadata").path("archived").asBoolean(), n.path("thread_metadata").path("locked").asBoolean(), n.path("thread_metadata").path("archive_timestamp").asText(null)); }
    private JsonNode request(String path) {
        if (token == null || token.isBlank()) throw new IllegalStateException("DISCORD_TOKEN is required for uncached archive resolution");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path)).header("Authorization", "Bot " + token).header("Accept", "application/json").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Discord archive request failed with HTTP " + response.statusCode());
            return json.readTree(response.body());
        } catch (Exception e) { throw new IllegalStateException("Discord archive request failed", e); }
    }
}
