package com.LucaStudios.HytaleDungeons.Debug;

import com.LucaStudios.HytaleDungeons.FloorGen.FloorData;
import com.LucaStudios.HytaleDungeons.FloorGen.FloorGenerator;
import com.LucaStudios.HytaleDungeons.FloorGen.PlacedRoom;
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
        sb.append("--- Floor ").append(floor.getFloorNumber()).append(" ---\n");
        sb.append("Rooms: ").append(floor.getRooms().size()).append("\n");
        sb.append("Origin: (").append(floor.getOriginX()).append(", ")
                .append(floor.getOriginY()).append(", ").append(floor.getOriginZ()).append(")\n");
        sb.append("Total size: ").append(floor.getTotalSizeX()).append("x")
                .append(floor.getTotalSizeY()).append("x").append(floor.getTotalSizeZ()).append("\n");

        int[] sp = floor.getPlayerSpawnPoint();
        sb.append("Spawn: (").append(sp[0]).append(", ").append(sp[1]).append(", ").append(sp[2]).append(")\n");

        int[] ez = floor.getExitZoneOrigin();
        sb.append("Exit zone: (").append(ez[0]).append(", ").append(ez[1]).append(", ").append(ez[2]).append(") ")
                .append(floor.getExitZoneWidth()).append("x").append(floor.getExitZoneDepth()).append("\n");

        sb.append("Mob spawns: ").append(floor.getMobSpawnCount()).append("\n");

        for (int i = 0; i < floor.getRooms().size(); i++) {
            PlacedRoom room = floor.getRooms().get(i);
            sb.append("  [").append(i).append("] ").append(room.template().id())
                    .append(" (").append(room.template().type()).append(") at Z=").append(room.worldZ())
                    .append(" size=").append(room.template().sizeX()).append("x")
                    .append(room.template().sizeZ()).append("\n");
        }

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
                send(sender, "Regenerated floor " + floor + " with " + floorData.getRooms().size()
                        + " rooms and " + floorData.getMobSpawnCount() + " mob spawns.");
            }
        });
    }

    private void handleHelp(PlayerRef sender) {
        send(sender, "--- Debug Commands ---\n"
                + "!floorinfo — show current floor layout\n"
                + "!runinfo — show run state\n"
                + "!regen — regenerate current floor\n"
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
