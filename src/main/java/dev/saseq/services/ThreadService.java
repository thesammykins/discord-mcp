package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ThreadService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    @Value("${DISCORD_THREAD_PARENT_CHANNEL_IDS:}")
    private String configuredParentIds;

    public ThreadService(JDA jda) {
        this.jda = jda;
    }

    private String resolveGuildId(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            return defaultGuildId;
        }
        return guildId;
    }

    /**
     * Lists all active threads in a specified Discord server.
     *
     * @param guildId Optional ID of the Discord server (guild). If not provided, the default server will be used.
     * @return A formatted string listing all active threads in the server, including their name, ID, and parent channel.
     */
    @Tool(name = "list_active_threads", description = "List all active threads in the server")
    public String listActiveThreads(@ToolParam(description = "Discord server ID", required = false) String guildId) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }

        // Retrieve active threads from Discord API
        List<ThreadChannel> threads = guild.retrieveActiveThreads().complete();

        if (threads.isEmpty()) {
            return "No active threads found in the server.";
        }

        return "Retrieved " + threads.size() + " active threads:\n" +
                threads.stream()
                        .map(t -> {
                            String parentName = t.getParentChannel() != null ? t.getParentChannel().getName() : "unknown";
                            String archived = t.isArchived() ? " (archived)" : "";
                            return "- " + t.getName() + " (ID: " + t.getId() + ") in #" + parentName + archived;
                        })
                        .collect(Collectors.joining("\n"));
    }

    @Tool(name = "list_channel_threads", description = "List active threads under one explicitly authorized parent channel")
    public String listChannelThreads(@ToolParam(description = "Parent channel ID") String parentChannelId,
                                     @ToolParam(description = "Thread kind: active") String kind,
                                     @ToolParam(description = "Maximum results (1-100)", required = false) String limit,
                                     @ToolParam(description = "Opaque page cursor", required = false) String cursor) {
        if (!"active".equalsIgnoreCase(kind)) {
            throw new IllegalArgumentException("Only active thread discovery is supported by this deployment; archived modes require a typed REST cursor");
        }
        if (parentChannelId == null || parentChannelId.isBlank()) throw new IllegalArgumentException("parentChannelId cannot be blank");
        if (configuredParentIds == null || configuredParentIds.isBlank()
                || java.util.Arrays.stream(configuredParentIds.split(",")).map(String::trim).noneMatch(parentChannelId::equals)) {
            throw new IllegalArgumentException("parentChannelId is outside the configured collection scope");
        }
        if (cursor != null && !cursor.isBlank()) throw new IllegalArgumentException("active thread snapshots do not support continuation; restart the bounded snapshot");
        int requested = 100;
        if (limit != null && !limit.isBlank()) {
            try { requested = Integer.parseInt(limit); } catch (NumberFormatException e) { throw new IllegalArgumentException("limit must be an integer between 1 and 100"); }
            if (requested < 1 || requested > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        Guild guild = jda.getGuilds().stream().filter(g -> g.getGuildChannelById(parentChannelId) != null).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Configured parent channel was not found in an accessible guild"));
        GuildChannel parent = guild.getGuildChannelById(parentChannelId);
        List<ThreadChannel> threads = guild.retrieveActiveThreads().complete().stream()
                .filter(t -> t.getParentChannel() != null && parentChannelId.equals(t.getParentChannel().getId()))
                .limit(requested).toList();
        String body = threads.stream().map(t -> String.format("{\"id\":\"%s\",\"parentChannelId\":\"%s\",\"guildId\":\"%s\",\"name\":%s,\"type\":\"%s\",\"archived\":%s,\"locked\":%s}",
                t.getId(), parent.getId(), guild.getId(), quote(t.getName()), t.getType().name(), t.isArchived(), t.isLocked())).collect(Collectors.joining(","));
        return String.format("{\"schemaVersion\":\"1\",\"parentChannelId\":\"%s\",\"guildId\":\"%s\",\"threads\":[%s],\"nextCursor\":null,\"hasMore\":false,\"complete\":true,\"coverage\":\"guild-wide active-thread fetch filtered to configured parent\"}", parentChannelId, guild.getId(), body);
    }

    @Tool(name = "set_thread_archived", description = "Archive or unarchive an ordinary Discord thread")
    public String setThreadArchived(@ToolParam(description = "Thread ID") String threadId,
                                    @ToolParam(description = "Expected parent channel ID") String parentChannelId,
                                    @ToolParam(description = "Archive state") String archived,
                                    @ToolParam(description = "Audit reason", required = false) String reason) {
        if (threadId == null || threadId.isBlank() || parentChannelId == null || parentChannelId.isBlank()) throw new IllegalArgumentException("threadId and parentChannelId are required");
        ThreadChannel thread = jda.getThreadChannelById(threadId);
        if (thread == null) throw new IllegalArgumentException("Thread not found in cache; uncached REST resolution is required before lifecycle mutation");
        if (thread.getParentChannel() == null || !parentChannelId.equals(thread.getParentChannel().getId())) throw new IllegalArgumentException("Thread parent does not match parentChannelId");
        var manager = thread.getManager().setArchived(Boolean.parseBoolean(archived));
        if (reason != null && !reason.isBlank()) manager.reason(reason);
        manager.complete();
        ThreadChannel updated = thread;
        return String.format("{\"threadId\":\"%s\",\"parentChannelId\":\"%s\",\"archived\":%s,\"locked\":%s}", updated.getId(), parentChannelId, updated.isArchived(), updated.isLocked());
    }

    private String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
