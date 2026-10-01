package dev.saseq;

import dev.saseq.services.DiscordRestClient;
import io.modelcontextprotocol.server.McpSyncServer;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.Set;
import java.util.Map;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs the application's real component scan and MCP auto-configuration, not a hand-built provider. */
@SpringBootTest(classes = DiscordMcpApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"DISCORD_TOKEN=", "DISCORD_GUILD_ID=400", "DISCORD_THREAD_PARENT_CHANNEL_IDS=100",
                "DISCORD_OWNER_USER_ID=300"})
@ActiveProfiles("http")
class SpringRegistrationTest {
    static final Set<String> NATIVE_TOOLS = Set.of("read_structured_messages", "read_structured_private_messages",
            "list_reaction_users", "list_archived_threads", "list_channel_threads", "set_thread_archived");
    @MockitoBean JDA jda;
    @MockitoBean DiscordRestClient rest;
    @Autowired McpSyncServer server;
    @Autowired Environment environment;
    final JsonMapper json = JsonMapper.builder().build();

    @Test void actualApplicationServerContainsLegacyAndNativeTools() {
        var names = server.listTools().stream().map(tool -> tool.name()).collect(Collectors.toSet());
        assertTrue(names.containsAll(NATIVE_TOOLS), "Assembled server has " + names.size() + " tools; missing "
                + NATIVE_TOOLS.stream().filter(name -> !names.contains(name)).toList());
        assertTrue(names.contains("list_active_threads"));
        assertEquals(server.listTools().size(), names.size(), "Tool names must be unique");
        assertEquals(81, names.size());
    }

    @Test void realHttpToolsListAdvertisesNativeOutputSchemasAlongsideLegacyTools() throws Exception {
        var client = new HttpWire();
        var tools = client.call("tools/list", Map.of()).path("result").path("tools");
        assertEquals(81, tools.size());
        for (var name : NATIVE_TOOLS) {
            var tool = McpWireTest.tool(tools, name);
            assertTrue(tool.path("outputSchema").isObject(), name);
        }
        assertEquals("boolean", McpWireTest.tool(tools, "set_thread_archived")
                .path("inputSchema").path("properties").path("archived").path("type").asText());
        verifyNoInteractions(jda, rest);
    }

    @Test void realHttpStructuredCallUsesApplicationServiceAndReturnsNativeContent() throws Exception {
        when(rest.get("/channels/100")).thenReturn(json.readTree(DiscordBehaviorTest.parent(0)));
        when(rest.get("/channels/100/messages?limit=1&after=50"))
                .thenReturn(json.readTree("[" + DiscordBehaviorTest.message("51", "100") + "]"));
        var response = new HttpWire().call("tools/call", Map.of("name", "read_structured_messages",
                "arguments", Map.of("channelId", "100", "count", 1, "after", "50")));
        assertFalse(response.has("error"), response.toString());
        var result = response.path("result");
        assertFalse(result.path("isError").asBoolean(), result.toString());
        var structured = result.path("structuredContent");
        assertEquals("51", structured.path("nextAfter").asText());
        assertEquals("300", structured.path("messages").get(0).path("author").path("id").asText());
        assertEquals(structured, json.readTree(result.path("content").get(0).path("text").asText()));
        verify(rest).get("/channels/100");
        verify(rest).get("/channels/100/messages?limit=1&after=50");
        verifyNoInteractions(jda);
    }

    /** Raw JSON-RPC against the actual auto-configured streamable HTTP endpoint. */
    class HttpWire {
        final HttpClient http = HttpClient.newHttpClient();
        final URI endpoint = URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + "/mcp");
        String session;
        String protocol;
        int id;
        HttpWire() throws Exception {
            var init = call("initialize", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "spring-context-test", "version", "1")));
            assertFalse(init.has("error"), init.toString());
            protocol = init.path("result").path("protocolVersion").asText();
        }
        JsonNode call(String method, Map<String, ?> params) throws Exception {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
            if (session != null) request.header("Mcp-Session-Id", session);
            if (protocol != null) request.header("MCP-Protocol-Version", protocol);
            var response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                    Map.of("jsonrpc", "2.0", "id", ++id, "method", method, "params", params)))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> session = value);
            if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
                var payload = response.body().lines().filter(line -> line.startsWith("data:"))
                        .map(line -> line.substring(5).strip()).collect(Collectors.joining("\n"));
                return json.readTree(payload);
            }
            return json.readTree(response.body());
        }
    }
}
