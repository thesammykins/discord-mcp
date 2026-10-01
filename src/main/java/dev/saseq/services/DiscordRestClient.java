package dev.saseq.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Small public-API adapter; serializes calls and honors bounded Discord 429 retries. */
@Component
public class DiscordRestClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String token;
    private final String base;
    public DiscordRestClient(@Value("${DISCORD_TOKEN:}") String token,
                             @Value("${DISCORD_API_BASE:https://discord.com/api/v10}") String base) {
        this.token = token; this.base = base;
    }
    public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    public JsonNode get(String path) { return request("GET", path, null, null); }
    public JsonNode patch(String path, JsonNode body, String reason) { return request("PATCH", path, body, reason); }
    public synchronized JsonNode request(String method, String path, JsonNode body, String reason) {
        if (token.isBlank()) throw new IllegalStateException("DISCORD_TOKEN is required");
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bot " + token).header("Accept", "application/json");
                if (reason != null && !reason.isBlank()) builder.header("X-Audit-Log-Reason", encode(reason));
                if (body != null) builder.header("Content-Type", "application/json");
                builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
                var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429 && attempt < 2) {
                    double seconds = json.readTree(response.body()).path("retry_after").asDouble(1);
                    if (seconds < 0 || seconds > 5) throw new IllegalStateException("Discord rate limited; retry later");
                    Thread.sleep((long) Math.ceil(seconds * 1000)); continue;
                }
                if (response.statusCode() / 100 != 2) throw new IllegalStateException("Discord " + method + " failed: HTTP " + response.statusCode());
                return json.readTree(response.body());
            }
            throw new IllegalStateException("Discord rate limited");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Discord request interrupted", e); }
        catch (java.io.IOException e) { throw new IllegalStateException("Discord transport failed", e); }
    }
}
