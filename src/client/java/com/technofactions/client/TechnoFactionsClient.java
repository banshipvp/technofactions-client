package com.technofactions.client;

import net.fabricmc.api.ClientModInitializer;

public final class TechnoFactionsClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        System.out.println("[TechnoFactions] Client mod loaded successfully.");
    }
}