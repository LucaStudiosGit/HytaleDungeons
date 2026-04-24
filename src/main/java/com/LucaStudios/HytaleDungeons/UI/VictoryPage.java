package com.LucaStudios.HytaleDungeons.UI;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.PageBuilder;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Modal victory screen — the final floor was cleared. Gold-on-navy typographic
 * hero + a row of run-stat cards, then two actions:
 *
 * <ul>
 *   <li><b>New Run</b> — calls {@link RunStateManager#onNewRunRequested}.</li>
 *   <li><b>Lobby</b>   — resets the run and reopens the {@link MainMenuPage}
 *       after a brief client-side tear-down delay (same pattern as
 *       {@link GameOverPage}).</li>
 * </ul>
 *
 * Non-dismissable ({@link CustomPageLifetime#CantClose}); the buttons are the
 * only way out.
 */
public final class VictoryPage {

    static final String BTN_RESTART = "btn_victory_restart";
    static final String BTN_LOBBY = "btn_victory_lobby";

    /** Match {@link GameOverPage#LOBBY_REOPEN_DELAY_MS} — lets the close packet
     *  fully tear down client-side before the main-menu re-wires its handlers. */
    private static final long LOBBY_REOPEN_DELAY_MS = 250L;

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "VictoryPage-reopen");
                t.setDaemon(true);
                return t;
            });

    private final RunStateManager runStateManager;
    private final MainMenuPage mainMenuPage;
    private final ConcurrentHashMap<UUID, HyUIPage> activePages = new ConcurrentHashMap<>();

    public VictoryPage(RunStateManager runStateManager, MainMenuPage mainMenuPage) {
        this.runStateManager = runStateManager;
        this.mainMenuPage = mainMenuPage;
    }

    /** Immutable stats snapshot baked into the page HTML at open time. */
    public record VictoryStats(int floorsCleared,
                               int mobsSlain,
                               int deaths,
                               int livesRemaining,
                               int playerLevel,
                               long runTimeMs) {}

    /** Open the victory page. Must be called on the world thread. */
    public void showFor(PlayerRef playerRef, Store<EntityStore> store, VictoryStats stats) {
        UUID playerId = playerRef.getUuid();
        final HyUIPage[] pageSlot = new HyUIPage[1];

        String html = buildHtml(stats);

        HyUIPage page = PageBuilder.pageForPlayer(playerRef)
                .fromHtml(html)
                .withLifetime(CustomPageLifetime.CantClose)
                .addEventListener(BTN_RESTART, CustomUIEventBindingType.Activating,
                        v -> handleRestart(pageSlot[0], playerRef, playerId))
                .addEventListener(BTN_LOBBY, CustomUIEventBindingType.Activating,
                        v -> handleLobby(pageSlot[0], playerRef, store))
                .open(store);
        pageSlot[0] = page;
        activePages.put(playerId, page);
    }

    public void closeFor(UUID playerId) {
        HyUIPage page = activePages.remove(playerId);
        if (page != null) {
            try { page.close(); } catch (Throwable ignored) {}
        }
    }

    private void handleRestart(HyUIPage page, PlayerRef playerRef, UUID playerId) {
        closePage(page, playerId);
        World world = worldFromPlayerRef(playerRef);
        if (world != null) {
            world.execute(() -> runStateManager.onNewRunRequested(playerId, playerRef));
        } else {
            runStateManager.onNewRunRequested(playerId, playerRef);
        }
    }

    private void handleLobby(HyUIPage page, PlayerRef playerRef, Store<EntityStore> store) {
        UUID playerId = playerRef.getUuid();
        closePage(page, playerId);
        World world = worldFromPlayerRef(playerRef);
        Runnable reset = () -> {
            if (!playerRef.isValid()) return;
            runStateManager.onReturnToLobby(playerId, playerRef);
        };
        Runnable reopenMenu = () -> {
            if (world == null) {
                if (playerRef.isValid()) mainMenuPage.showFor(playerRef, store);
                return;
            }
            world.execute(() -> {
                if (!playerRef.isValid()) return;
                mainMenuPage.showFor(playerRef, store);
            });
        };
        if (world != null) {
            world.execute(reset);
        } else {
            reset.run();
        }
        SCHEDULER.schedule(reopenMenu, LOBBY_REOPEN_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void closePage(HyUIPage page, UUID playerId) {
        activePages.remove(playerId);
        if (page != null) {
            try { page.close(); } catch (Throwable ignored) {}
        }
    }

    private static World worldFromPlayerRef(PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) return null;
        var ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return null;
        return ref.getStore().getExternalData().getWorld();
    }

    // ── HTML ────────────────────────────────────────────────────────────

    private static String buildHtml(VictoryStats stats) {
        String timeText = formatDuration(stats.runTimeMs());
        String statsRow = ""
                + statCard("MOBS SLAIN", Integer.toString(stats.mobsSlain()),     "#ff9060")
                + gap()
                + statCard("LIVES LEFT", Integer.toString(stats.livesRemaining()),"#ff6f8a")
                + gap()
                + statCard("TIME",       timeText,                                 "#e0c8ff");

        return ("""
                <style>
                  .vp_bg {
                    anchor-top: 0;
                    anchor-bottom: 0;
                    anchor-left: 0;
                    anchor-right: 0;
                    background-color: #050818ee;
                    layout-mode: top;
                  }
                  .vp_header_spacer {
                    anchor-width: 1920;
                    anchor-height: 90;
                    horizontal-align: center;
                  }
                  .vp_eyebrow {
                    anchor-width: 1920;
                    anchor-height: 44;
                    horizontal-align: center;
                    text-align: center;
                    color: #cfdcff;
                    font-size: 26;
                    font-weight: bold;
                    font-family: secondary;
                    text-transform: uppercase;
                  }
                  .vp_title {
                    anchor-width: 1920;
                    anchor-height: 160;
                    margin-top: 4;
                    horizontal-align: center;
                    text-align: center;
                    color: #ffd060;
                    font-size: 140;
                    font-weight: bold;
                    font-family: secondary;
                    text-transform: uppercase;
                  }
                  .vp_divider {
                    anchor-width: 560;
                    anchor-height: 3;
                    margin-top: 6;
                    horizontal-align: center;
                    background-color: #d8a43a;
                  }
                  .vp_subtitle {
                    anchor-width: 1920;
                    anchor-height: 46;
                    margin-top: 14;
                    horizontal-align: center;
                    text-align: center;
                    color: #ffe9b0;
                    font-size: 26;
                    font-weight: bold;
                    font-family: secondary;
                    text-transform: uppercase;
                  }

                  /* Stats row: 3 cards (180) + 2 gaps (24) = 588 wide */
                  .vp_stats_row {
                    layout-mode: left;
                    anchor-width: 588;
                    anchor-height: 200;
                    margin-top: 48;
                    horizontal-align: center;
                  }
                  .vp_stat_gap {
                    anchor-width: 24;
                    anchor-height: 200;
                  }
                  .vp_stat_frame {
                    anchor-width: 180;
                    anchor-height: 200;
                    layout-mode: top;
                    background-color: #1a2a46;
                  }
                  .vp_stat_frame_inset {
                    anchor-width: 176;
                    anchor-height: 196;
                    margin-top: 2;
                    horizontal-align: center;
                    layout-mode: top;
                    background-color: #0d1830ee;
                  }
                  .vp_stat_label {
                    anchor-width: 176;
                    anchor-height: 30;
                    margin-top: 22;
                    text-align: center;
                    color: #9db4dc;
                    font-size: 16;
                    font-weight: bold;
                    font-family: secondary;
                    text-transform: uppercase;
                  }
                  .vp_stat_rule {
                    anchor-width: 60;
                    anchor-height: 2;
                    margin-top: 10;
                    horizontal-align: center;
                    background-color: #2f4978;
                  }
                  .vp_stat_value {
                    anchor-width: 176;
                    anchor-height: 80;
                    margin-top: 14;
                    text-align: center;
                    font-size: 56;
                    font-weight: bold;
                    font-family: secondary;
                  }

                  /* Button row centered (2 btns 260 + gap 28 = 548 wide) */
                  .vp_btn_row {
                    layout-mode: left;
                    anchor-width: 548;
                    anchor-height: 64;
                    margin-top: 56;
                    horizontal-align: center;
                  }
                  .vp_btn_gap {
                    anchor-width: 28;
                    anchor-height: 64;
                  }
                  .vp_btn {
                    anchor-width: 260;
                    anchor-height: 64;
                  }
                </style>
                <div class="page-overlay">
                    <div class="vp_bg">
                        <div class="vp_header_spacer"></div>
                        <label class="vp_eyebrow">Tower Complete</label>
                        <label class="vp_title">Victory</label>
                        <div class="vp_divider"></div>
                        <label class="vp_subtitle">You conquered all %d floors</label>
                        <div class="vp_stats_row">
                            %s
                        </div>
                        <div class="vp_btn_row">
                            <button id="%BTN_RESTART%" class="custom-textbutton vp_btn"
                                data-hyui-default-bg="background-image: HUD/Images/BtnGreen.png;"
                                data-hyui-hovered-bg="background-image: HUD/Images/BtnGreenHov.png;"
                                data-hyui-pressed-bg="background-image: HUD/Images/BtnGreenPrs.png;"
                                data-hyui-default-label-style="color: #ffffff; font-size: 20; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;"
                                data-hyui-hovered-label-style="color: #ffffff; font-size: 20; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;"
                                data-hyui-pressed-label-style="color: #ddffdd; font-size: 19; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;">
                                <label>New Run</label>
                            </button>
                            <div class="vp_btn_gap"></div>
                            <button id="%BTN_LOBBY%" class="custom-textbutton vp_btn"
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
                .formatted(stats.floorsCleared(), statsRow);
    }

    private static String statCard(String label, String value, String valueColor) {
        return ("""
                <div class="vp_stat_frame">
                    <div class="vp_stat_frame_inset">
                        <label class="vp_stat_label">%LABEL%</label>
                        <div class="vp_stat_rule"></div>
                        <label class="vp_stat_value" style="color: %COLOR%;">%VALUE%</label>
                    </div>
                </div>
                """)
                .replace("%LABEL%", label)
                .replace("%COLOR%", valueColor)
                .replace("%VALUE%", value);
    }

    private static String gap() {
        return "<div class=\"vp_stat_gap\"></div>";
    }

    private static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%d:%02d", minutes, seconds);
    }
}
