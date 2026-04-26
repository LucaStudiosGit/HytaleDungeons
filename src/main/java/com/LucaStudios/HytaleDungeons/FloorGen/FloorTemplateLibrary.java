package com.LucaStudios.HytaleDungeons.FloorGen;

import com.LucaStudios.HytaleDungeons.Enemies.SpawnGroup;
import com.LucaStudios.HytaleDungeons.Enemies.SpawnZone;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public final class FloorTemplateLibrary {

    private static FloorTemplateLibrary instance;

    private final List<FloorTemplate> floors;

    private FloorTemplateLibrary(List<FloorTemplate> floors) {
        this.floors = Collections.unmodifiableList(floors);
    }

    public static FloorTemplateLibrary getInstance() {
        return instance;
    }

    public static void load(Consumer<String> logger) {
        List<FloorTemplate> floors = new ArrayList<>();

        try (InputStream is = FloorTemplateLibrary.class.getClassLoader()
                .getResourceAsStream("config/floors.json")) {
            if (is == null) {
                logger.accept("WARNING: config/floors.json not found — using fallback templates");
                instance = createFallback();
                return;
            }

            JsonArray jsonFloors = JsonParser.parseReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonArray();

            for (JsonElement element : jsonFloors) {
                floors.add(parseTemplate(element.getAsJsonObject()));
            }
        } catch (Exception e) {
            logger.accept("ERROR loading floors.json: " + e.getMessage() + " — using fallback");
            instance = createFallback();
            return;
        }

        instance = new FloorTemplateLibrary(floors);
    }

    private static FloorTemplate parseTemplate(JsonObject obj) {
        int floorNumber = obj.get("floorNumber").getAsInt();
        JsonObject spawn = obj.getAsJsonObject("playerSpawnPoint");
        float spawnX = spawn.get("x").getAsFloat();
        float spawnY = spawn.get("y").getAsFloat();
        float spawnZ = spawn.get("z").getAsFloat();

        if (!obj.has("fallY")) {
            throw new IllegalArgumentException("Floor " + floorNumber + " is missing required 'fallY'");
        }
        int fallY = obj.get("fallY").getAsInt();

        List<SpawnGroup> spawnGroups = new ArrayList<>();
        JsonArray groupsJson = obj.getAsJsonArray("spawnGroups");
        if (groupsJson != null) {
            for (JsonElement el : groupsJson) {
                spawnGroups.add(parseSpawnGroup(el.getAsJsonObject()));
            }
        }

        return new FloorTemplate(floorNumber, spawnX, spawnY, spawnZ,
                0, 0, 0, 0, 0, fallY, Collections.unmodifiableList(spawnGroups));
    }

    private static SpawnGroup parseSpawnGroup(JsonObject obj) {
        String id = obj.get("id").getAsString();

        JsonObject trigger = obj.getAsJsonObject("trigger");
        String triggerType = trigger.get("type").getAsString();
        int zPlane = (SpawnGroup.TRIGGER_ZONE.equals(triggerType) && trigger.has("zPlane"))
                ? trigger.get("zPlane").getAsInt()
                : 0;

        List<SpawnZone> zones = new ArrayList<>();
        JsonArray zonesJson = obj.getAsJsonArray("zones");
        if (zonesJson != null) {
            for (JsonElement el : zonesJson) {
                JsonObject z = el.getAsJsonObject();
                zones.add(new SpawnZone(
                        z.get("x0").getAsFloat(), z.get("y0").getAsFloat(), z.get("z0").getAsFloat(),
                        z.get("x1").getAsFloat(), z.get("y1").getAsFloat(), z.get("z1").getAsFloat(),
                        z.get("count").getAsInt()
                ));
            }
        }

        String nextGroupId = (obj.has("nextGroup") && !obj.get("nextGroup").isJsonNull())
                ? obj.get("nextGroup").getAsString()
                : null;

        return new SpawnGroup(id, triggerType, zPlane,
                Collections.unmodifiableList(zones), nextGroupId);
    }

    private static FloorTemplateLibrary createFallback() {
        List<FloorTemplate> floors = List.of(new FloorTemplate(
                1,
                16, 8, 16,
                0, 0, 0, 0, 0,
                Integer.MIN_VALUE,
                List.of()
        ));
        return new FloorTemplateLibrary(floors);
    }

    // ── Query Methods ───────────────────────────────────────────────────

    public int floorCount() { return floors.size(); }

    public FloorTemplate getTemplate(int floorNumber) {
        for (FloorTemplate template : floors) {
            if (template.floorNumber() == floorNumber) return template;
        }
        throw new IllegalArgumentException("No template found for floor " + floorNumber);
    }

    public float[] getFloorSpawnPoint(int floorNumber) {
        FloorTemplate t = getTemplate(floorNumber);
        return new float[]{t.spawnPointX(), t.spawnPointY(), t.spawnPointZ()};
    }
}
