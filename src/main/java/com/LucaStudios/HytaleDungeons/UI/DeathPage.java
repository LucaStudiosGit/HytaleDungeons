package com.LucaStudios.HytaleDungeons.UI;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.events.PageRefreshResult;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modal death screen shown for the duration of {@code RunStateManager.DEATH_SCREEN_DURATION_MS}.
 *
 * <p>Transparent red full-screen background with a "DEATH" title, skull image,
 * countdown (3 → 2 → 1), and remaining-lives label. No buttons; cannot be
 * dismissed with Esc ({@link CustomPageLifetime#CantClose}). Closes itself
 * when the countdown expires.</p>
 */
public final class DeathPage {

    static final String ID_COUNTDOWN = "death_countdown";
    static final long REFRESH_RATE_MS = 250L;

    private final ConcurrentHashMap<UUID, Long> deadlines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, HyUIPage> activePages = new ConcurrentHashMap<>();

    /**
     * Open the death page for a player. Must be called on the world thread.
     *
     * @param durationMs how long the page stays up before auto-closing
     * @param livesRemaining lives-left count baked into the page HTML at open time
     */
    public void showFor(PlayerRef playerRef,
                        Store<EntityStore> store,
                        long durationMs,
                        int livesRemaining) {
        UUID playerId = playerRef.getUuid();
        long deadline = System.currentTimeMillis() + durationMs;
        deadlines.put(playerId, deadline);

        String html = buildHtml(livesRemaining);

        HyUIPage page = PageBuilder.pageForPlayer(playerRef)
                .fromHtml(html)
                .withLifetime(CustomPageLifetime.CantClose)
                .withRefreshRate(REFRESH_RATE_MS)
                .onRefresh(p -> onRefresh(p, playerId))
                .open(store);
        activePages.put(playerId, page);
    }

    /** Called on HyUI scheduler thread. Update countdown label, close when expired. */
    private PageRefreshResult onRefresh(HyUIPage page, UUID playerId) {
        Long deadline = deadlines.get(playerId);
        if (deadline == null) {
            return PageRefreshResult.NONE;
        }
        long remainingMs = deadline - System.currentTimeMillis();
        if (remainingMs <= 0) {
            deadlines.remove(playerId);
            activePages.remove(playerId);
            page.close();
            return PageRefreshResult.NONE;
        }
        int secs = (int) Math.ceil(remainingMs / 1000.0);
        if (secs < 1) secs = 1;
        final String text = Integer.toString(secs);
        final String line = "Respawn in " + text + "..";
        page.getById(ID_COUNTDOWN, LabelBuilder.class).ifPresent(l -> l.withText(line));
        return PageRefreshResult.UPDATE;
    }

    /**
     * Force-close any active death page for this player (used on disconnect or
     * unexpected early respawn so the page doesn't leak).
     */
    public void closeFor(UUID playerId) {
        deadlines.remove(playerId);
        HyUIPage page = activePages.remove(playerId);
        if (page != null) {
            try { page.close(); } catch (Throwable ignored) {}
        }
    }

    private static String buildHtml(int livesRemaining) {
        int initialSecs = 3;
        return """
                <style>
                  .death_bg {
                    anchor-top: 0;
                    anchor-bottom: 0;
                    anchor-left: 0;
                    anchor-right: 0;
                    background-color: #aa000088;
                    layout-mode: top;
                  }
                  .death_top_spacer {
                    anchor-width: 1920;
                    anchor-height: 220;
                  }
                  .death_title {
                    anchor-width: 1920;
                    anchor-height: 140;
                    text-align: center;
                    color: #ffffff;
                    font-size: 96;
                    font-weight: bold;
                    font-family: secondary;
                  }
                  .death_skull {
                    anchor-width: 256;
                    anchor-height: 256;
                    horizontal-align: center;
                    margin-top: 20;
                    background-image: HUD/Images/SkullIcon.png;
                  }
                  .death_countdown {
                    anchor-width: 1920;
                    anchor-height: 100;
                    margin-top: 30;
                    text-align: center;
                    color: #ffffff;
                    font-size: 56;
                    font-weight: bold;
                    font-family: secondary;
                  }
                  .death_lives {
                    anchor-width: 1920;
                    anchor-height: 60;
                    margin-top: 20;
                    text-align: center;
                    color: #ffdddd;
                    font-size: 32;
                    font-family: secondary;
                  }
                </style>
                <div class="page-overlay">
                    <div class="death_bg">
                        <div class="death_top_spacer"></div>
                        <label class="death_title">You Have Died!</label>
                        <div class="death_skull"></div>
                        <label id="death_countdown" class="death_countdown">Respawn in %d..</label>
                        <label class="death_lives">Revivals remaining: %d</label>
                    </div>
                </div>
                """.formatted(initialSecs, livesRemaining);
    }
}
