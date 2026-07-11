package dev.glade.client.ui.theme;

/**
 * A fixed set of ARGB color tokens driving every {@code GladeUi} draw call. Screens
 * and widgets pull colors from {@link Theme#palette()} rather than hard-coding hex
 * values, so swapping the active {@link ThemeMode} re-themes the whole UI.
 */
public record ThemePalette(
        int bg,
        int panel,
        int panelHeader,
        int rect,
        int rectDark,
        int text,
        int description,
        int blurTint,
        int outline,
        int accent) {

    public static final ThemePalette DARK = new ThemePalette(
            0xFF1A1A20, 0xFF1E1E24, 0xFF26262E,
            0xFF2C2C34, 0xFF202026,
            0xFFD4D4D8, 0xFF878894,
            0xB01A1A20, 0xFF3A3A42, 0xFF3574F0);

    public static final ThemePalette LIGHT = new ThemePalette(
            0xFFF5F5F7, 0xFFFFFFFF, 0xFFEDEDF0,
            0xFFE4E4E8, 0xFFD2D2D8,
            0xFF1D1D1F, 0xFF6E6E76,
            0xB0F5F5F7, 0xFFD6D6DC, 0xFF3574F0);

    /** Warm dark variant — greige undertone instead of DARK's cool neutral. */
    public static final ThemePalette SOFT_DARK = new ThemePalette(
            0xFF262624, 0xFF30302E, 0xFF383836,
            0xFF3A3A37, 0xFF2C2C2A,
            0xFFE0DED8, 0xFF9C978C,
            0xB0262624, 0xFF44443F, 0xFFF9C12B);

    /** Warm light variant — greige undertone instead of LIGHT's cool neutral. */
    public static final ThemePalette SOFT_LIGHT = new ThemePalette(
            0xFFFAF9F5, 0xFFFFFFFF, 0xFFF0EEE7,
            0xFFE9E6DD, 0xFFDAD6C9,
            0xFF262420, 0xFF7A7568,
            0xB0FAF9F5, 0xFFDDD9CD, 0xFFE91E63);
}
