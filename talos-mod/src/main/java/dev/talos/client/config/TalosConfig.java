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
    /** Persisted by /talos bridge allow: once armed, scripts pushed from VS Code run
     *  without re-confirmation in every future session until the config is edited. */
    public boolean bridgeAutoAccept = false;
    public long seed = 0L;
    /** Global humanization intensity: 0 = near-robotic, 1 = profile default, up to 3. */
    public double humanIntensity = 1.0;
    /** Per-knob humanization overrides keyed by knob name (see HumanizationOverrides.Knob). */
    public java.util.Map<String, Double> humanKnobs = new java.util.LinkedHashMap<>();
    /** Trajectory-family restriction csv ("bezier,min_jerk,linear"); "" = profile default. */
    public String humanFamilies = "";
}
