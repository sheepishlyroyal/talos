package dev.glade.client.config;

/**
 * Gson-serialized persistent user settings for Glade. Plain data holder — no
 * behavior; {@link GladeConfigManager} owns loading, saving, and wiring values
 * into the live subsystems (Theme, Humanizer).
 */
public final class GladeConfig {
    public String themeMode = "DARK";
    public String activeProfile = "NATURAL";
    public double blurStrength = 1.0;
    public int uiPanelX = -1;
    public int uiPanelY = -1;
    public boolean bridgeAutoAccept = false;
    public long seed = 0L;
}
