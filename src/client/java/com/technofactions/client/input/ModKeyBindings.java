package com.technofactions.client.input;

import com.technofactions.client.ui.ClaimMapScreen;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class ModKeyBindings {

    private static final KeyBinding.Category MAIN_CATEGORY =
            KeyBinding.Category.create(Identifier.of("technofactions", "main"));

    private static KeyBinding OPEN_MAP;

    private ModKeyBindings() {}

    public static void register() {
        OPEN_MAP = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.technofactions.open_map",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                MAIN_CATEGORY
        ));
    }

    public static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        while (OPEN_MAP.wasPressed()) {
            if (mc.currentScreen instanceof ClaimMapScreen) mc.setScreen(null);
            else if (mc.currentScreen == null) mc.setScreen(new ClaimMapScreen());
        }
    }
}