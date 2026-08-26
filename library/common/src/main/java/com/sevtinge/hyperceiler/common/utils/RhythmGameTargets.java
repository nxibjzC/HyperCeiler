/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 */
package com.sevtinge.hyperceiler.common.utils;

import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared target-app selection for the system-side rhythm latency features. */
public final class RhythmGameTargets {
    public static final String PREF_TARGET_APPS = "rhythm_latency_target_apps";
    public static final String PREF_TARGET_APPS_INITIALIZED =
        "rhythm_latency_target_apps_initialized";

    private static final String PHIGROS = "com.PigeonGames.Phigros";
    private static final String ASTRODX = "com.Reflektone.AstroDX";
    private static final String PARADIGM = "com.tunergames.paradigmchina";
    private static final String MODULE_PACKAGE = "com.sevtinge.hyperceiler";
    private static final Pattern PACKAGE_NAME = Pattern.compile(
        "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");

    private RhythmGameTargets() {}

    /**
     * Returns the explicit app set, or the legacy three-switch selection until the new picker
     * has been initialized. An explicitly saved empty set remains empty.
     */
    public static LinkedHashSet<String> getSelectedPackages() {
        Set<String> source = hasExplicitSelection()
            ? PrefsBridge.getStringSet(PREF_TARGET_APPS)
            : getLegacySelectedPackages();
        return sanitize(source);
    }

    /** Migrates the old three switches from the app process without changing their values. */
    public static void migrateLegacySelectionForApp() {
        if (PrefsBridge.isHookProcess()
            || PrefsBridge.getBoolean(PREF_TARGET_APPS_INITIALIZED, false)) {
            return;
        }

        if (!hasStoredTargetSet()) {
            PrefsBridge.putByApp(PREF_TARGET_APPS, getLegacySelectedPackages());
        }
        PrefsBridge.putByApp(PREF_TARGET_APPS_INITIALIZED, true);
    }

    private static boolean hasExplicitSelection() {
        return PrefsBridge.getBoolean(PREF_TARGET_APPS_INITIALIZED, false)
            || hasStoredTargetSet();
    }

    private static boolean hasStoredTargetSet() {
        try {
            SharedPreferences prefs = PrefsBridge.getSharedPreferences();
            return prefs != null && prefs.contains("prefs_key_" + PREF_TARGET_APPS);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static LinkedHashSet<String> getLegacySelectedPackages() {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (PrefsBridge.getBoolean("rhythm_latency_target_phigros", true)) {
            selected.add(PHIGROS);
        }
        if (PrefsBridge.getBoolean("rhythm_latency_target_astrodx", true)) {
            selected.add(ASTRODX);
        }
        if (PrefsBridge.getBoolean("rhythm_latency_target_paradigm", true)) {
            selected.add(PARADIGM);
        }
        return selected;
    }

    private static LinkedHashSet<String> sanitize(Set<String> packages) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (packages == null) return result;

        for (String packageName : packages) {
            if (packageName == null || packageName.length() > 255) continue;
            String trimmed = packageName.trim();
            if ("android".equals(trimmed) || MODULE_PACKAGE.equals(trimmed)) continue;
            if (PACKAGE_NAME.matcher(trimmed).matches()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
