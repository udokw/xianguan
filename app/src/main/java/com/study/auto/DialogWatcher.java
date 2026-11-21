package com.study.auto;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.android.accessibility.selecttospeak.SelectToSpeakService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 挂机检测线程：App 功能运行期间全程开启，每 5 秒扫描一次无障碍节点树。
 *
 * <p>处理顺序（每轮只做一件事）：</p>
 * <ol>
 *   <li><b>「警告提示」弹窗</b>（断网 / 网关错误时小程序弹出，标题「警告提示」或正文含网络+错误词）
 *       → 点「确定」。它会盖住整个界面，所以优先级最高。</li>
 *   <li><b>挂机弹窗</b>（标题含「挂机检测」/「学习已暂停」或短文本「继续学习」）→ 点「继续学习」。
 *       这个弹窗是微信自己触发的，无论自动学习引擎是否在跑都必须点掉，否则学习进度一直停着。</li>
 *   <li><b>「我知道了」提示框</b> → 点掉。</li>
 *   <li><b>横屏全屏视频</b> → 点中心唤出控制条，再点右下角「缩放」回半屏。
 *       是否全屏用节点特征确认（见 {@link ScreenClassifier#isFullscreenVideo}），
 *       所以横屏平板上平时横着用也不会误触发。</li>
 * </ol>
 *
 * <p><b>坐标约定：</b>按钮优先用节点 bounds / ACTION_CLICK；只有「缩放」按钮是 canvas 画的、
 * 节点树里没有，用截图按「中性灰色图标 + 最靠右」动态识别，识别不到才用屏幕比例兜底。</p>
 */
public class DialogWatcher {

    private static final String TAG = "AutoStudy";

    private static final String BTN_CONTINUE = "继续学习";
    private static final String BTN_KNOW = "我知道了";
    /** 断网 / 网关错误弹窗标题与按钮文案（识别逻辑见 {@link ScreenClassifier#findWarnOk}） */
    private static final String WARN_TITLE = "警告提示";
    private static final String BTN_OK = "确定";
    /** 挂机弹窗的标题特征 */
    private static final String DIALOG_MARK_1 = "挂机检测";
    private static final String DIALOG_MARK_2 = "学习已暂停";

    /** 检测间隔：5 秒一次 */
    private static final long INTERVAL_MS = 5000L;

    /**
     * 「缩放」按钮距离屏幕右边/底边的 dp 值（小米8 实测：右 88px≈32dp、下 63px≈23dp，density=2.75）。
     * 微信播放器的控件是用 dp 布局的，所以按 dp 换算比按屏幕百分比更稳。
     * 正常情况下不靠它：{@link #findScaleButton} 会用截图识别真正的图标位置，
     * 这里只在截图失败/识别不到时兜底。
     */
    private static final float SCALE_BTN_INSET_RIGHT_DP = 32f;
    private static final float SCALE_BTN_INSET_BOTTOM_DP = 23f;

    private static volatile DialogWatcher inst;

    private volatile boolean running;
    private Thread thread;
    /** 刚点过对话框/刚做过缩放，短时间内不重复动作 */
    private long lastActionAt = 0;
    /** 最近一次「缩放按钮」截图识别失败的原因（只写日志，便于排查别的机型） */
    private volatile String lastScaleNote = "";

    public static synchronized DialogWatcher get() {
        if (inst == null)
            inst = new DialogWatcher();
        return inst;
    }

    public synchronized void start(SelectToSpeakService s) {
        if (running)
            return;
        running = true;
        thread = new Thread(this::loop, "dialog-watcher");
        thread.start();
        log("挂机检测已启动（每 5 秒检测「" + BTN_CONTINUE + "」「" + BTN_KNOW
                + "」，以及断网「" + WARN_TITLE + "」→「" + BTN_OK + "」）");
    }

    public synchronized void stop() {
        running = false;
        if (thread != null)
            thread.interrupt();
        thread = null;
        log("挂机检测已停止");
    }

    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            List<ScreenClassifier.Node> nodes = null;
            try {
                SelectToSpeakService s = SelectToSpeakService.INSTANCE;
                if (s == null) {
                    sleep(INTERVAL_MS);
                    continue;
                }
                boolean wechatFg = s.isWechatForeground();
                synchronized (ScreenClassifier.A11Y_LOCK) {
                    // 微信在前台只采微信窗口；系统网络弹窗盖在上面时微信不是焦点窗口，
                    // 这里改采所有窗口，否则看不到盖在外面的「警告提示」
                    nodes = wechatFg ? ScreenClassifier.collect(s) : ScreenClassifier.collectAll(s);
                }
                int[] disp = displaySize(s);

                // 0) 断网 / 网关错误的「警告提示」弹窗：盖住整个界面，最先处理
                ScreenClassifier.Node warnOk = ScreenClassifier.findWarnOk(nodes, disp[1]);
                if (warnOk != null && System.currentTimeMillis() - lastActionAt > 2000) {
                    String how = clickText(s, warnOk);
                    lastActionAt = System.currentTimeMillis();
                    if (how == null) {
                        log("挂机检测：发现「" + WARN_TITLE + "」弹窗，但「" + BTN_OK + "」点不动，下一轮重试");
                    } else {
                        log("挂机检测：发现「" + WARN_TITLE + "」弹窗（断网/网关错误）→ 点「" + BTN_OK + "」" + how);
                    }
                    sleep(INTERVAL_MS);
                    continue;
                }

                if (!wechatFg) {
                    sleep(INTERVAL_MS);
                    continue;
                }

                boolean pauseDialog = hasPauseDialog(nodes);
                ScreenClassifier.Node cont = findButton(nodes, BTN_CONTINUE);
                StudyEngine eng = StudyEngine.get();
                // 挂机弹窗一旦出现就必须点掉（引擎停了也要点）；
                // 另外保留旧逻辑：引擎在播放面板/文档页时，短文本「继续学习」也当作弹窗按钮点掉
                boolean takeContinue = (cont != null)
                        && (pauseDialog
                        || (eng.running && !ScreenClassifier.PAGE_LIST.equals(eng.page)));

                if (takeContinue && System.currentTimeMillis() - lastActionAt > 2500) {
                    String how = clickText(s, cont);
                    lastActionAt = System.currentTimeMillis();
                    if (how == null) {
                        log("挂机检测：发现挂机弹窗，但「" + BTN_CONTINUE + "」点不动，下一轮重试");
                    } else {
                        log("挂机检测：发现挂机弹窗 → 点「" + BTN_CONTINUE + "」" + how);
                        // 点「继续学习」后视频会转横屏全屏：等转屏动画结束，再用节点特征确认确实是全屏视频
                        awaitLandscape(s, 6000);
                        restoreIfFullscreen(s, null, "点「继续学习」后进入全屏");
                    }
                    sleep(INTERVAL_MS);
                    continue;
                }
                if (pauseDialog)
                    log("挂机检测：发现挂机弹窗，但树里没有可点的「" + BTN_CONTINUE + "」按钮，下一轮再找");

                if (System.currentTimeMillis() - lastActionAt > 3000) {
                    ScreenClassifier.Node know = findButton(nodes, BTN_KNOW);
                    if (know != null) {
                        String how = clickText(s, know);
                        if (how != null) {
                            lastActionAt = System.currentTimeMillis();
                            log("挂机检测：点「" + BTN_KNOW + "」" + how);
                            sleep(2000);
                            continue;
                        }
                    }
                }

                // 横屏全屏视频：缩放回半屏。
                // 注意不能只看「屏幕是横的」——横屏平板上微信平时就是横屏，
                // 必须用节点特征（全屏视频标题节点）确认，避免在普通界面乱点。
                if (disp[0] > disp[1]
                        && ScreenClassifier.isFullscreenVideo(nodes, disp[0], disp[1])
                        && System.currentTimeMillis() - lastActionAt > 3000) {
                    restoreHalfScreen(s, "检测到横屏全屏播放", disp[0], disp[1]);
                    sleep(INTERVAL_MS);
                    continue;
                }
                sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                Log.w(TAG, "挂机检测异常 " + t);
                sleep(INTERVAL_MS);
            } finally {
                ScreenClassifier.recycle(nodes);
            }
        }
        running = false;
    }

    /** 挂机弹窗特征：标题「挂机检测(...)」或正文「学习已暂停」 */
    private boolean hasPauseDialog(List<ScreenClassifier.Node> nodes) {
        if (nodes == null)
            return false;
        for (ScreenClassifier.Node n : nodes) {
            if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg) || n.text == null)
                continue;
            String t = n.text.replaceAll("\\s", "");
            if (t.isEmpty())
                continue;
            if (t.contains(DIALOG_MARK_1) || t.contains(DIALOG_MARK_2))
                return true;
        }
        return false;
    }

    /** 找文本按钮：优先短文本、可点击、bounds 有效的节点 */
    private ScreenClassifier.Node findButton(List<ScreenClassifier.Node> nodes, String keyword) {
        ScreenClassifier.Node best = null;
        int bestScore = Integer.MAX_VALUE;
        for (ScreenClassifier.Node n : nodes) {
            if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg) || n.text == null)
                continue;
            String t = n.text.replaceAll("\\s", "");
            if (t.isEmpty() || t.length() > 12 || !t.contains(keyword))
                continue;
            int score = t.length()
                    + (n.clickable ? 0 : 5)
                    + (n.bounds.width() > 0 && n.bounds.height() > 0 ? 0 : 10);
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    /**
     * 点按钮：先试无障碍 ACTION_CLICK（自身或最近的可点击祖先），
     * 不行再用可点击祖先/自身 bounds 的中心做坐标点击。
     * @return 执行方式（用于日志）；完全点不动返回 null
     */
    private String clickText(SelectToSpeakService s, ScreenClassifier.Node n) {
        AccessibilityNodeInfo cur = n.raw;
        Rect clickRect = null;
        int depth = 0;
        while (cur != null && depth < 12) {
            try {
                if (cur.isClickable()) {
                    Rect b = new Rect();
                    cur.getBoundsInScreen(b);
                    if (b.width() > 0 && b.height() > 0)
                        clickRect = b;
                    if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        return "（无障碍 ACTION_CLICK " + (clickRect == null ? "" : clickRect) + "）";
                }
            } catch (Throwable ignored) {
            }
            try {
                cur = cur.getParent();
            } catch (Throwable ignored) {
                break;
            }
            depth++;
        }
        Rect b = clickRect != null ? clickRect : n.bounds;
        if (b != null && b.width() > 0 && b.height() > 0) {
            int cx = b.centerX(), cy = b.centerY();
            synchronized (ScreenClassifier.A11Y_LOCK) {
                s.tapDirect(cx, cy);
            }
            return "坐标 (" + cx + "," + cy + ")";
        }
        return null;
    }

    /** 当前真实屏幕尺寸（像素，含状态栏/导航栏，随转屏变化） */
    private int[] displaySize(SelectToSpeakService s) {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) s.getSystemService("window");
            if (wm == null)
                return new int[]{0, 0};
            wm.getDefaultDisplay().getRealMetrics(dm);
            return new int[]{dm.widthPixels, dm.heightPixels};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    private boolean isLandscape(SelectToSpeakService s) {
        int[] sz = displaySize(s);
        return sz[0] > sz[1];
    }

    /** 点「继续学习」后视频要转横屏全屏，轮询等它转过去（最多 ms 毫秒） */
    private void awaitLandscape(SelectToSpeakService s, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until && running) {
            if (isLandscape(s))
                return;
            sleep(1000);
        }
    }

    /**
     * 确认「真的是横屏全屏视频」后再缩放回半屏。
     * 只看方向会在横屏平板的普通界面误触发，所以要用节点特征二次确认；
     * nodes 为 null 时现采一份。
     */
    private void restoreIfFullscreen(SelectToSpeakService s, List<ScreenClassifier.Node> nodes,
                                     String why) throws InterruptedException {
        int[] sz = displaySize(s);
        if (sz[0] <= sz[1])
            return;
        boolean fullscreen;
        if (nodes != null) {
            fullscreen = ScreenClassifier.isFullscreenVideo(nodes, sz[0], sz[1]);
        } else {
            List<ScreenClassifier.Node> fresh;
            synchronized (ScreenClassifier.A11Y_LOCK) {
                fresh = ScreenClassifier.collect(s);
            }
            fullscreen = ScreenClassifier.isFullscreenVideo(fresh, sz[0], sz[1]);
            ScreenClassifier.recycle(fresh);
        }
        if (!fullscreen) {
            log("挂机检测：" + why + " → 节点特征不是全屏视频，跳过缩放");
            return;
        }
        restoreHalfScreen(s, why, sz[0], sz[1]);
    }

    /**
     * 退出横屏全屏：点右下角「缩放」按钮回半屏面板。
     *
     * <p>「缩放」按钮是 XWeb canvas 画的，节点树里没有，只能用截图识别
     * （{@link #findScaleButton}）。难点在于全屏控制条会自动显示/隐藏：</p>
     * <ol>
     *   <li>先直接截一张：刚进全屏时控制条通常是显示的，能直接识别到；</li>
     *   <li>识别不到再点屏幕中心唤出控制条（注意：如果控制条本来就显示着，
     *       这一次点击反而会把它隐藏，所以必须先截图后点击）；</li>
     *   <li>点完 0.4 秒、1.1 秒各补一张，抓住控制条动画出现的那一小段时间；</li>
     *   <li>全都识别不到才退回按屏幕比例换算（{@link #SCALE_BTN_X_RATIO}）。</li>
     * </ol>
     * 每轮结束都重新确认是否还在横屏；最多 2 轮。
     */
    private void restoreHalfScreen(SelectToSpeakService s, String why, int w, int h)
            throws InterruptedException {
        for (int round = 1; round <= 2 && running; round++) {
            int[] sz = displaySize(s);
            if (sz[0] <= sz[1])
                return;
            w = sz[0];
            h = sz[1];
            log("挂机检测：" + why + " → 第" + round + "轮缩放回半屏 (屏 " + w + "x" + h + ")");

            // 1) 控制条可能正显示着，先直接识别，避免多余的中心点击把它关掉
            int[] icon = findScaleButton(s);
            if (icon == null) {
                // 2) 控制条没显示：点中心唤出，再快速补拍（控制条只显示约 3 秒）
                synchronized (ScreenClassifier.A11Y_LOCK) {
                    s.tapDirect(w / 2, h / 2);
                }
                sleep(400);
                icon = findScaleButton(s);
                if (icon == null) {
                    sleep(700);
                    icon = findScaleButton(s);
                }
            }

            int bx, by;
            if (icon != null) {
                bx = icon[0];
                by = icon[1];
                log("挂机检测：截图识别到缩放按钮 (" + bx + "," + by + ")");
            } else {
                // 兜底：按 dp 换算（微信控件是 dp 布局，比按比例更抗不同分辨率）
                float den = android.content.res.Resources.getSystem()
                        .getDisplayMetrics().density;
                bx = w - (int) (SCALE_BTN_INSET_RIGHT_DP * den);
                by = h - (int) (SCALE_BTN_INSET_BOTTOM_DP * den);
                log("挂机检测：截图未识别到缩放按钮（" + lastScaleNote + "），按 dp 兜底 ("
                        + bx + "," + by + ")");
            }
            synchronized (ScreenClassifier.A11Y_LOCK) {
                s.tapDirect(bx, by);                // 点右下角「缩放」
            }
            sleep(1800);
            if (!isLandscape(s)) {
                log("挂机检测：已退回半屏播放面板");
                lastActionAt = System.currentTimeMillis();
                return;
            }
        }
        log("挂机检测：缩放两次仍为横屏，交由引擎后续处理");
        lastActionAt = System.currentTimeMillis();
    }

    /**
     * 用截图在右下角找全屏控制条里的「缩放」图标，返回图标中心点；找不返回 null。
     *
     * <p>识别依据：控制条上的图标是<b>中性灰色</b>（R≈G≈B，实测约 128），
     * 而视频画面基本是彩色（蓝色调，B 明显大于 R），所以按「灰度」过滤后
     * 取最靠右的那一坨就是缩放按钮；左侧同样的灰色元素是时间文字，不会被选中。</p>
     *
     * <p>全部按截图实际尺寸计算，不含任何固定像素，换分辨率也能用。</p>
     */
    private int[] findScaleButton(SelectToSpeakService s) {
        Bitmap bmp = null;
        try {
            bmp = s.takeScreenshotNow();
        } catch (Throwable ignored) {
        }
        if (bmp == null) {
            lastScaleNote = "截图失败";
            return null;
        }
        try {
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            if (bw <= 0 || bh <= 0)
                return null;
            // 只看右下角：右边 14%、下边 28% 的范围
            int x0 = (int) (bw * 0.86f);
            int y0 = (int) (bh * 0.72f);
            int step = Math.max(2, bw / 600);       // 采样步长随分辨率缩放
            int cell = 16;                          // 分桶尺寸（像素）
            int cols = (bw + cell - 1) / cell;
            Map<Integer, int[]> buckets = new HashMap<>();   // key -> {数量, x 和, y 和}
            for (int y = y0; y < bh; y += step) {
                for (int x = x0; x < bw; x += step) {
                    int c = bmp.getPixel(x, y);
                    int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    int lum = (r + g + b) / 3;
                    if (lum < 45 || lum > 225)
                        continue;                       // 纯黑背景 / 过曝
                    int mx = Math.max(r, Math.max(g, b));
                    int mn = Math.min(r, Math.min(g, b));
                    if (mx - mn > 16)
                        continue;                       // 不够灰，是视频画面
                    int key = (y / cell) * cols + (x / cell);
                    int[] d = buckets.get(key);
                    if (d == null) {
                        d = new int[]{0, 0, 0};
                        buckets.put(key, d);
                    }
                    d[0]++;
                    d[1] += x;
                    d[2] += y;
                }
            }
            int bestKey = -1;
            long bestCx = -1;
            int bestCount = 0;
            for (Map.Entry<Integer, int[]> e : buckets.entrySet()) {
                int[] d = e.getValue();
                if (d[0] < 3)
                    continue;                           // 太稀疏，像噪声
                long cx = d[1] / d[0];
                if (cx > bestCx || (cx == bestCx && d[0] > bestCount)) {
                    bestCx = cx;
                    bestCount = d[0];
                    bestKey = e.getKey();
                }
            }
            if (bestKey < 0) {
                lastScaleNote = "右下角没有灰色图标";
                return null;
            }
            // 在最靠右的那一桶附近再精确算一次质心，并校验尺寸（图标约 40~60px，不应过大）
            int[] d = buckets.get(bestKey);
            int cx = d[1] / d[0], cy = d[2] / d[0];
            int radius = Math.max(24, bw / 60);
            int minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1;
            long sx = 0, sy = 0, n = 0;
            for (int y = Math.max(y0, cy - radius); y < Math.min(bh, cy + radius); y += 2) {
                for (int x = Math.max(x0, cx - radius); x < Math.min(bw, cx + radius); x += 2) {
                    int c = bmp.getPixel(x, y);
                    int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    int lum = (r + g + b) / 3;
                    int mx = Math.max(r, Math.max(g, b));
                    int mn = Math.min(r, Math.min(g, b));
                    if (lum < 45 || lum > 225 || mx - mn > 16)
                        continue;
                    sx += x;
                    sy += y;
                    n++;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
            if (n < 12) {
                lastScaleNote = "灰色像素太少";
                return null;
            }
            int iw = maxX - minX, ih = maxY - minY;
            if (iw < bw * 0.01f || ih < bh * 0.01f || iw > bw * 0.15f || ih > bh * 0.20f) {
                lastScaleNote = "灰色块尺寸不像图标";
                return null;                            // 多半是画面里的灰色块
            }
            lastScaleNote = "";
            int px = (int) (sx / n), py = (int) (sy / n);
            // 质心可能被图标周围的灰点带偏，夹回图标 bbox 中心更稳
            px = (minX + maxX) / 2;
            py = (minY + maxY) / 2;
            return new int[]{px, py};
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                bmp.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void log(String s) {
        Log.i(TAG, s);
        StudyEngine.get().note(s);
    }
}
