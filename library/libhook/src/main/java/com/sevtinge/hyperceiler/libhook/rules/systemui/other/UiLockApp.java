/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemui.other;

import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.Miui.isPad;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * @author 焕晨HChen
 * @co-author LingQiqi & Codex(GPT-5.3-Codex)
 */
public class UiLockApp extends BaseHook {
    private static final String SETTING_KEY_LOCK_APP = "key_lock_app";
    private static final String SETTING_HIDE_GESTURE_LINE = "hide_gesture_line";
    private static final String STATE_CONTEXT = "UiLockApp.context";
    private static final String STATE_STATUS_BAR_VIEW = "UiLockApp.statusBarView";

    private static final String[] STATUS_BAR_WINDOW_CONTROLLER_CLASS_CANDIDATES = new String[] {
        "com.android.systemui.statusbar.window.StatusBarWindowControllerImpl",
        "com.android.systemui.statusbar.window.StatusBarWindowController"
    };
    private static final String[] GESTURE_HANDLE_CLASS_CANDIDATES = new String[] {
        "com.android.systemui.navigationbar.gestural.NavigationHandle",
        "com.android.systemui.navigationbar.gestural.QuickswitchOrientedNavHandle",
        "com.android.systemui.navigationbar.views.NavigationHandle",
        "com.android.systemui.navigationbar.gestural.GestureHandleView",
        "com.android.systemui.navigationbar.gestural.HomeHandle"
    };
    private static final String[] NAVIGATION_BAR_CLASS_CANDIDATES = new String[] {
        "com.android.systemui.navigationbar.views.NavigationBar",
        "com.android.systemui.navigationbar.NavigationBar"
    };
    private static final String[] TASKBAR_DELEGATE_CLASS_CANDIDATES = new String[] {
        "com.android.systemui.navigationbar.TaskbarDelegate"
    };
    private static final String[] WINDOW_DECORATION_CLASS_CANDIDATES = new String[] {
        // 具体实现类优先：基类方法被覆写时，hook 基类不会命中实际绘制路径。
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MiuiWindowDecoration",
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MiuiBaseWindowDecoration"
    };
    private static final String[] DOT_VIEW_CLASS_CANDIDATES = new String[] {
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationDotView",
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MiuiDotView",
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MiuiDecorationRootView"
    };
    private static final String[] DECORATION_DOT_CLASS_CANDIDATES = new String[] {
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationDot"
    };

    private boolean mObserverRegistered = false;
    private boolean mUnlockStateCheckScheduled = false;
    private int mUnlockStateCheckAttempts = 0;
    private Object mNotifPipeline;
    private View mStatusBarView;
    private WeakReference<Object> mCollapsedStatusBarFragment = new WeakReference<>(null);
    private Boolean mLastLockedState = null;

    private final List<WeakReference<View>> mGestureHandleViews = new ArrayList<>();
    private final List<WeakReference<Object>> mNavigationBars = new ArrayList<>();
    private final List<WeakReference<Object>> mTaskbarDelegates = new ArrayList<>();
    private final List<WeakReference<Object>> mWindowDecorations = new ArrayList<>();
    private final Map<View, Integer> mHandleVisibilityBackup = new WeakHashMap<>();
    private final Map<View, Float> mHandleAlphaBackup = new WeakHashMap<>();

    @Override
    public void init() {
        Context restoredContext = getHotReloadRuntimeState(STATE_CONTEXT, Context.class);
        View restoredStatusBarView = getHotReloadRuntimeState(STATE_STATUS_BAR_VIEW, View.class);
        if (restoredStatusBarView != null) {
            mStatusBarView = restoredStatusBarView;
        }
        if (restoredContext != null) {
            registerObserverIfNeeded(restoredContext);
            updateStatusBarVisibility(restoredContext);
        }

        // 不在此处直接加载/挂接 SystemUI 类：过早强制加载大量 SystemUI/WMShell 类会打乱
        // SystemUI 自身的启动与懒加载序列，导致其它 SystemUI hook 的效果失效（日志仍为 Success）。
        // 统一推迟到 SystemUIApplication.onCreate 执行前再安装。
        findAndChainMethod("com.android.systemui.SystemUIApplication",
            "onCreate",
            new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Context context = (Context) callMethod(chain.getThisObject(), "getApplicationContext");
                        reconcileStaleLockState(context);
                    } catch (Throwable e) {
                        XposedLog.w(TAG, "reconcile stale lock state E: " + e);
                    }
                    installSystemUiHooks();
                    Object result = chain.proceed();
                    try {
                        Context context = (Context) callMethod(chain.getThisObject(), "getApplicationContext");
                        registerObserverIfNeeded(context);
                        // 未锁定时保持完全被动，避免开机阶段干扰其它 SystemUI hook。
                        if (getLockApp(context) != -1) {
                            updateStatusBarVisibility(context);
                        }
                    } catch (Throwable e) {
                        XposedLog.w(TAG, "SystemUIApplication onCreate hook E: " + e);
                    }
                    return result;
                }
            }
        );
    }

    /** 在 SystemUIApplication.onCreate 执行前安装需要加载 SystemUI/WMShell 类的 hook。 */
    private void installSystemUiHooks() {
        for (String className : STATUS_BAR_WINDOW_CONTROLLER_CLASS_CANDIDATES) {
            hookStatusBarWindowControllerClass(className);
        }
        hookCollapsedStatusBarFragment();
        hookNotificationPipeline();
        for (String className : GESTURE_HANDLE_CLASS_CANDIDATES) {
            hookGestureHandleClass(className);
        }
        for (String className : NAVIGATION_BAR_CLASS_CANDIDATES) {
            hookNavigationBarClass(className);
        }
        for (String className : TASKBAR_DELEGATE_CLASS_CANDIDATES) {
            hookTaskbarDelegateClass(className);
        }
        for (String className : WINDOW_DECORATION_CLASS_CANDIDATES) {
            hookWindowDecorationClass(className);
        }
        for (String className : DOT_VIEW_CLASS_CANDIDATES) {
            hookDotViewClass(className);
        }
        for (String className : DECORATION_DOT_CLASS_CANDIDATES) {
            hookDecorationDotClass(className);
        }
        if (isPad()) {
            hookLauncherProxyStopScreenPinning();
        }
    }

    private void hookNotificationPipeline() {
        Class<?> controllerClass = findClassIfExists(
            "com.android.systemui.statusbar.notification.init.NotificationsControllerImpl",
            getClassLoader());
        if (controllerClass == null) return;

        chainAllMethods(controllerClass, "initialize", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    Object lazyPipeline = getObjectField(chain.getThisObject(), "notifPipeline");
                    if (lazyPipeline != null) {
                        mNotifPipeline = callMethod(lazyPipeline, "get");
                    }
                } catch (Throwable e) {
                    XposedLog.w(TAG, "capture notification pipeline E: " + e);
                }
                return result;
            }
        });
    }

    private void hookCollapsedStatusBarFragment() {
        Class<?> fragmentClass = findClassIfExists(
            "com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment",
            getClassLoader());
        if (fragmentClass == null) return;

        chainAllMethods(fragmentClass, "onViewCreated", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                mCollapsedStatusBarFragment = new WeakReference<>(chain.getThisObject());
                return result;
            }
        });
    }

    /**
     * 意外重启后 ATMS 的 lock task 状态必然清零，但 key_lock_app 是持久化设置会残留，
     * 导致 SystemUI 把系统误判为锁定中（状态栏/手势条被隐藏、手势被禁用），无法退出。
     * 这里以框架真实状态为准：真实锁定中（如 SystemUI 单独重启）不动，残留则清掉镜像设置。
     */
    private void reconcileStaleLockState(Context context) {
        if (context == null || getLockApp(context) == -1) return;
        try {
            Object activityManager = context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager == null) return;
            int lockTaskState = (Integer) callMethod(activityManager, "getLockTaskModeState");
            if (lockTaskState == ActivityManager.LOCK_TASK_MODE_LOCKED) return;
            XposedLog.d(TAG, "clear stale lock state after reboot, lockTaskState=" + lockTaskState);
            Settings.Global.putInt(context.getContentResolver(), SETTING_KEY_LOCK_APP, -1);
            Settings.Global.putInt(context.getContentResolver(), SETTING_HIDE_GESTURE_LINE, 0);
        } catch (Throwable e) {
            XposedLog.w(TAG, "reconcileStaleLockState E: " + e);
        }
    }

    private void hookStatusBarWindowControllerClass(String className) {
        // 与 DisableMiuiMultiWinSwitch 一致：目标进程最终 ClassLoader 加载，找不到就跳过。
        Class<?> controllerClass = findClassIfExists(className, getClassLoader());
        if (controllerClass == null || controllerClass.isInterface()) return;

        chainAllConstructors(controllerClass, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    Context context = (Context) getObjectField(chain.getThisObject(), "mContext");
                    if (context == null) return result;

                    Object statusBarWindowView = getObjectField(chain.getThisObject(), "mStatusBarWindowView");
                    if (statusBarWindowView instanceof FrameLayout) {
                        mStatusBarView = (FrameLayout) statusBarWindowView;
                        putHotReloadRuntimeState(STATE_STATUS_BAR_VIEW, mStatusBarView);
                    }
                    registerObserverIfNeeded(context);
                    // 未锁定时只注册 observer 和保存状态栏视图，不写任何 SystemUI 状态。
                    if (getLockApp(context) != -1) {
                        updateStatusBarVisibility(context);
                    }
                } catch (Throwable e) {
                    XposedLog.w(TAG, "StatusBarWindowController hook E: " + e);
                }
                return result;
            }
        });
    }

    private void hookGestureHandleClass(String className) {
        Class<?> gestureHandleClass = findClassIfExists(className, getClassLoader());
        if (gestureHandleClass == null) return;

        chainAllConstructors(gestureHandleClass, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof View handleView) {
                    registerGestureHandleView(handleView);
                }
                return result;
            }
        });

        chainAllMethods(gestureHandleClass, "setVisibility", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (!(chain.getThisObject() instanceof View handleView)) return chain.proceed();
                if (!isLocked(handleView)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[0] = View.GONE;
                return chain.proceed(args);
            }
        });

        chainAllMethods(gestureHandleClass, "setAlpha", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (!(chain.getThisObject() instanceof View handleView)) return chain.proceed();
                if (!isLocked(handleView)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[0] = 0f;
                return chain.proceed(args);
            }
        });

        chainAllMethods(gestureHandleClass, "onDraw", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (!(chain.getThisObject() instanceof View handleView)) return chain.proceed();
                if (!isLocked(handleView)) return chain.proceed();
                return null;
            }
        });
    }

    private void registerGestureHandleView(View view) {
        for (WeakReference<View> reference : mGestureHandleViews) {
            if (reference.get() == view) return;
        }
        mGestureHandleViews.add(new WeakReference<>(view));
        Context context = view.getContext();
        if (context == null) return;
        registerObserverIfNeeded(context);
        updateGestureHandleVisibility(getLockApp(context) != -1);
    }

    private void hookNavigationBarClass(String className) {
        Class<?> navigationBarClass = findClassIfExists(className, getClassLoader());
        if (navigationBarClass == null) return;

        chainAllConstructors(navigationBarClass, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                registerNavigationBar(chain.getThisObject());
                return result;
            }
        });

        chainAllMethods(navigationBarClass, "updateScreenPinningGestures", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                registerNavigationBar(chain.getThisObject());
                return chain.proceed();
            }
        });
    }

    private void registerNavigationBar(Object navigationBar) {
        if (navigationBar == null) return;
        for (WeakReference<Object> reference : mNavigationBars) {
            if (reference.get() == navigationBar) return;
        }
        mNavigationBars.add(new WeakReference<>(navigationBar));
    }

    private void hookTaskbarDelegateClass(String className) {
        Class<?> taskbarDelegateClass = findClassIfExists(className, getClassLoader());
        if (taskbarDelegateClass == null) return;

        chainAllConstructors(taskbarDelegateClass, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                registerTaskbarDelegate(chain.getThisObject());
                return result;
            }
        });
    }

    private void registerTaskbarDelegate(Object taskbarDelegate) {
        if (taskbarDelegate == null) return;
        for (WeakReference<Object> reference : mTaskbarDelegates) {
            if (reference.get() == taskbarDelegate) return;
        }
        mTaskbarDelegates.add(new WeakReference<>(taskbarDelegate));
        Context context = resolveContext(taskbarDelegate);
        if (context != null) {
            registerObserverIfNeeded(context);
        }
    }

    private void hookLauncherProxyStopScreenPinning() {
        Class<?> launcherProxyClass = findClassIfExists(
            "com.android.systemui.recents.LauncherProxyService$1", getClassLoader());
        if (launcherProxyClass == null) return;

        chainAllMethods(launcherProxyClass, "stopScreenPinning", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Context context = resolveContext(chain.getThisObject());
                int lockApp = getLockApp(context);
                if (context != null && lockApp != -1) {
                    XposedLog.d(TAG, "GuidedAccess: Blocked ISystemUiProxy.stopScreenPinning in SystemUI");
                    return null; // 拦截桌面发来的解锁指令
                }
                return chain.proceed();
            }
        });
    }

    private void hookWindowDecorationClass(String className) {
        Class<?> decorClass = findClassIfExists(className, getClassLoader());
        if (decorClass == null) {
            XposedLog.e(TAG, getPackageName(), "UiLockApp target class not found: " + className);
            return;
        }

        chainAllConstructors(decorClass, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    registerWindowDecoration(chain.getThisObject());
                } catch (Throwable e) {
                    XposedLog.w(TAG, "registerWindowDecoration E: " + e);
                }
                return result;
            }
        });

        // 强行修正 shouldHideCaption
        chainAllMethods(decorClass, "shouldHideCaption", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (isLocked(chain.getThisObject())) {
                    return true;
                }
                return chain.proceed();
            }
        });

        // 核心：Hook relayout 确保每次布局刷新时强制应用隐藏状态
        chainAllMethods(decorClass, "relayout", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (isLocked(chain.getThisObject())) {
                    try {
                        // 强行修改实例变量，确保内部判定一致
                        setObjectField(chain.getThisObject(), "mCaptionVisible", false);
                    } catch (Throwable e) {
                        XposedLog.w(TAG, "relayout set mCaptionVisible E: " + e);
                    }
                }
                return chain.proceed();
            }
        });

        // 强制隐藏 Surface
        chainAllMethods(decorClass, "updateVisibility", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (chain.getArgs().size() >= 1 && chain.getArgs().get(0) instanceof Boolean) {
                    if (isLocked(chain.getThisObject())) {
                        Object[] args = chain.getArgs().toArray();
                        args[0] = false;
                        return chain.proceed(args);
                    }
                }
                return chain.proceed();
            }
        });

        // 拦截点击判定，防止触发拖拽
        chainAllMethods(decorClass, "pointInView", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (isLocked(chain.getThisObject())) {
                    return false;
                }
                return chain.proceed();
            }
        });
    }

    private void registerWindowDecoration(Object decor) {
        if (decor == null) return;
        for (WeakReference<Object> reference : mWindowDecorations) {
            if (reference.get() == decor) return;
        }
        mWindowDecorations.add(new WeakReference<>(decor));
    }

    private void hookDotViewClass(String className) {
        Class<?> dotViewClass = findClassIfExists(className, getClassLoader());
        if (dotViewClass == null) {
            XposedLog.e(TAG, getPackageName(), "UiLockApp target class not found: " + className);
            return;
        }

        // 核心：强制设置不可见状态，使其不参与触控分发（解决游戏死区问题）
        chainAllMethods(dotViewClass, "setVisibility", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (chain.getThisObject() instanceof View view && isLocked(view)) {
                    Object[] args = chain.getArgs().toArray();
                    args[0] = View.GONE; // 强制设为 GONE
                    return chain.proceed(args);
                }
                return chain.proceed();
            }
        });

        // 辅助：当 View 被添加到窗口时立即检查状态
        chainAllMethods(dotViewClass, "onAttachedToWindow", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (chain.getThisObject() instanceof View view && isLocked(view)) {
                    view.setVisibility(View.GONE);
                }
                return chain.proceed();
            }
        });

        chainAllMethods(dotViewClass, "onDraw", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (chain.getThisObject() instanceof View view && isLocked(view)) {
                    return null;
                }
                return chain.proceed();
            }
        });
    }

    private void hookDecorationDotClass(String className) {
        Class<?> dotClass = findClassIfExists(className, getClassLoader());
        if (dotClass == null) {
            XposedLog.e(TAG, getPackageName(), "UiLockApp target class not found: " + className);
            return;
        }

        chainAllMethods(dotClass, "createHandleMenu", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                if (isLocked(chain.getThisObject())) return null;
                return chain.proceed();
            }
        });
    }

    private void updateStatusBarVisibility(Context context) {
        boolean isLocked = getLockApp(context) != -1;

        // key_lock_app 也可被外部入口当作“请求退出”写为 -1；此时 ATMS 的
        // performStopLockTask() 可能还在 Handler 队列中。不要提前把 SystemUI 切到
        // 解锁态，必须以 framework 的真实 LockTask 状态为提交条件。
        if (!isLocked && Boolean.TRUE.equals(mLastLockedState)
            && isFrameworkLockTaskActive(context)) {
            scheduleUnlockStateCheck(context);
            return;
        }

        mUnlockStateCheckAttempts = 0;
        boolean wasLocked = mLastLockedState != null && mLastLockedState;
        boolean stateChanged = mLastLockedState == null || mLastLockedState != isLocked;
        mLastLockedState = isLocked;
        if (stateChanged) {
            XposedLog.d(TAG, "lockState locked=" + isLocked);
        }

        updateStatusBarWindowVisibility(isLocked);

        updateGestureHandleVisibility(isLocked);
        updateWindowDecorVisibility(isLocked);

        // SystemUI 启动时设置本来就是 -1；只处理真实的“锁定 → 解锁”，避免开机重启循环。
        if (stateChanged && wasLocked && !isLocked) {
            refreshNavigationBarPinningState();
            refreshTaskbarPinningState();
            refreshNavigationTransientState();
            refreshTaskbarTransientState();
            refreshStatusBarDisableFlags(context);
            refreshSystemUiConfiguration(context);
            scheduleNotificationPipelineRebuild(context);
        }
    }

    private boolean isFrameworkLockTaskActive(Context context) {
        try {
            Object activityManager = context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager == null) return false;
            int state = (Integer) callMethod(activityManager, "getLockTaskModeState");
            return state != ActivityManager.LOCK_TASK_MODE_NONE;
        } catch (Throwable e) {
            XposedLog.w(TAG, "get framework lock task state E: " + e);
            return false;
        }
    }

    private void scheduleUnlockStateCheck(Context context) {
        if (mUnlockStateCheckScheduled) return;
        if (mUnlockStateCheckAttempts++ >= 40) {
            XposedLog.w(TAG, "framework did not finish guided access exit in time");
            return;
        }
        mUnlockStateCheckScheduled = true;
        new Handler(context.getMainLooper()).postDelayed(() -> {
            mUnlockStateCheckScheduled = false;
            updateStatusBarVisibility(context);
        }, 50L);
    }

    /**
     * 不要直接隐藏 StatusBarWindowView：GONE、INVISIBLE 以及 alpha=0 都可能让 SystemUI
     * 判定状态栏不可见，从而暂停锁定期间新通知的测量、RemoteViews 绑定和图标绑定。
     * 根视图始终保持正常生命周期，锁定时的视觉屏蔽交给系统原生 disable/insets。
     */
    private void updateStatusBarWindowVisibility(boolean isLocked) {
        View statusBarView = mStatusBarView;
        if (statusBarView == null) return;

        if (statusBarView.getVisibility() != View.VISIBLE) {
            statusBarView.setVisibility(View.VISIBLE);
        }
        if (statusBarView.getAlpha() != 1f) {
            statusBarView.setAlpha(1f);
        }
        if (!isLocked && statusBarView.getWindowInsetsController() != null) {
            statusBarView.getWindowInsetsController().show(
                android.view.WindowInsets.Type.statusBars());
        }
        if (!isLocked) {
            requestStatusBarRelayout(statusBarView);
        }
    }

    private void requestStatusBarRelayout(View statusBarView) {
        try {
            statusBarView.requestApplyInsets();
            statusBarView.requestLayout();
            statusBarView.invalidate();
        } catch (Throwable e) {
            XposedLog.w(TAG, "requestStatusBarRelayout E: " + e);
        }
    }

    private void updateGestureHandleVisibility(boolean isLocked) {
        Iterator<WeakReference<View>> iterator = mGestureHandleViews.iterator();
        while (iterator.hasNext()) {
            View handleView = iterator.next().get();
            if (handleView == null) {
                iterator.remove();
                continue;
            }
            if (isLocked) {
                if (!mHandleVisibilityBackup.containsKey(handleView)) {
                    mHandleVisibilityBackup.put(handleView, handleView.getVisibility());
                }
                if (!mHandleAlphaBackup.containsKey(handleView)) {
                    mHandleAlphaBackup.put(handleView, handleView.getAlpha());
                }
                if (handleView.getVisibility() != View.GONE) {
                    handleView.setVisibility(View.GONE);
                }
                if (handleView.getAlpha() != 0f) {
                    handleView.setAlpha(0f);
                }
            } else {
                Float oldAlpha = mHandleAlphaBackup.remove(handleView);
                if (oldAlpha != null && handleView.getAlpha() != oldAlpha) {
                    handleView.setAlpha(oldAlpha);
                }
                Integer oldVisibility = mHandleVisibilityBackup.remove(handleView);
                if (oldVisibility != null && handleView.getVisibility() != oldVisibility) {
                    handleView.setVisibility(oldVisibility);
                }
            }
        }
    }

    private void updateWindowDecorVisibility(boolean isLocked) {
        Iterator<WeakReference<Object>> iterator = mWindowDecorations.iterator();
        while (iterator.hasNext()) {
            Object decor = iterator.next().get();
            if (decor == null) {
                iterator.remove();
                continue;
            }
            try {
                callMethod(decor, "setCaptionVisibility", !isLocked);
            } catch (Throwable e) {
                XposedLog.w(TAG, "updateWindowDecorVisibility error: " + e);
            }
        }
    }

    private void refreshNavigationBarPinningState() {
        Iterator<WeakReference<Object>> iterator = mNavigationBars.iterator();
        while (iterator.hasNext()) {
            Object navigationBar = iterator.next().get();
            if (navigationBar == null) {
                iterator.remove();
                continue;
            }
            try {
                setObjectField(navigationBar, "mScreenPinningActive", false);
                clearScreenPinningSysUiFlag(navigationBar);
                Object navView = getObjectField(navigationBar, "mView");
                if (navView != null) {
                    callMethod(navView, "setInScreenPinning", false);
                }
                callMethod(navigationBar, "updateScreenPinningGestures");
                callMethod(navigationBar, "updateSystemUiStateFlags");
            } catch (Throwable ignored) {
            }
        }
    }

    private void clearScreenPinningSysUiFlag(Object navigationBar) {
        if (navigationBar == null) return;
        try {
            Object sysUiState = getObjectField(navigationBar, "mSysUiFlagsContainer");
            if (sysUiState == null) return;
            Object chain = callMethod(sysUiState, "setFlag", 1L, false);
            if (chain != null) {
                callMethod(chain, "commitUpdate");
            } else {
                callMethod(sysUiState, "commitUpdate");
            }
        } catch (Throwable ignored) {
        }
    }

    private void refreshTaskbarPinningState() {
        Iterator<WeakReference<Object>> iterator = mTaskbarDelegates.iterator();
        while (iterator.hasNext()) {
            Object taskbarDelegate = iterator.next().get();
            if (taskbarDelegate == null) {
                iterator.remove();
                continue;
            }
            try {
                Object sysUiState = getObjectField(taskbarDelegate, "mSysUiState");
                if (sysUiState == null) continue;
                Object chain = callMethod(sysUiState, "setFlag", 1L, false);
                if (chain != null) {
                    callMethod(chain, "commitUpdate");
                } else {
                    callMethod(sysUiState, "commitUpdate");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void refreshNavigationTransientState() {
        Iterator<WeakReference<Object>> iterator = mNavigationBars.iterator();
        while (iterator.hasNext()) {
            Object navigationBar = iterator.next().get();
            if (navigationBar == null) {
                iterator.remove();
                continue;
            }
            try {
                setObjectField(navigationBar, "mTransientShown", false);
            } catch (Throwable ignored) {
            }
            try {
                setObjectField(navigationBar, "mTransientShownFromGestureOnSystemBar", false);
            } catch (Throwable ignored) {
            }
            try {
                Object edgeBack = getObjectField(navigationBar, "mEdgeBackGestureHandler");
                if (edgeBack != null) {
                    setObjectField(edgeBack, "mIsNavBarShownTransiently", false);
                }
            } catch (Throwable ignored) {
            }
            try {
                callMethod(navigationBar, "updateSystemUiStateFlags");
            } catch (Throwable ignored) {
            }
        }
    }

    private void refreshTaskbarTransientState() {
        Iterator<WeakReference<Object>> iterator = mTaskbarDelegates.iterator();
        while (iterator.hasNext()) {
            Object taskbarDelegate = iterator.next().get();
            if (taskbarDelegate == null) {
                iterator.remove();
                continue;
            }
            try {
                setObjectField(taskbarDelegate, "mTaskbarTransientShowing", false);
            } catch (Throwable ignored) {
            }
            try {
                Object edgeBack = getObjectField(taskbarDelegate, "mEdgeBackGestureHandler");
                if (edgeBack != null) {
                    setObjectField(edgeBack, "mIsNavBarShownTransiently", false);
                }
            } catch (Throwable ignored) {
            }
            try {
                callMethod(taskbarDelegate, "updateSysuiFlags");
            } catch (Throwable ignored) {
            }
        }
    }

    private void refreshStatusBarDisableFlags(Context context) {
        if (context == null) return;
        try {
            Class<?> dependencyClass = findClassIfExists(
                "com.android.systemui.Dependency", getClassLoader());
            Class<?> commandQueueClass = findClassIfExists(
                "com.android.systemui.statusbar.CommandQueue", getClassLoader());
            if (dependencyClass == null || commandQueueClass == null) return;

            Object commandQueue = null;
            try {
                Object dependency = getStaticObjectField(dependencyClass, "sDependency");
                if (dependency != null) {
                    commandQueue = callMethod(dependency, "getDependencyInner", commandQueueClass);
                }
            } catch (Throwable ignored) {
            }
            if (commandQueue == null) {
                try {
                    commandQueue = callStaticMethod(dependencyClass, "get", commandQueueClass);
                } catch (Throwable ignored) {
                }
            }
            if (commandQueue == null) return;

            int displayId = resolveDisplayId(context);
            callMethod(commandQueue, "recomputeDisableFlags", displayId, true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 通过 SystemUI 自己的配置变化入口做一次完整状态重建。真实的密度/字体变化也是
     * 由 ConfigurationControllerImpl 从这里统一分发：通知堆栈先更新资源和尺寸，
     * ViewConfigCoordinator 重建所有通知行及分组容器，IconManager 重建软件图标。
     *
     * 不修改 font_scale 等系统设置；只暂时让控制器的内部缓存失配，再把当前原始配置
     * 交回原生入口。入口返回时会自行恢复缓存，finally 则覆盖异常中断的情况。
     */
    private void refreshSystemUiConfiguration(Context context) {
        Object configurationController = null;
        float currentFontScale = context.getResources().getConfiguration().fontScale;
        try {
            Class<?> dependencyClass = findClassIfExists(
                "com.android.systemui.Dependency", getClassLoader());
            Class<?> configurationControllerClass = findClassIfExists(
                "com.android.systemui.statusbar.policy.ConfigurationController", getClassLoader());
            if (dependencyClass == null || configurationControllerClass == null) return;

            Object dependency = getStaticObjectField(dependencyClass, "sDependency");
            if (dependency == null) return;
            configurationController = callMethod(
                dependency, "getDependencyInner", configurationControllerClass);
            if (configurationController == null) return;

            setObjectField(configurationController, "fontScale", Float.NaN);
            Configuration currentConfiguration = new Configuration(
                context.getResources().getConfiguration());
            callMethod(configurationController, "onConfigurationChanged", currentConfiguration);
            XposedLog.d(TAG, "refreshed SystemUI configuration after guided access exit");
        } catch (Throwable e) {
            XposedLog.w(TAG, "refreshSystemUiConfiguration E: " + e);
        } finally {
            if (configurationController != null) {
                try {
                    setObjectField(configurationController, "fontScale", currentFontScale);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 配置分发负责重建每个通知 Row；管线重建则重新计算 GroupEntry 并把锁定期间产生的
     * child rows 挂回 NotificationChildrenContainer。等异步 RemoteViews 绑定完成后再从
     * pipeline stage 0 调度一轮，等价于下一次原生通知更新所触发的完整列表渲染。
     */
    private void scheduleNotificationPipelineRebuild(Context context) {
        if (context == null) return;
        new Handler(context.getMainLooper()).postDelayed(() -> {
            try {
                Object notifPipeline = mNotifPipeline;
                if (notifPipeline == null) {
                    XposedLog.w(TAG, "notification pipeline unavailable after guided access exit");
                    return;
                }
                Object shadeListBuilder = getObjectField(notifPipeline, "mShadeListBuilder");
                if (shadeListBuilder == null) return;
                normalizeRenderedNotificationVisibility(notifPipeline);
                callMethod(shadeListBuilder, "rebuildListIfBefore", 1);
                new Handler(context.getMainLooper()).postDelayed(
                    this::restartStatusBarNotificationIconBinding, 100L);
                XposedLog.d(TAG, "scheduled notification pipeline rebuild after guided access exit");
            } catch (Throwable e) {
                XposedLog.w(TAG, "scheduleNotificationPipelineRebuild E: " + e);
            }
        }, 250L);
    }

    /**
     * 当前 SystemUI 使用 NotificationIconContainerStatusBarViewBinder 收集通知图标
     * Flow。lock-task 的 disable 状态可能让收集器在自动分组摘要创建时固定为空集合；
     * 通知列表恢复并不会重新启动这个独立的收集生命周期。沿 Fragment 自身的标准
     * onDestroyView/onViewCreated 路径，只 dispose 并重新 bind 顶部图标容器，让原生
     * ViewModel/Flow 从当前 ActiveNotificationsStore 重放一次完整状态。
     */
    private void restartStatusBarNotificationIconBinding() {
        Object fragment = mCollapsedStatusBarFragment.get();
        if (fragment == null) return;
        try {
            Object oldBinding = getObjectField(fragment, "mNicBindingDisposable");
            Object binder = getObjectField(fragment, "mNicViewBinder");
            Object iconContainer = getObjectField(fragment, "mNotificationIconAreaInner");
            if (binder == null || iconContainer == null) return;
            if (oldBinding != null) {
                callMethod(oldBinding, "dispose");
            }
            Object newBinding = callMethod(binder, "bindWhileAttached", iconContainer);
            setObjectField(fragment, "mNicBindingDisposable", newBinding);
            XposedLog.d(TAG, "restarted status bar notification icon binding after guided access exit");
        } catch (Throwable e) {
            XposedLog.w(TAG, "restartStatusBarNotificationIconBinding E: " + e);
        }
    }

    /**
     * MIUI 在 lock-task 禁止通知栏时仍会创建通知 Row，但新挂入分组的 child row 会被
     * StackScrollAlgorithm 固化为 GONE；普通配置重建只替换 Row 内部内容，不会清除
     * 根 ViewState.gone，因而解锁后会留下只有摘要图标的空分组。这里只归一化仍实际
     * 挂在通知视图树中的 Row 根状态，未渲染/已过滤的 entry 不会被触碰；具体布局、
     * 分组可见数量和动画仍交给随后一轮原生 pipeline 计算。
     */
    private void normalizeRenderedNotificationVisibility(Object notifPipeline) {
        try {
            Object allNotifs = callMethod(notifPipeline, "getAllNotifs");
            if (!(allNotifs instanceof Iterable<?> entries)) return;
            int restored = 0;
            for (Object entry : entries) {
                Object rowObject;
                try {
                    rowObject = getObjectField(entry, "row");
                } catch (Throwable ignored) {
                    continue;
                }
                if (!(rowObject instanceof View row) || row.getParent() == null) continue;

                boolean changed = false;
                if (row.getVisibility() != View.VISIBLE) {
                    row.setVisibility(View.VISIBLE);
                    changed = true;
                }
                if (row.getAlpha() != 1f) {
                    row.setAlpha(1f);
                    changed = true;
                }
                try {
                    Object viewState = getObjectField(row, "mViewState");
                    if (viewState != null) {
                        setObjectField(viewState, "gone", false);
                    }
                } catch (Throwable ignored) {
                }
                if (changed) restored++;
            }
            if (restored > 0) {
                XposedLog.d(TAG, "restored " + restored
                    + " notification row root states after guided access exit");
            }
        } catch (Throwable e) {
            XposedLog.w(TAG, "normalizeRenderedNotificationVisibility E: " + e);
        }
    }

    private int resolveDisplayId(Context context) {
        if (context == null) return 0;
        try {
            Object display = callMethod(context, "getDisplay");
            if (display != null) {
                Object displayId = callMethod(display, "getDisplayId");
                if (displayId instanceof Integer) {
                    return (Integer) displayId;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private Context resolveContext(Object target) {
        if (target instanceof Context) return (Context) target;
        if (target instanceof View) return ((View) target).getContext();
        if (target == null) return null;
        try {
            Object context = getObjectField(target, "mContext");
            if (context instanceof Context) return (Context) context;
        } catch (Throwable ignored) {
        }
        try {
            Object context = getObjectField(target, "context");
            if (context instanceof Context) return (Context) context;
        } catch (Throwable ignored) {
        }
        try {
            Object outer = getObjectField(target, "this$0");
            if (outer != null && outer != target) {
                return resolveContext(outer);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 判断目标是否处于引导式访问锁定状态。任何反射/取 context 的异常都不外泄，
     * 保证 hook 回调绝不会因本规则抛异常而影响 SystemUI 其它 hook。
     */
    private boolean isLocked(Object target) {
        try {
            Context context = resolveContext(target);
            return context != null && getLockApp(context) != -1;
        } catch (Throwable t) {
            XposedLog.d(TAG, "isLocked check failed: " + t);
            return false;
        }
    }

    public static int getLockApp(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), SETTING_KEY_LOCK_APP);
        } catch (Settings.SettingNotFoundException e) {
            XposedLog.w("LockApp", "getInt hyceiler_lock_app e: " + e);
        }
        return -1;
    }

    private void registerObserverIfNeeded(Context context) {
        if (context == null || mObserverRegistered) return;
        ContentObserver contentObserver = new ContentObserver(new Handler(context.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                updateStatusBarVisibility(context);
            }
        };
        context.getContentResolver().registerContentObserver(
            Settings.Global.getUriFor(SETTING_KEY_LOCK_APP),
            false,
            contentObserver
        );
        mObserverRegistered = true;
        registerContentObserverHotReloadCleanup(context.getContentResolver(), contentObserver);
        putHotReloadRuntimeState(STATE_CONTEXT, context);
    }
}
