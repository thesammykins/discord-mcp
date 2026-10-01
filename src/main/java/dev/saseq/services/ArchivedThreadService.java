package dev.saseq.services;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.tool.annotation.ToolParam;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

@Service
public class ArchivedThreadService {
    private final DiscordRestClient rest;
    private final DiscordScope scope;
    public ArchivedThreadService(DiscordRestClient rest, DiscordScope scope) { this.rest = rest; this.scope = scope; }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArchiveCursor(String version, String parentChannelId, String mode, String value) {
        public ArchiveCursor {
            if (!"1".equals(version) || parentChannelId == null || value == null || value.contains("|")) throw new IllegalArgumentException("Invalid archive cursor");
            if ("joined_private".equals(mode)) DiscordScope.snowflake(value);
            else if ("public".equals(mode) || "private".equals(mode)) OffsetDateTime.parse(value);
            else throw new IllegalArgumentException("Invalid cursor mode");
        }
        public String encode() { return Base64.getUrlEncoder().withoutPadding().encodeToString((version + "|" + parentChannelId + "|" + mode + "|" + value).getBytes(StandardCharsets.UTF_8)); }
        public static ArchiveCursor decode(String encoded, String parent, String mode) {
            try {
                var p = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", -1);
                if (p.length != 4 || !parent.equals(p[1]) || !mode.equals(p[2])) throw new IllegalArgumentException("Cursor scope mismatch");
                return new ArchiveCursor(p[0], p[1], p[2], p[3]);
            } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid or mismatched archive cursor", e); }
        }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArchivedThread(String id, String parentChannelId, String guildId, String name, int type,
                                  @ToolParam(required = false) @McpToolParam(required = false) String ownerId, boolean archived, boolean locked, @ToolParam(required = false) @McpToolParam(required = false) String archiveTimestamp,
                                  int autoArchiveDuration, @ToolParam(required = false) @McpToolParam(required = false) String lastMessageId, List<String> tagIds) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArchivedPage(String schemaVersion, String parentChannelId, String mode, List<ArchivedThread> threads,
                               @ToolParam(required = false) @McpToolParam(required = false) String nextCursor, boolean hasMore, boolean complete) {}
    @McpTool(name = "list_archived_threads", description = "List one archived thread mode under a configured parent", generateOutputSchema = true)
    public ArchivedPage listArchivedThreads(@McpToolParam(description = "Configured parent ID") String parentChannelId,
            @McpToolParam(description = "public, private, or joined_private") String mode,
            @McpToolParam(description = "Page size 1-100", required = false) Integer limit,
            @McpToolParam(description = "Scope-bound archive cursor", required = false) String cursor) {
        scope.parent(parentChannelId);
        if (!List.of("public", "private", "joined_private").contains(mode == null ? "" : mode)) throw new IllegalArgumentException("Invalid archive mode");
        int n = limit == null ? 100 : limit;
        if (n < 1 || n > 100) throw new IllegalArgumentException("limit must be 1-100");
        var c = cursor == null ? null : ArchiveCursor.decode(cursor, parentChannelId, mode);
        var parent = rest.get("/channels/" + parentChannelId);
        scope.guild(parent.path("guild_id").asText());
        if (!parentChannelId.equals(parent.path("id").asText())) throw new IllegalStateException("Discord parent ID mismatch");
        int parentType = parent.path("type").asInt(-1);
        if (parentType != 0 && parentType != 5 && parentType != 15 && parentType != 16) throw new IllegalArgumentException("Not a thread parent");
        if (!"public".equals(mode) && parentType != 0) throw new IllegalArgumentException("Private archives require a text parent");
        String path = "/channels/" + parentChannelId + ("joined_private".equals(mode) ? "/users/@me/threads/archived/private" : "/threads/archived/" + mode);
        var root = rest.get(path + "?limit=" + n + (c == null ? "" : "&before=" + DiscordRestClient.encode(c.value())));
        if (!root.path("threads").isArray() || !root.path("has_more").isBoolean()) throw new IllegalStateException("Invalid Discord archive page");
        var threads = root.path("threads").valueStream().peek(t -> validateThread(t, parentChannelId)).map(this::map).toList();
        boolean more = root.path("has_more").asBoolean(false);
        if (more && threads.isEmpty()) throw new IllegalStateException("Discord returned has_more without a continuation");
        var last = threads.isEmpty() ? null : threads.get(threads.size() - 1);
        String next = !more ? null : new ArchiveCursor("1", parentChannelId, mode, "joined_private".equals(mode) ? last.id() : last.archiveTimestamp()).encode();
        return new ArchivedPage("1", parentChannelId, mode, threads, next, more, !more);
    }
    public JsonNode resolveThread(String threadId, String parent) {
        scope.parent(parent); DiscordScope.snowflake(threadId);
        var node = rest.get("/channels/" + threadId);
        if (!threadId.equals(node.path("id").asText())) throw new IllegalStateException("Discord thread ID mismatch");
        validateThread(node, parent); return node;
    }
    public void validateThread(JsonNode node, String parent) {
        if (!parent.equals(node.path("parent_id").asText())) throw new IllegalArgumentException("Thread belongs to another parent");
        scope.guild(node.path("guild_id").asText());
        int type = node.path("type").asInt(-1);
        if (type != 10 && type != 11 && type != 12) throw new IllegalArgumentException("Channel is not a thread");
    }
    public ArchivedThread setThreadArchived(String id, String parent, Boolean archived, String reason) {
        scope.parent(parent); DiscordScope.snowflake(id);
        if (archived == null) throw new IllegalArgumentException("archived is required");
        resolveThread(id, parent);
        var body = new ObjectMapper().createObjectNode().put("archived", archived);
        var updated = rest.patch("/channels/" + id, body, reason);
        if (!id.equals(updated.path("id").asText())) throw new IllegalStateException("Discord updated thread ID mismatch");
        validateThread(updated, parent);
        if (!updated.path("thread_metadata").has("archived")) throw new IllegalStateException("Discord response lacks authoritative archive state");
        return map(updated);
    }
    public void requireConfiguredParent(String parent) { scope.parent(parent); }
    public void requireConfiguredGuild(String guild) { scope.guild(guild); }
    public ArchivedThread map(JsonNode n) {
        var m = n.path("thread_metadata");
        return new ArchivedThread(n.path("id").asText(), n.path("parent_id").asText(), n.path("guild_id").asText(), n.path("name").asText(), n.path("type").asInt(0), n.path("owner_id").asText(null), m.path("archived").asBoolean(false), m.path("locked").asBoolean(false), m.path("archive_timestamp").asText(null), m.path("auto_archive_duration").asInt(0), n.path("last_message_id").asText(null), n.path("applied_tags").valueStream().map(JsonNode::asText).toList());
    }
}
