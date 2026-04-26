package com.LucaStudios.HytaleDungeons.UI;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public final class LobbyConfig {

    private static final String CONFIG_PATH = "config/lobby.json";

    private static LobbyConfig instance;

    private final float spawnX;
    private final float spawnY;
    private final float spawnZ;
    private final float spawnYaw;
    private final float spawnPitch;
    private final String weather;

    private LobbyConfig(float x, float y, float z, float yaw, float pitch, String weather) {
        this.spawnX = x;
        this.spawnY = y;
        this.spawnZ = z;
        this.spawnYaw = yaw;
        this.spawnPitch = pitch;
        this.weather = weather;
    }

    public static LobbyConfig getInstance() {
        return instance;
    }

    public float getSpawnX() { return spawnX; }
    public float getSpawnY() { return spawnY; }
    public float getSpawnZ() { return spawnZ; }
    public float getSpawnYaw() { return spawnYaw; }
    public float getSpawnPitch() { return spawnPitch; }
    public String getWeather() { return weather; }

    public static void load(Consumer<String> logger) {
        try (InputStream is = LobbyConfig.class.getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (is == null) {
                logger.accept("WARNING: " + CONFIG_PATH + " not found — using fallback lobby config");
                instance = createFallback();
                return;
            }

            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject spawn = root.getAsJsonObject("spawn");

            float x = spawn.get("x").getAsFloat();
            float y = spawn.get("y").getAsFloat();
            float z = spawn.get("z").getAsFloat();
            float yaw = spawn.has("yaw") ? spawn.get("yaw").getAsFloat() : 0f;
            float pitch = spawn.has("pitch") ? spawn.get("pitch").getAsFloat() : 0f;
            String weather = root.has("weather") && !root.get("weather").isJsonNull()
                    ? root.get("weather").getAsString()
                    : null;

            instance = new LobbyConfig(x, y, z, yaw, pitch, weather);
        } catch (Exception e) {
            logger.accept("ERROR loading " + CONFIG_PATH + ": " + e.getMessage() + " — using fallback");
            instance = createFallback();
        }
    }

    private static LobbyConfig createFallback() {
        return new LobbyConfig(30f, 122f, 47f, 0f, 0f, null);
    }
}
