package com.LucaStudios.HytaleDungeons.FloorGen;

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
import java.util.Random;
import java.util.function.Consumer;

/**
 * Loads and indexes room templates from JSON config.
 * Thread-safe after initialization (immutable after load).
 */
public final class RoomTemplateLibrary {

    private static RoomTemplateLibrary instance;

    private final List<RoomTemplate> spawnRooms;
    private final List<RoomTemplate> combatRooms;
    private final List<RoomTemplate> exitRooms;

    private RoomTemplateLibrary(List<RoomTemplate> spawnRooms,
                                 List<RoomTemplate> combatRooms,
                                 List<RoomTemplate> exitRooms) {
        this.spawnRooms = Collections.unmodifiableList(spawnRooms);
        this.combatRooms = Collections.unmodifiableList(combatRooms);
        this.exitRooms = Collections.unmodifiableList(exitRooms);
    }

    public static RoomTemplateLibrary getInstance() {
        return instance;
    }

    /**
     * Load room templates from config/rooms.json on the classpath.
     */
    public static void load(Consumer<String> logger) {
        List<RoomTemplate> spawn = new ArrayList<>();
        List<RoomTemplate> combat = new ArrayList<>();
        List<RoomTemplate> exit = new ArrayList<>();

        try (InputStream is = RoomTemplateLibrary.class.getClassLoader()
                .getResourceAsStream("config/rooms.json")) {
            if (is == null) {
                logger.accept("WARNING: config/rooms.json not found — using fallback templates");
                instance = createFallback();
                return;
            }

            JsonArray rooms = JsonParser.parseReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonArray();

            for (JsonElement element : rooms) {
                JsonObject obj = element.getAsJsonObject();
                RoomTemplate template = parseTemplate(obj);
                switch (template.type()) {
                    case SPAWN -> spawn.add(template);
                    case COMBAT -> combat.add(template);
                    case EXIT -> exit.add(template);
                }
            }

            logger.accept("Loaded " + (spawn.size() + combat.size() + exit.size())
                    + " room templates (" + spawn.size() + " spawn, "
                    + combat.size() + " combat, " + exit.size() + " exit)");
        } catch (Exception e) {
            logger.accept("ERROR loading rooms.json: " + e.getMessage() + " — using fallback");
            instance = createFallback();
            return;
        }

        if (spawn.isEmpty() || combat.isEmpty() || exit.isEmpty()) {
            logger.accept("WARNING: Missing room types in rooms.json — merging with fallback");
            RoomTemplateLibrary fallback = createFallback();
            if (spawn.isEmpty()) spawn.addAll(fallback.spawnRooms);
            if (combat.isEmpty()) combat.addAll(fallback.combatRooms);
            if (exit.isEmpty()) exit.addAll(fallback.exitRooms);
        }

        instance = new RoomTemplateLibrary(spawn, combat, exit);
    }

    private static RoomTemplate parseTemplate(JsonObject obj) {
        String id = obj.get("id").getAsString();
        RoomType type = RoomType.fromString(obj.get("type").getAsString());

        JsonObject size = obj.getAsJsonObject("size");
        int sizeX = size.get("x").getAsInt();
        int sizeY = size.get("y").getAsInt();
        int sizeZ = size.get("z").getAsInt();

        JsonObject doors = obj.getAsJsonObject("doors");
        JsonObject entry = doors.getAsJsonObject("entry");
        JsonObject exitDoor = doors.getAsJsonObject("exit");

        int entryX = entry.get("x").getAsInt();
        int entryY = entry.get("y").getAsInt();
        int entryZ = entry.get("z").getAsInt();
        int exitX = exitDoor.get("x").getAsInt();
        int exitY = exitDoor.get("y").getAsInt();
        int exitZ = exitDoor.get("z").getAsInt();

        // Optional spawnPoint (required for spawn rooms)
        int spawnPointX = 0, spawnPointY = 0, spawnPointZ = 0;
        if (obj.has("spawnPoint")) {
            JsonObject sp = obj.getAsJsonObject("spawnPoint");
            spawnPointX = sp.get("x").getAsInt();
            spawnPointY = sp.get("y").getAsInt();
            spawnPointZ = sp.get("z").getAsInt();
        }

        // Optional exitZone (required for exit rooms)
        int exitZoneX = 0, exitZoneZ = 0, exitZoneWidth = 0, exitZoneDepth = 0;
        if (obj.has("exitZone")) {
            JsonObject ez = obj.getAsJsonObject("exitZone");
            exitZoneX = ez.get("x").getAsInt();
            exitZoneZ = ez.get("z").getAsInt();
            exitZoneWidth = ez.get("width").getAsInt();
            exitZoneDepth = ez.get("depth").getAsInt();
        }

        // Mob spawns
        List<int[]> mobSpawns = new ArrayList<>();
        if (obj.has("mobSpawns")) {
            for (JsonElement ms : obj.getAsJsonArray("mobSpawns")) {
                JsonObject pos = ms.getAsJsonObject();
                mobSpawns.add(new int[]{
                        pos.get("x").getAsInt(),
                        pos.get("y").getAsInt(),
                        pos.get("z").getAsInt()
                });
            }
        }

        int minFloor = obj.has("minFloor") ? obj.get("minFloor").getAsInt() : 1;
        int maxFloor = obj.has("maxFloor") ? obj.get("maxFloor").getAsInt() : 99;

        List<String> tags = new ArrayList<>();
        if (obj.has("tags")) {
            for (JsonElement tag : obj.getAsJsonArray("tags")) {
                tags.add(tag.getAsString());
            }
        }

        return new RoomTemplate(id, type,
                sizeX, sizeY, sizeZ,
                entryX, entryY, entryZ,
                exitX, exitY, exitZ,
                spawnPointX, spawnPointY, spawnPointZ,
                exitZoneX, exitZoneZ, exitZoneWidth, exitZoneDepth,
                Collections.unmodifiableList(mobSpawns),
                minFloor, maxFloor,
                Collections.unmodifiableList(tags));
    }

    /**
     * Hardcoded fallback templates for when config is missing.
     */
    private static RoomTemplateLibrary createFallback() {
        List<RoomTemplate> spawn = List.of(new RoomTemplate(
                "fallback_spawn", RoomType.SPAWN,
                16, 8, 16,
                8, 0, 0,
                8, 0, 15,
                8, 0, 8,
                0, 0, 0, 0,
                List.of(),
                1, 99, List.of()
        ));

        List<RoomTemplate> combat = List.of(new RoomTemplate(
                "fallback_combat", RoomType.COMBAT,
                20, 8, 20,
                10, 0, 0,
                10, 0, 19,
                0, 0, 0,
                0, 0, 0, 0,
                List.of(new int[]{5, 0, 10}, new int[]{15, 0, 10}, new int[]{10, 0, 15}),
                1, 99, List.of()
        ));

        List<RoomTemplate> exit = List.of(new RoomTemplate(
                "fallback_exit", RoomType.EXIT,
                16, 8, 16,
                8, 0, 0,
                8, 0, 15,
                0, 0, 0,
                6, 13, 4, 2,
                List.of(new int[]{5, 0, 8}, new int[]{11, 0, 8}),
                1, 99, List.of()
        ));

        return new RoomTemplateLibrary(spawn, combat, exit);
    }

    // ── Query Methods ───────────────────────────────────────────────────

    /**
     * Pick a random spawn room.
     */
    public RoomTemplate randomSpawnRoom(Random random) {
        return spawnRooms.get(random.nextInt(spawnRooms.size()));
    }

    /**
     * Pick a random combat room eligible for the given floor.
     * Falls back to any combat room if none match the floor filter.
     */
    public RoomTemplate randomCombatRoom(Random random, int floorNumber) {
        List<RoomTemplate> eligible = combatRooms.stream()
                .filter(r -> r.isEligibleForFloor(floorNumber))
                .toList();
        if (eligible.isEmpty()) {
            return combatRooms.get(random.nextInt(combatRooms.size()));
        }
        return eligible.get(random.nextInt(eligible.size()));
    }

    /**
     * Pick a random exit room.
     */
    public RoomTemplate randomExitRoom(Random random) {
        return exitRooms.get(random.nextInt(exitRooms.size()));
    }

    public int spawnRoomCount() { return spawnRooms.size(); }
    public int combatRoomCount() { return combatRooms.size(); }
    public int exitRoomCount() { return exitRooms.size(); }
    public int totalCount() { return spawnRooms.size() + combatRooms.size() + exitRooms.size(); }
}
