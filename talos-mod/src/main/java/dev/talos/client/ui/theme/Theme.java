package dev.talos.client.ui.theme;

/**
 * Static holder for the UI toolkit's active {@link ThemeMode} and resolved
 * {@link ThemePalette}. Every {@code TalosUi} caller reads colors through
 * {@link #palette()}; flipping {@link #setMode} re-themes every screen still open,
 * since nothing caches a palette reference across frames.
 */
public final class Theme {
    private static ThemeMode mode = ThemeMode.SYSTEM;
    private static ThemePalette active = resolve(mode);

    private Theme() {
    }

    public static ThemePalette palette() {
        return active;
    }

    public static ThemeMode mode() {
        return mode;
    }

    public static void setMode(ThemeMode newMode) {
        mode = newMode;
        active = resolve(newMode);
    }

    private static ThemePalette resolve(ThemeMode requested) {
        return switch (requested) {
            // No OS-appearance query wired up yet; default SYSTEM to dark.
            case SYSTEM, DARK -> ThemePalette.DARK;
            case LIGHT -> ThemePalette.LIGHT;
        };
    }
}
