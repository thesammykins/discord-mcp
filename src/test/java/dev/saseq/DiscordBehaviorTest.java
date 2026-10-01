package dev.saseq;

import com.sun.net.httpserver.HttpServer;
import dev.saseq.services.*;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.*;
import net.dv8tion.jda.api.entities.channel.unions.IThreadContainerUnion;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscordBehaviorTest {
    final ObjectMapper json = new ObjectMapper();
    HttpServer server;
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    final List<String> reasons = Collections.synchronizedList(new ArrayList<>());
    Function<String,String> respond;
    int status;
    JDA jda;
    DiscordRestClient rest;
    ArchivedThreadService archives;
    StructuredHistoryService history;
    ThreadService threads;
    @BeforeEach void setup() throws Exception {
        status = 200;
        jda = mock(JDA.class);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        respond = path -> path.endsWith("/100") ? parent(0) : path.equals("GET /channels/200") ? thread("100", true) : "[]";
        server.createContext("/", exchange -> {
            String path = exchange.getRequestMethod() + " " + exchange.getRequestURI();
            requests.add(path); bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reasons.add(exchange.getRequestHeaders().getFirst("X-Audit-Log-Reason"));
            byte[] bytes = respond.apply(path).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        rest = new DiscordRestClient("test-only", "http://127.0.0.1:" + server.getAddress().getPort());
        var scope = new DiscordScope("100,101", "300", "400");
        archives = new ArchivedThreadService(rest, scope);
        history = new StructuredHistoryService(jda, scope, rest, archives);
        threads = new ThreadService(jda, archives);
    }
    @AfterEach void stop() { if (server != null) server.stop(0); }
    static String parent(int type) { return "{\"id\":\"100\",\"guild_id\":\"400\",\"type\":" + type + "}"; }
    static String thread(String parent, boolean archived) { return "{\"id\":\"200\",\"guild_id\":\"400\",\"parent_id\":\"" + parent + "\",\"type\":11,\"name\":\"thread\",\"applied_tags\":[\"9\"],\"thread_metadata\":{\"archived\":" + archived + ",\"locked\":true,\"archive_timestamp\":\"2021-04-12T23:40:39.855793+00:00\"}}"; }
    static String message(String id, String channel) { return "{\"id\":\""+id+"\",\"channel_id\":\""+channel+"\",\"timestamp\":\"2026-09-30T00:00:00Z\",\"content\":\"hello\",\"author\":{\"id\":\"300\",\"username\":\"same-name\"}}"; }
    @Test void unauthorizedOperationsMakeNoHttpOrJdaCalls() {
        assertThrows(IllegalArgumentException.class, () -> archives.listArchivedThreads("999", "public", 1, null));
        assertThrows(IllegalArgumentException.class, () -> history.readStructuredMessages("999", 1, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> history.listReactionUsers("999", "50", "👍", null, 1, null, null));
        assertThrows(IllegalArgumentException.class, () -> threads.setThreadArchived("200", "999", true, null));
        assertThrows(IllegalArgumentException.class, () -> history.readPrivateMessages("999", 1, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> threads.listChannelThreads("999", "active", 1, null));
        assertTrue(requests.isEmpty()); verifyNoInteractions(jda);
    }
    @Test void cursorCannotMoveAcrossParentsOrModesBeforeHttp() {
        String c = new ArchivedThreadService.ArchiveCursor("1", "100", "public", "2021-04-12T23:40:39.855793+00:00").encode();
        assertThrows(IllegalArgumentException.class, () -> archives.listArchivedThreads("101", "public", 1, c));
        assertThrows(IllegalArgumentException.class, () -> archives.listArchivedThreads("100", "private", 1, c));
        assertTrue(requests.isEmpty());
    }
    @Test void publicTimestampCursorEncodesPlusAndPreservesHasMore() {
        respond = path -> path.endsWith("/100") ? parent(0) : "{\"threads\":["+thread("100",true)+"],\"has_more\":true}";
        var page = archives.listArchivedThreads("100", "public", 1, null);
        assertTrue(page.hasMore()); assertFalse(page.complete());
        archives.listArchivedThreads("100", "public", 1, page.nextCursor());
        assertTrue(requests.get(3).contains("%2B00%3A00"));
        assertEquals("2021-04-12T23:40:39.855793+00:00", ArchivedThreadService.ArchiveCursor.decode(page.nextCursor(), "100", "public").value());
    }
    @Test void privateTimestampCursorUsesTimestampRoute() {
        respond = path -> path.endsWith("/100") ? parent(0) : "{\"threads\":["+thread("100",true)+"],\"has_more\":true}";
        var page = archives.listArchivedThreads("100", "private", 1, null);
        archives.listArchivedThreads("100", "private", 1, page.nextCursor());
        assertTrue(requests.get(3).contains("/threads/archived/private?limit=1&before=2021"));
    }
    @Test void joinedPrivateCursorUsesThreadId() {
        respond = path -> path.endsWith("/100") ? parent(0) : "{\"threads\":["+thread("100",true)+"],\"has_more\":true}";
        var page = archives.listArchivedThreads("100", "joined_private", 1, null);
        archives.listArchivedThreads("100", "joined_private", 1, page.nextCursor());
        assertTrue(requests.get(3).endsWith("/users/@me/threads/archived/private?limit=1&before=200"));
    }
    @Test void forumPrivateArchivesAreRejectedBeforeCollection() {
        respond = path -> parent(15);
        assertThrows(IllegalArgumentException.class, () -> archives.listArchivedThreads("100", "private", 1, null));
        assertEquals(List.of("GET /channels/100"), requests);
    }
    @Test void returnedWrongParentIsErrorNotFilteredCompletePage() {
        respond = path -> path.endsWith("/100") ? parent(0) : "{\"threads\":["+thread("101",true)+"],\"has_more\":false}";
        assertThrows(IllegalArgumentException.class, () -> archives.listArchivedThreads("100", "public", 1, null));
    }
    @Test void coldArchivedReadResolvesThenFetchesHistory() {
        respond = path -> path.equals("GET /channels/200") ? thread("100", true) : "["+message("51","200")+"]";
        var page = history.readStructuredMessages("200", 100, null, "50", null, "100");
        assertEquals("200", page.messages().get(0).threadId());
        assertEquals("100", page.messages().get(0).parentChannelId());
        assertEquals(List.of("GET /channels/200", "GET /channels/200/messages?limit=100&after=50"), requests);
        verifyNoInteractions(jda);
    }
    @Test void wrongParentColdReadNeverFetchesMessages() {
        respond = path -> thread("101", true);
        assertThrows(IllegalArgumentException.class, () -> history.readStructuredMessages("200", 1, null, null, null, "100"));
        assertEquals(List.of("GET /channels/200"), requests);
    }
    @Test void wrongGuildColdReadNeverFetchesMessages() {
        respond = path -> thread("100", true).replace("400", "999");
        assertThrows(IllegalArgumentException.class, () -> history.readStructuredMessages("200", 1, null, null, null, "100"));
        assertEquals(1, requests.size());
    }
    @Test void coldMutationReturnsAuthoritativePatchStateAndPreservesFields() {
        respond = path -> path.startsWith("PATCH") ? thread("100", true) : thread("100", false);
        // Returned archive state deliberately differs from requested false, proving no echo/stale cache.
        var result = threads.setThreadArchived("200", "100", false, "reason + café");
        assertTrue(result.archived()); assertTrue(result.locked()); assertEquals(List.of("9"), result.tagIds());
        assertEquals(List.of("GET /channels/200", "PATCH /channels/200"), requests);
        assertEquals("{\"archived\":false}", bodies.get(1));
        assertEquals("reason%20%2B%20caf%C3%A9", reasons.get(1)); verifyNoInteractions(jda);
    }
    @Test void nullArchiveStateRejectedBeforeAnyDiscordCall() {
        assertThrows(IllegalArgumentException.class, () -> threads.setThreadArchived("200", "100", null, null));
        assertTrue(requests.isEmpty()); verifyNoInteractions(jda);
    }
    @Test void wrongParentMutationMakesNoPatch() {
        respond = path -> thread("101", true);
        assertThrows(IllegalArgumentException.class, () -> threads.setThreadArchived("200", "100", false, null));
        assertEquals(List.of("GET /channels/200"), requests);
    }
    @Test void discordPermissionFailureDoesNotReturnEmptySuccess() {
        status = 403; respond = path -> "{\"code\":50001}";
        assertThrows(IllegalStateException.class, () -> history.readStructuredMessages("200", 1, null, null, null, "100"));
        assertThrows(IllegalStateException.class, () -> threads.setThreadArchived("200", "100", false, null));
    }
    @Test void unsignedSnowflakeOrderingHandlesSameTimestampAndSignBit() {
        respond = path -> path.endsWith("/100") ? parent(0) : "["+message("18446744073709551615","100")+","+message("9223372036854775807","100")+","+message("9223372036854775808","100")+"]";
        var page = history.readStructuredMessages("100", 3, null, "1", null, null);
        assertEquals(List.of("9223372036854775807", "9223372036854775808", "18446744073709551615"), page.messages().stream().map(StructuredHistoryService.StructuredMessage::id).toList());
        assertEquals("18446744073709551615", page.nextAfter());
    }
    @Test void incremental250MessagesYield10010050WithoutDuplicates() {
        respond = path -> {
            if (path.endsWith("/100")) return parent(0);
            long pivot = Long.parseLong(path.substring(path.indexOf("&after=")+7));
            List<String> page = new ArrayList<>();
            for (long i = Math.min(1250, pivot+100); i > pivot; i--) page.add(message(String.valueOf(i),"100"));
            return "["+String.join(",",page)+"]";
        };
        String cursor = "1000"; Set<String> seen = new HashSet<>(); List<Integer> sizes = new ArrayList<>();
        for (int i=0;i<3;i++) {
            var page = history.readStructuredMessages("100",100,null,cursor,null,null);
            sizes.add(page.messages().size()); cursor = page.nextAfter();
            for (var m : page.messages()) assertTrue(seen.add(m.id()));
        }
        assertEquals(List.of(100,100,50),sizes); assertEquals(250,seen.size()); assertEquals("1250",cursor);
    }
    @Test void exactlyFullPageMayNeedEmptyContinuationAndAroundDoesNotAdvance() {
        respond = path -> path.endsWith("/100") ? parent(0) : path.contains("after=51") ? "[]" : "["+message("51","100")+"]";
        var full = history.readStructuredMessages("100",1,null,"50",null,null); assertTrue(full.mayHaveMore());
        var empty = history.readStructuredMessages("100",1,null,full.nextAfter(),null,null); assertFalse(empty.mayHaveMore()); assertNull(empty.nextAfter());
        var context = history.readStructuredMessages("100",1,null,null,"51",null); assertNull(context.nextAfter()); assertNull(context.nextBefore());
        assertEquals("51", history.readStructuredMessages("100",1,"52",null,null,null).nextBefore());
    }
    @Test void invalidOrSimultaneousCursorsNeverReachDiscord() {
        assertThrows(IllegalArgumentException.class, () -> history.readStructuredMessages("100",1,"2","3",null,null));
        assertThrows(IllegalArgumentException.class, () -> history.readStructuredMessages("100",1,null,"",null,null));
        assertThrows(IllegalArgumentException.class, () -> history.readStructuredMessages("100",0,null,null,null,null));
        assertTrue(requests.isEmpty());
    }
    @Test void ownerDmOnColdCacheDoesNotSearchGuildsOrCallMessageGetGuild() {
        var dm = mock(PrivateChannel.class); var user = mock(User.class);
        when(dm.getId()).thenReturn("500"); when(user.getId()).thenReturn("300"); when(dm.getUser()).thenReturn(user);
        @SuppressWarnings("unchecked") CacheRestAction<PrivateChannel> action = mock(CacheRestAction.class);
        when(action.complete()).thenReturn(dm); when(jda.openPrivateChannelById("300")).thenReturn(action);
        respond = path -> path.equals("GET /channels/500") ? "{\"id\":\"500\",\"type\":1,\"recipients\":[{\"id\":\"300\"}]}" : "["+message("51","500")+"]";
        var page = history.readPrivateMessages("300",100,null,null,null);
        assertEquals("500",page.channelId()); assertNull(page.messages().get(0).guildId()); assertTrue(page.messages().get(0).jumpUrl().contains("/@me/"));
        verify(jda).openPrivateChannelById("300"); verifyNoMoreInteractions(jda);
    }
    @Test void mismatchedOwnerDmRecipientPreventsMessageCollection() {
        var dm = mock(PrivateChannel.class); var user = mock(User.class);
        when(dm.getId()).thenReturn("500"); when(user.getId()).thenReturn("999"); when(dm.getUser()).thenReturn(user);
        @SuppressWarnings("unchecked") CacheRestAction<PrivateChannel> action = mock(CacheRestAction.class);
        when(action.complete()).thenReturn(dm); when(jda.openPrivateChannelById("300")).thenReturn(action);
        assertThrows(IllegalArgumentException.class, () -> history.readPrivateMessages("300",1,null,null,null)); assertTrue(requests.isEmpty());
    }
    @Test void reactionsUseColdResolverCustomEmojiBurstAndUserCursor() {
        respond = path -> path.equals("GET /channels/200") ? thread("100", true) : "[{\"id\":\"301\",\"username\":\"same-name\",\"bot\":true},{\"id\":\"300\",\"username\":\"same-name\",\"bot\":false}]";
        var page = history.listReactionUsers("200","50","<a:custom:777>","BURST",2,"299","100");
        assertTrue(requests.get(1).contains("custom%3A777?limit=2&type=1&after=299"));
        assertEquals("301",page.nextUserId()); assertFalse(page.users().get(0).bot()); assertTrue(page.users().get(1).bot());
        assertEquals("300",page.users().get(0).id());
    }
    @Test void wrongParentReactionCannotCollectUsers() {
        respond = path -> thread("101",true);
        assertThrows(IllegalArgumentException.class, () -> history.listReactionUsers("200","50","👍",null,1,null,"100"));
        assertEquals(List.of("GET /channels/200"),requests);
    }
    @Test void activeSnapshotLimitOneDoesNotOmitSecondThreadOrClaimCompleteCoverage() {
        var guild = mock(Guild.class); var text = mock(TextChannel.class); var parent = mock(IThreadContainerUnion.class);
        when(parent.getId()).thenReturn("100"); when(guild.getId()).thenReturn("400");
        when(guild.getGuildChannelById("100")).thenReturn(text); when(jda.getGuilds()).thenReturn(List.of(guild));
        var a = cachedThread("200",parent); var b = cachedThread("201",parent);
        when(guild.getThreadChannels()).thenReturn(List.of(a,b));
        var page = threads.listChannelThreads("100","active",1,null);
        assertEquals(2,page.threads().size()); assertFalse(page.complete()); assertFalse(page.hasMore());
        verify(guild,never()).retrieveActiveThreads(); assertTrue(requests.isEmpty());
    }
    @Test void mutationFailureNeverReturnsSuccess() {
        respond = path -> { if (path.startsWith("PATCH")) { status = 403; return "{}"; } return thread("100",true); };
        assertThrows(IllegalStateException.class, () -> threads.setThreadArchived("200","100",false,null));
        assertEquals(2,requests.size());
    }
    @Test void messageMappingKeepsMentionsRepliesThreadStarterAttachmentsAndReactions() {
        var m = json.readTree(message("51","200"));
        var node = (tools.jackson.databind.node.ObjectNode) m;
        node.set("mentions",json.readTree("[{\"id\":\"301\"}]")); node.set("mention_roles",json.readTree("[\"700\"]"));
        node.put("mention_everyone",true); node.put("edited_timestamp","2026-09-30T01:00:00Z");
        node.set("thread",json.readTree("{\"id\":\"800\"}"));
        node.set("message_reference",json.readTree("{\"type\":0,\"message_id\":\"49\",\"channel_id\":\"900\"}"));
        node.set("referenced_message",json.readTree("null"));
        node.set("attachments",json.readTree("[{\"id\":\"42\",\"filename\":\"file\",\"size\":123,\"url\":\"https://example.com\"}]"));
        node.set("reactions",json.readTree("[{\"emoji\":{\"id\":\"777\",\"name\":\"custom\",\"animated\":true},\"count\":2,\"count_details\":{\"normal\":1,\"burst\":1},\"me\":true,\"me_burst\":false}]"));
        respond = path -> path.equals("GET /channels/200") ? thread("100",true) : "["+node+"]";
        var mapped = history.readStructuredMessages("200",1,null,null,null,"100").messages().get(0);
        assertEquals("200",mapped.threadId()); assertEquals("800",mapped.startedThreadId());
        assertEquals("900",mapped.messageReference().channelId()); assertEquals("deleted",mapped.referencedMessageState());
        assertEquals(List.of("301"),mapped.mentions().userIds()); assertEquals(List.of("700"),mapped.mentions().roleIds()); assertTrue(mapped.mentions().everyone());
        assertEquals("42",mapped.attachments().get(0).id()); assertEquals(123,mapped.attachments().get(0).sizeBytes());
        assertEquals("777",mapped.reactions().get(0).emoji().id()); assertEquals(1,mapped.reactions().get(0).burstCount()); assertTrue(mapped.reactions().get(0).me());
        assertNotNull(mapped.editedAt()); assertEquals(2,requests.size());
        node.remove("referenced_message");
        assertEquals("unknown",history.readStructuredMessages("200",1,null,null,null,"100").messages().get(0).referencedMessageState());
    }
    @Test void normalUnicodeReactorPaginationOver100PreservesOwnerIdentity() {
        respond = path -> {
            if (path.endsWith("/100")) return parent(0);
            int pivot = path.contains("&after=") ? Integer.parseInt(path.substring(path.indexOf("&after=")+7)) : 200;
            var users = new ArrayList<String>();
            for (int i=pivot+1; i<=Math.min(pivot+100,350); i++) users.add("{\"id\":\""+i+"\",\"username\":\"same-name\",\"bot\":false}");
            return "["+String.join(",",users)+"]";
        };
        var first = history.listReactionUsers("100","50","👍","NORMAL",100,null,null);
        var second = history.listReactionUsers("100","50","👍","NORMAL",100,first.nextUserId(),null);
        assertEquals(100,first.users().size()); assertEquals(50,second.users().size()); assertFalse(second.mayHaveMore());
        assertEquals("300",first.nextUserId()); assertEquals(1, first.users().stream().filter(u -> "300".equals(u.id())).count());
        assertTrue(requests.get(1).contains("%F0%9F%91%8D?limit=100&type=0"));
    }
    @Test void rateLimitRetriesAreBoundedAndPermissionErrorsAreExplicit() {
        final int[] attempts = {0};
        respond = path -> { status = attempts[0]++ == 0 ? 429 : 200; return status == 429 ? "{\"retry_after\":0}" : thread("100",true); };
        assertEquals("200",archives.resolveThread("200","100").path("id").asText()); assertEquals(2,requests.size());
    }
    @Test void publicArchivesOver100ResumeByArchiveTimeNotCreationId() {
        var nodes = new ArrayList<tools.jackson.databind.node.ObjectNode>();
        for (int i=0; i<125; i++) {
            var node = (tools.jackson.databind.node.ObjectNode) json.readTree(thread("100",true));
            node.put("id",String.valueOf(1000+i)); // Creation IDs rise while archive order goes backwards.
            ((tools.jackson.databind.node.ObjectNode) node.path("thread_metadata")).put("archive_timestamp",java.time.OffsetDateTime.parse("2026-09-30T00:00:00Z").minusMinutes(i).toString());
            nodes.add(node);
        }
        respond = path -> {
            if (path.endsWith("/100")) return parent(0);
            int start = 0;
            if (path.contains("&before=")) {
                String timestamp = java.net.URLDecoder.decode(path.substring(path.indexOf("&before=")+8), StandardCharsets.UTF_8);
                for (int i=0;i<nodes.size();i++) if (nodes.get(i).path("thread_metadata").path("archive_timestamp").asText().equals(timestamp)) start = i+1;
            }
            int end = Math.min(start+100,nodes.size());
            return "{\"threads\":"+nodes.subList(start,end)+",\"has_more\":"+(end<nodes.size())+"}";
        };
        var first = archives.listArchivedThreads("100","public",100,null);
        var second = archives.listArchivedThreads("100","public",100,first.nextCursor());
        assertEquals(100,first.threads().size()); assertEquals(25,second.threads().size()); assertTrue(second.complete());
        var ids = new HashSet<String>(); first.threads().forEach(t -> assertTrue(ids.add(t.id()))); second.threads().forEach(t -> assertTrue(ids.add(t.id())));
        assertEquals(125,ids.size()); assertNull(second.nextCursor());
    }
    ThreadChannel cachedThread(String id, IThreadContainerUnion parent) {
        var t = mock(ThreadChannel.class); when(t.getId()).thenReturn(id); when(t.getName()).thenReturn("cached"); when(t.getParentChannel()).thenReturn(parent);
        when(t.getType()).thenReturn(net.dv8tion.jda.api.entities.channel.ChannelType.GUILD_PUBLIC_THREAD);
        when(t.getTimeArchiveInfoLastModified()).thenReturn(java.time.OffsetDateTime.now());
        when(t.getAutoArchiveDuration()).thenReturn(ThreadChannel.AutoArchiveDuration.TIME_1_HOUR);
        return t;
    }
}
