package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用后台超时清理（不区分设备型号，对所有第三方 APP 生效；腾讯极光盒子 5X/5 与
 * 小米/谷歌盒子等一律适用）。
 *
 * 第一层（主动清扫）：记录每个 APP 进入后台的时刻（HomeKeyService 的窗口切换事件为主，
 * MainActivity.onResume 兜底近似），后台定时器每 15 秒扫描一次，凡后台停留超过设定
 * 档位秒数的调用 killBackgroundProcesses() 干净退出（进程+缓存，下次打开即为全新数据，
 * 像手动退出一样）。正在前台的 APP（当前窗口包）与桌面自身不清理。
 *
 * 第二层（兜底冷启动）：咪视界这类有前台服务常驻的进程 kill 不掉，打开它时若检测到
 * 后台停留已超时，则启动 intent 附加 FLAG_ACTIVITY_CLEAR_TASK|NEW_TASK 强制清任务栈
 * 冷启动，保证数据最新（相当于自动完成"退出重进"）。
 *
 * 档位（launcher_settings 设置页"后台超时清理"）：
 *   -1 = 不启用；0 = 进后台立即清（每次打开都是全新）；5/30/60/120 = 后台超过 N 秒清。
 *   默认 30 秒。
 */
final class BackgroundCleaner {
    static final long SWEEP_INTERVAL_MS = 15_000L;

    /** pkg → 进入后台的时刻（System.currentTimeMillis）。 */
    private static final Map<String, Long> leftAt = new ConcurrentHashMap<>();
    /** 用户从本桌面（含遥控器快捷键/应用库）启动过的第三方 APP。 */
    private static final Set<String> launched = ConcurrentHashMap.newKeySet();
    /** 后台清理例外：这些 APP 依赖后台常驻运行，不参与超时清理，也不做冷启动（Clash Meta 代理）。 */
    private static final Set<String> EXEMPT = ConcurrentHashMap.newKeySet();

    private static void ensureExempt() {
        if (!EXEMPT.isEmpty()) return;
        EXEMPT.add("com.github.metacubex.clash.meta");
    }
    /** 当前前台窗口包（HomeKeyService 窗口事件更新；空表示未知）。 */
    private static volatile String frontPkg = "";
    /** 最近一次从桌面启动的 APP（onResume 兜底近似用）。 */
    private static volatile String lastLaunchedPkg = "";

    private static volatile boolean sweeperStarted;

    private BackgroundCleaner() {}

    /** 设置页"后台超时清理"的档位秒数；-1 不启用，0 立即，默认 30。 */
    static int bgKillSeconds(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        return sp.getInt(Config.BG_KILL_KEY, Config.BG_KILL_DEFAULT);
    }

    /** HomeKeyService 窗口事件：更新当前前台包。 */
    static void noteFront(String pkg) {
        frontPkg = pkg;
    }

    static String frontPkg() {
        return frontPkg;
    }

    /** 窗口从前台切走（回到桌面或切到另一个 APP）：记为进入后台。 */
    static void noteLeft(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        leftAt.put(pkg, System.currentTimeMillis());
    }

    /** 兜底近似：HomeKeyService 未绑定时，回到桌面把"最后启动的 APP"记为进入后台。 */
    static void noteLeftApprox(Context ctx) {
        String last = lastLaunchedPkg;
        if (last != null && !last.isEmpty() && !last.equals(ctx.getPackageName())) noteLeft(last);
    }

    /** 记录用户启动过的第三方 APP（桌面 tile / 应用库 / 遥控器快捷键统一调用）。 */
    static void noteLaunched(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(ctx.getPackageName())) return;
        launched.add(pkg);
        lastLaunchedPkg = pkg;
    }

    /** 打开 pkg 前判定：是否应强制冷启动（第二层）。 */
    static boolean shouldColdStart(Context ctx, String pkg) {
        ensureExempt();
        if (EXEMPT.contains(pkg)) return false;        // 例外 APP（如 Clash 代理）不冷启动
        int seconds = bgKillSeconds(ctx);
        if (seconds < 0) return false;               // 不启用
        if (!launched.contains(pkg)) return false;   // 非本桌面启动过的第三方：不管
        if (seconds == 0) return true;               // 0 = 每次打开都全新
        Long left = leftAt.get(pkg);
        return left != null && System.currentTimeMillis() - left > seconds * 1000L;
    }

    /** 启动 intent 前调用：后台已超时则附加 CLEAR_TASK，强制冷启动。 */
    static void coldStartIfNeeded(Context ctx, String pkg, Intent intent) {
        if (shouldColdStart(ctx, pkg)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        }
    }

    /** 后台定时清扫（幂等启动；launcher 进程活着就一直跑，HOME 进程系统不杀）。 */
    static void startSweeper(Context ctx) {
        if (sweeperStarted) return;
        sweeperStarted = true;
        HandlerThread thread = new HandlerThread("bg-clean");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        final Context app = ctx.getApplicationContext();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sweep(app);
                handler.postDelayed(this, SWEEP_INTERVAL_MS);
            }
        }, SWEEP_INTERVAL_MS);
    }

    /** 第一层：清扫所有后台停留超时的第三方 APP（正在前台的与桌面自身不碰）。 */
    static void sweep(Context ctx) {
        ensureExempt();
        int seconds = bgKillSeconds(ctx);
        if (seconds < 0) return;                     // 不启用
        long now = System.currentTimeMillis();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        String front = frontPkg;
        String self = ctx.getPackageName();
        for (Map.Entry<String, Long> e : leftAt.entrySet()) {
            String pkg = e.getKey();
            if (pkg.equals(self)) continue;
            if (EXEMPT.contains(pkg)) continue;       // 例外 APP（如 Clash 代理）不参与清理
            if (!launched.contains(pkg)) continue;   // 只清本桌面启动过的第三方
            if (pkg.equals(front)) continue;         // 前台正在用的不杀
            long left = e.getValue();
            boolean idle = seconds == 0 ? now - left >= 0 : now - left > seconds * 1000L;
            if (idle) {
                try {
                    am.killBackgroundProcesses(pkg);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
