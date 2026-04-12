package com.LucaStudios.HytaleDungeons.UI;

import au.ellie.hyui.builders.HudBuilder;
import au.ellie.hyui.builders.HyUIHud;
import au.ellie.hyui.builders.ImageBuilder;
import au.ellie.hyui.builders.LabelBuilder;
import com.LucaStudios.HytaleDungeons.Health.HealthManager;
import com.LucaStudios.HytaleDungeons.PlayerData.PlayerDataManager;
import com.LucaStudios.HytaleDungeons.Run.RunData;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent bottom-bar HUD that covers the native Hytale hotbar with three
 * slots: potion (left), HP heart (center), arrows (right).
 *
 * <p>Data is re-read from {@link HealthManager}, {@link PlayerDataManager},
 * and {@link RunStateManager} on every refresh tick. The HUD is shown on
 * {@link PlayerReadyEvent} and lives behind the main menu modal.</p>
 *
 * <p>The {@link HudModel} holding the displayed values is pure-Java so it
 * is unit-tested separately in {@code HudModelTest}. Display formatters
 * all live on {@code HudModel} for the same reason.</p>
 */
public final class GameHud {

    static final String HUD_NAME = "hytale_dungeons_hud";
    static final long REFRESH_RATE_MS = 500L;

    static final String ID_HP_FILL = "hud_hp_fill";
    static final String ID_POTION = "hud_potion";
    static final String ID_ARROWS = "hud_arrows";

    /**
     * Pre-rendered heart fill frames, indexed by HP bucket (0..10 = 0%..100%).
     * Bucket 0 is unused — at 0 HP the overlay is hidden entirely. Bucket 10
     * uses the bare {@code redHeart.png} (no numeric suffix).
     */
    private static final String[] HEART_FILL_FRAMES = {
            "",                          // 0 → hidden
            "HUD/Images/redHeart10.png", // 10%
            "HUD/Images/redHeart20.png", // 20%
            "HUD/Images/redHeart30.png", // 30%
            "HUD/Images/redHeart40.png", // 40%
            "HUD/Images/redHeart50.png", // 50%
            "HUD/Images/redHeart60.png", // 60%
            "HUD/Images/redHeart70.png", // 70%
            "HUD/Images/redHeart80.png", // 80%
            "HUD/Images/redHeart90.png", // 90%
            "HUD/Images/redHeart.png",   // 100%
    };

    private final JavaPlugin plugin;
    private final HealthManager healthManager;
    private final PlayerDataManager playerDataManager;
    private final RunStateManager runStateManager;

    /** Track the live HUD per player so we don't leak on disconnect/re-join. */
    private final ConcurrentHashMap<UUID, HyUIHud> activeHuds = new ConcurrentHashMap<>();

    /**
     * Cached HP snapshot per player as {curHp, maxHp}. HP reads go through
     * the ECS and must happen on the world thread, but the HyUI refresh tick
     * runs on HyUI's scheduler thread. The refresh callback reads from this
     * cache (cheap, thread-safe) and posts an async world-thread update for
     * the next tick. Values start at -1/-1 until the first world-thread read.
     */
    private final ConcurrentHashMap<UUID, int[]> hpCache = new ConcurrentHashMap<>();

    /** Cached arrow count per player, updated on the world thread. */
    private final ConcurrentHashMap<UUID, Integer> arrowCache = new ConcurrentHashMap<>();

    private static final String ARROW_ITEM_ID = "Weapon_Arrow_Crude";

    public GameHud(JavaPlugin plugin,
                   HealthManager healthManager,
                   PlayerDataManager playerDataManager,
                   RunStateManager runStateManager) {
        this.plugin = plugin;
        this.healthManager = healthManager;
        this.playerDataManager = playerDataManager;
        this.runStateManager = runStateManager;
    }

    public void register() {
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        Ref<EntityStore> entityRef = event.getPlayerRef();
        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (!entityRef.isValid()) return;
            Player player = store.getComponent(entityRef, Player.getComponentType());
            PlayerRef playerRef = store.getComponent(entityRef, PlayerRef.getComponentType());
            if (player == null || playerRef == null) return;
            showFor(player, playerRef, store, world);
        });
    }

    private void showFor(Player player, PlayerRef playerRef,
                         Store<EntityStore> store, World world) {
        UUID playerId = playerRef.getUuid();

        // Hide every native HUD component (hotbar + right-side utility/ability
        // slot selectors + health/mana/stamina etc.) by clearing the server's
        // authoritative visible-component set. Empty varargs clears it; the
        // HudManager syncs the empty set to the client, and the post-join
        // re-sync from PlayerHudManagerSystems will resend the same empty set
        // instead of restoring defaults.
        player.getHudManager().setVisibleHudComponents(playerRef);

        // Seed caches now while we're on the world thread so the very
        // first HUD refresh already shows real values instead of 0 / 0.
        updateHpCache(playerId);
        updateArrowCache(playerId, player);

        HyUIHud hud = HudBuilder.hudForPlayer(playerRef)
                .fromHtml(HUD_HTML)
                .withRefreshRate(REFRESH_RATE_MS)
                .onRefresh(h -> onRefreshTick(h, playerRef, world))
                .show(store);
        activeHuds.put(playerId, hud);
    }

    /**
     * HyUI-thread refresh callback. Applies the cached model to the HUD, then
     * posts a world-thread task to refresh the HP cache for the next tick.
     * Package-private so tests can exercise the update path through a seam.
     */
    void onRefreshTick(HyUIHud hud, PlayerRef playerRef, World world) {
        UUID playerId = playerRef.getUuid();
        HudModel model = snapshot(playerId);
        applyModel(hud, model);
        world.execute(() -> {
            if (!playerRef.isValid()) {
                hpCache.remove(playerId);
                arrowCache.remove(playerId);
                activeHuds.remove(playerId);
                return;
            }
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef == null || !entityRef.isValid()) return;
            Store<EntityStore> store = entityRef.getStore();
            Player player = store.getComponent(entityRef, Player.getComponentType());
            updateHpCache(playerId);
            if (player != null) updateArrowCache(playerId, player);
        });
    }

    /**
     * Build a model from the cached HP plus thread-safe manager reads. Called
     * on the HyUI scheduler thread — must NOT touch ECS components directly.
     */
    HudModel snapshot(UUID playerId) {
        int[] hp = hpCache.get(playerId);
        int curHp = (hp == null || hp[0] < 0) ? 0 : hp[0];
        int maxHp = (hp == null || hp[1] < 0) ? 0 : hp[1];

        long potionCd = healthManager.getPotionCooldownRemainingMs(playerId);

        // level / lives are no longer displayed but HudModel's record still
        // carries them for test compatibility; pass sane defaults.
        int level = playerDataManager.getPlayerLevel(playerId);
        RunData runData = runStateManager.getRunData(playerId);
        int lives = runData == null ? 0 : runData.getLivesRemaining();

        Integer arrows = arrowCache.get(playerId);
        int arrowCount = arrows == null ? 0 : arrows;

        return new HudModel(
                curHp,
                maxHp,
                potionCd,
                arrowCount,
                level,
                lives);
    }

    /** World-thread arrow count. Scans hotbar + storage + backpack. */
    private void updateArrowCache(UUID playerId, Player player) {
        var inventory = player.getInventory();
        if (inventory == null) { arrowCache.put(playerId, 0); return; }
        int count = countItemIn(inventory.getHotbar())
                  + countItemIn(inventory.getStorage())
                  + countItemIn(inventory.getBackpack());
        arrowCache.put(playerId, count);
    }

    private static int countItemIn(ItemContainer container) {
        if (container == null) return 0;
        int total = 0;
        for (short slot = 0; slot < container.getCapacity(); slot++) {
            ItemStack item = container.getItemStack(slot);
            if (item != null && !item.isEmpty() && ARROW_ITEM_ID.equals(item.getItemId())) {
                total += item.getQuantity();
            }
        }
        return total;
    }

    /** World-thread HP read. Safe to touch ECS here. */
    private void updateHpCache(UUID playerId) {
        float curHpF = healthManager.getCurrentHp(playerId);
        float maxHpF = healthManager.getMaxHp(playerId);
        int curHp = curHpF < 0 ? -1 : Math.round(curHpF);
        int maxHp = maxHpF < 0 ? -1 : Math.round(maxHpF);
        hpCache.put(playerId, new int[]{curHp, maxHp});
    }

    /** Apply model fields to the HUD by id. Missing ids are silently ignored. */
    static void applyModel(HyUIHud hud, HudModel model) {
        setLabel(hud, ID_POTION, model.formatPotion());
        setLabel(hud, ID_ARROWS, model.formatArrows());
        setHeartFillFrame(hud, model.hpRatio());
    }

    private static void setLabel(HyUIHud hud, String id, String text) {
        hud.getById(id, LabelBuilder.class).ifPresent(label -> label.withText(text));
    }

    /**
     * Snap the red heart overlay to the nearest 10% pre-rendered frame. The
     * CSS already pins the overlay's anchor dimensions, so we only swap the
     * image path and toggle visibility — no geometry mutation needed.
     *
     * <p>Buckets round to nearest 10%: HP between 5% and 14% shows the 10%
     * frame, 0% exact hides the overlay entirely.</p>
     */
    static void setHeartFillFrame(HyUIHud hud, float ratio) {
        int bucket = Math.max(0, Math.min(10, Math.round(ratio * 10f)));
        hud.getById(ID_HP_FILL, ImageBuilder.class).ifPresent(img -> {
            if (bucket == 0) {
                img.withVisible(false);
            } else {
                img.withVisible(true);
                img.withImage(HEART_FILL_FRAMES[bucket]);
            }
        });
    }

    // ── Placeholder visual shell ────────────────────────────────────────────
    //
    // Uses the same <style> + class pattern as InventoryPage.html: a single
    // bar <div> anchored center-bottom with a CSS background-image, and five
    // flex-laid-out slot children via `layout-mode: left`. We avoid inline
    // styles on containers since HyUI's DivHandler runs them through the
    // styling whitelist (empty for containers) — but CSS classes route
    // through the same path as InventoryPage, which does work.
    //
    // Image paths are relative to `Common/UI/Custom/` (the asset pack root
    // for HyUI images), so `HUD/Images/*.png` resolves to
    // `src/main/resources/Common/UI/Custom/HUD/Images/*.png`.

    private static final String HUD_HTML = """
            <style>
              .hud_bar {
                layout-mode: left;
                horizontal-align: center;
                vertical-align: bottom;
                anchor-width: 600;
                anchor-height: 130;
                margin-bottom: 6;
              }
              .hud_slot {
                layout-mode: middle;
                anchor-width: 200;
                anchor-height: 130;
              }
              .hud_icon {
                anchor-width: 96;
                anchor-height: 96;
                horizontal-align: center;
                vertical-align: center;
              }
              .hud_heart {
                anchor-width: 144;
                anchor-height: 124;
                horizontal-align: center;
                vertical-align: center;
                layout-mode: middle;
                background-image: HUD/Images/HeartIcon.png;
              }
              .hud_heart_fill {
                anchor-width: 119;
                anchor-height: 93;
                horizontal-align: center;
                vertical-align: bottom;
                margin-left: 13;
                margin-bottom: 5;
              }
              .hud_potion_label {
                anchor-width: 40;
                anchor-height: 28;
                horizontal-align: center;
                vertical-align: bottom;
                font-size: 20;
                text-align: center;
              }
              .hud_arrow_label {
                anchor-width: 40;
                anchor-height: 28;
                horizontal-align: right;
                vertical-align: bottom;
                font-size: 18;
                text-align: center;
              }
            </style>
            <div class="page-overlay">
                <div id="hud_bar" class="hud_bar">
                    <div class="hud_slot">
                        <img src="HUD/Images/PotionIcon.png" class="hud_icon"/>
                        <label id="hud_potion" class="hud_potion_label">4</label>
                    </div>
                    <div class="hud_slot">
                        <div class="hud_heart">
                            <img id="hud_hp_fill" src="HUD/Images/redHeart.png" class="hud_heart_fill"/>
                        </div>
                    </div>
                    <div class="hud_slot">
                        <img src="HUD/Images/ArrowsIcon.png" class="hud_icon"/>
                        <label id="hud_arrows" class="hud_arrow_label">30</label>
                    </div>
                </div>
            </div>
            """;
}
