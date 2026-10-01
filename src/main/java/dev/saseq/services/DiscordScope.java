package dev.saseq.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** New scoped tools fail closed before any Discord operation. Legacy tools remain separate. */
@Component
public class DiscordScope {
    private final Set<String> parents;
    private final String owner;
    private final String guild;
    public DiscordScope(@Value("${DISCORD_THREAD_PARENT_CHANNEL_IDS:}") String parents,
                        @Value("${DISCORD_OWNER_USER_ID:}") String owner,
                        @Value("${DISCORD_GUILD_ID:}") String guild) {
        this.parents = Arrays.stream(parents.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.owner = owner;
        this.guild = guild;
    }
    public void parent(String id) {
        snowflake(id);
        if (!parents.contains(id)) throw new IllegalArgumentException("Parent is outside the configured scope");
    }
    public void owner(String id) {
        snowflake(id);
        if (owner.isBlank() || !owner.equals(id)) throw new IllegalArgumentException("DM recipient is outside the configured owner scope");
    }
    public void guild(String id) {
        if (guild.isBlank() || !guild.equals(id)) throw new IllegalArgumentException("Guild is outside the configured scope");
    }
    public static void snowflake(String id) {
        if (id == null || !id.matches("[0-9]{1,20}")) throw new IllegalArgumentException("Invalid Discord snowflake");
        try { if (Long.parseUnsignedLong(id) == 0) throw new IllegalArgumentException("Invalid Discord snowflake"); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid Discord snowflake", e); }
    }
}
