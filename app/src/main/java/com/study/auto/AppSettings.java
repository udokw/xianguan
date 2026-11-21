package com.study.auto;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 用户自定义配置，全部落在 SharedPreferences，重启应用不丢。
 * 写入即刻生效（引擎每轮读取）。
 */
public class AppSettings {

    private static final String PREF = "autostudy_settings";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ============ 时长（界面按分钟，内部换算毫秒） ============

    /**
     * 视频兜底停留分钟数：只有在列表行读不到「已播/总长」时才用它（默认 10 分钟）。
     * key 加 _v2：老版本存的 "play_min" 默认是 1 分钟，换 key 让新默认值立刻生效。
     */
    public static int playMin(Context c) {
        return sp(c).getInt("play_min_v2", 10);
    }

    public static void setPlayMin(Context c, int min) {
        sp(c).edit().putInt("play_min_v2", Math.max(1, min)).apply();
    }

    public static long playMs(Context c) {
        return playMin(c) * 60_000L;
    }

    /** 文档学习停留分钟数，默认 10 分钟（文档没有进度可读，只能按固定时长挂机） */
    public static int docMin(Context c) {
        return sp(c).getInt("doc_min_v2", 10);
    }

    public static void setDocMin(Context c, int min) {
        sp(c).edit().putInt("doc_min_v2", Math.max(1, min)).apply();
    }

    public static long docMs(Context c) {
        return docMin(c) * 60_000L;
    }

    // ============ 返回列表后的刷新等待（1～9 秒，默认 5 秒） ============

    public static int backToListSec(Context c) {
        return Math.max(1, Math.min(9, sp(c).getInt("back_to_list_sec", 5)));
    }

    public static void setBackToListSec(Context c, int sec) {
        sp(c).edit().putInt("back_to_list_sec", Math.max(1, Math.min(9, sec))).apply();
    }

    public static long backToListMs(Context c) {
        return backToListSec(c) * 1000L;
    }

    // ============ 自动检测更新（默认开启） ============

    public static boolean autoUpdate(Context c) {
        return sp(c).getBoolean("auto_update", true);
    }

    public static void setAutoUpdate(Context c, boolean on) {
        sp(c).edit().putBoolean("auto_update", on).apply();
    }
}

