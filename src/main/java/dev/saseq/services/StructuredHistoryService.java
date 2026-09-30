package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageReaction;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/** Versioned machine-readable history and reaction contracts. */
@Service
public class StructuredHistoryService {
    private final JDA jda;

    public StructuredHistoryService(JDA jda) { this.jda = jda; }

    public record Author(String id, String username, String displayName, boolean bot) {}
    public record MentionIds(List<String> userIds, List<String> roleIds, boolean everyone) {}
    public record Attachment(String id, String filename, String contentType, int sizeBytes, String url, String proxyUrl) {}
    public record StructuredMessage(String id, String channelId, String guildId, String channelType,
                                    Author author, String contentRaw, String contentDisplay,
                                    OffsetDateTime createdAt, OffsetDateTime editedAt, String jumpUrl,
                                    MentionIds mentions, List<Attachment> attachments, String startedThreadId) {}
    public record HistoryPage(String schemaVersion, String channelId, List<StructuredMessage> messages,
                              String order, String oldestId, String newestId, String nextAfter,
                              String nextBefore, boolean mayHaveMore, OffsetDateTime fetchedAt) {}
    public record ReactionUser(String id, String username, boolean bot) {}
    public record ReactionUsersPage(String schemaVersion, String channelId, String messageId,
                                    String emoji, String type, List<ReactionUser> users,
                                    String nextUserId, boolean mayHaveMore) {}

    @McpTool(name = "read_structured_messages", description = "Read versioned structured Discord message history with explicit cursor semantics", generateOutputSchema = true)
    public HistoryPage readStructuredMessages(
            @McpToolParam(description = "Discord channel ID") String channelId,
            @McpToolParam(description = "Number of messages (1-100)", required = false) Integer count,
            @McpToolParam(description = "Exclusive before message ID", required = false) String before,
            @McpToolParam(description = "Exclusive after message ID", required = false) String after,
            @McpToolParam(description = "Context message ID", required = false) String around) {
        validateCursors(before, after, around);
        MessageChannel channel = resolve(channelId);
        int limit = count == null ? 100 : count;
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("count must be between 1 and 100");
        List<Message> raw;
        if (after != null) raw = channel.getHistoryAfter(after, limit).complete().getRetrievedHistory();
        else if (before != null) raw = channel.getHistoryBefore(before, limit).complete().getRetrievedHistory();
        else if (around != null) raw = channel.getHistoryAround(around, limit).complete().getRetrievedHistory();
        else raw = channel.getHistory().retrievePast(limit).complete();
        List<StructuredMessage> messages = raw.stream().map(this::map).sorted((a,b) -> a.createdAt().compareTo(b.createdAt())).toList();
        String oldest = messages.isEmpty() ? null : messages.get(0).id();
        String newest = messages.isEmpty() ? null : messages.get(messages.size() - 1).id();
        return new HistoryPage("1", channelId, messages, "oldest_first", oldest, newest,
                after == null || newest == null ? null : newest,
                before == null || oldest == null ? null : oldest,
                messages.size() == limit && around == null, OffsetDateTime.now());
    }

    @McpTool(name = "list_reaction_users", description = "List identities for one message reaction", generateOutputSchema = true)
    public ReactionUsersPage listReactionUsers(
            @McpToolParam(description = "Discord channel ID") String channelId,
            @McpToolParam(description = "Discord message ID") String messageId,
            @McpToolParam(description = "Unicode or custom emoji") String emoji,
            @McpToolParam(description = "NORMAL or BURST", required = false) String type,
            @McpToolParam(description = "Maximum users (1-100)", required = false) Integer count,
            @McpToolParam(description = "Exclusive user cursor", required = false) String afterUserId) {
        int limit = count == null ? 100 : count;
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("count must be between 1 and 100");
        MessageChannel channel = resolve(channelId);
        Message message = channel.retrieveMessageById(messageId).complete();
        Emoji parsed = Emoji.fromFormatted(emoji);
        MessageReaction.ReactionType reactionType = type == null || type.isBlank()
                ? MessageReaction.ReactionType.NORMAL : MessageReaction.ReactionType.valueOf(type.toUpperCase());
        var action = message.retrieveReactionUsers(parsed, reactionType).limit(limit);
        if (afterUserId != null && !afterUserId.isBlank()) action.skipTo(Long.parseLong(afterUserId));
        List<User> users = action.complete();
        List<ReactionUser> result = users.stream().map(u -> new ReactionUser(u.getId(), u.getName(), u.isBot())).toList();
        String next = result.size() == limit && !result.isEmpty() ? result.get(result.size() - 1).id() : null;
        return new ReactionUsersPage("1", channelId, messageId, emoji, reactionType.name(), result, next, next != null);
    }

    private MessageChannel resolve(String channelId) {
        MessageChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) channel = jda.getNewsChannelById(channelId);
        if (channel == null) channel = jda.getThreadChannelById(channelId);
        if (channel == null) channel = jda.getPrivateChannelById(channelId);
        if (channel == null) throw new IllegalArgumentException("Channel unavailable or not accessible: " + channelId);
        return channel;
    }

    private StructuredMessage map(Message m) {
        var a = m.getAuthor();
        var mentions = m.getMentions();
        List<Attachment> attachments = m.getAttachments().stream().map(x -> new Attachment(x.getId(), x.getFileName(), x.getContentType(), x.getSize(), x.getUrl(), x.getProxyUrl())).toList();
        return new StructuredMessage(m.getId(), m.getChannel().getId(), m.getGuild() == null ? null : m.getGuild().getId(),
                m.getChannelType().name(), new Author(a.getId(), a.getName(), a.getEffectiveName(), a.isBot()),
                m.getContentRaw(), m.getContentDisplay(), m.getTimeCreated(), m.getTimeEdited(), m.getJumpUrl(),
                new MentionIds(mentions.getUsers().stream().map(User::getId).toList(), mentions.getRoles().stream().map(x -> x.getId()).toList(), mentions.mentionsEveryone()),
                attachments, m.getStartedThread() == null ? null : m.getStartedThread().getId());
    }

    private void validateCursors(String before, String after, String around) {
        int n = (before == null || before.isBlank() ? 0 : 1) + (after == null || after.isBlank() ? 0 : 1) + (around == null || around.isBlank() ? 0 : 1);
        if (n > 1) throw new IllegalArgumentException("before, after, and around are mutually exclusive");
    }
}
