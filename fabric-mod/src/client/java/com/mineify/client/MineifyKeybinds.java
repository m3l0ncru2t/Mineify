package com.mineify.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

@Environment(EnvType.CLIENT)
public class MineifyKeybinds {
    private static final KeyMapping.Category MINEIFY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mineify", "mineify"));

    private static KeyMapping openGuiKey;

    public static void register() {
        openGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mineify.open_gui",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_N,
                MINEIFY_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey.consumeClick()) {
                if (client.player != null) {
                    Minecraft.getInstance().setScreenAndShow(new MineifyScreen());
                }
            }
        });
    }
}
