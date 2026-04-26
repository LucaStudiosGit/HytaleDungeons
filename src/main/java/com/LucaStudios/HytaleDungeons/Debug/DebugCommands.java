package com.LucaStudios.HytaleDungeons.Debug;

import com.LucaStudios.HytaleDungeons.FloorGen.FloorData;
import com.LucaStudios.HytaleDungeons.FloorGen.FloorGenerator;
import com.LucaStudios.HytaleDungeons.Run.RunData;
import com.LucaStudios.HytaleDungeons.Run.RunStateManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

/**
 * Debug chat commands for testing. Listens for chat messages starting with "!"
 * and responds with system state info. Remove before release.
 */
public final class DebugCommands {

    private final RunStateManager runStateManager;
    private final FloorGenerator floorGenerator;

    public DebugCommands(RunStateManager runStateManager, FloorGenerator floorGenerator) {
        this.runStateManager = runStateManager;
        this.floorGenerator = floorGenerator;
    }

    /**
     * Register chat listener for debug commands.
     */
    public void register(JavaPlugin plugin) {
        plugin.getEventRegistry().registerGlobal(PlayerChatEvent.class, this::onChat);
    }

    private void onChat(PlayerChatEvent event) {
        String content = event.getContent();
        if (content == null || !content.startsWith("!")) {
            return;
        }

        PlayerRef sender = event.getSender();
        UUID playerId = sender.getUuid();

        event.setCancelled(true);

        switch (content.trim().toLowerCase()) {
            case "!floorinfo" -> handleFloorInfo(sender, playerId);
            case "!runinfo" -> handleRunInfo(sender, playerId);
            case "!regen" -> handleRegen(sender, playerId);
            case "!finishfloor" -> handleFinishFloor(sender, playerId);
            case "!gameover" -> handleGameOver(sender, playerId);
            case "!help" -> handleHelp(sender);
            default -> send(sender, "Unknown command: " + content + " — type !help");
        }
    }

    private void handleFloorInfo(PlayerRef sender, UUID playerId) {
        FloorData floor = floorGenerator.getActiveFloor(playerId);
        if (floor == null) {
            send(sender, "No active floor.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("--- Floor ").append(floor.floorNumber()).append(" ---\n");

        float[] sp = floor.playerSpawnPoint();
        sb.append("Spawn: (").append(sp[0]).append(", ").append(sp[1]).append(", ").append(sp[2]).append(")\n");

        sb.append("Mob spawns: ").append(floor.getMobSpawnCount()).append("\n");

        send(sender, sb.toString());
    }

    private void handleRunInfo(PlayerRef sender, UUID playerId) {
        RunData data = runStateManager.getRunData(playerId);
        if (data == null) {
            send(sender, "No active run.");
            return;
        }

        String info = "--- Run Info ---\n"
                + "State: " + data.getState() + "\n"
                + "Floor: " + data.getCurrentFloor() + "\n"
                + "Lives: " + data.getLivesRemaining() + "\n"
                + "Mobs remaining: " + data.getMobsRemaining();
        send(sender, info);
    }

    private void handleRegen(PlayerRef sender, UUID playerId) {
        RunData data = runStateManager.getRunData(playerId);
        if (data == null) {
            send(sender, "No active run.");
            return;
        }

        int floor = data.getCurrentFloor();
        World world = worldFromPlayerRef(sender);
        floorGenerator.generateFloor(playerId, floor, world, sender, () -> {
            FloorData floorData = floorGenerator.getActiveFloor(playerId);
            if (floorData != null) {
                runStateManager.setMobCount(playerId, floorData.getMobSpawnCount());
            }
        });
    }

    private void handleFinishFloor(PlayerRef sender, UUID playerId) {
        send(sender, "Force-finishing current floor (debug)...");
        runStateManager.debugFinishFloor(playerId);
    }

    private void handleGameOver(PlayerRef sender, UUID playerId) {
        send(sender, "Opening game-over page (debug)...");
        runStateManager.debugOpenGameOver(playerId, sender);
    }

    private void handleHelp(PlayerRef sender) {
        send(sender, "--- Debug Commands ---\n"
                + "!floorinfo — show current floor layout\n"
                + "!runinfo — show run state\n"
                + "!regen — regenerate current floor\n"
                + "!finishfloor — clear current floor and open upgrade screen\n"
                + "!gameover — force-open game-over page\n"
                + "!help — this message");
    }

    private static World worldFromPlayerRef(PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) return null;
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return null;
        return ref.getStore().getExternalData().getWorld();
    }

    private void send(PlayerRef player, String text) {
        player.sendMessage(Message.raw(text));
    }
}
