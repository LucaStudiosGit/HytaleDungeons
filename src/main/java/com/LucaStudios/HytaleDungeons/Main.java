package com.LucaStudios.HytaleDungeons;

import com.LucaStudios.HytaleDungeons.Camera.TopDownView;
import com.LucaStudios.HytaleDungeons.Combat.RightClickCrossbowHandler;
import com.LucaStudios.HytaleDungeons.InventoryHandler.InventoryOpenDisabler;
import com.LucaStudios.HytaleDungeons.Movement.ClickToMoveHandler;
import com.LucaStudios.HytaleDungeons.Pages.InventoryPage;
import com.LucaStudios.HytaleDungeons.Restrictions.PlayerRestrictions;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.logging.Level;

public class Main extends JavaPlugin {

    private static Main instance;

    private InventoryOpenDisabler inventoryOpenDisabler;
    private ClickToMoveHandler clickToMoveHandler;
    private RightClickCrossbowHandler rightClickCrossbowHandler;

    public Main(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static Main getInstance() {
        return instance;
    }

    @Override
    protected void setup() {
        getLogger().at(Level.INFO).log("HytaleDungeons Plugin start!");

        registerEvents();
        registerCommands();
    }

    @Override
    public void shutdown() {
        getLogger().at(Level.INFO).log("HytaleDungeons Plugin disabled!");

        if (clickToMoveHandler != null) {
            clickToMoveHandler.shutdown();
        }
        if (rightClickCrossbowHandler != null) {
            rightClickCrossbowHandler.shutdown();
        }
    }

    private void registerEvents() {
//        inventoryOpenDisabler = new InventoryOpenDisabler(this, new InventoryPage());
//        inventoryOpenDisabler.register();

        getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);

        clickToMoveHandler = new ClickToMoveHandler();
        clickToMoveHandler.register(this);

        new RightClickCrossbowHandler().register(this);

        new PlayerRestrictions(this).register();
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        Ref<EntityStore> entityRef = event.getPlayerRef();
        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        ItemStack sword = new ItemStack("Weapon_Sword_Iron", 1);
        world.execute(() -> {
            if (!entityRef.isValid()) {
                return;
            }

            PlayerRef playerRef = store.getComponent(entityRef, PlayerRef.getComponentType());
            if (playerRef == null) {
                return;
            }
            TopDownView.enable(playerRef);

            Player player = event.getPlayer();

            player.getInventory()
                    .getHotbar()
                    .setItemStackForSlot((short) 0, sword);
        });
    }

    private void registerCommands() {

    }
}
