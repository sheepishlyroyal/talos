package dev.glade.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GladeClientMod implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Glade");

    @Override public void onInitializeClient() { LOGGER.info("Glade initializing"); }
}
