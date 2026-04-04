package com.LucaStudios.HytaleDungeons.Loot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Immutable item database loaded from config/items.json at startup.
 * Thread-safe for reads from any context after initialization.
 */
public final class ItemDatabase {

    public static final double DEFAULT_LEVEL_SCALE_FACTOR = 0.1;
    private static final String CONFIG_PATH = "config/items.json";

    private static ItemDatabase instance;

    private final Map<String, ItemDefinition> itemsById;
    private final Map<String, ItemDefinition> itemsByHytaleId;
    private final Map<ItemCategory, List<ItemDefinition>> itemsByCategory;

    private ItemDatabase(List<ItemDefinition> items) {
        Map<String, ItemDefinition> byId = new LinkedHashMap<>();
        Map<String, ItemDefinition> byHytaleId = new LinkedHashMap<>();
        Map<ItemCategory, List<ItemDefinition>> byCategory = new LinkedHashMap<>();

        for (ItemCategory cat : ItemCategory.values()) {
            byCategory.put(cat, new ArrayList<>());
        }

        for (ItemDefinition item : items) {
            byId.put(item.getId(), item);
            if (!item.getHytaleItemId().isEmpty()) {
                byHytaleId.put(item.getHytaleItemId(), item);
            }
            byCategory.get(item.getCategory()).add(item);
        }

        // Make everything unmodifiable
        this.itemsById = Collections.unmodifiableMap(byId);
        this.itemsByHytaleId = Collections.unmodifiableMap(byHytaleId);
        Map<ItemCategory, List<ItemDefinition>> unmodCat = new LinkedHashMap<>();
        for (var entry : byCategory.entrySet()) {
            unmodCat.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        this.itemsByCategory = Collections.unmodifiableMap(unmodCat);
    }

    /**
     * Loads the item database from the classpath config file.
     * Call once at plugin startup.
     */
    public static void load(Consumer<String> logger) {
        List<ItemDefinition> items = loadFromClasspath(logger);
        if (items.isEmpty()) {
            logger.accept("No items loaded — using hardcoded fallback set");
            items = createFallbackItems();
        }

        // Check for duplicate IDs
        var seen = new java.util.HashSet<String>();
        for (ItemDefinition item : items) {
            if (!seen.add(item.getId())) {
                logger.accept("Duplicate item ID: " + item.getId() + " — last definition wins");
            }
        }

        instance = new ItemDatabase(items);
        logger.accept("ItemDatabase loaded: " + instance.itemsById.size() + " items");
    }

    public static ItemDatabase getInstance() {
        if (instance == null) {
            throw new IllegalStateException("ItemDatabase not loaded — call ItemDatabase.load() first");
        }
        return instance;
    }

    /**
     * Get an item by its unique ID. Returns FISTS if not found.
     */
    public ItemDefinition get(String id) {
        return itemsById.getOrDefault(id, ItemDefinition.FISTS);
    }

    /**
     * Get an item by its Hytale item ID (e.g., "Weapon_Sword_Iron"). Returns FISTS if not found.
     */
    public ItemDefinition getByHytaleId(String hytaleItemId) {
        return itemsByHytaleId.getOrDefault(hytaleItemId, ItemDefinition.FISTS);
    }

    /**
     * Get all items in a category.
     */
    public List<ItemDefinition> getByCategory(ItemCategory category) {
        return itemsByCategory.getOrDefault(category, Collections.emptyList());
    }

    /**
     * Get all items matching a category and rarity.
     */
    public List<ItemDefinition> getByCategoryAndRarity(ItemCategory category, Rarity rarity) {
        return getByCategory(category).stream()
                .filter(item -> item.getRarity() == rarity)
                .collect(Collectors.toList());
    }

    /**
     * Select a random item from a category using rarity-weighted drop chances.
     * First rolls a rarity based on drop weights, then picks a random item of that rarity.
     * If no items exist for the rolled rarity, falls back to any item in the category.
     */
    public ItemDefinition getRandomForCategory(ItemCategory category) {
        List<ItemDefinition> pool = getByCategory(category);
        if (pool.isEmpty()) {
            return ItemDefinition.FISTS;
        }

        Rarity rolledRarity = rollRarity();
        List<ItemDefinition> rarityPool = pool.stream()
                .filter(item -> item.getRarity() == rolledRarity)
                .collect(Collectors.toList());

        if (rarityPool.isEmpty()) {
            return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        }
        return rarityPool.get(ThreadLocalRandom.current().nextInt(rarityPool.size()));
    }

    /**
     * Get the total number of items in the database.
     */
    public int size() {
        return itemsById.size();
    }

    private static Rarity rollRarity() {
        Rarity[] rarities = Rarity.values();
        int totalWeight = 0;
        for (Rarity r : rarities) {
            totalWeight += r.getDropWeight();
        }

        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        for (Rarity r : rarities) {
            roll -= r.getDropWeight();
            if (roll < 0) {
                return r;
            }
        }
        return Rarity.COMMON;
    }

    private static List<ItemDefinition> loadFromClasspath(Consumer<String> logger) {
        try (InputStream is = ItemDatabase.class.getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            if (is == null) {
                logger.accept("Config file not found on classpath: " + CONFIG_PATH);
                return Collections.emptyList();
            }
            return parseItems(new InputStreamReader(is, StandardCharsets.UTF_8), logger);
        } catch (IOException e) {
            logger.accept("Failed to read item config: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<ItemDefinition> parseItems(InputStreamReader reader, Consumer<String> logger) {
        List<ItemDefinition> items = new ArrayList<>();
        JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();

        for (JsonElement element : array) {
            try {
                JsonObject obj = element.getAsJsonObject();
                String id = obj.get("id").getAsString();
                ItemCategory category = ItemCategory.valueOf(obj.get("category").getAsString().toUpperCase());
                String displayName = obj.get("displayName").getAsString();
                String hytaleItemId = obj.get("hytaleItemId").getAsString();
                int baseStat = obj.get("baseStat").getAsInt();
                Rarity rarity = Rarity.fromString(obj.get("rarity").getAsString());
                String description = obj.has("description") ? obj.get("description").getAsString() : "";
                int cooldownMs = obj.has("cooldownMs") ? obj.get("cooldownMs").getAsInt() : getDefaultCooldown(category);

                items.add(new ItemDefinition(id, category, displayName, hytaleItemId, baseStat, rarity, description, cooldownMs));
            } catch (Exception e) {
                logger.accept("Failed to parse item entry: " + e.getMessage());
            }
        }
        return items;
    }

    private static int getDefaultCooldown(ItemCategory category) {
        return switch (category) {
            case WEAPON -> 400;
            case CROSSBOW -> 600;
            case ARMOR -> 0;
        };
    }

    static List<ItemDefinition> createFallbackItems() {
        List<ItemDefinition> items = new ArrayList<>();
        items.add(new ItemDefinition("iron_sword", ItemCategory.WEAPON, "Iron Sword",
                "Weapon_Sword_Iron", 8, Rarity.COMMON, "A reliable iron blade.", 400));
        items.add(new ItemDefinition("iron_armor", ItemCategory.ARMOR, "Iron Armor",
                "Armor_Iron", 6, Rarity.COMMON, "Sturdy iron protection.", 0));
        items.add(new ItemDefinition("iron_crossbow", ItemCategory.CROSSBOW, "Iron Crossbow",
                "Weapon_Crossbow_Iron", 9, Rarity.COMMON, "A dependable crossbow.", 600));
        return items;
    }
}
