/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 */
package com.sevtinge.hyperceiler.libhook.app;

import com.hchen.database.HookBase;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseLoad;
import com.sevtinge.hyperceiler.libhook.rules.touchservice.RhythmTouchDiagnostics;

@HookBase(targetPackage = "com.xiaomi.touchservice")
public class TouchService extends BaseLoad {
    @Override
    public void onPackageLoaded() {
        boolean rhythmLatency = PrefsBridge.getBoolean("rhythm_latency_enable", false);
        boolean diagnostics = PrefsBridge.getBoolean("rhythm_latency_touch_diagnostics", false);
        initHook(new RhythmTouchDiagnostics(), rhythmLatency && diagnostics);
    }
}
