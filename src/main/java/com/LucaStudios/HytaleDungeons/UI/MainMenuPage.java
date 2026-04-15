package com.LucaStudios.HytaleDungeons.UI;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.PageBuilder;
import com.LucaStudios.HytaleDungeons.Config.LobbyConfig;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Shows a modal main menu (Start / Discord / Quit) on {@link PlayerReadyEvent}.
 *
 * <ul>
 *   <li><b>Start</b>: closes the page; the run was generated in the background.</li>
 *   <li><b>Discord</b>: sends the invite URL as a chat message. Does not close the menu.</li>
 *   <li><b>Quit</b>: disconnects the player from the server.</li>
 * </ul>
 *
 * One-shot per join — there is no re-open binding.
 *
 * <p>The three button handlers are defined against the {@link PageController} and
 * {@link PlayerActions} seams so they are unit-testable without HyUI or Hytale
 * types on the test classpath. Live implementations of those seams wrap the real
 * {@link HyUIPage} and {@link PlayerRef}.
 */
public final class MainMenuPage {

    static final String DISCORD_URL = "https://discord.gg/WTdNGetHsP";
    static final String BTN_START = "btn_start";
    static final String BTN_DISCORD = "btn_discord";
    static final String BTN_QUIT = "btn_quit";
    static final String DISCONNECT_REASON = "Thanks for playing!\nJoin our Discord: discord.gg/WTdNGetHsP";
    static final String DISCORD_CHAT_PREFIX = "Join our Discord: ";

    /** Abstracts the opened HyUI page so tests don't need HyUI on the classpath. */
    interface PageController {
        void close();
    }

    /** Abstracts per-player verbs (chat, disconnect) so tests don't need PlayerRef. */
    interface PlayerActions {
        void sendDiscordLink(String prefix, String url);

        void disconnect(String reason);
    }

    private final JavaPlugin plugin;
    private final LobbyConfig lobbyConfig;
    private final RunStateManager runStateManager;
    private final GameHud gameHud;

    public MainMenuPage(JavaPlugin plugin, LobbyConfig lobbyConfig, RunStateManager runStateManager, GameHud gameHud) {
        this.plugin = plugin;
        this.lobbyConfig = lobbyConfig;
        this.runStateManager = runStateManager;
        this.gameHud = gameHud;
    }

    public void register() {
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        var entityRef = event.getPlayerRef();
        var store = entityRef.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!entityRef.isValid()) return;
            PlayerRef playerRef = store.getComponent(entityRef, PlayerRef.getComponentType());
            if (playerRef == null) return;
            teleportToLobby(playerRef);
            openFor(playerRef, store);
        });
    }

    private void teleportToLobby(PlayerRef playerRef) {
        if (!playerRef.isValid()) return;
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return;
        Store<EntityStore> store = ref.getStore();

        LobbyConfig.LobbySpawn spawn = lobbyConfig.getLobbySpawn();
        Teleport teleport = Teleport.createForPlayer(
                new Vector3d(spawn.x(), spawn.y(), spawn.z()),
                new Vector3f(spawn.yaw(), spawn.pitch(), spawn.roll())
        );
        store.addComponent(ref, Teleport.getComponentType(), teleport);
    }

    private HyUIPage openFor(PlayerRef playerRef, Store<EntityStore> store) {
        // pageSlot holds the opened page so the Start handler can close it.
        // Lambdas capture the array, then read pageSlot[0] at click-time (after open() has run).
        final HyUIPage[] pageSlot = new HyUIPage[1];
        final PlayerActions actions = liveActions(playerRef);

        PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                .fromHtml(MAIN_MENU_HTML)
                .withLifetime(CustomPageLifetime.CantClose)
                .addEventListener(BTN_START, CustomUIEventBindingType.Activating,
                        v -> {
                            handleStart(wrap(pageSlot[0]));
                            runStateManager.startRun(playerRef.getUuid(), playerRef);
                            gameHud.show(playerRef);
                        })
                .addEventListener(BTN_DISCORD, CustomUIEventBindingType.Activating,
                        v -> handleDiscord(actions))
                .addEventListener(BTN_QUIT, CustomUIEventBindingType.Activating,
                        v -> handleQuit(actions));

        HyUIPage page = builder.open(store);
        pageSlot[0] = page;
        return page;
    }

    private static PageController wrap(HyUIPage page) {
        return page == null ? null : page::close;
    }

    private static PlayerActions liveActions(PlayerRef playerRef) {
        return new PlayerActions() {
            @Override
            public void sendDiscordLink(String prefix, String url) {
                Message msg = Message.raw(prefix)
                        .insert(Message.raw(url).link(url));
                playerRef.sendMessage(msg);
            }

            @Override
            public void disconnect(String reason) {
                playerRef.getPacketHandler().disconnect(Message.raw(reason));
            }
        };
    }

    // --- Pure handlers. Package-private for tests. ---

    static void handleStart(PageController page) {
        if (page != null) page.close();
    }

    static void handleDiscord(PlayerActions actions) {
        actions.sendDiscordLink(DISCORD_CHAT_PREFIX, DISCORD_URL);
        // Intentionally does NOT close the menu — player can still click Start after.
    }

    static void handleQuit(PlayerActions actions) {
        actions.disconnect(DISCONNECT_REASON);
    }

    private static final String MAIN_MENU_HTML = """
            <style>
              .menu_bg {
                anchor-width: 1955;
                anchor-height: 1080;
                background-color: #00000066;
                layout-mode: top;
                horizontal-align: center;
                vertical-align: center;
              }
              .menu_title_area {
                layout-mode: top;
                anchor-width: 1955;
                anchor-height: 120;
                horizontal-align: center;
                margin-top: 80;
              }
              .menu_v_spacer {
                flex-weight: 1;
              }
              .menu_bottom_row {
                layout-mode: left;
                layout-align: middlecenter;
                anchor-width: 1955;
                anchor-height: 70;
                margin-bottom: 10;
              }
              .menu_h_spacer {
                flex-weight: 1;
              }
              .menu_btn_gap {
                anchor-width: 10;
                anchor-height: 64;
              }
              .menu_margin {
                anchor-width: 60;
                anchor-height: 64;
              }
              .btn_start {
                anchor-width: 340;
                anchor-height: 64;
              }
              .btn_dark {
                anchor-width: 200;
                anchor-height: 52;
              }
            </style>
            <div class="page-overlay">
                <div class="menu_bg">
                    <div class="menu_title_area">
                        <label style="text-align: center; font-size: 36;">HYTALE DUNGEONS</label>
                        <label style="text-align: center; font-size: 14;">Descend. Survive. Ascend.</label>
                    </div>
                    <div class="menu_v_spacer"></div>
                    <div class="menu_bottom_row">
                        <div class="menu_margin"></div>
                        <button id="btn_start" class="custom-textbutton btn_start"
                            data-hyui-default-bg="background-image: HUD/Images/BtnGreen.png;"
                            data-hyui-hovered-bg="background-image: HUD/Images/BtnGreenHov.png;"
                            data-hyui-pressed-bg="background-image: HUD/Images/BtnGreenPrs.png;"
                            data-hyui-default-label-style="color: #ffffff; font-size: 20; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;"
                            data-hyui-hovered-label-style="color: #ffffff; font-size: 20; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;"
                            data-hyui-pressed-label-style="color: #ddffdd; font-size: 19; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;">
                            <label>START GAME</label>
                        </button>
                        <div class="menu_h_spacer"></div>
                        <button id="btn_discord" class="custom-textbutton btn_dark"
                            data-hyui-default-bg="background-image: HUD/Images/BtnDark.png;"
                            data-hyui-hovered-bg="background-image: HUD/Images/BtnDarkHov.png;"
                            data-hyui-pressed-bg="background-image: HUD/Images/BtnDarkPrs.png;"
                            data-hyui-default-label-style="color: #cccccc; font-size: 16; font-family: secondary; text-align: center; vertical-align: center;"
                            data-hyui-hovered-label-style="color: #ffffff; font-size: 16; font-family: secondary; text-align: center; vertical-align: center;"
                            data-hyui-pressed-label-style="color: #aaaaaa; font-size: 16; font-family: secondary; text-align: center; vertical-align: center;">
                            <label>Discord</label>
                        </button>
                        <div class="menu_btn_gap"></div>
                        <button id="btn_quit" class="custom-textbutton btn_dark"
                            data-hyui-default-bg="background-image: HUD/Images/BtnDark.png;"
                            data-hyui-hovered-bg="background-image: HUD/Images/BtnDarkHov.png;"
                            data-hyui-pressed-bg="background-image: HUD/Images/BtnDarkPrs.png;"
                            data-hyui-default-label-style="color: #cccccc; font-size: 16; font-family: secondary; text-align: center; vertical-align: center;"
                            data-hyui-hovered-label-style="color: #ffffff; font-size: 16; font-family: secondary; text-align: center; vertical-align: center;"
                            data-hyui-pressed-label-style="color: #aaaaaa; font-size: 16; font-family: secondary; text-align: center; vertical-align: center;"
                            <label>Exit</label>
                        </button>
                        <div class="menu_margin"></div>
                    </div>
                </div>
            </div>
            """;
}
