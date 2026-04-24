package com.LucaStudios.HytaleDungeons.UI;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.PageBuilder;
import com.LucaStudios.HytaleDungeons.Loot.ItemDatabase;
import com.LucaStudios.HytaleDungeons.Loot.ItemDefinition;
import com.LucaStudios.HytaleDungeons.Loot.LootOffer;
import com.LucaStudios.HytaleDungeons.Loot.Rarity;
import com.LucaStudios.HytaleDungeons.PlayerData.PlayerDataManager;
import com.LucaStudios.HytaleDungeons.PlayerData.PlayerLoadout;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modal screen shown after a floor is cleared. Presents three rarity-weighted
 * loot offers from {@link ItemDatabase#rollOffersForFloor(int, int)}; clicking a card
 * replaces the player's equipped slot of that item's category (via
 * {@link RunStateManager#onOfferSelected}) and advances to the next floor.
 *
 * <p>Weapon visuals use HyUI's native {@code <span class="item-icon">} tag,
 * which renders the real Hytale item icon by {@code data-hyui-item-id} — no
 * per-item PNG authoring required.</p>
 */
public final class BetweenFloorsPage {

    static final int OFFER_COUNT = 3;
    static final String BTN_OFFER_PREFIX = "btn_offer_";

    private final RunStateManager runStateManager;
    private final PlayerDataManager playerDataManager;
    private final ConcurrentHashMap<UUID, HyUIPage> activePages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<LootOffer>> activeOffers = new ConcurrentHashMap<>();

    public BetweenFloorsPage(RunStateManager runStateManager, PlayerDataManager playerDataManager) {
        this.runStateManager = runStateManager;
        this.playerDataManager = playerDataManager;
    }

    /** Open the between-floors page. Must be called on the world thread. */
    public void showFor(PlayerRef playerRef,
                        Store<EntityStore> store,
                        int clearedFloor,
                        int revivesRemaining) {
        UUID playerId = playerRef.getUuid();
        final HyUIPage[] pageSlot = new HyUIPage[1];

        Map<String, Integer> equippedSnapshot = snapshotEquippedLevels(playerId);
        List<LootOffer> offers = ItemDatabase.getInstance()
                .rollOffersForFloor(OFFER_COUNT, clearedFloor, equippedSnapshot);
        activeOffers.put(playerId, offers);

        String html = buildHtml(clearedFloor, revivesRemaining, offers);

        PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                .fromHtml(html)
                .withLifetime(CustomPageLifetime.CantClose);

        for (int i = 0; i < offers.size(); i++) {
            final int idx = i;
            builder.addEventListener(BTN_OFFER_PREFIX + idx, CustomUIEventBindingType.Activating,
                    v -> handlePick(pageSlot[0], playerRef, playerId, idx));
        }

        HyUIPage page = builder.open(store);
        pageSlot[0] = page;
        activePages.put(playerId, page);
    }

    public void closeFor(UUID playerId) {
        activeOffers.remove(playerId);
        HyUIPage page = activePages.remove(playerId);
        if (page != null) {
            try { page.close(); } catch (Throwable ignored) {}
        }
    }

    private void handlePick(HyUIPage page, PlayerRef playerRef, UUID playerId, int offerIndex) {
        List<LootOffer> offers = activeOffers.remove(playerId);
        activePages.remove(playerId);
        if (page != null) {
            try { page.close(); } catch (Throwable ignored) {}
        }
        if (offers == null || offerIndex < 0 || offerIndex >= offers.size()) {
            return;
        }
        LootOffer picked = offers.get(offerIndex);
        String itemId = picked.item().getId();
        int itemLevel = picked.level();
        World world = worldFromPlayerRef(playerRef);
        if (world != null) {
            world.execute(() -> runStateManager.onOfferSelected(playerId, playerRef, itemId, itemLevel));
        } else {
            runStateManager.onOfferSelected(playerId, playerRef, itemId, itemLevel);
        }
    }

    /**
     * Build {@code itemId -> level} for the player's currently equipped slots,
     * so the offer roll can skip an exact (item, level) duplicate the player
     * already has. Empty map if the player has no loadout yet.
     */
    private Map<String, Integer> snapshotEquippedLevels(UUID playerId) {
        Map<String, Integer> snapshot = new HashMap<>(3);
        if (playerDataManager == null) return snapshot;
        PlayerLoadout loadout = playerDataManager.getLoadout(playerId);
        if (loadout == null) return snapshot;
        if (loadout.getEquippedWeaponId() != null) {
            snapshot.put(loadout.getEquippedWeaponId(), loadout.getEquippedWeaponLevel());
        }
        if (loadout.getEquippedArmorId() != null) {
            snapshot.put(loadout.getEquippedArmorId(), loadout.getEquippedArmorLevel());
        }
        if (loadout.getEquippedCrossbowId() != null) {
            snapshot.put(loadout.getEquippedCrossbowId(), loadout.getEquippedCrossbowLevel());
        }
        return snapshot;
    }

    private static World worldFromPlayerRef(PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) return null;
        var ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return null;
        return ref.getStore().getExternalData().getWorld();
    }

    // ── HTML ────────────────────────────────────────────────────────────

    private static String buildHtml(int clearedFloor,
                                    int revivesRemaining,
                                    List<LootOffer> offers) {
        StringBuilder cards = new StringBuilder();
        for (int i = 0; i < offers.size(); i++) {
            cards.append(buildCardHtml(i, offers.get(i)));
            if (i < offers.size() - 1) {
                cards.append("<div class=\"bf_card_gap\"></div>");
            }
        }

        return """
                <style>
                  .bf_bg {
                    anchor-top: 0;
                    anchor-bottom: 0;
                    anchor-left: 0;
                    anchor-right: 0;
                    background-color: #07101fee;
                    layout-mode: top;
                  }
                  .bf_header_spacer {
                    anchor-width: 1920;
                    anchor-height: 60;
                    horizontal-align: center;
                  }
                  .bf_title {
                    anchor-width: 1920;
                    anchor-height: 110;
                    horizontal-align: center;
                    text-align: center;
                    color: #ffffff;
                    font-size: 80;
                    font-weight: bold;
                    font-family: secondary;
                  }
                  .bf_divider {
                    anchor-width: 420;
                    anchor-height: 3;
                    margin-top: 6;
                    horizontal-align: center;
                    background-color: #5aa8ff;
                  }
                  .bf_subtitle {
                    anchor-width: 1920;
                    anchor-height: 50;
                    margin-top: 18;
                    horizontal-align: center;
                    text-align: center;
                    color: #cce0ff;
                    font-size: 30;
                    font-weight: bold;
                    font-family: secondary;
                    text-transform: uppercase;
                  }
                  .bf_lives_pill {
                    anchor-width: 360;
                    anchor-height: 42;
                    margin-top: 14;
                    horizontal-align: center;
                    background-color: #18243acc;
                    layout-mode: top;
                  }
                  .bf_lives_text {
                    anchor-width: 360;
                    anchor-height: 42;
                    text-align: center;
                    vertical-align: center;
                    color: #ffd28a;
                    font-size: 20;
                    font-weight: bold;
                    font-family: secondary;
                  }
                  /* Cards row centered horizontally (3 cards + 2 gaps = 1272 wide) */
                  .bf_card_row {
                    layout-mode: left;
                    anchor-width: 1272;
                    anchor-height: 660;
                    margin-top: 36;
                    horizontal-align: center;
                  }
                  .bf_card_gap {
                    anchor-width: 36;
                    anchor-height: 660;
                  }
                  /* Outer rarity-tinted frame (simulates a colored border) */
                  .bf_card_frame {
                    anchor-width: 400;
                    anchor-height: 660;
                    layout-mode: top;
                  }
                  /* Dark inner panel inset by 3px on each side */
                  .bf_card_inset {
                    anchor-width: 394;
                    anchor-height: 654;
                    margin-top: 3;
                    horizontal-align: center;
                    background-color: #0c1626ee;
                    layout-mode: top;
                  }
                  /* Rarity ribbon at the top of the card */
                  .bf_card_ribbon {
                    anchor-width: 394;
                    anchor-height: 36;
                  }
                  .bf_card_ribbon_text {
                    anchor-width: 394;
                    anchor-height: 36;
                    text-align: center;
                    vertical-align: center;
                    color: #0a0f18;
                    font-size: 18;
                    font-weight: bold;
                    font-family: secondary;
                    text-transform: uppercase;
                  }
                  /* Image area — rarity-tinted backdrop holding the native HyUI item icon */
                  .bf_card_image_bg {
                    anchor-width: 220;
                    anchor-height: 220;
                    margin-top: 16;
                    horizontal-align: center;
                    layout-mode: top;
                  }
                  .bf_card_item {
                    anchor-width: 180;
                    anchor-height: 180;
                    margin-top: 20;
                    horizontal-align: center;
                  }
                  .bf_card_name {
                    anchor-width: 394;
                    anchor-height: 38;
                    margin-top: 14;
                    text-align: center;
                    color: #ffffff;
                    font-size: 24;
                    font-weight: bold;
                    font-family: secondary;
                  }
                  .bf_card_category {
                    anchor-width: 394;
                    anchor-height: 22;
                    margin-top: 2;
                    text-align: center;
                    color: #8fa4bf;
                    font-size: 14;
                    font-family: secondary;
                    text-transform: uppercase;
                  }
                  /* Stat row: 3 cells (PWR/DEF | LVL | SPD) for weapons, 2 (DEF | LVL) for armor */
                  .bf_card_stat_row_3 {
                    layout-mode: left;
                    anchor-width: 360;
                    anchor-height: 36;
                    margin-top: 12;
                    horizontal-align: center;
                  }
                  .bf_card_stat_row_2 {
                    layout-mode: left;
                    anchor-width: 240;
                    anchor-height: 36;
                    margin-top: 12;
                    horizontal-align: center;
                  }
                  .bf_card_stat_cell {
                    anchor-width: 118;
                    anchor-height: 36;
                    text-align: center;
                    vertical-align: center;
                    font-size: 20;
                    font-weight: bold;
                    font-family: secondary;
                  }
                  .bf_card_stat_pwr { color: #ffd060; }
                  .bf_card_stat_def { color: #d0a060; }
                  .bf_card_stat_lvl { color: #ffffff; }
                  .bf_card_stat_spd { color: #9fd6ff; }
                  .bf_card_stat_sep {
                    anchor-width: 2;
                    anchor-height: 24;
                    background-color: #33425a;
                    margin-top: 6;
                  }
                  .bf_card_desc {
                    anchor-width: 360;
                    anchor-height: 56;
                    horizontal-align: center;
                    margin-top: 10;
                    text-align: center;
                    color: #b6c2d4;
                    font-size: 14;
                    font-family: secondary;
                  }
                  .bf_card_btn {
                    anchor-width: 300;
                    anchor-height: 54;
                    margin-top: 14;
                    horizontal-align: center;
                  }
                </style>
                <div class="page-overlay">
                    <div class="bf_bg">
                        <div class="bf_header_spacer"></div>
                        <label class="bf_title">Floor %d Cleared</label>
                        <div class="bf_divider"></div>
                        <label class="bf_subtitle">Choose Your Reward</label>
                        <div class="bf_lives_pill">
                            <label class="bf_lives_text">REVIVALS REMAINING: %d</label>
                        </div>
                        <div class="bf_card_row">
                            %s
                        </div>
                    </div>
                </div>
                """.formatted(clearedFloor, revivesRemaining, cards.toString());
    }

    private static String buildCardHtml(int index, LootOffer offer) {
        ItemDefinition item = offer.item();
        String rarityColor = rarityColor(item.getRarity());
        String rarityTint = rarityTint(item.getRarity());
        boolean isArmor = item.getCategory() == com.LucaStudios.HytaleDungeons.Loot.ItemCategory.ARMOR;
        String categoryLabel = switch (item.getCategory()) {
            case WEAPON -> "Melee Weapon";
            case CROSSBOW -> "Crossbow";
            case ARMOR -> "Armor";
        };
        String desc = item.getDescription() == null ? "" : item.getDescription();
        int baseStat = item.getBaseStat();
        int level = offer.level();

        String statRow;
        if (isArmor) {
            statRow = """
                    <div class="bf_card_stat_row_2">
                        <label class="bf_card_stat_cell bf_card_stat_def">DEF %STAT%</label>
                        <div class="bf_card_stat_sep"></div>
                        <label class="bf_card_stat_cell bf_card_stat_lvl">LVL %LVL%</label>
                    </div>
                    """
                    .replace("%STAT%", Integer.toString(baseStat))
                    .replace("%LVL%", Integer.toString(level));
        } else {
            statRow = """
                    <div class="bf_card_stat_row_3">
                        <label class="bf_card_stat_cell bf_card_stat_pwr">PWR %STAT%</label>
                        <div class="bf_card_stat_sep"></div>
                        <label class="bf_card_stat_cell bf_card_stat_lvl">LVL %LVL%</label>
                        <div class="bf_card_stat_sep"></div>
                        <label class="bf_card_stat_cell bf_card_stat_spd">SPD %SPD%ms</label>
                    </div>
                    """
                    .replace("%STAT%", Integer.toString(baseStat))
                    .replace("%LVL%", Integer.toString(level))
                    .replace("%SPD%", Integer.toString(item.getCooldownMs()));
        }

        return ("""
                <div class="bf_card_frame" style="background-color: %RARITY_COLOR%;">
                    <div class="bf_card_inset">
                        <div class="bf_card_ribbon" style="background-color: %RARITY_COLOR%;">
                            <label class="bf_card_ribbon_text">%RARITY_NAME%</label>
                        </div>
                        <div class="bf_card_image_bg" style="background-color: %RARITY_TINT%;">
                            <span class="item-icon bf_card_item" data-hyui-item-id="%HYTALE_ID%"></span>
                        </div>
                        <label class="bf_card_name">%NAME%</label>
                        <label class="bf_card_category">%CATEGORY%</label>
                        %STAT_ROW%
                        <label class="bf_card_desc">%DESC%</label>
                        <button id="%BTN_ID%" class="custom-textbutton bf_card_btn"
                            data-hyui-default-bg="background-image: HUD/Images/BtnGreen.png;"
                            data-hyui-hovered-bg="background-image: HUD/Images/BtnGreenHov.png;"
                            data-hyui-pressed-bg="background-image: HUD/Images/BtnGreenPrs.png;"
                            data-hyui-default-label-style="color: #ffffff; font-size: 20; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;"
                            data-hyui-hovered-label-style="color: #ffffff; font-size: 20; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;"
                            data-hyui-pressed-label-style="color: #ddffdd; font-size: 19; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;">
                            <label>SELECT</label>
                        </button>
                    </div>
                </div>
                """)
                .replace("%RARITY_COLOR%", rarityColor)
                .replace("%RARITY_TINT%", rarityTint)
                .replace("%RARITY_NAME%", item.getRarity().name())
                .replace("%HYTALE_ID%", escape(item.getHytaleItemId()))
                .replace("%NAME%", escape(item.getDisplayName()))
                .replace("%CATEGORY%", categoryLabel)
                .replace("%STAT_ROW%", statRow)
                .replace("%DESC%", escape(desc))
                .replace("%BTN_ID%", BTN_OFFER_PREFIX + index);
    }

    private static String rarityColor(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> "#c8cdd4";
            case UNCOMMON -> "#6bd36b";
            case RARE -> "#5aa8ff";
            case EPIC -> "#c77bff";
            case LEGENDARY -> "#ffb84d";
        };
    }

    /** Dark rarity-tinted backdrop sitting behind the native HyUI item icon. */
    private static String rarityTint(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> "#2a3240";
            case UNCOMMON -> "#1f3a1f";
            case RARE -> "#15304f";
            case EPIC -> "#341a4f";
            case LEGENDARY -> "#4a3110";
        };
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
