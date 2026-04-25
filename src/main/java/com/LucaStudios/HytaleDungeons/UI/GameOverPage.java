package com.LucaStudios.HytaleDungeons.UI;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.PageBuilder;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Modal game-over screen (no lives left). Visually mirrors {@link VictoryPage}
 * — same typographic hero + stats-row layout — but with a crimson palette.
 *
 * <ul>
 *   <li><b>New Run</b> — calls {@link RunStateManager#onNewRunRequested}.</li>
 *   <li><b>Lobby</b>   — resets the run and reopens {@link MainMenuPage} after
 *       a brief tear-down delay.</li>
 * </ul>
 *
 * Non-dismissable ({@link CustomPageLifetime#CantClose}); the buttons are the
 * only way out.
 */
public final class GameOverPage {

    static final String BTN_RESTART = "btn_restart";
    static final String BTN_LOBBY = "btn_lobby";

    /** Delay between close(game-over) and open(main-menu) so the client-side
     *  HyUI state fully tears down the old page before the new one is wired up.
     *  Without this delay the main-menu reopens in the same tick and its button
     *  events never fire. */
    private static final long LOBBY_REOPEN_DELAY_MS = 250L;

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "GameOverPage-reopen");
                t.setDaemon(true);
                return t;
            });

    private final RunStateManager runStateManager;
    private final MainMenuPage mainMenuPage;
    private final JavaPlugin plugin;
    private final ConcurrentHashMap<UUID, HyUIPage> activePages = new ConcurrentHashMap<>();

    public GameOverPage(RunStateManager runStateManager, MainMenuPage mainMenuPage, JavaPlugin plugin) {
        this.runStateManager = runStateManager;
        this.mainMenuPage = mainMenuPage;
        this.plugin = plugin;
    }

    /** Immutable stats snapshot baked into the page HTML at open time. */
    public record GameOverStats(int floorReached,
                                int mobsSlain,
                                int livesRemaining,
                                long runTimeMs) {}

    /** Open the game-over page. Must be called on the world thread. */
    public void showFor(PlayerRef playerRef, Store<EntityStore> store, GameOverStats stats) {
        UUID playerId = playerRef.getUuid();
        final HyUIPage[] pageSlot = new HyUIPage[1];

        // Capture the world NOW from the valid store. After the death→revival
        // archetype transition (DeathComponent removed, HP reset) the entity's
        // Ref may be transitional at button-click time, making a live
        // worldFromPlayerRef() call return null and silently break the handlers.
        World world = store.getExternalData().getWorld();

        String html = buildHtml(stats);

        HyUIPage page = PageBuilder.pageForPlayer(playerRef)
                .fromHtml(html)
                .withLifetime(CustomPageLifetime.CantClose)
                .addEventListener(BTN_RESTART, CustomUIEventBindingType.Activating,
                        v -> handleRestart(pageSlot[0], playerRef, playerId, world))
                .addEventListener(BTN_LOBBY, CustomUIEventBindingType.Activating,
                        v -> handleLobby(pageSlot[0], playerRef, playerId, world))
                .open(store);
        if (page == null) {
            plugin.getLogger().at(Level.WARNING).log(
                    "GameOverPage: PageBuilder.open() returned null for player " + playerId);
            return;
        }
        plugin.getLogger().at(Level.INFO).log(
                "GameOverPage: opened for player " + playerId);
        pageSlot[0] = page;
        activePages.put(playerId, page);
    }

    public void closeFor(UUID playerId) {
        HyUIPage page = activePages.remove(playerId);
        if (page != null) {
            try { page.close(); } catch (Throwable ignored) {}
        }
    }

    private void handleRestart(HyUIPage page, PlayerRef playerRef, UUID playerId, World world) {
        // Close the page directly FIRST — in single player the world tick is
        // paused while a CantClose modal is showing, so world.execute() would
        // deadlock. Closing synchronously unpauses the tick; the state method's
        // own internal world.execute() calls then run normally (same pattern as
        // MainMenuPage's Start button calling startRunFromLobby directly).
        activePages.remove(playerId);
        if (page != null) try { page.close(); } catch (Throwable ignored) {}
        runStateManager.onNewRunRequested(playerId, playerRef);
    }

    private void handleLobby(HyUIPage page, PlayerRef playerRef, UUID playerId, World world) {
        activePages.remove(playerId);
        if (page != null) try { page.close(); } catch (Throwable ignored) {}
        runStateManager.onReturnToLobby(playerId, playerRef);
        // Defer the main-menu open so the game-over close packet has time to
        // fully tear down on the client before the new page is wired up.
        // By now the page is closed, so world.execute() will run normally.
        SCHEDULER.schedule(() -> {
            if (world == null) {
                if (playerRef.isValid()) {
                    Ref<EntityStore> ref = playerRef.getReference();
                    if (ref != null && ref.isValid()) {
                        mainMenuPage.showFor(playerRef, ref.getStore());
                    }
                }
                return;
            }
            world.execute(() -> {
                if (!playerRef.isValid()) return;
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref == null || !ref.isValid()) return;
                mainMenuPage.showFor(playerRef, ref.getStore());
            });
        }, LOBBY_REOPEN_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    // ── HTML ────────────────────────────────────────────────────────────

    private static String buildHtml(GameOverStats stats) {
        String timeText = formatDuration(stats.runTimeMs());
        String statsRow = ""
                + statCard("MOBS SLAIN", Integer.toString(stats.mobsSlain()),      "#ff9060")
                + gap()
                + statCard("FLOORS CLEARED", Integer.toString(Math.max(0, stats.floorReached() - 1)), "#ff6f8a")
                + gap()
                + statCard("TIME",       timeText,                                  "#e0c8ff");

        return ("""
                <style>
                  .go_bg {
                    anchor-top: 0;
                    anchor-bottom: 0;
                    anchor-left: 0;
                    anchor-right: 0;
                    background-color: #130308ee;
                    layout-mode: top;
                  }
                  .go_header_spacer {
                    anchor-width: 1920;
                    anchor-height: 90;
                    horizontal-align: center;
                  }
                  .go_eyebrow {
                    anchor-width: 1920;
                    anchor-height: 44;
                    horizontal-align: center;
                    text-align: center;
                    color: #e6b0b0;
                    font-size: 26;
                    font-weight: bold;
                    font-family: secondary;
                    text-transform: uppercase;
                  }
                  .go_title {
                    anchor-width: 1920;
                    anchor-height: 160;
                    margin-top: 4;
                    horizontal-align: center;
                    text-align: center;
                    color: #ff5a5a;
                    font-size: 140;
                    font-weight: bold;
                    font-family: secondary;
                    text-transform: uppercase;
                  }
                  .go_divider {
                    anchor-width: 560;
                    anchor-height: 3;
                    margin-top: 6;
                    horizontal-align: center;
                    background-color: #c03030;
                  }
                  .go_subtitle {
                    anchor-width: 1920;
                    anchor-height: 46;
                    margin-top: 14;
                    horizontal-align: center;
                    text-align: center;
                    color: #ffcfcf;
                    font-size: 26;
                    font-weight: bold;
                    font-family: secondary;
                    text-transform: uppercase;
                  }

                  /* Stats row: 3 cards (180) + 2 gaps (24) = 588 wide */
                  .go_stats_row {
                    layout-mode: left;
                    anchor-width: 588;
                    anchor-height: 200;
                    margin-top: 48;
                    horizontal-align: center;
                  }
                  .go_stat_gap {
                    anchor-width: 24;
                    anchor-height: 200;
                  }
                  .go_stat_frame {
                    anchor-width: 180;
                    anchor-height: 200;
                    layout-mode: top;
                    background-color: #46101a;
                  }
                  .go_stat_frame_inset {
                    anchor-width: 176;
                    anchor-height: 196;
                    margin-top: 2;
                    horizontal-align: center;
                    layout-mode: top;
                    background-color: #150810ee;
                  }
                  .go_stat_label {
                    anchor-width: 176;
                    anchor-height: 30;
                    margin-top: 22;
                    text-align: center;
                    color: #c88898;
                    font-size: 16;
                    font-weight: bold;
                    font-family: secondary;
                    text-transform: uppercase;
                  }
                  .go_stat_rule {
                    anchor-width: 60;
                    anchor-height: 2;
                    margin-top: 10;
                    horizontal-align: center;
                    background-color: #5a2a36;
                  }
                  .go_stat_value {
                    anchor-width: 176;
                    anchor-height: 80;
                    margin-top: 14;
                    text-align: center;
                    font-size: 56;
                    font-weight: bold;
                    font-family: secondary;
                  }

                  /* Button row centered (2 btns 260 + gap 28 = 548 wide) */
                  .go_btn_row {
                    layout-mode: left;
                    anchor-width: 548;
                    anchor-height: 64;
                    margin-top: 56;
                    horizontal-align: center;
                  }
                  .go_btn_gap {
                    anchor-width: 28;
                    anchor-height: 64;
                  }
                  .go_btn {
                    anchor-width: 260;
                    anchor-height: 64;
                  }
                </style>
                <div class="page-overlay">
                    <div class="go_bg">
                        <div class="go_header_spacer"></div>
                        <label class="go_eyebrow">Run Ended</label>
                        <label class="go_title">Defeated</label>
                        <div class="go_divider"></div>
                        <label class="go_subtitle">You fell on floor %d</label>
                        <div class="go_stats_row">
                            %s
                        </div>
                        <div class="go_btn_row">
                            <button id="%BTN_RESTART%" class="custom-textbutton go_btn"
                                data-hyui-default-bg="background-image: HUD/Images/BtnGreen.png;"
                                data-hyui-hovered-bg="background-image: HUD/Images/BtnGreenHov.png;"
                                data-hyui-pressed-bg="background-image: HUD/Images/BtnGreenPrs.png;"
                                data-hyui-default-label-style="color: #ffffff; font-size: 20; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;"
                                data-hyui-hovered-label-style="color: #ffffff; font-size: 20; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;"
                                data-hyui-pressed-label-style="color: #ddffdd; font-size: 19; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;">
                                <label>New Run</label>
                            </button>
                            <div class="go_btn_gap"></div>
                            <button id="%BTN_LOBBY%" class="custom-textbutton go_btn"
                                data-hyui-default-bg="background-image: HUD/Images/BtnDark.png;"
                                data-hyui-hovered-bg="background-image: HUD/Images/BtnDarkHov.png;"
                                data-hyui-pressed-bg="background-image: HUD/Images/BtnDarkPrs.png;"
                                data-hyui-default-label-style="color: #cccccc; font-size: 18; font-family: secondary; text-align: center; vertical-align: center;"
                                data-hyui-hovered-label-style="color: #ffffff; font-size: 18; font-family: secondary; text-align: center; vertical-align: center;"
                                data-hyui-pressed-label-style="color: #aaaaaa; font-size: 18; font-family: secondary; text-align: center; vertical-align: center;">
                                <label>Back to Lobby</label>
                            </button>
                        </div>
                    </div>
                </div>
                """)
                .replace("%BTN_RESTART%", BTN_RESTART)
                .replace("%BTN_LOBBY%", BTN_LOBBY)
                .formatted(stats.floorReached(), statsRow);
    }

    private static String statCard(String label, String value, String valueColor) {
        return ("""
                <div class="go_stat_frame">
                    <div class="go_stat_frame_inset">
                        <label class="go_stat_label">%LABEL%</label>
                        <div class="go_stat_rule"></div>
                        <label class="go_stat_value" style="color: %COLOR%;">%VALUE%</label>
                    </div>
                </div>
                """)
                .replace("%LABEL%", label)
                .replace("%COLOR%", valueColor)
                .replace("%VALUE%", value);
    }

    private static String gap() {
        return "<div class=\"go_stat_gap\"></div>";
    }

    private static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%d:%02d", minutes, seconds);
    }
}
