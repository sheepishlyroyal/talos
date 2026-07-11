package dev.glade.client.ui.theme;

/**
 * Which {@link ThemePalette} preset is active. {@link #SYSTEM} is meant to follow the
 * OS appearance; Glade has no OS-appearance query yet, so it resolves to
 * {@link ThemePalette#DARK} for now (see {@link Theme}).
 */
public enum ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}
