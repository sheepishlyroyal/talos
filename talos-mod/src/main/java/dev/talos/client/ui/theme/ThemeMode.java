package dev.talos.client.ui.theme;

/**
 * Which {@link ThemePalette} preset is active. {@link #SYSTEM} is meant to follow the
 * OS appearance; Talos has no OS-appearance query yet, so it resolves to
 * {@link ThemePalette#DARK} for now (see {@link Theme}).
 */
public enum ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}
