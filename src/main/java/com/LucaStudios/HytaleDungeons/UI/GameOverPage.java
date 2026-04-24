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
 * Modal game-over screen (no lives left). Same visual treatment as
 * {@link DeathPage} — transparent-red fullscreen overlay, "You Have Died!"
 * title, skull — but shows "No more lives!" and offers two buttons:
 *
 * <ul>
 *   <li><b>Restart</b> — calls {@link RunStateManager#onNewRunRequested}.</li>
 *   <li><b>Lobby</b>  — reopens the {@link MainMenuPage}.</li>
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
    private final ConcurrentHashMap<UUID, HyUIPage> activePages = new ConcurrentHashMap<>();

    public GameOverPage(RunStateManager runStateManager, MainMenuPage mainMenuPage) {
        this.runStateManager = runStateManager;
        this.mainMenuPage = mainMenuPage;
    }

    /** Open the game-over page. Must be called on the world thread. */
    public void showFor(PlayerRef playerRef, Store<EntityStore> store) {
        UUID playerId = playerRef.getUuid();
        final HyUIPage[] pageSlot = new HyUIPage[1];

        HyUIPage page = PageBuilder.pageForPlayer(playerRef)
                .fromHtml(HTML)
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
        Runnable flow = () -> {
            if (!playerRef.isValid()) return;
            // Reset the run so the player lands on floor 1 at the proper
            // spawn point (same pipeline the Restart button uses).
            runStateManager.onNewRunRequested(playerId, playerRef);
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
            world.execute(flow);
        } else {
            flow.run();
        }
        // Defer the main-menu open so the game-over close packet has time to
        // fully tear down on the client before the new page is wired up.
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

    private static final String HTML = """
            <style>
              .go_bg {
                anchor-top: 0;
                anchor-bottom: 0;
                anchor-left: 0;
                anchor-right: 0;
                background-color: #aa000088;
                layout-mode: top;
              }
              .go_top_spacer {
                anchor-width: 1920;
                anchor-height: 220;
              }
              .go_title {
                anchor-width: 1920;
                anchor-height: 140;
                text-align: center;
                color: #ffffff;
                font-size: 96;
                font-weight: bold;
                font-family: secondary;
              }
              .go_skull {
                anchor-width: 256;
                anchor-height: 256;
                horizontal-align: center;
                margin-top: 20;
                background-image: HUD/Images/SkullIcon.png;
              }
              .go_line {
                anchor-width: 1920;
                anchor-height: 100;
                margin-top: 30;
                text-align: center;
                color: #ffffff;
                font-size: 56;
                font-weight: bold;
                font-family: secondary;
              }
              .go_btn_row {
                layout-mode: left;
                layout-align: middlecenter;
                anchor-width: 1920;
                anchor-height: 80;
                margin-top: 40;
              }
              .go_btn {
                anchor-width: 260;
                anchor-height: 64;
              }
              .go_btn_gap {
                anchor-width: 20;
                anchor-height: 64;
              }
            </style>
            <div class="page-overlay">
                <div class="go_bg">
                    <div class="go_top_spacer"></div>
                    <label class="go_title">You Have Died!</label>
                    <div class="go_skull"></div>
                    <label class="go_line">No more lives!</label>
                    <div class="go_btn_row">
                        <button id="btn_restart" class="custom-textbutton go_btn"
                            data-hyui-default-bg="background-image: HUD/Images/BtnGreen.png;"
                            data-hyui-hovered-bg="background-image: HUD/Images/BtnGreenHov.png;"
                            data-hyui-pressed-bg="background-image: HUD/Images/BtnGreenPrs.png;"
                            data-hyui-default-label-style="color: #ffffff; font-size: 20; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;"
                            data-hyui-hovered-label-style="color: #ffffff; font-size: 20; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;"
                            data-hyui-pressed-label-style="color: #ddffdd; font-size: 19; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;">
                            <label>Restart</label>
                        </button>
                        <div class="go_btn_gap"></div>
                        <button id="btn_lobby" class="custom-textbutton go_btn"
                            data-hyui-default-bg="background-image: HUD/Images/BtnDark.png;"
                            data-hyui-hovered-bg="background-image: HUD/Images/BtnDarkHov.png;"
                            data-hyui-pressed-bg="background-image: HUD/Images/BtnDarkPrs.png;"
                            data-hyui-default-label-style="color: #cccccc; font-size: 18; font-family: secondary; text-align: center; vertical-align: center;"
                            data-hyui-hovered-label-style="color: #ffffff; font-size: 18; font-family: secondary; text-align: center; vertical-align: center;"
                            data-hyui-pressed-label-style="color: #aaaaaa; font-size: 18; font-family: secondary; text-align: center; vertical-align: center;">
                            <label>Go to Lobby</label>
                        </button>
                    </div>
                </div>
            </div>
            """;
}
