package dev.saseq.services;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.tool.annotation.ToolParam;

import net.dv8tion.jda.api.JDA;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/** Public REST DTOs support cold archived channels without JDA internal entity builders. */
@Service
public class StructuredHistoryService {
    private final JDA jda;
    private final DiscordScope scope;
    private final DiscordRestClient rest;
    private final ArchivedThreadService threads;
    public StructuredHistoryService(JDA jda, DiscordScope scope, DiscordRestClient rest, ArchivedThreadService threads) {
        this.jda = jda; this.scope = scope; this.rest = rest; this.threads = threads;
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Author(String id, String username, @ToolParam(required = false) @McpToolParam(required = false) String displayName, boolean bot) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MentionIds(List<String> userIds, List<String> roleIds, boolean everyone) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attachment(String id, String filename, @ToolParam(required = false) @McpToolParam(required = false) String contentType, int sizeBytes, String url, @ToolParam(required = false) @McpToolParam(required = false) String proxyUrl) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reference(int type, @ToolParam(required = false) @McpToolParam(required = false) String messageId, @ToolParam(required = false) @McpToolParam(required = false) String channelId, @ToolParam(required = false) @McpToolParam(required = false) String guildId) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReactionEmoji(@ToolParam(required = false) @McpToolParam(required = false) String id, String name, boolean animated) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reaction(ReactionEmoji emoji, int count, @ToolParam(required = false) @McpToolParam(required = false) Integer normalCount, @ToolParam(required = false) @McpToolParam(required = false) Integer burstCount, boolean me, boolean meBurst) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StructuredMessage(String id, String channelId, @ToolParam(required = false) @McpToolParam(required = false) String guildId, String channelType,
                                    @ToolParam(required = false) @McpToolParam(required = false) String parentChannelId, @ToolParam(required = false) @McpToolParam(required = false) String threadId, Author author, @ToolParam(required = false) @McpToolParam(required = false) String webhookId, int type,
                                    String contentRaw, @ToolParam(required = false) @McpToolParam(required = false) String contentDisplay, OffsetDateTime createdAt, @ToolParam(required = false) @McpToolParam(required = false) OffsetDateTime editedAt,
                                    String jumpUrl, MentionIds mentions, @ToolParam(required = false) @McpToolParam(required = false) Reference messageReference, @ToolParam(required = false) @McpToolParam(required = false) String referencedAuthorId,
                                    @ToolParam(required = false) @McpToolParam(required = false) String referencedMessageState, List<Attachment> attachments, List<Reaction> reactions, @ToolParam(required = false) @McpToolParam(required = false) String startedThreadId) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryPage(String schemaVersion, String channelId, List<StructuredMessage> messages,
                              String order, @ToolParam(required = false) @McpToolParam(required = false) String oldestId, @ToolParam(required = false) @McpToolParam(required = false) String newestId, @ToolParam(required = false) @McpToolParam(required = false) String nextAfter,
                              @ToolParam(required = false) @McpToolParam(required = false) String nextBefore, boolean mayHaveMore, OffsetDateTime fetchedAt) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReactionUser(String id, String username, boolean bot) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReactionUsersPage(String schemaVersion, String channelId, @ToolParam(required = false) @McpToolParam(required = false) String messageId, String emoji,
                                    String type, List<ReactionUser> users, @ToolParam(required = false) @McpToolParam(required = false) String nextUserId, boolean mayHaveMore) {}

    @McpTool(name = "read_structured_messages", description = "Read scoped history ordered by unsigned message ID; supply approved parent for threads", generateOutputSchema = true)
    public HistoryPage readStructuredMessages(@McpToolParam(description = "Channel ID") String channelId,
            @McpToolParam(description = "Page size 1-100", required = false) Integer count,
            @McpToolParam(description = "Exclusive before ID", required = false) String before,
            @McpToolParam(description = "Exclusive after ID", required = false) String after,
            @McpToolParam(description = "Context ID; does not advance checkpoints", required = false) String around,
            @McpToolParam(description = "Configured parent ID; required for thread IDs", required = false) String parentChannelId) {
        int n = limit(count); validateCursors(before, after, around);
        var channel = resolve(channelId, parentChannelId);
        return history(channelId, channel, n, before, after, around);
    }
    @McpTool(name = "read_structured_private_messages", description = "Read the configured owner's DM by user ID", generateOutputSchema = true)
    public HistoryPage readPrivateMessages(@McpToolParam(description = "Configured owner user ID") String userId,
            @McpToolParam(description = "Page size 1-100", required = false) Integer count,
            @McpToolParam(description = "Exclusive before ID", required = false) String before,
            @McpToolParam(description = "Exclusive after ID", required = false) String after,
            @McpToolParam(description = "Context ID", required = false) String around) {
        scope.owner(userId); int n = limit(count); validateCursors(before, after, around);
        // No guild membership searches. JDA's public API opens the owner DM on a cold cache.
        var dm = jda.openPrivateChannelById(userId).complete();
        if (dm.getUser() == null || !userId.equals(dm.getUser().getId())) throw new IllegalArgumentException("DM recipient mismatch");
        var channel = rest.get("/channels/" + dm.getId());
        if (!dm.getId().equals(channel.path("id").asText())) throw new IllegalStateException("Discord DM channel ID mismatch");
        verifyDm(channel);
        return history(dm.getId(), channel, n, before, after, around);
    }
    private void verifyDm(JsonNode channel) {
        if (channel.path("type").asInt(-1) != 1 || channel.path("recipients").size() != 1) throw new IllegalArgumentException("Not an owner DM");
        scope.owner(channel.path("recipients").get(0).path("id").asText());
    }
    private JsonNode resolve(String channelId, String parent) {
        DiscordScope.snowflake(channelId);
        // User IDs / private IDs belong to the separate owner-DM tool; no guessing identities.
        String expected = parent == null ? channelId : parent;
        scope.parent(expected);
        if (!channelId.equals(expected)) return threads.resolveThread(channelId, expected);
        var channel = rest.get("/channels/" + channelId);
        if (!channelId.equals(channel.path("id").asText())) throw new IllegalStateException("Discord channel ID mismatch");
        scope.guild(channel.path("guild_id").asText());
        int type = channel.path("type").asInt(-1);
        if (type != 0 && type != 5) throw new IllegalArgumentException("Channel does not support parent message history");
        return channel;
    }
    private HistoryPage history(String id, JsonNode channel, int n, String before, String after, String around) {
        String query = "?limit=" + n;
        if (before != null) query += "&before=" + before;
        if (after != null) query += "&after=" + after;
        if (around != null) query += "&around=" + around;
        var raw = rest.get("/channels/" + id + "/messages" + query);
        if (!raw.isArray()) throw new IllegalStateException("Invalid Discord history page");
        var messages = raw.valueStream().map(m -> map(m, channel)).sorted((a,b) -> Long.compareUnsigned(Long.parseUnsignedLong(a.id()), Long.parseUnsignedLong(b.id()))).toList();
        String oldest = messages.isEmpty() ? null : messages.get(0).id();
        String newest = messages.isEmpty() ? null : messages.get(messages.size() - 1).id();
        return new HistoryPage("1", id, messages, "oldest_first", oldest, newest,
                around != null ? null : newest, around != null ? null : oldest, around == null && messages.size() == n, OffsetDateTime.now());
    }
    @McpTool(name = "list_reaction_users", description = "List reactor IDs for a scoped guild/thread message; NORMAL and BURST are separate", generateOutputSchema = true)
    public ReactionUsersPage listReactionUsers(@McpToolParam(description = "Channel ID") String channelId,
            @McpToolParam(description = "Message ID") String messageId,
            @McpToolParam(description = "Unicode emoji or name:id or <:name:id>") String emoji,
            @McpToolParam(description = "NORMAL or BURST", required = false) String type,
            @McpToolParam(description = "Page size 1-100", required = false) Integer count,
            @McpToolParam(description = "Exclusive user ID", required = false) String afterUserId,
            @McpToolParam(description = "Configured parent ID; required for threads", required = false) String parentChannelId) {
        int n = limit(count); DiscordScope.snowflake(messageId);
        if (afterUserId != null) DiscordScope.snowflake(afterUserId);
        String mode = type == null ? "NORMAL" : type.toUpperCase(Locale.ROOT);
        if (!List.of("NORMAL", "BURST").contains(mode)) throw new IllegalArgumentException("Invalid reaction type");
        if (emoji == null || emoji.isBlank()) throw new IllegalArgumentException("emoji required");
        String parsed = emoji.replaceAll("^<a?:|>$", "");
        resolve(channelId, parentChannelId);
        var root = rest.get("/channels/" + channelId + "/messages/" + messageId + "/reactions/" + DiscordRestClient.encode(parsed)
                + "?limit=" + n + "&type=" + (mode.equals("NORMAL") ? 0 : 1) + (afterUserId == null ? "" : "&after=" + afterUserId));
        if (!root.isArray()) throw new IllegalStateException("Invalid Discord reactor page");
        var users = root.valueStream().map(u -> new ReactionUser(u.path("id").asText(), u.path("username").asText(), u.path("bot").asBoolean(false))).sorted((a,b) -> Long.compareUnsigned(Long.parseUnsignedLong(a.id()), Long.parseUnsignedLong(b.id()))).toList();
        boolean more = users.size() == n;
        return new ReactionUsersPage("1", channelId, messageId, emoji, mode, users, more ? users.get(users.size()-1).id() : null, more);
    }
    private StructuredMessage map(JsonNode m, JsonNode channel) {
        String id = m.path("id").asText(); DiscordScope.snowflake(id);
        String channelId = m.path("channel_id").asText();
        if (!channel.path("id").asText().equals(channelId)) throw new IllegalStateException("Message channel mismatch");
        String guild = channel.path("guild_id").asText(null);
        int channelType = channel.path("type").asInt(0);
        var a = m.path("author");
        var ref = m.path("message_reference");
        Reference reference = ref.isMissingNode() ? null : new Reference(ref.path("type").asInt(0), ref.path("message_id").asText(null), ref.path("channel_id").asText(null), ref.path("guild_id").asText(null));
        var referenced = m.path("referenced_message");
        String refState = reference == null ? null : !m.has("referenced_message") ? "unknown" : referenced.isNull() ? "deleted" : "present";
        var attachments = m.path("attachments").valueStream().map(x -> new Attachment(x.path("id").asText(), x.path("filename").asText(), x.path("content_type").asText(null), x.path("size").asInt(0), x.path("url").asText(), x.path("proxy_url").asText(null))).toList();
        var reactions = m.path("reactions").valueStream().map(x -> new Reaction(new ReactionEmoji(x.path("emoji").path("id").asText(null), x.path("emoji").path("name").asText(), x.path("emoji").path("animated").asBoolean(false)), x.path("count").asInt(0), x.path("count_details").has("normal") ? x.path("count_details").path("normal").asInt(0) : null, x.path("count_details").has("burst") ? x.path("count_details").path("burst").asInt(0) : null, x.path("me").asBoolean(false), x.path("me_burst").asBoolean(false))).toList();
        boolean isThread = channelType == 10 || channelType == 11 || channelType == 12;
        return new StructuredMessage(id, channelId, guild, String.valueOf(channelType), isThread ? channel.path("parent_id").asText() : null,
                isThread ? channelId : null, new Author(a.path("id").asText(), a.path("username").asText(), a.path("global_name").asText(null), a.path("bot").asBoolean(false)), m.path("webhook_id").asText(null), m.path("type").asInt(0), m.path("content").asText(""), null,
                OffsetDateTime.parse(m.path("timestamp").asText()), m.path("edited_timestamp").isTextual() ? OffsetDateTime.parse(m.path("edited_timestamp").asText()) : null,
                "https://discord.com/channels/" + (guild == null ? "@me" : guild) + "/" + channelId + "/" + id,
                new MentionIds(m.path("mentions").valueStream().map(u -> u.path("id").asText()).toList(), m.path("mention_roles").valueStream().map(JsonNode::asText).toList(), m.path("mention_everyone").asBoolean(false)), reference,
                referenced.path("author").path("id").asText(null), refState, attachments, reactions, m.path("thread").path("id").asText(null));
    }
    private int limit(Integer count) { int n = count == null ? 100 : count; if (n < 1 || n > 100) throw new IllegalArgumentException("count must be 1-100"); return n; }
    private void validateCursors(String before, String after, String around) {
        int n = 0;
        for (String cursor : new String[]{before, after, around}) { if (cursor != null) { DiscordScope.snowflake(cursor); n++; } }
        if (n > 1) throw new IllegalArgumentException("before, after, around are mutually exclusive");
    }
}
