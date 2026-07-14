package dev.talos.client.config;

/**
 * Gson-serialized persistent user settings for Talos. Plain data holder — no
 * behavior; {@link TalosConfigManager} owns loading, saving, and wiring values
 * into the live subsystems (Theme, Humanizer).
 */
public final class TalosConfig {
    public String themeMode = "DARK";
    public String activeProfile = "NATURAL";
    public double blurStrength = 1.0;
    public int uiPanelX = -1;
    public int uiPanelY = -1;
    public boolean bridgeAutoAccept = true;
    public long seed = 0L;
}
