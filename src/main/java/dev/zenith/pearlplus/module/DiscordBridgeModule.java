package dev.zenith.pearlplus.module;

import com.github.rfresh2.EventConsumer;
import com.google.gson.JsonObject;
import com.zenith.cache.data.entity.EntityPlayer;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.event.module.ServerPlayerInVisualRangeEvent;
import com.zenith.event.module.ServerPlayerLeftVisualRangeEvent;
import com.zenith.event.module.ServerPlayerLogoutInVisualRangeEvent;
import com.zenith.module.api.Module;
import dev.zenith.pearlplus.PearlPlusPlugin;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.PLAYER_LISTS;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class DiscordBridgeModule extends Module {
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public boolean enabledSetting() {
        return true;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(WhisperChatEvent.class, this::sendWhisperEvent),
            of(ServerPlayerInVisualRangeEvent.class, event -> sendVisualRangeEvent("enter", event.playerEntry(), event.playerEntity())),
            of(ServerPlayerLeftVisualRangeEvent.class, event -> sendVisualRangeEvent("leave", event.playerEntry(), event.playerEntity())),
            of(ServerPlayerLogoutInVisualRangeEvent.class, event -> sendVisualRangeEvent("logout", event.playerEntry(), event.playerEntity()))
        );
    }

    @Override
    public void onDisable() {
        executor.shutdownNow();
    }

    private void sendVisualRangeEvent(final String action,
                                      final PlayerListEntry playerEntry,
                                      final EntityPlayer playerEntity) {
        if (playerEntry == null || playerEntity == null) {
            return;
        }

        executor.execute(() -> {
            try {
                final String endpoint = discordEventsEndpoint();
                if (endpoint == null) {
                    return;
                }

                final JsonObject payload = new JsonObject();
                payload.addProperty("type", "visual_range");
                payload.addProperty("action", action);
                payload.addProperty("playerName", playerEntry.getName());
                payload.addProperty("playerUuid", String.valueOf(playerEntry.getProfileId()));
                payload.addProperty("friend", PLAYER_LISTS.getFriendsList().contains(playerEntity.getUuid()));
                payload.addProperty("x", playerEntity.getX());
                payload.addProperty("y", playerEntity.getY());
                payload.addProperty("z", playerEntity.getZ());
                payload.addProperty("timestamp", System.currentTimeMillis());

                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json");

                final String apiKey = PLUGIN_CONFIG.scanner.apiKey;
                if (apiKey != null && !apiKey.isEmpty()) {
                    builder.header("X-API-Key", apiKey);
                }

                final HttpResponse<String> response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    PearlPlusPlugin.LOG.warn("Discord bridge visual-range post failed: {} {}", response.statusCode(), response.body());
                }
            } catch (final Exception e) {
                PearlPlusPlugin.LOG.warn("Discord bridge visual-range post failed", e);
            }
        });
    }

    private void sendWhisperEvent(final WhisperChatEvent event) {
        if (event == null || event.sender() == null || event.receiver() == null) {
            return;
        }

        executor.execute(() -> {
            try {
                final String endpoint = discordEventsEndpoint();
                if (endpoint == null) {
                    return;
                }

                final JsonObject payload = new JsonObject();
                payload.addProperty("type", "whisper");
                payload.addProperty("outgoing", event.outgoing());
                payload.addProperty("senderName", event.sender().getName());
                payload.addProperty("senderUuid", String.valueOf(event.sender().getProfileId()));
                payload.addProperty("receiverName", event.receiver().getName());
                payload.addProperty("receiverUuid", String.valueOf(event.receiver().getProfileId()));
                payload.addProperty("message", event.message());
                payload.addProperty("timestamp", System.currentTimeMillis());

                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json");

                final String apiKey = PLUGIN_CONFIG.scanner.apiKey;
                if (apiKey != null && !apiKey.isEmpty()) {
                    builder.header("X-API-Key", apiKey);
                }

                final HttpResponse<String> response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    PearlPlusPlugin.LOG.warn("Discord bridge whisper post failed: {} {}", response.statusCode(), response.body());
                }
            } catch (final Exception e) {
                PearlPlusPlugin.LOG.warn("Discord bridge whisper post failed", e);
            }
        });
    }

    private String discordEventsEndpoint() {
        final String endpoint = PLUGIN_CONFIG.scanner.apiEndpoint;
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        if (endpoint.endsWith("/api/chests")) {
            return endpoint.substring(0, endpoint.length() - "/api/chests".length()) + "/api/discord/events";
        }
        if (endpoint.endsWith("/")) {
            return endpoint + "api/discord/events";
        }
        return endpoint + "/discord/events";
    }
}
