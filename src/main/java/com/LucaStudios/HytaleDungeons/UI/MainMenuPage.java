package com.LucaStudios.HytaleDungeons.UI;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.PageBuilder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
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
    static final String DISCONNECT_REASON = "You chose to quit.";
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

    public MainMenuPage(JavaPlugin plugin) {
        this.plugin = plugin;
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

    private HyUIPage openFor(PlayerRef playerRef, Store<EntityStore> store) {
        // pageSlot holds the opened page so the Start handler can close it.
        // Lambdas capture the array, then read pageSlot[0] at click-time (after open() has run).
        final HyUIPage[] pageSlot = new HyUIPage[1];
        final PlayerActions actions = liveActions(playerRef);

        PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                .fromHtml(MAIN_MENU_HTML)
                .addEventListener(BTN_START, CustomUIEventBindingType.Activating,
                        v -> handleStart(wrap(pageSlot[0])))
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
            <div class="page-overlay">
                <div class="panel">
                    <div class="group" style="layout-mode: top; horizontal-align: center; vertical-align: middle; anchor-width: 420; anchor-height: 360;">
                        <label style="text-align: center; font-size: 36;">HYTALE DUNGEONS</label>
                        <label style="text-align: center; font-size: 14;">Descend. Survive. Ascend.</label>
                        <div class="group" style="anchor-height: 20;"></div>
                        <button id="btn_start" style="anchor-width: 320; anchor-height: 48;">Start</button>
                        <div class="group" style="anchor-height: 12;"></div>
                        <button id="btn_discord" style="anchor-width: 320; anchor-height: 48;">Discord</button>
                        <div class="group" style="anchor-height: 12;"></div>
                        <button id="btn_quit" style="anchor-width: 320; anchor-height: 48;">Quit</button>
                    </div>
                </div>
            </div>
            """;
}
