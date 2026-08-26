/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.others;

import android.content.Context;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.RhythmGameTargets;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.util.HashSet;
import java.util.Set;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/**
 * Adds selected rhythm games to Xiaomi's native gesture RT scheduler.
 *
 * <p>HyperOS calls RealTimeModeControllerImpl.onDown()/onMove() for every
 * touch, but only packages in RT_PKG_WHITE_LIST get the stock two-second
 * main/render-thread boost. This hook changes only those exact package-list
 * entries. The OEM SchedBoostService still owns affinity, FIFO priority,
 * timeout, screen-off cleanup and thermal policy.</p>
 */
public class RhythmGameRtMode extends BaseHook {
    private static final String CONTROLLER =
        "com.android.server.wm.RealTimeModeControllerImpl";
    private final Set<String> addedToWhiteList = new HashSet<>();
    private final Set<String> removedFromBlackList = new HashSet<>();
    private Class<?> controllerClass;

    @Override
    public void init() {
        controllerClass = findClassIfExists(CONTROLLER, getClassLoader());
        if (controllerClass == null) {
            XposedLog.w(TAG, getPackageName(),
                "RhythmLatency: Xiaomi RTMode controller missing; touch boost disabled");
            return;
        }

        // Apply immediately for hot reloads and again after the firmware adds
        // its resource/cloud lists during boot.
        applySelectedPackages("hook-load");
        findAndHookMethod(controllerClass, "init", Context.class, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                applySelectedPackages("controller-init");
            }
        });
        findAndHookMethod(controllerClass, "updateCloudControlParas", new IMethodHook() {
            @Override
            public void after(HookParam param) {
                applySelectedPackages("cloud-update");
            }
        });

        registerHotReloadCleanup(this::restorePackageLists);
    }

    @SuppressWarnings("unchecked")
    private void applySelectedPackages(String reason) {
        Object rawWhite = getStaticObjectField(controllerClass, "RT_PKG_WHITE_LIST");
        Object rawBlack = getStaticObjectField(controllerClass, "RT_PKG_BLACK_LIST");
        if (!(rawWhite instanceof Set<?>) || !(rawBlack instanceof Set<?>)) {
            XposedLog.w(TAG, getPackageName(),
                "RhythmLatency: RTMode package lists have an unexpected type");
            return;
        }

        Set<String> whiteList = (Set<String>) rawWhite;
        Set<String> blackList = (Set<String>) rawBlack;
        Set<String> selected = selectedPackages();
        int added = 0;
        int unblocked = 0;
        synchronized (whiteList) {
            for (String packageName : selected) {
                if (whiteList.add(packageName)) {
                    addedToWhiteList.add(packageName);
                    added++;
                }
            }
        }
        synchronized (blackList) {
            for (String packageName : selected) {
                if (blackList.remove(packageName)) {
                    removedFromBlackList.add(packageName);
                    unblocked++;
                }
            }
        }
        XposedLog.i(TAG, getPackageName(),
            "RhythmLatency: RTMode reason=" + reason + " targets=" + selected
                + " added=" + added + " unblocked=" + unblocked
                + "; OEM 2000ms gesture timeout preserved");
    }

    @SuppressWarnings("unchecked")
    private void restorePackageLists() {
        if (controllerClass == null) return;
        Object rawWhite = getStaticObjectField(controllerClass, "RT_PKG_WHITE_LIST");
        Object rawBlack = getStaticObjectField(controllerClass, "RT_PKG_BLACK_LIST");
        if (rawWhite instanceof Set<?> && rawBlack instanceof Set<?>) {
            Set<String> whiteList = (Set<String>) rawWhite;
            Set<String> blackList = (Set<String>) rawBlack;
            synchronized (whiteList) {
                whiteList.removeAll(addedToWhiteList);
            }
            synchronized (blackList) {
                blackList.addAll(removedFromBlackList);
            }
            XposedLog.i(TAG, getPackageName(),
                "RhythmLatency: RTMode restored white=" + addedToWhiteList
                    + " black=" + removedFromBlackList);
        }
        addedToWhiteList.clear();
        removedFromBlackList.clear();
    }

    private Set<String> selectedPackages() {
        return RhythmGameTargets.getSelectedPackages();
    }
}
