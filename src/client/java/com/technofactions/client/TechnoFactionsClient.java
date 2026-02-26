package com.technofactions.client;

import com.technofactions.client.input.ModKeyBindings;
import com.technofactions.client.net.Net;
import com.technofactions.client.ui.MinimapHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class TechnoFactionsClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Net.registerPayloads();
        Net.registerClientReceivers();

        ModKeyBindings.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> ModKeyBindings.tick());

        MinimapHud.register();
    }
}