package dev.saseq;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual newline JSON-RPC transport: no production token or gateway connection. */
class McpWireTest {
    DiscordBehaviorTest fixture;
    Wire wire;
    @BeforeEach void setup() throws Exception {
        fixture = new DiscordBehaviorTest(); fixture.setup();
        var specifications = new SyncMcpToolProvider(List.of(fixture.history, fixture.archives, fixture.threads)).getToolSpecifications();
        wire = new Wire(specifications);
    }
    @AfterEach void stop() throws Exception { if (wire != null) wire.close(); if (fixture != null) fixture.stop(); }
    @Test void toolsListAdvertisesUniqueNamesOutputSchemasAndTypedLimitsBooleans() throws Exception {
        var tools = wire.call("tools/list", Map.of()).path("result").path("tools");
        Set<String> names = new HashSet<>();
        for (var t : tools) { assertTrue(names.add(t.path("name").asText())); assertTrue(t.path("outputSchema").isObject()); }
        assertEquals(Set.of("read_structured_messages","read_structured_private_messages","list_reaction_users","list_archived_threads","list_channel_threads","set_thread_archived"), names);
        var archive = tool(tools, "set_thread_archived");
        assertEquals("boolean", archive.path("inputSchema").path("properties").path("archived").path("type").asText());
        assertTrue(archive.path("inputSchema").path("required").toString().contains("archived"));
        assertEquals("integer", tool(tools,"read_structured_messages").path("inputSchema").path("properties").path("count").path("type").asText());
    }
    @Test void toolsCallReturnsNativeStructuredContentAndMatchingJsonFallback() throws Exception {
        fixture.respond = path -> path.endsWith("/100") ? DiscordBehaviorTest.parent(0) : "["+DiscordBehaviorTest.message("51","100")+"]";
        var result = wire.call("tools/call", Map.of("name","read_structured_messages","arguments",Map.of("channelId","100","count",1,"after","50"))).path("result");
        assertFalse(result.path("isError").asBoolean());
        var structured = result.path("structuredContent"); assertTrue(structured.isObject());
        assertEquals("51",structured.path("nextAfter").asText());
        assertEquals("300",structured.path("messages").get(0).path("author").path("id").asText());
        assertEquals(structured, fixture.json.readTree(result.path("content").get(0).path("text").asText()));
    }
    @Test void archiveMalformedNullOrMissingBooleanFailsBeforeDiscordOperations() throws Exception {
        for (var args : List.of(Map.of("threadId","200","parentChannelId","100","archived","yes"),
                                Map.of("threadId","200","parentChannelId","100"))) {
            var response = wire.call("tools/call", Map.of("name","set_thread_archived","arguments",args));
            assertTrue(response.has("error") || response.path("result").path("isError").asBoolean(), response.toString());
        }
        var nullArgs = new HashMap<String,Object>(); nullArgs.put("threadId","200"); nullArgs.put("parentChannelId","100"); nullArgs.put("archived",null);
        var response = wire.call("tools/call",Map.of("name","set_thread_archived","arguments",nullArgs));
        assertTrue(response.has("error") || response.path("result").path("isError").asBoolean());
        assertTrue(fixture.requests.isEmpty());
    }
    @Test void reactionsAreNativeStructuredContentOnWire() throws Exception {
        fixture.respond = path -> path.endsWith("/100") ? DiscordBehaviorTest.parent(0) : "[{\"id\":\"300\",\"username\":\"owner\",\"bot\":false}]";
        var result = wire.call("tools/call",Map.of("name","list_reaction_users","arguments",Map.of("channelId","100","messageId","50","emoji","👍","count",1))).path("result");
        assertFalse(result.path("isError").asBoolean()); assertEquals("300",result.path("structuredContent").path("users").get(0).path("id").asText());
    }
    @Test void archiveListingAndMutationHaveSchemaValidNativeResults() throws Exception {
        fixture.respond = path -> path.endsWith("/100") ? DiscordBehaviorTest.parent(0)
                : path.contains("/threads/archived/") ? "{\"threads\":["+DiscordBehaviorTest.thread("100",true)+"],\"has_more\":false}"
                : DiscordBehaviorTest.thread("100",false);
        var page = wire.call("tools/call",Map.of("name","list_archived_threads","arguments",Map.of("parentChannelId","100","mode","public","limit",1))).path("result");
        assertFalse(page.path("isError").asBoolean()); assertTrue(page.path("structuredContent").path("complete").asBoolean());
        assertEquals("200",page.path("structuredContent").path("threads").get(0).path("id").asText());
        var mutation = wire.call("tools/call",Map.of("name","set_thread_archived","arguments",Map.of("threadId","200","parentChannelId","100","archived",false))).path("result");
        assertFalse(mutation.path("isError").asBoolean()); assertFalse(mutation.path("structuredContent").path("archived").asBoolean());
        assertTrue(mutation.path("structuredContent").path("locked").asBoolean());
    }
    @Test void nonemptyOwnerDmHasSchemaValidWireResult() throws Exception {
        var dm = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel.class);
        var user = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.User.class);
        org.mockito.Mockito.when(dm.getId()).thenReturn("500"); org.mockito.Mockito.when(user.getId()).thenReturn("300"); org.mockito.Mockito.when(dm.getUser()).thenReturn(user);
        @SuppressWarnings("unchecked") var action = (net.dv8tion.jda.api.requests.restaction.CacheRestAction<net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel>) org.mockito.Mockito.mock(net.dv8tion.jda.api.requests.restaction.CacheRestAction.class);
        org.mockito.Mockito.when(action.complete()).thenReturn(dm); org.mockito.Mockito.when(fixture.jda.openPrivateChannelById("300")).thenReturn(action);
        fixture.respond = path -> path.equals("GET /channels/500") ? "{\"id\":\"500\",\"type\":1,\"recipients\":[{\"id\":\"300\"}]}" : "["+DiscordBehaviorTest.message("51","500")+"]";
        var result = wire.call("tools/call",Map.of("name","read_structured_private_messages","arguments",Map.of("userId","300","count",1))).path("result");
        assertFalse(result.path("isError").asBoolean()); assertEquals("500",result.path("structuredContent").path("channelId").asText());
        assertFalse(result.path("structuredContent").path("messages").get(0).has("guildId"));
        org.mockito.Mockito.verify(fixture.jda,org.mockito.Mockito.never()).getGuilds();
    }
    @Test void unauthorizedWireCallsCannotReachDiscord() throws Exception {
        for (var call : List.of(Map.of("name","read_structured_messages","arguments",Map.of("channelId","999")),
                                Map.of("name","list_reaction_users","arguments",Map.of("channelId","999","messageId","50","emoji","👍")),
                                Map.of("name","list_archived_threads","arguments",Map.of("parentChannelId","999","mode","public")),
                                Map.of("name","set_thread_archived","arguments",Map.of("parentChannelId","999","threadId","200","archived",false)),
                                Map.of("name","read_structured_private_messages","arguments",Map.of("userId","999")))) {
            var response = wire.call("tools/call", call);
            assertTrue(response.has("error") || response.path("result").path("isError").asBoolean());
        }
        assertTrue(fixture.requests.isEmpty()); org.mockito.Mockito.verifyNoInteractions(fixture.jda);
    }
    static JsonNode tool(JsonNode tools, String name) { return tools.valueStream().filter(t -> name.equals(t.path("name").asText())).findFirst().orElseThrow(); }
    static class Wire implements AutoCloseable {
        final JsonMapper json = JsonMapper.builder().build();
        final PipedInputStream serverInput = new PipedInputStream(65536);
        final PipedOutputStream clientOutput;
        final PipedInputStream clientInput = new PipedInputStream(65536);
        final PipedOutputStream serverOutput;
        final BufferedReader reader;
        final ExecutorService reads = Executors.newSingleThreadExecutor();
        final McpSyncServer server;
        int id;
        Wire(List<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification> tools) throws Exception {
            clientOutput = new PipedOutputStream(serverInput); serverOutput = new PipedOutputStream(clientInput);
            reader = new BufferedReader(new InputStreamReader(clientInput, StandardCharsets.UTF_8));
            var mapper = new JacksonMcpJsonMapper(json);
            server = McpServer.sync(new StdioServerTransportProvider(mapper, serverInput, serverOutput))
                    .serverInfo("test", "1").capabilities(McpSchema.ServerCapabilities.builder().tools(false).build()).tools(tools).build();
            var init = call("initialize",Map.of("protocolVersion","2025-11-25","capabilities",Map.of(),"clientInfo",Map.of("name","test","version","1")));
            assertFalse(init.has("error"),init.toString());
            send(Map.of("jsonrpc","2.0","method","notifications/initialized"));
        }
        void send(Map<String,Object> message) throws Exception { clientOutput.write((json.writeValueAsString(message)+"\n").getBytes(StandardCharsets.UTF_8)); clientOutput.flush(); }
        JsonNode call(String method, Map<String,?> params) throws Exception {
            int requestId = ++id;
            send(Map.of("jsonrpc","2.0","id",requestId,"method",method,"params",params));
            return reads.submit(() -> {
                String line;
                while ((line = reader.readLine()) != null) { var response = json.readTree(line); if (response.path("id").asInt(-1) == requestId) return response; }
                throw new EOFException("MCP transport closed");
            }).get(10,TimeUnit.SECONDS);
        }
        public void close() throws Exception { server.close(); clientOutput.close(); serverOutput.close(); clientInput.close(); serverInput.close(); reads.shutdownNow(); }
    }
}
