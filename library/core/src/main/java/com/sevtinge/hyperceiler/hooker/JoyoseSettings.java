/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 */
package com.sevtinge.hyperceiler.hooker;

import static com.sevtinge.hyperceiler.sub.SubPickerActivity.LAUNCHER_MODE;

import android.content.Intent;

import androidx.preference.Preference;

import com.sevtinge.hyperceiler.common.utils.RhythmGameTargets;
import com.sevtinge.hyperceiler.core.R;
import com.sevtinge.hyperceiler.dashboard.DashboardFragment;
import com.sevtinge.hyperceiler.sub.SubPickerActivity;

/** Joyose settings with an installed-launcher-app picker for rhythm game targets. */
public class JoyoseSettings extends DashboardFragment {
    private static final String KEY_TARGET_APPS =
        "prefs_key_" + RhythmGameTargets.PREF_TARGET_APPS;

    private Preference targetApps;

    @Override
    public int getPreferenceScreenResId() {
        return R.xml.joyose;
    }

    @Override
    public void initPrefs() {
        RhythmGameTargets.migrateLegacySelectionForApp();
        targetApps = findPreference(KEY_TARGET_APPS);
        if (targetApps == null) return;

        // The picker owns this key as a StringSet; the navigation preference must not persist.
        targetApps.setPersistent(false);
        targetApps.setOnPreferenceClickListener(preference -> {
            Intent intent = new Intent(requireContext(), SubPickerActivity.class);
            intent.putExtra("mode", LAUNCHER_MODE);
            intent.putExtra("key", KEY_TARGET_APPS);
            startActivity(intent);
            return true;
        });
        updateTargetSummary();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateTargetSummary();
    }

    private void updateTargetSummary() {
        if (targetApps == null) return;
        int count = RhythmGameTargets.getSelectedPackages().size();
        targetApps.setSummary(getString(R.string.rhythm_latency_target_apps_desc, count));
    }
}
