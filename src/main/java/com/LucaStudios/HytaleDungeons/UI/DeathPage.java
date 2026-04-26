package com.LucaStudios.HytaleDungeons.UI;

import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.LabelBuilder;
import au.ellie.hyui.builders.PageBuilder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Modal death screen shown for the duration of {@code RunStateManager.DEATH_SCREEN_DURATION_MS}.
 *
 * <p>Visually mirrors {@link GameOverPage} (crimson palette, eyebrow + title +
 * divider + subtitle layout) but without buttons — a large countdown card sits
 * where the stats/button rows go. Closes itself when the countdown expires.
 * Non-dismissable ({@link CustomPageLifetime#CantClose}).</p>
 *
 * <p>Countdown updates are driven by our own {@link ScheduledExecutorService}
 * rather than HyUI's {@code onRefresh} mechanism — HyUI cannot register
 * recurring refresh callbacks when a page is opened via {@code world.execute()}
 * (a one-shot deferred context). Instead, each tick updates the label in memory
 * and dispatches {@code page.updatePage(false)} on the world thread to push the
 * change to the client.</p>
 */
public final class DeathPage {

    static final String ID_COUNTDOWN = "death_countdown";
    static final long REFRESH_RATE_MS = 250L;

    private JavaPlugin plugin;
    private final ConcurrentHashMap<UUID, Long> deadlines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, HyUIPage> activePages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> refreshTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "death-page-refresh");
        t.setDaemon(true);
        return t;
    });

    /**
     * Open the death page for a player. Must be called on the world thread.
     *
     * @param durationMs how long the page stays up before auto-closing
     * @param livesRemaining lives-left count baked into the page HTML at open time
     */
    public void showFor(PlayerRef playerRef,
                        Store<EntityStore> store,
                        long durationMs,
                        int livesRemaining,
                        JavaPlugin plugin) {
        this.plugin = plugin;
        UUID playerId = playerRef.getUuid();
        long deadline = System.currentTimeMillis() + durationMs;
        deadlines.put(playerId, deadline);

        String html = buildHtml(livesRemaining, (int) Math.ceil(durationMs / 1000.0));

        HyUIPage page = PageBuilder.pageForPlayer(playerRef)
                .fromHtml(html)
                .withLifetime(CustomPageLifetime.CantClose)
                .open(store);
        if (page == null) {
            plugin.getLogger().at(Level.WARNING).log(
                    "DeathPage: PageBuilder.open() returned null for player " + playerId);
            deadlines.remove(playerId);
            return;
        }
        activePages.put(playerId, page);

        // HyUI cannot register recurring refresh callbacks when a page is opened
        // from world.execute() — drive countdown updates from our own scheduler instead.
        ScheduledFuture<?> task = refreshScheduler.scheduleAtFixedRate(
                () -> tickRefresh(playerId, playerRef, page),
                REFRESH_RATE_MS, REFRESH_RATE_MS, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> old = refreshTasks.put(playerId, task);
        if (old != null) old.cancel(false);
    }

    /**
     * Runs every {@link #REFRESH_RATE_MS} on the refresh scheduler thread.
     * Updates the countdown label in memory, then dispatches {@code updatePage}
     * to the world thread to push the change to the client.
     */
    private void tickRefresh(UUID playerId, PlayerRef playerRef, HyUIPage page) {
        Long deadline = deadlines.get(playerId);
        if (deadline == null) return;

        long remainingMs = deadline - System.currentTimeMillis();

        if (remainingMs <= 0) {
            deadlines.remove(playerId);
            activePages.remove(playerId);
            ScheduledFuture<?> task = refreshTasks.remove(playerId);
            if (task != null) task.cancel(false);
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref != null && ref.isValid()) {
                ref.getStore().getExternalData().getWorld().execute(page::close);
            }
            return;
        }

        int secs = (int) Math.ceil(remainingMs / 1000.0);
        if (secs < 1) secs = 1;
        final String text = Integer.toString(secs);
        page.getById(ID_COUNTDOWN, LabelBuilder.class).ifPresent(l -> l.withText(text));

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        ref.getStore().getExternalData().getWorld().execute(() -> page.updatePage(false));
    }

    /**
     * Force-close any active death page for this player (used on disconnect or
     * unexpected early respawn so the page doesn't leak).
     */
    public void closeFor(UUID playerId) {
        deadlines.remove(playerId);
        ScheduledFuture<?> task = refreshTasks.remove(playerId);
        if (task != null) task.cancel(false);
        HyUIPage page = activePages.remove(playerId);
        if (page != null) {
            try { page.close(); } catch (Throwable ignored) {}
        }
    }

    private void log(String format, Object... args) {
        plugin.getLogger().at(Level.INFO).log(String.format(format, args));
    }

    private static String buildHtml(int livesRemaining, int initialSecs) {
        return ("""
                <style>
                  .dp_bg {
                    anchor-top: 0;
                    anchor-bottom: 0;
                    anchor-left: 0;
                    anchor-right: 0;
                    background-color: #130308cc;
                    layout-mode: top;
                  }
                  .dp_header_spacer {
                    anchor-width: 1920;
                    anchor-height: 180;
                    horizontal-align: center;
                  }
                  .dp_eyebrow {
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
                  .dp_title {
                    anchor-width: 1920;
                    anchor-height: 140;
                    margin-top: 4;
                    horizontal-align: center;
                    text-align: center;
                    color: #ff5a5a;
                    font-size: 120;
                    font-weight: bold;
                    font-family: secondary;
                    text-transform: uppercase;
                  }
                  .dp_divider {
                    anchor-width: 560;
                    anchor-height: 3;
                    margin-top: 6;
                    horizontal-align: center;
                    background-color: #c03030;
                  }
                  .dp_subtitle {
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

                  /* Countdown card — mirrors GameOverPage stat-frame styling */
                  .dp_countdown_frame {
                    anchor-width: 260;
                    anchor-height: 220;
                    margin-top: 48;
                    layout-mode: top;
                    horizontal-align: center;
                    background-color: #46101a;
                  }
                  .dp_countdown_inset {
                    anchor-width: 256;
                    anchor-height: 216;
                    margin-top: 2;
                    horizontal-align: center;
                    layout-mode: top;
                    background-color: #150810ee;
                  }
                  .dp_countdown_label {
                    anchor-width: 256;
                    anchor-height: 30;
                    margin-top: 22;
                    text-align: center;
                    color: #c88898;
                    font-size: 16;
                    font-weight: bold;
                    font-family: secondary;
                    text-transform: uppercase;
                  }
                  .dp_countdown_rule {
                    anchor-width: 60;
                    anchor-height: 2;
                    margin-top: 10;
                    horizontal-align: center;
                    background-color: #5a2a36;
                  }
                  .dp_countdown_value {
                    anchor-width: 256;
                    anchor-height: 110;
                    margin-top: 10;
                    text-align: center;
                    color: #ff9060;
                    font-size: 96;
                    font-weight: bold;
                    font-family: secondary;
                  }
                </style>
                <div class="page-overlay">
                    <div class="dp_bg">
                        <div class="dp_header_spacer"></div>
                        <label class="dp_eyebrow">Respawning</label>
                        <label class="dp_title">You Died</label>
                        <div class="dp_divider"></div>
                        <label class="dp_subtitle">Revivals remaining: %d</label>
                        <div class="dp_countdown_frame">
                            <div class="dp_countdown_inset">
                                <label class="dp_countdown_label">Respawn In</label>
                                <div class="dp_countdown_rule"></div>
                                <label id="%ID_COUNTDOWN%" class="dp_countdown_value">%d</label>
                            </div>
                        </div>
                    </div>
                </div>
                """)
                .replace("%ID_COUNTDOWN%", ID_COUNTDOWN)
                .formatted(livesRemaining, initialSecs);
    }
}
