package com.google.android.accessibility.selecttospeak;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.study.auto.MainActivity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 伪装成谷歌原生 SelectToSpeak 的无障碍服务。
 * 微信小程序的节点树对普通服务加密，对 selecttospeaker 包名放行。
 */
public class SelectToSpeakService extends AccessibilityService {
    private static final String TAG = "AutoStudy";
    public static SelectToSpeakService INSTANCE;
    private static final String FG_CHANNEL_ID = "autostudy_fg";
    private static final int FG_NOTIF_ID = 1001;

    private boolean flagsInjected = false;
    private Handler workerHandler;
    private long lastScan = 0;
    public String lastStatus = "就绪";

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        startForegroundKeepalive();
        HandlerThread ht = new HandlerThread("autostudy-worker");
        ht.start();
        workerHandler = new Handler(ht.getLooper());
        workerHandler.post(this::injectFlagsOnce);
        Log.i(TAG, "✓ SelectToSpeakService onCreate");
    }

    private void injectFlagsOnce() {
        if (flagsInjected)
            return;
        if (injectFlags()) {
            flagsInjected = true;
        } else {
            // onCreate 时服务还没连上，getServiceInfo() 会返回 null；1 秒后重试直到成功
            workerHandler.postDelayed(this::injectFlagsOnce, 1000L);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        injectFlagsOnce();
    }

    /** 保留 0x52 = INCLUDE_NOT_IMPORTANT_VIEWS(0x02) | REPORT_VIEW_IDS(0x10) | RETRIEVE_INTERACTIVE_WINDOWS(0x40)
     *  必须清掉 0x04(REQUEST_TOUCH_EXPLORATION_MODE) 与 0x08(REQUEST_ENHANCED_WEB_ACCESSIBILITY)：
     *  触摸探索开启后注入的手势会被当成悬停探索，微信小程序不认滚动，只有点击生效。
     *  注意 XML 里配置的 0x08 会被系统带进 flags，必须用 &= ~ 清除，|= 是清不掉的。 */
    private static final int KEEP_FLAGS = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
    private static final int STRIP_FLAGS = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            | AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;

    private boolean injectFlags() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                Log.w(TAG, "getServiceInfo() null, retry");
                return false;
            }
            int before = info.flags;
            info.flags = (info.flags & ~STRIP_FLAGS) | KEEP_FLAGS;
            info.eventTypes = -1;
            info.feedbackType = -1;
            Method m = AccessibilityService.class.getDeclaredMethod("setServiceInfo", AccessibilityServiceInfo.class);
            m.setAccessible(true);
            m.invoke(this, info);
            Log.i(TAG, "✓ injectFlags OK 0x" + Integer.toHexString(before)
                    + " → 0x" + Integer.toHexString(info.flags));
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "injectFlags failed, fallback setServiceInfo", t);
            try {
                AccessibilityServiceInfo info2 = getServiceInfo();
                if (info2 != null) {
                    int before = info2.flags;
                    info2.flags = (info2.flags & ~STRIP_FLAGS) | KEEP_FLAGS;
                    info2.eventTypes = -1;
                    setServiceInfo(info2);
                    Log.i(TAG, "✓ injectFlags fallback OK 0x" + Integer.toHexString(before)
                            + " → 0x" + Integer.toHexString(info2.flags));
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "⚠ Service destroyed");
        INSTANCE = null;
        try {
            stopForeground(1);
        } catch (Throwable ignored) {
        }
        if (workerHandler != null) {
            workerHandler.removeCallbacksAndMessages(null);
            ((HandlerThread) workerHandler.getLooper().getThread()).quitSafely();
        }
    }

    @Override
    public void onInterrupt() {
    }

    private void startForegroundKeepalive() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService("notification");
            NotificationChannel ch = new NotificationChannel(FG_CHANNEL_ID, "闲观保活", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            PendingIntent pi = null;
            try {
                Intent i = new Intent(this, MainActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            } catch (Throwable ignored) {
            }
            Notification.Builder nb = new Notification.Builder(this, FG_CHANNEL_ID);
            nb.setContentTitle(TAG).setContentText("无障碍服务运行中").setOngoing(true)
                    .setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(pi);
            startForeground(FG_NOTIF_ID, nb.build());
        } catch (Throwable t) {
            Log.e(TAG, "fg failed", t);
        }
    }

    // ==================== 节点树 ====================

    /** 只收集微信窗口 root（引擎只采信微信节点，排除系统UI/悬浮球减负）。
     *  每个 root 先 refresh()：微信小程序滚动后节点树会滞留在滚动前的快照，
     *  必须主动向它重新请求一次才能拿到新的行坐标。 */
    public List<AccessibilityNodeInfo> collectRoots() {
        return collectRoots(true);
    }

    /**
     * 不 refresh 的根节点快照（只取微信窗口）。
     *
     * <p>小程序播放面板里的视频 <b>播放按钮是 XWeb 的虚拟节点</b>，
     * 对 root 调 refresh() 会让这批虚拟子节点失效（实测登录后第一次能读到，
     * 再 refresh 就读不到）。所以专门给「找播放按钮」提供不清缓存的通道。</p>
     */
    public List<AccessibilityNodeInfo> collectRootsNoRefresh() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || !"com.tencent.mm".equals(String.valueOf(r.getPackageName())))
                    continue;
                roots.add(r);
            }
        }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && !roots.contains(active)
                && "com.tencent.mm".equals(String.valueOf(active.getPackageName())))
            roots.add(active);
        return roots;
    }

    /** 收集所有窗口 root（含系统弹窗/微信弹窗），用于识别断网时的「警告提示」框 */
    public List<AccessibilityNodeInfo> collectRootsAll() {
        return collectRoots(false);
    }

    private List<AccessibilityNodeInfo> collectRoots(boolean wechatOnly) {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || (wechatOnly && !"com.tencent.mm".equals(String.valueOf(r.getPackageName()))))
                    continue;
                try {
                    r.refresh();
                } catch (Throwable ignored) {
                }
                roots.add(r);
            }
        }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && !roots.contains(active)
                && (!wechatOnly || "com.tencent.mm".equals(String.valueOf(active.getPackageName())))) {
            try {
                active.refresh();
            } catch (Throwable ignored) {
            }
            roots.add(active);
        }
        return roots;
    }

    /** 用无障碍滚动动作让列表前进一屏（比触摸滑动更容易触发节点树重排） */
    public boolean scrollForwardA11y() {
        List<AccessibilityNodeInfo> roots = collectRoots();
        for (AccessibilityNodeInfo r : roots) {
            AccessibilityNodeInfo s = findScrollable(r, 0);
            if (s != null) {
                Rect rb = new Rect();
                s.getBoundsInScreen(rb);
                Log.i(TAG, "无障碍滚动 目标=" + s.getClassName() + " " + rb);
                if (s.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                    return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 20)
            return null;
        if (n.isScrollable())
            return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null)
                continue;
            AccessibilityNodeInfo r = findScrollable(c, depth + 1);
            if (r != null)
                return r;
        }
        return null;
    }

    /** 当前是否有交互焦点的窗口就是微信（防止在其它界面误操作） */
    public boolean isWechatForeground() {
        try {
            AccessibilityNodeInfo a = getRootInActiveWindow();
            if (a == null)
                return false;
            CharSequence p = a.getPackageName();
            return p != null && "com.tencent.mm".equals(p.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 手势（直发，无回调无闸） ====================

    private final Object gestureLock = new Object();
    private boolean gestureBusy = false;
    private long gestureStartAt = 0;
    private static final long GESTURE_TIMEOUT_MS = 2500;

    /**
     * 手势串行分发：同一时刻只允许一个手势在途，避免并发注入在 InputDispatcher 里留下卡死的虚拟手指。
     * 若上一个手势回调丢失超过 2.5 秒，视为卡死并强制释放，避免永久卡住。
     */
    private boolean dispatchSerial(GestureDescription g, String tag) {
        synchronized (gestureLock) {
            if (gestureBusy) {
                long held = SystemClock.uptimeMillis() - gestureStartAt;
                if (held < GESTURE_TIMEOUT_MS) {
                    Log.w(TAG, "手势忙(已" + held + "ms)，丢弃 " + tag);
                    return false;
                }
                Log.w(TAG, "手势卡死" + held + "ms，强制释放后继续 " + tag);
            }
            gestureBusy = true;
            gestureStartAt = SystemClock.uptimeMillis();
        }
        try {
            boolean ok = dispatchGesture(g, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    releaseGesture(tag);
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    releaseGesture(tag + " 被取消");
                }
            }, null);
            if (!ok) {
                releaseGesture(tag + " 分发失败");
                return false;
            }
            return true;
        } catch (Throwable t) {
            releaseGesture(tag + " 异常 " + t);
            return false;
        }
    }

    private void releaseGesture(String why) {
        synchronized (gestureLock) {
            gestureBusy = false;
            gestureStartAt = 0;
        }
        Log.i(TAG, "手势结束: " + why);
    }

    /**
     * 轻触 50ms 直发（坐标是整块物理屏的像素，含状态栏/导航栏）。
     * 引擎专用：走串行队列，避免并发的虚拟手指卡死 InputDispatcher。
     */
    public boolean tapDirect(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(path, 0L, 50L));
        return dispatchSerial(gb.build(), "tap(" + x + "," + y + ")");
    }

    /** 滑动直发（起终点同为物理屏像素坐标，各机型自动适配）。 */
    public boolean swipeDirect(int x1, int y1, int x2, int y2, long durationMs) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(path, 0L, durationMs));
        return dispatchSerial(gb.build(), "swipe(" + x1 + "," + y1 + "→" + x2 + "," + y2 + ")");
    }

    // ==================== 截图 ====================

    /**
     * 立即截一张屏（同步等待，返回 ARGB_8888 Bitmap；失败返回 null）。
     * 依次尝试 displayId 0 / 1 / -1，兼容不同机型的默认显示号；
     * 返回的 Bitmap 尺寸就是当前真实分辨率（转屏后横竖互换），调用方按实际尺寸计算即可。
     */
    public Bitmap takeScreenshotNow() {
        ExecutorService cbExecutor = Executors.newSingleThreadExecutor();
        int attempt = 0;
        while (attempt < 3) {
            final int displayId = (attempt == 0) ? 0 : (attempt == 1 ? 1 : -1);
            final CountDownLatch latch = new CountDownLatch(1);
            final Bitmap[] holder = new Bitmap[1];
            try {
                takeScreenshot(displayId, cbExecutor, new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        try {
                            android.hardware.HardwareBuffer hb = result.getHardwareBuffer();
                            if (hb != null) {
                                Bitmap wrapped = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                                if (wrapped != null) {
                                    holder[0] = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                                    wrapped.recycle();
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        latch.countDown();
                    }
                });
                latch.await(3, TimeUnit.SECONDS);
                if (holder[0] != null)
                    return holder[0];
            } catch (Throwable t) {
                Log.w(TAG, "screenshot attempt " + attempt + " failed", t);
            }
            attempt++;
        }
        return null;
    }
}
