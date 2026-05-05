package com.LucaStudios.HytaleDungeons.UI;

import au.ellie.hyui.builders.CustomButtonBuilder;
import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import com.LucaStudios.HytaleDungeons.Party.PartyManager;
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
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Lobby main menu. The page is built ONCE per player with every possible element
 * present (each with a stable id), and every event listener bound up front. State
 * changes mutate visibility/text in place via {@link HyUIPage#getById} and flush
 * with {@link HyUIPage#updatePage}. We never close + reopen for refreshes — that
 * was the source of both the visible blank gap and the broken-bindings problem.
 */
public final class MainMenuPage {

    static final String DISCORD_URL         = "https://discord.gg/WTdNGetHsP";
    static final String BTN_START           = "btn_start";
    static final String BTN_START_PARTY     = "btn_start_party";
    static final String BTN_CREATE          = "btn_create_party";
    static final String BTN_JOIN            = "btn_join_party";
    static final String BTN_LEAVE           = "btn_leave_party";
    static final String BTN_BURGER          = "btn_burger";
    static final String BTN_CLOSE           = "btn_close";       // X inside the open sidebar
    static final String EL_BURGER_WRAP      = "el_burger_wrap";  // wraps PARTY btn so we can hide it when sidebar is open
    static final String BTN_DISCORD         = "btn_discord";
    static final String BTN_QUIT            = "btn_quit";
    static final String DISCONNECT_REASON   = "Thanks for playing!\nJoin our Discord: discord.gg/WTdNGetHsP";
    static final String DISCORD_CHAT_PREFIX = "Join our Discord: ";

    // Sidebar panel and its inner sections
    static final String EL_PANEL         = "el_panel";
    static final String EL_PANEL_ACTIONS = "el_panel_actions";  // create/join (not in party)
    static final String EL_PANEL_INPARTY = "el_panel_inparty";  // in-party management
    static final String LBL_INPUT        = "lbl_input";
    static final String LBL_ERROR        = "lbl_error";
    static final String LBL_PARTY_CODE   = "lbl_party_code";
    static final String LBL_PARTY_COUNT  = "lbl_party_count";   // "X / 4" in header


    interface PageController { void close(); }
    interface PlayerActions {
        void sendDiscordLink(String prefix, String url);
        void disconnect(String reason);
    }

    private final JavaPlugin plugin;
    private final ConcurrentHashMap<UUID, HyUIPage>  activePages   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlayerRef> storedRefs    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean>   panelOpen     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String>    pendingCode   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String>    lastJoinError = new ConcurrentHashMap<>();

    private RunStateManager runStateManager;
    private PartyManager    partyManager;

    public MainMenuPage(JavaPlugin plugin) { this.plugin = plugin; }

    public void setRunStateManager(RunStateManager rsm) { this.runStateManager = rsm; }
    public void setPartyManager(PartyManager pm)         { this.partyManager    = pm;  }

    public void register() {
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        plugin.getEventRegistry().registerGlobal(PlayerChatEvent.class, this::onChat);
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        UUID playerId = event.getPlayerRef().getUuid();
        // Unhide this player from each remaining lobby player so they don't stay invisible
        // to others if they reconnect or another player joins later.
        PlayerRef leaving = storedRefs.get(playerId);
        if (leaving != null) clearLobbyIsolation(leaving);
        closeFor(playerId);
        storedRefs.remove(playerId);
        panelOpen.remove(playerId);
        pendingCode.remove(playerId);
        lastJoinError.remove(playerId);
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        var entityRef = event.getPlayerRef();
        var store     = entityRef.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!entityRef.isValid()) return;
            PlayerRef playerRef = store.getComponent(entityRef, PlayerRef.getComponentType());
            if (playerRef == null) return;
            openFor(playerRef, store);
        });
    }

    /** Intercepts chat as party-code input when the panel is open and not in a party. */
    private void onChat(PlayerChatEvent event) {
        PlayerRef sender   = event.getSender();
        UUID      playerId = sender.getUuid();
        if (!Boolean.TRUE.equals(panelOpen.get(playerId))) return;
        if (partyManager != null && partyManager.isInParty(playerId)) return;
        String text = event.getContent();
        if (text == null || text.isBlank()) return;
        event.setCancelled(true);
        pendingCode.put(playerId, text.trim().toUpperCase());
        lastJoinError.remove(playerId);
        scheduleRefresh(playerId);
    }

    public HyUIPage showFor(PlayerRef playerRef, Store<EntityStore> store) {
        return openFor(playerRef, store);
    }

    public void refreshFor(UUID playerId) {
        scheduleRefresh(playerId);
    }

    private void scheduleRefresh(UUID playerId) {
        HyUIPage page = activePages.get(playerId);
        if (page == null) return;
        PlayerRef ref = storedRefs.get(playerId);
        if (ref == null || !ref.isValid()) return;
        Ref<EntityStore> entityRef = ref.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        World world = entityRef.getStore().getExternalData().getWorld();
        if (world == null) return;

        world.execute(() -> {
            HyUIPage current = activePages.get(playerId);
            if (current == null) return;
            applyState(current, playerId);
            try { current.updatePage(true); }
            catch (Throwable t) {
                plugin.getLogger().at(Level.WARNING).withCause(t).log("MainMenuPage updatePage failed");
            }
        });
    }

    private HyUIPage openFor(PlayerRef playerRef, Store<EntityStore> store) {
        UUID playerId = playerRef.getUuid();
        closeFor(playerId);
        storedRefs.put(playerId, playerRef);
        prepareLobby(playerRef, store);
        return openPageFor(playerRef, store);
    }

    private HyUIPage openPageFor(PlayerRef playerRef, Store<EntityStore> store) {
        UUID playerId = playerRef.getUuid();

        HyUIPage existing = activePages.get(playerId);
        if (existing != null) {
            applyState(existing, playerId);
            try { existing.updatePage(true); } catch (Throwable ignored) {}
            return existing;
        }

        PlayerActions actions = liveActions(playerRef);

        PageBuilder builder = PageBuilder.pageForPlayer(playerRef)
                .fromHtml(buildHtml())
                .withLifetime(CustomPageLifetime.CantClose)
                .addEventListener(BTN_DISCORD, CustomUIEventBindingType.Activating,
                        v -> handleDiscord(actions))
                .addEventListener(BTN_QUIT, CustomUIEventBindingType.Activating,
                        v -> handleQuit(actions))
                .addEventListener(BTN_BURGER, CustomUIEventBindingType.Activating, v -> {
                    boolean current = Boolean.TRUE.equals(panelOpen.get(playerId));
                    panelOpen.put(playerId, !current);
                    if (current) {
                        pendingCode.remove(playerId);
                        lastJoinError.remove(playerId);
                    }
                    scheduleRefresh(playerId);
                })
                .addEventListener(BTN_CLOSE, CustomUIEventBindingType.Activating, v -> {
                    panelOpen.put(playerId, false);
                    pendingCode.remove(playerId);
                    lastJoinError.remove(playerId);
                    scheduleRefresh(playerId);
                })
                .addEventListener(BTN_START, CustomUIEventBindingType.Activating, v -> {
                    panelOpen.remove(playerId);
                    pendingCode.remove(playerId);
                    lastJoinError.remove(playerId);
                    clearLobbyIsolation(playerRef);
                    closeFor(playerId);
                    storedRefs.remove(playerId);
                    if (runStateManager != null) runStateManager.startRunFromLobby(playerId, playerRef);
                })
                .addEventListener(BTN_CREATE, CustomUIEventBindingType.Activating, v -> {
                    if (partyManager != null) {
                        String code = partyManager.createParty(playerId, playerRef.getUsername());
                        playerRef.sendMessage(Message.raw(
                                "Party created! Code: " + code
                                + " — share it and friends can join via the party menu."));
                    }
                    pendingCode.remove(playerId);
                    lastJoinError.remove(playerId);
                    scheduleRefresh(playerId);
                })
                .addEventListener(BTN_JOIN, CustomUIEventBindingType.Activating, v -> {
                    if (partyManager == null) return;
                    String code = pendingCode.getOrDefault(playerId, "");
                    if (code.isEmpty()) {
                        playerRef.sendMessage(Message.raw("Type your party code in chat first!"));
                        return;
                    }
                    PartyManager.JoinResult result = partyManager.joinParty(playerId, code, playerRef.getUsername());
                    switch (result) {
                        case SUCCESS -> {
                            pendingCode.remove(playerId);
                            lastJoinError.remove(playerId);
                            Set<UUID> members = partyManager.getPartyMembers(playerId);
                            members.forEach(this::scheduleRefresh);
                        }
                        case FULL -> {
                            lastJoinError.put(playerId, "Party \"" + code + "\" is full (" + PartyManager.MAX_PARTY_SIZE + " max).");
                            scheduleRefresh(playerId);
                        }
                        case NOT_FOUND -> {
                            lastJoinError.put(playerId, "\"" + code + "\" not found — check the code.");
                            scheduleRefresh(playerId);
                        }
                    }
                })
                .addEventListener(BTN_LEAVE, CustomUIEventBindingType.Activating, v -> {
                    if (partyManager == null) return;
                    Set<UUID> before = partyManager.getPartyMembers(playerId);
                    partyManager.leaveParty(playerId);
                    scheduleRefresh(playerId);
                    before.forEach(mid -> { if (!mid.equals(playerId)) scheduleRefresh(mid); });
                })
                .addEventListener(BTN_START_PARTY, CustomUIEventBindingType.Activating, v -> {
                    clearLobbyIsolation(playerRef);
                    closeFor(playerId);
                    storedRefs.remove(playerId);
                    if (runStateManager != null) runStateManager.startRunFromLobby(playerId, playerRef);
                });

        HyUIPage page = builder.open(store);
        activePages.put(playerId, page);
        applyState(page, playerId);
        try { page.updatePage(true); } catch (Throwable ignored) {}
        return page;
    }

    /** Re-applies derived state (visibility + dynamic labels) onto an open page. */
    private void applyState(HyUIPage page, UUID playerId) {
        boolean inParty     = partyManager != null && partyManager.isInParty(playerId);
        boolean isLeader    = inParty && partyManager.isLeader(playerId);
        boolean isPanelOpen = Boolean.TRUE.equals(panelOpen.get(playerId));

        // Open: full-height sidebar visible, PARTY button hidden (X inside sidebar closes it).
        // Closed: PARTY button visible, sidebar hidden.
        setVisible(page, EL_PANEL,        isPanelOpen);
        setVisible(page, EL_BURGER_WRAP, !isPanelOpen);

        // Build ordered player list: leader always in slot 1, others sorted A→Z by username
        Set<UUID> members = (partyManager != null)
                ? partyManager.getPartyMembers(playerId)
                : Set.of(playerId);
        UUID leaderId = inParty ? partyManager.getLeaderId(playerId) : playerId;
        if (leaderId == null) leaderId = playerId;

        final UUID leader = leaderId;
        List<UUID> ordered = new ArrayList<>(members.size());
        if (members.contains(leader)) ordered.add(leader);
        members.stream()
                .filter(m -> !m.equals(leader))
                .sorted(Comparator.comparing(m -> getDisplayName(m, playerId).toLowerCase()))
                .forEach(ordered::add);

        // Players header count
        setLabelText(page, LBL_PARTY_COUNT, members.size() + " / " + PartyManager.MAX_PARTY_SIZE);

        // Fill slots 1-PartyManager.MAX_PARTY_SIZE. We explicitly toggle visibility on every inner
        // label too — HyUI's withVisible(false) on a parent doesn't always hide
        // descendants, so HOST text would otherwise bleed through on empty rows.
        for (int i = 1; i <= PartyManager.MAX_PARTY_SIZE; i++) {
            boolean filled = i <= ordered.size();
            setVisible(page, slotFilled(i), filled);
            setVisible(page, slotEmpty(i),  !filled);
            setVisible(page, slotIdx(i),    filled);
            setVisible(page, slotName(i),   filled);
            if (filled) {
                UUID uid = ordered.get(i - 1);
                setLabelText(page, slotName(i), getDisplayName(uid, playerId));
                setVisible(page, slotHost(i), uid.equals(leaderId));
            } else {
                setVisible(page, slotHost(i), false);
            }
        }

        // Sidebar sub-sections
        setVisible(page, EL_PANEL_ACTIONS, !inParty);
        setVisible(page, EL_PANEL_INPARTY, inParty);

        // Code input state
        String pendCode = pendingCode.getOrDefault(playerId, "");
        String joinErr  = lastJoinError.getOrDefault(playerId, "");
        setLabelText(page, LBL_INPUT, pendCode.isEmpty() ? "type code in chat..." : pendCode);
        setLabelText(page, LBL_ERROR, joinErr);
        setVisible(page, LBL_ERROR, !joinErr.isEmpty());

        // In-party labels
        String partyCode = inParty ? partyManager.getPartyCode(playerId).orElse("?") : "?";
        setLabelText(page, LBL_PARTY_CODE, "CODE: " + partyCode);

        // Bottom-row game start buttons
        setVisible(page, BTN_START,       !inParty);
        setVisible(page, BTN_START_PARTY, inParty && isLeader);
    }

    // ── Slot element ID helpers ───────────────────────────────────────────────

    private static String slotFilled(int i) { return "el_s" + i + "_f"; }
    private static String slotEmpty(int i)  { return "el_s" + i + "_e"; }
    private static String slotName(int i)   { return "lbl_s" + i;       }
    private static String slotHost(int i)   { return "el_s" + i + "_h"; }
    private static String slotIdx(int i)    { return "lbl_idx" + i;     }

    /** Gets the display name for a UUID: own ref for self, PartyManager cache for others. */
    private String getDisplayName(UUID uuid, UUID self) {
        if (uuid.equals(self)) {
            PlayerRef ref = storedRefs.get(uuid);
            return ref != null ? ref.getUsername() : "?";
        }
        if (partyManager != null) {
            String name = partyManager.getUsername(uuid);
            if (name != null) return name;
        }
        return "?";
    }

    private static void setVisible(HyUIPage page, String id, boolean visible) {
        page.getByIdRaw(id).ifPresent(e -> e.withVisible(visible));
    }

    private static void setLabelText(HyUIPage page, String id, String text) {
        page.getById(id, LabelBuilder.class).ifPresent(l -> l.withText(text));
    }

    private static void setBtnText(HyUIPage page, String id, String text) {
        page.getById(id, CustomButtonBuilder.class).ifPresent(b -> b.withText(text));
    }

    // ── Lobby setup ───────────────────────────────────────────────────────────

    private void prepareLobby(PlayerRef playerRef, Store<EntityStore> store) {
        LobbyConfig config = LobbyConfig.getInstance();
        if (config == null || !playerRef.isValid()) return;
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        try {
            store.addComponent(entityRef, Teleport.getComponentType(),
                    Teleport.createForPlayer(
                            new Vector3d(config.getSpawnX(), config.getSpawnY(), config.getSpawnZ()),
                            new Vector3f(config.getSpawnPitch(), config.getSpawnYaw(), 0f)));
        } catch (Throwable t) {
            plugin.getLogger().at(Level.WARNING).log("prepareLobby teleport failed: %s", t.getMessage());
        }
        String weatherId = config.getWeather();
        if (weatherId != null && !weatherId.isBlank()) {
            try {
                WeatherResource w = store.getResource(WeatherPlugin.get().getWeatherResourceType());
                if (w != null) w.setForcedWeather(weatherId);
            } catch (Throwable t) {
                plugin.getLogger().at(Level.WARNING).log("prepareLobby weather failed: %s", t.getMessage());
            }
        }

        // Lobby isolation: this player cannot push other lobby players, and other lobby
        // players are hidden from this player (and vice versa). Cleared on game start.
        try {
            store.addComponent(entityRef, Intangible.getComponentType(), Intangible.INSTANCE);
        } catch (Throwable t) {
            plugin.getLogger().at(Level.WARNING).log("prepareLobby intangible failed: %s", t.getMessage());
        }
        UUID selfId = playerRef.getUuid();
        for (var e : storedRefs.entrySet()) {
            UUID otherId = e.getKey();
            if (otherId.equals(selfId)) continue;
            PlayerRef otherRef = e.getValue();
            if (otherRef == null || !otherRef.isValid()) continue;
            try {
                playerRef.getHiddenPlayersManager().hidePlayer(otherId);
                otherRef.getHiddenPlayersManager().hidePlayer(selfId);
            } catch (Throwable t) {
                plugin.getLogger().at(Level.WARNING).log("prepareLobby hide failed: %s", t.getMessage());
            }
        }
    }

    /** Reverses lobby isolation for a player about to leave the lobby (e.g. starting a run). */
    private void clearLobbyIsolation(PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) return;
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) return;
        UUID selfId = playerRef.getUuid();
        try {
            entityRef.getStore().removeComponentIfExists(entityRef, Intangible.getComponentType());
        } catch (Throwable t) {
            plugin.getLogger().at(Level.WARNING).log("clearLobbyIsolation intangible remove failed: %s", t.getMessage());
        }
        // Unhide self from each remaining lobby player so reconnections / re-entries are clean.
        for (var e : storedRefs.entrySet()) {
            UUID otherId = e.getKey();
            if (otherId.equals(selfId)) continue;
            PlayerRef otherRef = e.getValue();
            if (otherRef == null || !otherRef.isValid()) continue;
            try {
                otherRef.getHiddenPlayersManager().showPlayer(selfId);
                playerRef.getHiddenPlayersManager().showPlayer(otherId);
            } catch (Throwable t) {
                plugin.getLogger().at(Level.WARNING).log("clearLobbyIsolation show failed: %s", t.getMessage());
            }
        }
    }

    public void closeFor(UUID playerId) {
        HyUIPage page = activePages.remove(playerId);
        if (page != null) { try { page.close(); } catch (Throwable ignored) {} }
    }

    private static PlayerActions liveActions(PlayerRef playerRef) {
        return new PlayerActions() {
            @Override public void sendDiscordLink(String prefix, String url) {
                playerRef.sendMessage(Message.raw(prefix).insert(Message.raw(url).link(url)));
            }
            @Override public void disconnect(String reason) {
                playerRef.getPacketHandler().disconnect(Message.raw(reason));
            }
        };
    }

    static void handleStart(PageController page) { if (page != null) page.close(); }
    static void handleDiscord(PlayerActions a)   { a.sendDiscordLink(DISCORD_CHAT_PREFIX, DISCORD_URL); }
    static void handleQuit(PlayerActions a)      { a.disconnect(DISCONNECT_REASON); }

    // ── HTML ──────────────────────────────────────────────────────────────────

    private static String buildHtml() {
        StringBuilder h = new StringBuilder();
        h.append(CSS);
        h.append("<div class=\"page-overlay\"><div class=\"pg\">");

        // ── TITLE: pinned to top, horizontally centred, NEVER shifted by sidebar ──
        h.append("<div style=\"anchor-top: 28; anchor-left: 0; anchor-right: 0; anchor-height: 80; "
                + "layout-mode: top; horizontal-align: center;\">");
        h.append(titleRow());
        h.append("</div>");

        // ── BURGER (open menu): same 40×36 BtnDark square as the close X, but with three
        // literal horizontal lines drawn as thin divs (true hamburger icon, not a glyph).
        h.append("<div id=\"").append(EL_BURGER_WRAP)
                .append("\" style=\"anchor-top: 7; anchor-right: 14; anchor-width: 40; anchor-height: 36;\">");
        h.append(burgerLinesBtn(BTN_BURGER));
        h.append("</div>");

        // ── SIDEBAR: right-edge panel with solid background. Stops just above the bottom
        // button row (which is anchor-bottom: 14, anchor-height: 70 → top edge ~84px from
        // screen bottom; we leave an extra ~16px gap).
        h.append("<div id=\"").append(EL_PANEL)
                .append("\" style=\"anchor-top: 0; anchor-bottom: 100; anchor-right: 0; "
                + "anchor-width: 280; layout-mode: top; "
                + "background-image: HUD/Images/HudBackground.png;\">");

        // Top header strip with the X close button on the right — small grey square.
        // Layout: [flex 1][X 40][14] = 280, height 50.
        h.append("<div style=\"anchor-width: 280; anchor-height: 50; layout-mode: left; "
                + "layout-align: middlecenter;\">");
        h.append("<div style=\"flex-weight: 1; anchor-height: 50;\"></div>");
        h.append(btn(BTN_CLOSE, 40, 36, "#cccccc", "X", "BtnDark"));
        h.append("<div style=\"anchor-width: 14; anchor-height: 50;\"></div>");
        h.append("</div>");

        // In-party section: PLAYERS list + party code + LEAVE.
        // Hidden completely when the player isn't in a party.
        // horizontal-align: center centres the inner 256-wide rows inside the 280 sidebar
        // so the boxes have a small gap on each side.
        h.append("<div id=\"").append(EL_PANEL_INPARTY)
                .append("\" style=\"anchor-width: 280; layout-mode: top; horizontal-align: center;\">");

        // Players header — 256 wide so it matches the slot rows below; right-edge counter
        // aligns with the LEADER badge in slot rows.
        // Layout: [14][PLAYERS 164][COUNT 64 right-aligned][14] = 256
        h.append("<div style=\"anchor-width: 256; anchor-height: 46; layout-mode: left; "
                + "layout-align: middlecenter; background-image: HUD/Images/HudBackground.png;\">");
        h.append("<div style=\"anchor-width: 14; anchor-height: 46;\"></div>");
        h.append("<label style=\"anchor-width: 164; anchor-height: 46; "
                + "color: #e8a020; font-size: 20; font-weight: bold; "
                + "font-family: secondary; text-transform: uppercase; "
                + "vertical-align: center;\">PLAYERS</label>");
        h.append("<label id=\"").append(LBL_PARTY_COUNT)
                .append("\" style=\"anchor-width: 64; anchor-height: 46; "
                + "color: #888888; font-size: 14; font-weight: bold; "
                + "text-align: right; vertical-align: center;\">1 / 4</label>");
        h.append("<div style=\"anchor-width: 14; anchor-height: 46;\"></div>");
        h.append("</div>");

        // Player slots 1–PartyManager.MAX_PARTY_SIZE, with a thin slate divider between rows
        for (int i = 1; i <= PartyManager.MAX_PARTY_SIZE; i++) {
            h.append(playerSlot(i));
            if (i < PartyManager.MAX_PARTY_SIZE) {
                h.append("<div style=\"anchor-width: 256; anchor-height: 1; "
                        + "background-color: #33425a;\"></div>");
            }
        }

        // Party code + separator + leave. Code sits in a darker box that contrasts
        // against the sidebar's HudBackground.png (otherwise the box would be invisible).
        h.append("<div style=\"anchor-width: 280; anchor-height: 12;\"></div>");
        h.append(sidebarCentredRow(40,
                "<div style=\"anchor-width: 256; anchor-height: 40; "
                + "background-color: #0a0e15; "
                + "layout-mode: left; layout-align: middlecenter;\">"
                + "<label id=\"" + LBL_PARTY_CODE + "\" style=\"anchor-width: 256; anchor-height: 40; "
                + "text-align: center; vertical-align: center; "
                + "font-size: 16; font-weight: bold; font-family: secondary; "
                + "color: #aaddff;\">CODE: ?</label>"
                + "</div>"));
        h.append("<div style=\"anchor-width: 280; anchor-height: 10;\"></div>");
        h.append(sidebarCentredRow(2,
                "<div style=\"anchor-width: 220; anchor-height: 2; background-color: #33425a;\"></div>"));
        h.append("<div style=\"anchor-width: 280; anchor-height: 10;\"></div>");
        h.append(sidebarCentredRow(44, btn(BTN_LEAVE, 256, 40, "#ffaaaa", "LEAVE PARTY", "BtnDark")));

        h.append("</div>"); // el_panel_inparty

        // Not-in-party section: CREATE / JOIN code. No PLAYERS list here.
        h.append("<div id=\"").append(EL_PANEL_ACTIONS)
                .append("\" style=\"anchor-width: 280; layout-mode: top;\">");
        h.append(sidebarCentredRow(44, btn(BTN_CREATE, 256, 40, "#aaddff", "CREATE PARTY", "BtnDark")));
        // "OR JOIN WITH CODE" inline divider — same style as the in-party separator,
        // with the label sitting in the centre between two short rule segments.
        // Layout: [14][line 56][8][label 124][8][line 56][14] = 280
        h.append("<div style=\"anchor-width: 280; anchor-height: 20; layout-mode: left; "
                + "layout-align: middlecenter;\">");
        h.append("<div style=\"anchor-width: 14; anchor-height: 20;\"></div>");
        h.append("<div style=\"anchor-width: 56; anchor-height: 2; background-color: #33425a;\"></div>");
        h.append("<div style=\"anchor-width: 8; anchor-height: 20;\"></div>");
        h.append("<label style=\"anchor-width: 124; anchor-height: 20; text-align: center; "
                + "vertical-align: center; font-size: 11; color: #888888;\">OR JOIN WITH CODE</label>");
        h.append("<div style=\"anchor-width: 8; anchor-height: 20;\"></div>");
        h.append("<div style=\"anchor-width: 56; anchor-height: 2; background-color: #33425a;\"></div>");
        h.append("<div style=\"anchor-width: 14; anchor-height: 20;\"></div>");
        h.append("</div>");
        h.append(inputBox());
        h.append("<div style=\"anchor-width: 280; anchor-height: 4;\"></div>");
        h.append(sidebarCentredRow(44, btn(BTN_JOIN, 256, 40, "#ffffff", "JOIN PARTY", "BtnGreen")));
        h.append("</div>"); // el_panel_actions

        h.append("</div>"); // el_panel (sidebar)

        // ── BOTTOM ROW: absolutely positioned at bottom; START left / DISCORD+EXIT right ──
        h.append("<div style=\"anchor-bottom: 14; anchor-left: 0; anchor-right: 0; "
                + "anchor-height: 70; layout-mode: left; layout-align: middlecenter;\">");
        h.append("<div style=\"anchor-width: 60;\"></div>");
        h.append(btn(BTN_START,       300, 64, "#ffffff", "START GAME",       "BtnGreen"));
        h.append(btn(BTN_START_PARTY, 310, 64, "#ffffff", "START WITH PARTY", "BtnGreen"));
        h.append("<div style=\"flex-weight: 1;\"></div>");
        h.append(discordBtn());
        h.append("<div style=\"anchor-width: 10;\"></div>");
        h.append(btn(BTN_QUIT, 200, 52, "#cccccc", "EXIT", "BtnDark"));
        h.append("<div style=\"anchor-width: 60;\"></div>");
        h.append("</div>");

        h.append("</div></div>");
        return h.toString();
    }

    private static String titleRow() {
        return "<div style=\"anchor-width: 1200; layout-mode: top; horizontal-align: center;\">"
                + "<label style=\"anchor-width: 1200; text-align: center; font-size: 36;\">HYTALE DUNGEONS</label>"
                + "<label style=\"anchor-width: 1200; text-align: center; font-size: 14;\">Descend. Survive. Ascend.</label>"
                + "</div>";
    }

    // ── HTML helpers ──────────────────────────────────────────────────────────

    /** Renders one player slot row (filled + empty variants, both pre-rendered). Solid dark bg, no button look. */
    private static String playerSlot(int i) {
        // Layout: [14][16 idx][8][140 name][64 LEADER][14] = 256
        // Rows are 256 wide (centred in the 280-wide sidebar by horizontal-align)
        // so the boxes appear inset from the sidebar edges with ~12px of gap each side.
        String filled = "<div id=\"" + slotFilled(i) + "\" style=\"anchor-width: 256; anchor-height: 38; "
                + "background-color: #2a3140; "
                + "layout-mode: left; layout-align: middlecenter;\">"
                + "<div style=\"anchor-width: 14; anchor-height: 38;\"></div>"
                + "<label id=\"" + slotIdx(i) + "\" style=\"anchor-width: 16; anchor-height: 38; "
                + "color: #888888; font-size: 13; font-weight: bold; "
                + "font-family: secondary; vertical-align: center;\">" + i + "</label>"
                + "<div style=\"anchor-width: 8; anchor-height: 38;\"></div>"
                + "<label id=\"" + slotName(i) + "\" style=\"anchor-width: 140; anchor-height: 38; "
                + "color: #ffffff; font-size: 15; "
                + "font-family: secondary; vertical-align: center;\"></label>"
                + "<label id=\"" + slotHost(i) + "\" style=\"anchor-width: 64; anchor-height: 38; "
                + "color: #ffcc44; font-size: 11; "
                + "font-weight: bold; font-family: secondary; text-transform: uppercase; "
                + "text-align: right; vertical-align: center;\">LEADER</label>"
                + "<div style=\"anchor-width: 14; anchor-height: 38;\"></div>"
                + "</div>";

        String empty = "<div id=\"" + slotEmpty(i) + "\" style=\"anchor-width: 256; anchor-height: 38; "
                + "background-color: #161a23; "
                + "layout-mode: left; layout-align: middlecenter;\">"
                + "<div style=\"anchor-width: 14; anchor-height: 38;\"></div>"
                + "<label style=\"anchor-width: 228; anchor-height: 38; "
                + "color: #555555; font-size: 14; font-family: secondary; "
                + "vertical-align: center;\">Empty Slot</label>"
                + "<div style=\"anchor-width: 14; anchor-height: 38;\"></div>"
                + "</div>";

        return filled + empty;
    }

    private static String inputBox() {
        String box = "<div style=\"anchor-width: 256; anchor-height: 40; "
                   + "background-color: #0a0e15; "
                   + "layout-mode: left; layout-align: middlecenter;\">"
                   + "<label id=\"" + LBL_INPUT + "\" style=\"color: #aaddff; font-size: 15; "
                   + "font-weight: bold; font-family: secondary; "
                   + "margin-left: 12; vertical-align: center;\">type code in chat...</label>"
                   + "</div>";
        String err = "<label id=\"" + LBL_ERROR + "\" style=\"color: #ff6666; font-size: 11; "
                   + "text-align: center; anchor-width: 256; anchor-height: 16;\"></label>";
        return sidebarCentredRow(60,
                "<div style=\"layout-mode: top; layout-align: center; anchor-width: 256;\">"
                + box + err + "</div>");
    }

    /** Full-sidebar-width row with content horizontally centred. */
    private static String sidebarCentredRow(int height, String content) {
        return "<div style=\"anchor-width: 280; anchor-height: " + height + "; layout-mode: left; "
                + "layout-align: middlecenter;\">"
                + "<div style=\"flex-weight: 1;\"></div>"
                + content
                + "<div style=\"flex-weight: 1;\"></div>"
                + "</div>";
    }

    private static String btn(String id, int w, int h, String color, String label, String imgBase) {
        String def = "background-image: HUD/Images/" + imgBase + ".png;";
        String hov = "background-image: HUD/Images/" + imgBase + "Hov.png;";
        String prs = "background-image: HUD/Images/" + imgBase + "Prs.png;";
        String lbl = "color: " + color + "; font-size: 17; font-weight: bold; font-family: secondary; "
                   + "text-transform: uppercase; text-align: center; vertical-align: center;";
        return "<button id=\"" + id + "\" class=\"custom-textbutton\" "
                + "style=\"anchor-width: " + w + "; anchor-height: " + h + ";\" "
                + "data-hyui-default-bg=\"" + def + "\" "
                + "data-hyui-hovered-bg=\"" + hov + "\" "
                + "data-hyui-pressed-bg=\"" + prs + "\" "
                + "data-hyui-default-label-style=\"" + lbl + "\" "
                + "data-hyui-hovered-label-style=\"" + lbl + "\" "
                + "data-hyui-pressed-label-style=\"" + lbl + "\">"
                + "<label>" + label + "</label></button>";
    }

    /**
     * 40×36 BtnDark button with a hamburger menu icon drawn as three literal
     * horizontal line divs (no font glyph involved).
     */
    private static String burgerLinesBtn(String id) {
        // Three 22×2 light-grey bars, separated by 6-tall transparent spacers.
        // Total icon height: 2 + 6 + 2 + 6 + 2 = 18, vertically centred in the 36-tall button.
        String line   = "<div style=\"anchor-width: 22; anchor-height: 2; background-color: #cccccc;\"></div>";
        String spacer = "<div style=\"anchor-width: 22; anchor-height: 6;\"></div>";
        return "<button id=\"" + id + "\" class=\"custom-button\" "
                + "style=\"anchor-width: 40; anchor-height: 36; "
                + "layout-mode: top; layout-align: middlecenter;\" "
                + "data-hyui-default-bg=\"background-image: HUD/Images/BtnDark.png;\" "
                + "data-hyui-hovered-bg=\"background-image: HUD/Images/BtnDarkHov.png;\" "
                + "data-hyui-pressed-bg=\"background-image: HUD/Images/BtnDarkPrs.png;\">"
                + line + spacer + line + spacer + line
                + "</button>";
    }

    private static String discordBtn() {
        return "<button id=\"" + BTN_DISCORD + "\" class=\"custom-button\" "
                + "style=\"anchor-width: 200; anchor-height: 52; layout-mode: left; layout-align: middlecenter;\" "
                + "data-hyui-default-bg=\"background-image: HUD/Images/BtnDark.png;\" "
                + "data-hyui-hovered-bg=\"background-image: HUD/Images/BtnDarkHov.png;\" "
                + "data-hyui-pressed-bg=\"background-image: HUD/Images/BtnDarkPrs.png;\">"
                + "<img src=\"HUD/Images/DiscordIcon.png\" "
                + "style=\"anchor-width: 28; anchor-height: 28; margin-left: 12; margin-right: 8;\"/>"
                + "<label style=\"color: #cccccc; font-size: 16; font-weight: bold; font-family: secondary; "
                + "text-transform: uppercase; vertical-align: center;\">Discord</label>"
                + "</button>";
    }

    private static final String CSS = """
            <style>
              .pg   { anchor-top: 0; anchor-bottom: 0; anchor-left: 0; anchor-right: 0; }
              .vspc { flex-weight: 1; }
            </style>
            """;
}
