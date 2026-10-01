package dev.saseq.services;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ThreadService {

    private final JDA jda;
    private final ArchivedThreadService archivedThreadService;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public ThreadService(JDA jda, ArchivedThreadService archivedThreadService) {
        this.jda = jda;
        this.archivedThreadService = archivedThreadService;
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActivePage(String parentChannelId, String guildId, List<ArchivedThreadService.ArchivedThread> threads,
                             @ToolParam(required = false) @McpToolParam(required = false) String nextCursor, boolean hasMore, boolean complete, String coverage) {}

    @McpTool(name = "list_channel_threads",
            description = "Return a complete untruncated cache snapshot for a configured parent; cache coverage is partial",
            generateOutputSchema = true)
    public ActivePage listChannelThreads(
            @McpToolParam(description = "Configured parent ID") String parentChannelId,
            @McpToolParam(description = "active") String kind,
            @McpToolParam(description = "Compatibility page size; snapshot is untruncated", required = false) Integer limit,
            @McpToolParam(description = "Not supported for complete cache snapshots", required = false) String cursor) {
        archivedThreadService.requireConfiguredParent(parentChannelId);
        if (!"active".equals(kind)) throw new IllegalArgumentException("Use list_archived_threads for archived modes");
        if (limit != null && (limit < 1 || limit > 100)) throw new IllegalArgumentException("limit must be 1-100");
        if (cursor != null) throw new IllegalArgumentException("Cache snapshots have no continuation");
        Guild guild = jda.getGuilds().stream().filter(g -> g.getGuildChannelById(parentChannelId) != null).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Configured parent is not in the ready cache"));
        archivedThreadService.requireConfiguredGuild(guild.getId());
        List<ArchivedThreadService.ArchivedThread> result = guild.getThreadChannels().stream()
                .filter(t -> !t.isArchived() && t.getParentChannel() != null && parentChannelId.equals(t.getParentChannel().getId()))
                .map(t -> new ArchivedThreadService.ArchivedThread(t.getId(), parentChannelId, guild.getId(), t.getName(),
                        t.getType().getId(), t.getOwnerId(), t.isArchived(), t.isLocked(),
                        t.getTimeArchiveInfoLastModified().toString(), t.getAutoArchiveDuration().getMinutes(),
                        t.getLatestMessageId(), t.getAppliedTags().stream().map(x -> x.getId()).toList())).toList();
        return new ActivePage(parentChannelId, guild.getId(), result, null, false, false,
                "Untruncated authorized-parent JDA cache snapshot; archived/private visibility and cold cache may be incomplete. No guild-wide REST fetch.");
    }

    @McpTool(name = "set_thread_archived", description = "Archive/unarchive a verified thread and return authoritative Discord state", generateOutputSchema = true)
    public ArchivedThreadService.ArchivedThread setThreadArchivedTool(
            @McpToolParam(description = "Thread ID") String threadId,
            @McpToolParam(description = "Configured parent ID") String parentChannelId,
            @McpToolParam(description = "Required archive Boolean") Boolean archived,
            @McpToolParam(description = "Audit reason", required = false) String reason,
            org.springframework.ai.mcp.annotation.context.McpSyncRequestContext context) {
        var request = (io.modelcontextprotocol.spec.McpSchema.CallToolRequest) context.request();
        // Spring's converter can coerce malformed strings to false. Reject the raw input first.
        if (!(request.arguments().get("archived") instanceof Boolean)) throw new IllegalArgumentException("archived must be a JSON Boolean");
        return setThreadArchived(threadId, parentChannelId, archived, reason);
    }

    public ArchivedThreadService.ArchivedThread setThreadArchived(String threadId, String parentChannelId, Boolean archived, String reason) {
        return archivedThreadService.setThreadArchived(threadId, parentChannelId, archived, reason);
    }
}
