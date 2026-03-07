package com.LucaStudios.HytaleDungeons.Pages;

import au.ellie.hyui.builders.PageBuilder;
import au.ellie.hyui.builders.HyUIPage;
import au.ellie.hyui.builders.ImageBuilder;
import au.ellie.hyui.elements.UIType;
import au.ellie.hyui.events.UIContext;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public final class InventoryPage {
    private static final String EXIT_BUTTON_IMAGE_ID = "ExitButtonImage";
    private static final String EXIT_IMAGE_DEFAULT = "Inventory/Images/ExitButton.png";
    private static final String EXIT_IMAGE_HOVER = "Inventory/Images/ExitButtonHover.png";
    private static final String EXIT_IMAGE_PRESSED = "Inventory/Images/ExitButtonPressed.png";

    public void open(PlayerRef playerRef) {
        var entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return;
        }

        var store = entityRef.getStore();
        var world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (!entityRef.isValid()) {
                return;
            }
            PageBuilder pageBuilder = PageBuilder.pageForPlayer(playerRef)
                    .withLifetime(CustomPageLifetime.CanDismiss)
                    .loadHtml("Inventory/Pages/InventoryPage.html", UIType.HYWIND);

            pageBuilder.addEventListener("ExitButton", CustomUIEventBindingType.MouseEntered, (ignored, ctx) ->
                    setExitButtonImage(ctx, EXIT_IMAGE_HOVER));
            pageBuilder.addEventListener("ExitButton", CustomUIEventBindingType.MouseExited, (ignored, ctx) ->
                    setExitButtonImage(ctx, EXIT_IMAGE_DEFAULT));
            pageBuilder.addEventListener("ExitButton", CustomUIEventBindingType.Activating, (ignored, ctx) ->
                    setExitButtonImage(ctx, EXIT_IMAGE_PRESSED));
            pageBuilder.addEventListener("ExitButton", CustomUIEventBindingType.MouseButtonReleased, (ignored, ctx) ->
                    ctx.getPage().ifPresent(HyUIPage::close));

            pageBuilder.open(store);
        });
    }

    private void setExitButtonImage(UIContext ctx, String imagePath) {
        ctx.editById(EXIT_BUTTON_IMAGE_ID, ImageBuilder.class, image -> image.withImage(imagePath));
        ctx.updatePage(true);
    }
}
