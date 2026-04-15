package com.LucaStudios.HytaleDungeons.Config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Loads lobby spawn coordinates from {@code config/config.json}.
 * Thread-safe after initialization (immutable after load).
 */
public final class LobbyConfig {

    public record LobbySpawn(float x, float y, float z, float yaw, float pitch, float roll) {}

    private static LobbyConfig instance;

    private final LobbySpawn lobbySpawn;
    private final String weather;

    private LobbyConfig(LobbySpawn lobbySpawn, String weather) {
        this.lobbySpawn = lobbySpawn;
        this.weather = weather;
    }

    public static LobbyConfig getInstance() {
        return instance;
    }

    public LobbySpawn getLobbySpawn() {
        return lobbySpawn;
    }

    /** Weather ID to lock, e.g. {@code "Zone1_Sunny"}. Never null. */
    public String getWeather() {
        return weather;
    }

    public static void load(Consumer<String> logger) {
        try (InputStream is = LobbyConfig.class.getClassLoader()
                .getResourceAsStream("config/config.json")) {
            if (is == null) {
                logger.accept("WARNING: config/config.json not found — using fallback lobby spawn");
                instance = createFallback();
                return;
            }

            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();

            JsonObject spawn = root.getAsJsonObject("lobbySpawn");
            float x     = spawn.get("x").getAsFloat();
            float y     = spawn.get("y").getAsFloat();
            float z     = spawn.get("z").getAsFloat();
            float yaw   = spawn.get("yaw").getAsFloat();
            float pitch = spawn.get("pitch").getAsFloat();
            float roll  = spawn.get("roll").getAsFloat();

            String weather = root.has("weather") ? root.get("weather").getAsString() : "";

            instance = new LobbyConfig(new LobbySpawn(x, y, z, yaw, pitch, roll), weather);
            logger.accept(String.format(
                    "Loaded lobby spawn from config.json: (%.1f, %.1f, %.1f) yaw=%.1f pitch=%.1f roll=%.1f weather=%s",
                    x, y, z, yaw, pitch, roll, weather));

        } catch (Exception e) {
            logger.accept("ERROR loading config.json: " + e.getMessage() + " — using fallback lobby spawn");
            instance = createFallback();
        }
    }

    private static LobbyConfig createFallback() {
        return new LobbyConfig(new LobbySpawn(0f, 64f, 0f, 0f, 0f, 0f), "");
    }
}
