package com.LucaStudios.HytaleDungeons.UI;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.PageBuilder;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.builtin.weather.WeatherPlugin;
import com.hypixel.hytale.builtin.weather.resources.WeatherResource;
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

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

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
    private final ConcurrentHashMap<UUID, HyUIPage> activePages = new ConcurrentHashMap<>();
    private RunStateManager runStateManager;

    public MainMenuPage(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Wire the run-state manager so the Start button can kick off a run. */
    public void setRunStateManager(RunStateManager runStateManager) {
        this.runStateManager = runStateManager;
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
            openFor(playerRef, store);
        });
    }

    /** Reopen the menu on demand (e.g. "Back to Lobby" from the game-over screen). */
    public HyUIPage showFor(PlayerRef playerRef, Store<EntityStore> store) {
        return openFor(playerRef, store);
    }

    private HyUIPage openFor(PlayerRef playerRef, Store<EntityStore> store) {
        final UUID playerId = playerRef.getUuid();
        final PlayerActions actions = liveActions(playerRef);

        // Close any previously-open menu for this player before opening a new one,
        // so Lobby-reopen after death doesn't leave the prior page handle orphaned.
        closeFor(playerId);

        prepareLobby(playerRef, store);

        PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                .fromHtml(MAIN_MENU_HTML)
                .withLifetime(CustomPageLifetime.CantClose)
                .addEventListener(BTN_START, CustomUIEventBindingType.Activating,
                        v -> {
                            plugin.getLogger().at(Level.INFO)
                                    .log("MainMenu: Start pressed by %s", playerId);
                            handleStart(wrap(activePages.remove(playerId)));
                            if (runStateManager != null) {
                                runStateManager.startRunFromLobby(playerId, playerRef);
                            }
                        })
                .addEventListener(BTN_DISCORD, CustomUIEventBindingType.Activating,
                        v -> handleDiscord(actions))
                .addEventListener(BTN_QUIT, CustomUIEventBindingType.Activating,
                        v -> handleQuit(actions));

        HyUIPage page = builder.open(store);
        activePages.put(playerId, page);
        return page;
    }

    private void prepareLobby(PlayerRef playerRef, Store<EntityStore> store) {
        LobbyConfig config = LobbyConfig.getInstance();
        if (config == null) return;
        if (!playerRef.isValid()) return;

        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;

        try {
            Teleport teleport = Teleport.createForPlayer(
                    new Vector3d(config.getSpawnX(), config.getSpawnY(), config.getSpawnZ()),
                    new Vector3f(config.getSpawnPitch(), config.getSpawnYaw(), 0f));
            store.addComponent(entityRef, Teleport.getComponentType(), teleport);
        } catch (Throwable t) {
            plugin.getLogger().at(Level.WARNING)
                    .log("prepareLobby: teleport addComponent failed — %s",
                            t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        String weatherId = config.getWeather();
        if (weatherId != null && !weatherId.isBlank()) {
            try {
                WeatherResource weather = store.getResource(
                        WeatherPlugin.get().getWeatherResourceType());
                if (weather != null) weather.setForcedWeather(weatherId);
            } catch (Throwable t) {
                plugin.getLogger().at(Level.WARNING)
                        .log("Failed to set lobby weather '%s': %s",
                                weatherId, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /** Force-close any active main menu for this player. Safe to call from any thread. */
    public void closeFor(UUID playerId) {
        HyUIPage page = activePages.remove(playerId);
        if (page != null) {
            try { page.close(); } catch (Throwable ignored) {}
        }
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
                anchor-top: 0;
                anchor-bottom: 0;
                anchor-left: 0;
                anchor-right: 0;
                layout-mode: top;
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
              .menu_start_size {
                anchor-width: 340;
                anchor-height: 64;
              }
              .menu_dark_size {
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
                        <button id="btn_start" class="custom-textbutton menu_start_size"
                            data-hyui-default-bg="background-image: HUD/Images/BtnGreen.png;"
                            data-hyui-hovered-bg="background-image: HUD/Images/BtnGreenHov.png;"
                            data-hyui-pressed-bg="background-image: HUD/Images/BtnGreenPrs.png;"
                            data-hyui-default-label-style="color: #ffffff; font-size: 20; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;"
                            data-hyui-hovered-label-style="color: #ffffff; font-size: 20; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;"
                            data-hyui-pressed-label-style="color: #ddffdd; font-size: 19; font-weight: bold; font-family: secondary; text-transform: uppercase; text-align: center; vertical-align: center;">
                            <label>START GAME</label>
                        </button>
                        <div class="menu_h_spacer"></div>
                        <button id="btn_discord" class="custom-button menu_dark_size"
                            data-hyui-default-bg="background-image: HUD/Images/BtnDark.png;"
                            data-hyui-hovered-bg="background-image: HUD/Images/BtnDarkHov.png;"
                            data-hyui-pressed-bg="background-image: HUD/Images/BtnDarkPrs.png;"
                            style="layout-mode: left; layout-align: middlecenter;">
                            <img src="HUD/Images/DiscordIcon.png"
                                style="anchor-width: 28; anchor-height: 28; margin-left: 12; margin-right: 8;"/>
                            <label style="color: #cccccc; font-size: 16; font-weight: bold; font-family: secondary; text-transform: uppercase; vertical-align: center;">Discord</label>
                        </button>
                        <div class="menu_btn_gap"></div>
                        <button id="btn_quit" class="custom-textbutton menu_dark_size"
                            data-hyui-default-bg="background-image: HUD/Images/BtnDark.png;"
                            data-hyui-hovered-bg="background-image: HUD/Images/BtnDarkHov.png;"
                            data-hyui-pressed-bg="background-image: HUD/Images/BtnDarkPrs.png;"
                            data-hyui-default-label-style="color: #cccccc; font-size: 16; font-family: secondary; text-align: center; vertical-align: center;"
                            data-hyui-hovered-label-style="color: #ffffff; font-size: 16; font-family: secondary; text-align: center; vertical-align: center;"
                            data-hyui-pressed-label-style="color: #aaaaaa; font-size: 16; font-family: secondary; text-align: center; vertical-align: center;">
                            <label>Exit</label>
                        </button>
                        <div class="menu_margin"></div>
                    </div>
                </div>
            </div>
            """;
}
