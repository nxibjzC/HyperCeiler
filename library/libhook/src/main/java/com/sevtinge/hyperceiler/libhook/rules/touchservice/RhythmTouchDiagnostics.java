/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 */
package com.sevtinge.hyperceiler.libhook.rules.touchservice;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.RhythmGameTargets;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** Records the exact game-touch calls that reach Xiaomi's service and HAL wrapper. */
public class RhythmTouchDiagnostics extends BaseHook {
    private static final String STUB_CLASS =
        "com.xiaomi.touchservice.ITouchServiceStubImpl";
    private static final String FEATURE_CLASS =
        "com.xiaomi.touchservice.ITouchFeature";
    /** Last package explicitly selected through Xiaomi's touch feature wrapper. */
    private volatile String activeTargetPackage;

    @Override
    public void init() {
        hookServiceBoundary();
        hookHalWrapper();
    }

    private void hookServiceBoundary() {
        Class<?> stub = findClassIfExists(STUB_CLASS, getClassLoader());
        if (stub == null) {
            XposedLog.w(TAG, getPackageName(),
                "RhythmLatency: touch service stub missing; diagnostics disabled");
            return;
        }

        findAndHookMethod(stub, "setTouchMode",
            int.class, int.class, int.class, loggingHook("service.setTouchMode"));
        findAndHookMethod(stub, "resetTouchMode",
            int.class, int.class, loggingHook("service.resetTouchMode"));
        findAndHookMethod(stub, "getModeValues",
            int.class, int.class, loggingHook("service.getModeValues"));
    }

    private void hookHalWrapper() {
        Class<?> feature = findClassIfExists(FEATURE_CLASS, getClassLoader());
        if (feature == null) {
            XposedLog.w(TAG, getPackageName(),
                "RhythmLatency: ITouchFeature wrapper missing; HAL diagnostics disabled");
            return;
        }

        findAndHookMethod(feature, "setModePackageName",
            int.class, int.class, String.class, packageNameHook());
        findAndHookMethod(feature, "setTouchMode",
            int.class, int.class, int.class, loggingHook("hal.setTouchMode"));
        findAndHookMethod(feature, "resetTouchMode",
            int.class, int.class, loggingHook("hal.resetTouchMode"));
        findAndHookMethod(feature, "getModeValues",
            int.class, int.class, loggingHook("hal.getModeValues"));
    }

    private IMethodHook loggingHook(String operation) {
        return new IMethodHook() {
            @Override
            public void before(HookParam param) {
                if (activeTargetPackage == null) return;
                XposedLog.i(TAG, getPackageName(),
                    "RhythmLatency: package=" + activeTargetPackage + " " + operation
                        + " args=" + formatArgs(param.getArgs()));
            }

            @Override
            public void after(HookParam param) {
                if (activeTargetPackage == null) return;
                Object result = param.getResult();
                String formatted = result instanceof int[] values
                    ? java.util.Arrays.toString(values)
                    : String.valueOf(result);
                XposedLog.i(TAG, getPackageName(),
                    "RhythmLatency: package=" + activeTargetPackage + " " + operation
                        + " result=" + formatted);
            }
        };
    }

    private IMethodHook packageNameHook() {
        return new IMethodHook() {
            @Override
            public void before(HookParam param) {
                Object[] args = param.getArgs();
                String packageName = args.length > 2 && args[2] instanceof String
                    ? (String) args[2] : null;
                activeTargetPackage = isSelectedTarget(packageName) ? packageName : null;
                if (activeTargetPackage != null) {
                    XposedLog.i(TAG, getPackageName(),
                        "RhythmLatency: package=" + activeTargetPackage
                            + " hal.setModePackageName args=" + formatArgs(args));
                }
            }

            @Override
            public void after(HookParam param) {
                if (activeTargetPackage == null) return;
                XposedLog.i(TAG, getPackageName(),
                    "RhythmLatency: package=" + activeTargetPackage
                        + " hal.setModePackageName result=" + String.valueOf(param.getResult()));
            }
        };
    }

    private boolean isSelectedTarget(String packageName) {
        return packageName != null
            && RhythmGameTargets.getSelectedPackages().contains(packageName);
    }

    private String formatArgs(Object[] args) {
        return java.util.Arrays.toString(args);
    }
}
