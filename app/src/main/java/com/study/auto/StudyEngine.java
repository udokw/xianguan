package com.study.auto;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.android.accessibility.selecttospeak.SelectToSpeakService;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 自动学习引擎（四态状态机：播放面板 / 文档学习 / 课程列表 / 未识别）。
 *
 * <p><b>坐标约定：</b>本类不写任何固定像素坐标，所有点击/滑动位置都由
 * 「当前真实屏幕尺寸 × 比例」或「节点树里节点的 bounds」实时算出，
 * 所以换分辨率、换长宽比的手机都能直接用。需要微调时改对应的比例常量即可。</p>
 *
 * <p>各分支要点：</p>
 * <ul>
 *   <li><b>播放面板</b>：用「帧差 + 音频」判断是否在播放；未播放先点大播放按钮（优先黑区截图定位），
 *       两次复检仍不动就判卡住并关闭面板；停留时长优先用列表行读到的「已播/总长」倒计时，
 *       读不到就用探测时长，到点后点「面板上方遮罩」回列表（见 {@link #exitPanel}）。</li>
 *   <li><b>文档学习</b>：没有播放控件，按设置时长挂机；到点用节点树的返回钮、「课件学习」标题行
 *       点左上角返回（见 {@link #onDoc}）。</li>
 *   <li><b>课程列表</b>：找带 % 的未完成课程；优先对节点做无障碍 ACTION_CLICK（不依赖坐标），
 *       失败再按行容器 bounds 中心点击；本屏没有 % 就「等节点树刷新 → 轻推 → 上滑半屏」（见 {@link #onList}）。</li>
 *   <li><b>未识别</b>：转场动画中或全屏横屏播放时什么都不做，交给挂机检测线程处理。</li>
 * </ul>
 */
public class StudyEngine {
    private static final String TAG = "AutoStudy";
    private static StudyEngine inst;

    public static synchronized StudyEngine get() {
        if (inst == null)
            inst = new StudyEngine();
        return inst;
    }

    // 面板/文档停留时长由设置决定（默认各 1 分钟），每轮实时读取
    private static final int MAX_PLAY_TRIES = 2;
    private static final int MAX_LIST_SCROLL = 15;
    /** 节点树刷不出坐标时，轻推催树的最大次数 */
    private static final int REFRESH_WAIT_TRIES = 5;
    /** 读到视频剩余时长后，在剩余基础上多留的时间（保证看完再多学 1 分钟） */
    private static final long VIDEO_EXTRA_MS = 60_000L;
    /** 面板停留的安全上限，防止异常情况下无限挂在一个面板里 */
    private static final long MAX_PANEL_MS = 3 * 3_600_000L;
    /** 列表行读到的剩余时长上限，超过就认为读错了（课程视频不会超过 2 小时） */
    private static final long MAX_REMAIN_MS = 2 * 3_600_000L;
    /** 读不到进度时的"探测时长"：先学 90 秒，回列表这一行就会显示「已播/总长」 */
    private static final long PROBE_CHUNK_MS = 90_000L;
    /** 滑动后固定等界面动画结束、节点树稳定，再重新采集信息，避免拿旧快照做判断 */
    private static final long SCROLL_SETTLE_MS = 3000;

    public volatile boolean running = false;
    public volatile String page = "待启动";
    public volatile String evidence = "—";
    public volatile int nodeCount = 0;
    public volatile int openedCount = 0;
    public volatile List<String> logs = new ArrayList<>();
    public volatile List<String> samples = new ArrayList<>();

    private final ArrayDeque<String> logBuf = new ArrayDeque<>();

    private SelectToSpeakService svc;
    private Thread thread;
    private int screenW = 1080, screenH = 2248;
    private List<ScreenClassifier.Node> lastNodes = null;
    // 面板
    private long panelEnterAt = 0;
    private long lastPlayTapAt = 0;
    private int playTries = 0;
    private Boolean lastPlaying = null;
    private int panelTopY = -1;
    private long lastHeartbeat = 0;
    private String lastProgress = "";
    private long progressChangedAt = 0;
    private int staticStreak = 0;
    /** 本次面板停留目标：读到视频剩余时长就用「剩余 + 1 分钟」，为 0 表示沿用设置时长 */
    private long panelStayMs = 0;
    /**
     * 视频播放结束时刻（绝对时间戳）= 当前时刻 + 剩余时长 + 1 分钟。
     * 每次重新读到「已播/总长」就按 now + 剩余 + 1 分钟 校正一次；
     * 剩余时长随时间递减，所以这个值稳定指向「视频播完 + 1 分钟」，到点才点屏幕中心回列表。
     */
    private long panelDeadlineAt = 0;
    private long lastStayProbeAt = 0;
    /** 上一次探针读到的剩余时长，用来判断"变长"还是"文本没刷新" */
    private long lastProbeRemainMs = 0;
    /** 列表行里读到的「已播 / 总长」换算出的剩余毫秒，进面板时转成倒计时；-1 表示没读到 */
    private long pendingRemainMs = -1;
    /** 连续几轮进面板时都没读到列表行进度（用来把"探测时长"逐步升级为兜底时长） */
    private int unknownRounds = 0;
    /** 挂机弹窗日志节流 */
    private long lastPauseLogAt = 0;

    // 文档
    private long docEnterAt = 0;

    // 列表
    private int listScrollTries = 0;
    private int ghostStreak = 0;
    private int emptyStreak = 0;
    private String lastListSig = "";
    private long lastListLogAt = 0;
    private long lastListTapAt = 0;
    /** 连续几次上滑都滑不动（说明已经到底） */
    private int noMoreScroll = 0;
    /** 「全部课程已完成」只提示一次，列表里重新出现 % 后复位 */
    private boolean allDoneAnnounced = false;

    // 动作冷却：点击/滑动后给界面留出跳转时间，期间只观察不动手
    private long cooldownUntil = 0;
    private String stablePage = "";
    private long stableSince = 0;
    private boolean pendingOpen = false;

    public synchronized void start(SelectToSpeakService s) {
        if (running)
            return;
        this.svc = s;
        reset(s);
        running = true;
        thread = new Thread(this::loop, "study-engine");
        thread.start();
        log("引擎启动 屏幕=" + screenW + "x" + screenH);
    }

    public synchronized void stop() {
        running = false;
        if (thread != null)
            thread.interrupt();
        thread = null;
        page = "已停止";
        log("引擎停止");
    }

    private void reset(SelectToSpeakService s) {
        openedCount = 0;
        panelEnterAt = 0;
        lastPlayTapAt = 0;
        playTries = 0;
        lastPlaying = null;
        panelTopY = -1;
        lastProgress = "";
        progressChangedAt = 0;
        staticStreak = 0;
        panelStayMs = 0;
        panelDeadlineAt = 0;
        pendingRemainMs = -1;
        unknownRounds = 0;
        lastStayProbeAt = 0;
        lastProbeRemainMs = 0;
        lastPauseLogAt = 0;
        docEnterAt = 0;
        listScrollTries = 0;
        ghostStreak = 0;
        emptyStreak = 0;
        noMoreScroll = 0;
        allDoneAnnounced = false;
        lastListSig = "";
        lastListLogAt = 0;
        lastListTapAt = 0;
        cooldownUntil = 0;
        stablePage = "";
        stableSince = 0;
        logBuf.clear();
        logs = new ArrayList<>();
        samples = new ArrayList<>();
        page = "识别中";
        evidence = "—";
        nodeCount = 0;

        // 无障碍坐标空间是整块物理屏（含状态栏/导航栏），用真实分辨率，避免比例阈值偏差
        syncScreenSize(s);
    }

    /** 每轮按当前设备真实分辨率换算一次，换设备/转屏都能自适应 */
    private void syncScreenSize(SelectToSpeakService s) {
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            android.view.WindowManager wm =
                    (android.view.WindowManager) s.getSystemService("window");
            if (wm != null) {
                wm.getDefaultDisplay().getRealMetrics(dm);
                if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                    screenW = dm.widthPixels;
                    screenH = dm.heightPixels;
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        Resources r = Resources.getSystem();
        screenW = r.getDisplayMetrics().widthPixels;
        screenH = r.getDisplayMetrics().heightPixels;
    }

    // ==================== 日志 ====================

    private static final SimpleDateFormat HF = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private void log(String s) {
        Log.i(TAG, s);
        String line = HF.format(new Date()) + " " + s;
        synchronized (logBuf) {
            logBuf.addLast(line);
            while (logBuf.size() > 60)
                logBuf.removeFirst();
            logs = new ArrayList<>(logBuf);
        }
    }

    /** 供挂机检测线程写入同一条日志流，便于在浮窗里一起看 */
    public void note(String s) {
        log(s);
    }

    // ==================== 主循环 ====================

    private void loop() {
        while (running) {
            try {
                SelectToSpeakService s = svc != null ? svc : SelectToSpeakService.INSTANCE;
                if (s == null) {
                    page = "无障碍未连接";
                    evidence = "请开启本应用的无障碍服务";
                    safeSleep(1000);
                    continue;
                }
                if (!s.isWechatForeground()) {
                    if (panelEnterAt != 0 || docEnterAt != 0) {
                        log("【等待】已离开微信，清零计时");
                        panelEnterAt = 0;
                        docEnterAt = 0;
                        lastPlaying = null;
                    }
                    page = "非微信界面";
                    evidence = "切到微信小程序后自动开始";
                    nodeCount = 0;
                    safeSleep(1000);
                    continue;
                }
                List<ScreenClassifier.Node> nodes;
                synchronized (ScreenClassifier.A11Y_LOCK) {
                    nodes = ScreenClassifier.collect(s);
                }
                syncScreenSize(s);
                ScreenClassifier.Result r = ScreenClassifier.classify(nodes, screenH);
                ScreenClassifier.recycle(lastNodes);
                lastNodes = nodes;
                page = r.page;
                evidence = r.evidence;
                nodeCount = r.nodeCount;
                samples = r.samples;

                // 页面稳定性：两次连续判定一致才动手，避免跳转瞬间误判
                long t = System.currentTimeMillis();
                if (!r.page.equals(stablePage)) {
                    stablePage = r.page;
                    stableSince = t;
                }
                if (t - stableSince < 1200) {
                    safeSleep(600);
                    continue;
                }
                // 动作冷却期：只观察，不动手
                if (t < cooldownUntil) {
                    evidence = r.evidence + " · 冷却中";
                    safeSleep(800);
                    continue;
                }

                // 断网 / 网关错误的「警告提示」弹窗盖在界面上：原地等待挂机线程点「确定」，
                // 否则无障碍 ACTION_CLICK 会穿过弹窗点到后面的行，界面状态全乱
                if (ScreenClassifier.findWarnOk(nodes, screenH) != null) {
                    evidence = "检测到「警告提示」弹窗，等挂机线程点「确定」";
                    cooldownUntil = t + 1500;
                    safeSleep(1500);
                    continue;
                }

                // 「观看不满30秒，本次观看不计入进度」这类小程序提示框会盖住列表，
                // 先点掉再继续，否则后续点击全被它吞掉
                if (ScreenClassifier.PAGE_LIST.equals(r.page) && dismissHintDialog(nodes)) {
                    safeSleep(1200);
                    continue;
                }

                switch (r.page) {
                    case ScreenClassifier.PAGE_PANEL:
                        onPanel(s, r, nodes);
                        break;
                    case ScreenClassifier.PAGE_DOC:
                        onDoc(nodes);
                        break;
                    case ScreenClassifier.PAGE_LIST:
                        onList(nodes);
                        break;
                    default:
                        page = ScreenClassifier.PAGE_NONE;
                        evidence = r.evidence;
                        safeSleep(1200);
                        break;
                }
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                log("循环异常 " + t);
                safeSleepQuiet(1500);
            }
        }
        running = false;
    }

    // ==================== 播放面板 ====================

    /** 挂机弹窗特征：标题「挂机检测(...)」或正文「学习已暂停」 */
    private boolean pauseDialogOnPanel(List<ScreenClassifier.Node> nodes) {
        if (nodes == null)
            return false;
        for (ScreenClassifier.Node n : nodes) {
            if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg) || n.text == null)
                continue;
            String t = n.text.replaceAll("\\s", "");
            if (t.contains("挂机检测") || t.contains("学习已暂停"))
                return true;
        }
        return false;
    }

    /** 小程序里的「我知道了」提示框会盖住整个列表，点掉它 */
    private boolean dismissHintDialog(List<ScreenClassifier.Node> nodes) {
        for (ScreenClassifier.Node n : nodes) {
            if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg) || n.text == null)
                continue;
            if (!"我知道了".equals(n.text.trim()))
                continue;
            Rect b = n.bounds;
            if (b == null || b.height() <= 0 || b.centerY() < screenH * 0.1f)
                continue;
            log("【提示框】点掉「我知道了」 (" + b.centerX() + "," + b.centerY() + ")");
            tap(b.centerX(), b.centerY());
            return true;
        }
        return false;
    }

    private void onPanel(SelectToSpeakService s, ScreenClassifier.Result r, List<ScreenClassifier.Node> nodes)
            throws InterruptedException {
        long now = System.currentTimeMillis();
        // 挂机弹窗盖在面板上：此时点播放/点屏幕中心都会落在弹窗上，
        // 引擎什么都不做，交给挂机检测线程点「继续学习」（倒计时顺延，不白等）
        if (pauseDialogOnPanel(nodes)) {
            if (panelDeadlineAt > 0)
                panelDeadlineAt += 1500;
            if (now - lastPauseLogAt > 5000) {
                lastPauseLogAt = now;
                log("【面板】检测到挂机弹窗，暂停操作，等挂机检测线程点「继续学习」");
            }
            cooldownUntil = now + 1500;
            safeSleep(1500);
            return;
        }
        if (panelEnterAt == 0) {
            panelEnterAt = now;
            playTries = 0;
            lastPlayTapAt = 0;
            lastPlaying = null;
            staticStreak = 0;
            lastProgress = "";
            progressChangedAt = 0;
            panelTopY = ScreenClassifier.panelTop(r.hit, screenW, screenH);
            panelStayMs = 0;
            panelDeadlineAt = 0;
            lastStayProbeAt = 0;
            lastProbeRemainMs = 0;
            log("【面板】进入播放面板 顶边Y=" + panelTopY + (panelTopY < 0 ? "（未取到容器，用估算）" : ""));
            // 进面板前从列表行读到的「已播 / 总长」最可靠（面板控件时间读不到），
            // 先按它定倒计时：剩余 + 1 分钟，到点再点屏幕中心回列表
            android.content.Context octx = svc != null ? svc : SelectToSpeakService.INSTANCE;
            if (pendingRemainMs > 0) {
                unknownRounds = 0;
                panelDeadlineAt = now + pendingRemainMs + VIDEO_EXTRA_MS;
                panelStayMs = pendingRemainMs + VIDEO_EXTRA_MS;
                lastStayProbeAt = now;
                log("【面板】按列表进度倒计时 " + fmtDur(panelStayMs)
                        + "（剩余" + fmtDur(pendingRemainMs) + " + 1分钟），播完再回列表");
            } else {
                // 没读到进度（一般是还没开始学的课）：视频播完会自动重播，
                // 靠"停住"判断永远等不到，所以先挂一段"探测时长"让学生真的播起来，
                // 回列表后这一行就会显示「已播/总长」，下一轮再进这门课就能精确倒计时。
                // 前两轮用 90 秒短探测；连续两轮还读不到才退回设置的兜底时长。
                unknownRounds++;
                long chunk = unknownRounds <= 2
                        ? PROBE_CHUNK_MS
                        : Math.max(AppSettings.playMs(octx), 3 * 60_000L);
                panelDeadlineAt = now + chunk;
                panelStayMs = chunk;
                lastStayProbeAt = now;
                log("【面板】列表行没有进度时间（第" + unknownRounds + "轮）→ 先学 "
                        + fmtDur(chunk) + " 再回列表刷新真实进度");
            }
            pendingRemainMs = -1;
            if (pendingOpen) {
                openedCount++;
                pendingOpen = false;
                log("【面板】确认课程已打开，累计 " + openedCount + " 个");
            }
            dumpPanelNodes(nodes);
        }
        // 每轮刷新面板顶边（界面可能滚动/尺寸变化）
        int top = ScreenClassifier.panelTop(r.hit, screenW, screenH);
        if (top >= 0)
            panelTopY = top;

        // 读视频时长：播放控件上的「当前 / 总长」通常就在节点树文本里。
        // 没读到就 5 秒重试，读到后每 30 秒校正一次（暂停/重播/控件文本滞后都能跟上）
        if (now - lastStayProbeAt > (panelDeadlineAt > 0 ? 30000 : 5000)) {
            lastStayProbeAt = now;
            updateStayTarget(nodes, panelTopY, now);
        }

        // 播放判定：帧差动 = 播放中；另外音频在输出也算播放中
        // （课件讲义画面本来就几乎静止，只看帧差会把正常播放误判成未播放、反复点成暂停）
        Float motion = motionRatio(s, top);
        boolean audio = isAudioActive(s);
        boolean playing;
        String source;
        if (motion != null) {
            playing = motion > 0.010f || audio;
            source = "帧差 " + String.format(Locale.CHINA, "%.4f", motion) + (audio ? " + 音频输出" : "");
        } else {
            playing = audio || (lastPlaying != null && lastPlaying);
            source = audio ? "音频输出" : "截图失败·沿用上次";
        }

        // 静帧确认：连续两次都判定静止才允许点播放，避免把正常播放误点成暂停
        if (!playing) {
            staticStreak++;
            if (staticStreak == 1) {
                log("【面板】静止候选（" + source + "），再确认一次");
                safeSleep(1200);
                return;
            }
        } else {
            staticStreak = 0;
        }
        if (lastPlaying == null || lastPlaying != playing) {
            log("【面板】播放状态 → " + (playing ? "播放中" : "未播放") + "（" + source + "）");
            lastPlaying = playing;
        }

        // 未播放：第 1 次点大播放按钮；第 2 次唤出菜单点三角。
        // 每次点完都按 3秒→复检、未播放再 3秒→复检；两次都未播放即判卡住，点屏幕中心回列表
        if (!playing && now - lastPlayTapAt > 2500 && now - panelEnterAt > 3000) {
            int px, py;
            String how;
            if (playTries == 0) {
                // 大播放按钮在节点树里就是一个可点击控件（空文本正方形 TextView），
                // 直接 performAction(ACTION_CLICK) 即可，不依赖分辨率和截图坐标。
                // 注意：视频子树的 a11y 节点是懒加载的，轮首那份快照常常还没有它，
                // 所以这里先实时重采一次，仍没有就等 600ms 再采一次。
                ScreenClassifier.Node btn = findPanelPlayButton(nodes, top);
                List<ScreenClassifier.Node> fresh = null;
                if (btn == null) {
                    fresh = collectFreshNodes(s, top);
                    if (fresh != null)
                        btn = findPanelPlayButton(fresh, top);
                }
                if (btn != null) {
                    boolean ok = clickByAction(btn);
                    log("【面板】大播放按钮是控件 " + btn.bounds + "，ACTION_CLICK "
                            + (ok ? "成功" : "失败"));
                    if (fresh != null) {
                        ScreenClassifier.recycle(fresh);
                        fresh = null;
                    }
                    if (ok) {
                        lastPlayTapAt = System.currentTimeMillis();
                        cooldownUntil = lastPlayTapAt + 3200;
                        boolean afterNode = verifyPlaying(s, panelTopY);
                        lastPlaying = afterNode;
                        if (afterNode) {
                            playTries = 0;
                            staticStreak = 0;
                            log("【面板】点播后确认已开始播放（大播放按钮·控件）");
                            return;
                        }
                        log("【面板】控件点击后仍未播放 → 补一次视频中心坐标点击");
                    }
                } else {
                    if (fresh != null) {
                        ScreenClassifier.recycle(fresh);
                        fresh = null;
                    }
                    log("【面板】节点树里没找到大播放按钮控件，改用视频中心坐标");
                }
                Rect video = locateVideo(s, nodes, top);
                if (video == null) {
                    // 首次没扫到（动画/半透明遮罩）→ 稍等重扫一次，别急着用估算坐标
                    safeSleepQuiet(700);
                    video = locateVideo(s, nodes, top);
                }
                if (video != null) {
                    px = video.centerX();
                    py = video.centerY();
                    how = "[大播放按钮·视频中心] " + video;
                } else {
                    px = screenW / 2;
                    py = top >= 0 ? top + (int) ((screenH - top) * 0.34f) : (int) (screenH * 0.65);
                    how = "[大播放按钮·估算]";
                }
                log("【面板】点大播放按钮 第1次 " + how + " (" + px + "," + py + ")");
                tap(px, py);
            } else {
                // 大播放按钮已消失（播放过又被暂停）：先点面板中心唤出控制条，再点左下角三角
                int cx = screenW / 2, cy = screenH / 2;
                log("【面板】点面板中心唤出控制条 (" + cx + "," + cy + ")");
                tap(cx, cy);
                safeSleep(1200);
                // 唤出的控制条压在视频画面底部，播放/暂停三角在「视频区左下角」：
                // 优先用截图圈出的黑色视频区（换分辨率也准），截不到再按面板高度估算
                Rect video = locateVideo(s, nodes, top);
                if (video != null) {
                    px = video.left + (int) (video.width() * 0.09f);
                    py = video.bottom - (int) (video.height() * 0.06f);
                    how = "[菜单播放按钮·视频左下] " + video;
                } else {
                    px = (int) (screenW * 0.06f);
                    py = top >= 0 ? top + (int) ((screenH - top) * 0.60f) : (int) (screenH * 0.80f);
                    how = "[菜单播放按钮·估算]";
                }
                log("【面板】点菜单播放按钮 第2次 " + how + " (" + px + "," + py + ")");
                tap(px, py);
            }
            playTries++;
            lastPlayTapAt = System.currentTimeMillis();
            cooldownUntil = System.currentTimeMillis() + 3200;
            boolean after = verifyPlaying(s, panelTopY);
            lastPlaying = after;
            if (after) {
                playTries = 0;
                staticStreak = 0;
                log("【面板】点播后确认已开始播放");
            } else if (playTries >= MAX_PLAY_TRIES) {
                log("【面板】两次复检都未播放，判为卡住 → 点屏幕中心返回课程列表刷新");
                exitPanel(System.currentTimeMillis());
            }
            return;
        }

        long elapsed = now - panelEnterAt;
        // 绝对截止时刻：进面板时 = 此刻 + 剩余（列表行读到的）+ 1 分钟；
        // 读不到剩余就先用兜底时长，到时回列表刷新真实进度再重进
        if (panelDeadlineAt > 0 && now >= panelDeadlineAt) {
            log("【面板】倒计时结束（" + fmtDur(panelStayMs) + " 到点），点屏幕中心返回课程列表");
            exitPanel(now);
            return;
        }
        if (elapsed >= MAX_PANEL_MS) {
            log("【面板】已达安全上限" + fmtDur(MAX_PANEL_MS) + "，返回课程列表");
            exitPanel(now);
            return;
        }

        if (now - lastHeartbeat > 10000) {
            lastHeartbeat = now;
            long left = Math.max(0, panelDeadlineAt - now);
            log("【面板】进行中 倒计时" + fmtDur(left) + " 播放=" + playing + " 尝试=" + playTries);
        }
        safeSleep(1500);
    }

    /**
     * 关闭半屏播放面板，回到课程列表。
     *
     * <p>点击位置：面板「上方那条遮罩区域」——面板顶边 {@link #panelTopY} 是从节点树实时取到的，
     * 所以这里在面板顶边上方 4% 屏高处点，任何分辨率都落在遮罩上、不会点进视频区（点视频会暂停/播放）；
     * 取不到面板顶边时才退回屏幕中心（小米8 实测中心也刚好在面板上方，同样能关闭）。</p>
     */
    private void exitPanel(long now) throws InterruptedException {
        SelectToSpeakService s = svc != null ? svc : SelectToSpeakService.INSTANCE;
        if (s != null)
            syncScreenSize(s);
        int x = screenW / 2;
        int y;
        if (panelTopY >= 0)
            y = panelTopY - (int) (screenH * 0.04f);
        else
            y = screenH / 2;
        y = Math.max((int) (screenH * 0.08f), Math.min(y, screenH / 2));
        log("【面板】点面板上方 (" + x + "," + y + ") 回课程列表" + (panelTopY < 0 ? "（未取到面板顶边·用屏幕中心）" : ""));
        tap(x, y);
        panelEnterAt = 0;
        panelTopY = -1;
        panelStayMs = 0;
        panelDeadlineAt = 0;
        lastStayProbeAt = 0;
        lastProbeRemainMs = 0;
        lastPlaying = null;
        playTries = 0;
        staticStreak = 0;
        lastProgress = "";
        long waitMs = AppSettings.backToListMs(s);
        cooldownUntil = now + waitMs;
        log("【面板】已返回列表，等待" + (waitMs / 1000) + "秒后重新扫描");
        safeSleep(waitMs);
    }

    /** 播放控件的时间文本：mm:ss 或 hh:mm:ss */
    private static final Pattern TIME_TEXT = Pattern.compile("(\\d{1,3}):(\\d{2})(?::(\\d{2}))?");
    /** 列表行里的「已播 / 总长」 */
    private static final Pattern CUR_TOTAL_TEXT =
            Pattern.compile("(\\d{1,3}:\\d{2}(?::\\d{2})?)/(\\d{1,3}:\\d{2}(?::\\d{2})?)");

    /** 把 mm:ss / hh:mm:ss 解析成毫秒；解析失败返回 -1 */
    private long parseClock(String s) {
        String[] p = s.split(":");
        try {
            if (p.length == 2)
                return Long.parseLong(p[0]) * 60_000L + Long.parseLong(p[1]) * 1000L;
            if (p.length == 3)
                return Long.parseLong(p[0]) * 3_600_000L + Long.parseLong(p[1]) * 60_000L
                        + Long.parseLong(p[2]) * 1000L;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * 从列表某一行的节点文字里读「已播 / 总长」，返回剩余毫秒。
     * 视频面板的控件时间是 canvas 画的、读不到，但列表行上的进度文本是真实 TextView。
     * 关键点：虚拟列表里行容器的 bounds 经常塌陷成高 0，所以不能用尺寸筛容器，
     * 直接从 % 节点往上逐层找：哪一层的子树里出现「已播 / 总长」就用它；
     * 子树里出现两门以上课程标题就说明已经升到整块列表容器，停止。
     * @return 剩余毫秒；没读到返回 -1
     */
    private long rowRemainMs(List<ScreenClassifier.Node> all, ScreenClassifier.Node anchor) {
        if (all == null || anchor == null)
            return -1;
        ScreenClassifier.Node cur = anchor;
        int depth = 0;
        while (cur != null && depth < 9) {
            List<String> texts = ScreenClassifier.textsUnder(all, cur);
            int titles = 0;
            long best = -1;
            for (String text : texts) {
                String s = text.replaceAll("\\s", "");
                if (s.contains(".mp4") || s.contains(".pdf"))
                    titles++;
                long r = remainFromText(text);
                if (r > best)
                    best = r;
            }
            if (best > 0)
                return best;
            if (titles > 1)
                break;                  // 已经是多行容器的层级了，再往上会串到别的课程
            cur = cur.parent;
            depth++;
        }
        return -1;
    }

    /** 「0:32 / 29:06」→ 剩余毫秒；不是这种文本返回 -1 */
    private long remainFromText(String text) {
        if (text == null)
            return -1;
        String t = text.replaceAll("\\s", "");
        java.util.regex.Matcher m = CUR_TOTAL_TEXT.matcher(t);
        if (!m.find())
            return -1;
        long cur = parseClock(m.group(1));
        long total = parseClock(m.group(2));
        if (cur < 0 || total <= 0 || total <= cur || total > MAX_REMAIN_MS)
            return -1;
        return total - cur;
    }
    /** 面板底部的日期（2026-07-31 12:13:57）不能当成视频时长 */
    private static final Pattern DATE_TEXT = Pattern.compile("\\d{4}\\s*[-/年.]\\s*\\d{1,2}");

    /**
     * 面板里的时长探针：只用来「延长」倒计时，绝不缩短。
     *
     * 这个 App 的视频控制条时间是 canvas 画的，节点树里读不到；树里能匹配到的 mm:ss
     * 往往来自面板背后列表里其它课程的行文本，如果直接采用会把当前视频提前结束。
     * 所以：
     *  - 已经在列表行拿到本次课程进度（panelDeadlineAt 已定）→ 只有读到明显更长的剩余才延长；
     *  - 没拿到（panelDeadlineAt == 0）→ 只记日志，不采用，交给「播放自己停住」判定。
     */
    private void updateStayTarget(List<ScreenClassifier.Node> nodes, int panelTop, long now) {
        long remaining = parseRemainingMs(nodes, panelTop);
        if (remaining <= 0)
            return;
        long prev = lastProbeRemainMs;
        lastProbeRemainMs = remaining;
        if (panelDeadlineAt == 0) {
            log("【面板·时长】树里读到剩余 " + fmtDur(remaining)
                    + "（来源不一定是本次课程，仅记录，不据此缩短停留）");
            return;
        }
        // 只有剩余时长"变长"才说明真的重播/换片，需要把终点往后推；
        // 变小是正常推进，相等就是控件文本没刷新——这两种都不能动终点，
        // 否则每 30 秒读一次同一个值会把倒计时无限续命，视频播完也永远不返回。
        if (prev <= 0 || remaining <= prev + 30_000L)
            return;
        long candidate = now + remaining + VIDEO_EXTRA_MS;
        if (candidate > panelDeadlineAt + 30_000L
                && candidate < panelDeadlineAt + 30 * 60_000L) {
            panelDeadlineAt = candidate;
            panelStayMs = panelDeadlineAt - panelEnterAt;
            log("【面板·时长】剩余从 " + fmtDur(prev) + " 变长为 " + fmtDur(remaining)
                    + " → 倒计时延长为 " + fmtDur(Math.max(0, panelDeadlineAt - now)));
        }
    }

    /**
     * 从节点文字解析视频剩余时长（毫秒），只读无障碍文本，不做 OCR。
     *
     * 两个关键过滤：
     * 1) 只要「面板区域内、真实可见」的节点：列表里残留的课程行是零高幽灵节点
     *    （例如 [759,2182][932,2182] 的 "0:00 / 29:06"），高度为 0 直接排除；
     * 2) 面板控件的时间常被拆成两个独立 TextView（左「10:07」右「10:51」），
     *    同一行左右配对后取 总长 − 当前 = 剩余。
     *
     * @return 剩余毫秒；解析不到返回 -1
     */
    private long parseRemainingMs(List<ScreenClassifier.Node> nodes, int panelTop) {
        long labeled = -1, pairInNode = -1;
        StringBuilder cand = new StringBuilder();
        List<long[]> singles = new ArrayList<>();   // {毫秒, centerX, centerY}
        int top = panelTop > 0 ? panelTop : (int) (screenH * 0.5f);

        for (ScreenClassifier.Node n : nodes) {
            if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg))
                continue;
            Rect b = n.bounds;
            if (b == null || b.height() <= 0 || b.width() <= 0)
                continue;                      // 幽灵节点：零高，不是真实控件
            int cy = b.centerY();
            if (cy < top || cy > screenH * 0.98f)
                continue;                      // 只看面板区域内的真实控件
            String t = ((n.text == null ? "" : n.text) + " " + (n.desc == null ? "" : n.desc)).trim();
            if (t.isEmpty() || DATE_TEXT.matcher(t).find())
                continue;
            List<Long> times = new ArrayList<>();
            java.util.regex.Matcher m = TIME_TEXT.matcher(t);
            while (m.find()) {
                long h = m.group(3) != null ? Long.parseLong(m.group(1)) : 0;
                long mi = m.group(3) != null ? Long.parseLong(m.group(2)) : Long.parseLong(m.group(1));
                long se = m.group(3) != null ? Long.parseLong(m.group(3)) : Long.parseLong(m.group(2));
                times.add(h * 3_600_000L + mi * 60_000L + se * 1000L);
            }
            if (times.isEmpty())
                continue;
            if (times.size() >= 2) {
                long total = times.get(times.size() - 1);
                long cur = times.get(0);
                long r = total - cur;
                if (total <= 0 || r <= 0 || r > 4 * 3_600_000L)
                    continue;
                appendCand(cand, ScreenClassifier.quote(t), " 剩", fmtDur(r));
                pairInNode = Math.max(pairInNode, r);
            } else {
                long v = times.get(0);
                if (v <= 0 || v > 4 * 3_600_000L)
                    continue;
                boolean isRemain = t.contains("剩余");
                boolean isTotal = t.contains("时长") || t.contains("总") || t.contains("共");
                if (isRemain || isTotal) {
                    appendCand(cand, ScreenClassifier.quote(t),
                            isRemain ? "（剩余）" : "（总长）", fmtDur(v));
                    if (labeled < 0)
                        labeled = v;
                } else {
                    singles.add(new long[]{v, b.centerX(), cy});
                }
            }
        }

        // 同一行（Y 相差 < 2% 屏高）的左右两个时间节点：左=当前，右=总长
        long groupPair = -1;
        int tol = (int) (screenH * 0.02f);
        for (int i = 0; i < singles.size(); i++) {
            long[] a = singles.get(i);
            long max = a[0], min = a[0];
            int count = 1;
            for (int j = i + 1; j < singles.size(); j++) {
                long[] o = singles.get(j);
                if (Math.abs(o[2] - a[2]) > tol)
                    continue;
                max = Math.max(max, o[0]);
                min = Math.min(min, o[0]);
                count++;
            }
            if (count < 2)
                continue;
            long r = max - min;
            if (r <= 0 || r > 4 * 3_600_000L)
                continue;
            appendCand(cand, "同行2节点", " y=" + a[2] + " 剩", fmtDur(r));
            // 面板后面还压着列表里其它课程行的进度文本，同一屏会出现多组；
            // 无法确定哪一组属于本次打开的课程，取剩余最长的（宁可多等，不能提前退出）
            groupPair = Math.max(groupPair, r);
        }

        long result = Math.max(labeled, Math.max(pairInNode, groupPair));
        if (cand.length() > 0)
            log("【面板·时长】候选 " + cand + " → 采用 " + (result > 0 ? fmtDur(result) : "无"));
        return result;
    }

    private void appendCand(StringBuilder sb, String a, String b, String c) {
        if (sb.length() > 0)
            sb.append(" | ");
        sb.append(a).append(b).append(c);
    }

    private String fmtDur(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.CHINA, "%d分%02d秒", s / 60, s % 60);
    }

    /** 播放状态：帧差动或音频在输出都算播放中 */
    private boolean detectPlaying(SelectToSpeakService s, List<ScreenClassifier.Node> nodes, int panelTop) {
        Float motion = motionRatio(s, panelTop);
        boolean audio = isAudioActive(s);
        if (motion != null) {
            boolean p = motion > 0.010f || audio;
            log("【面板】帧差 " + String.format(Locale.CHINA, "%.4f", motion)
                    + (audio ? " + 音频输出" : "") + " → " + (p ? "播放中" : "静止"));
            return p;
        }
        if (audio)
            return true;
        return lastPlaying != null && lastPlaying;
    }

    /** 系统媒体音频是否在输出（视频播放时微信会占用 USAGE_MEDIA 音频流） */
    private boolean isAudioActive(SelectToSpeakService s) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) s.getSystemService("audio");
            return am != null && am.isMusicActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 点完播放后的复检：等 3 秒查一次，未播放再等 3 秒查第二次 */
    private boolean verifyPlaying(SelectToSpeakService s, int panelTop) {
        safeSleepQuiet(3000);
        boolean first = detectPlaying(s, null, panelTop);
        log("【面板】复检1（点后3秒）→ " + (first ? "播放中" : "未播放"));
        if (first)
            return true;
        safeSleepQuiet(3000);
        boolean second = detectPlaying(s, null, panelTop);
        log("【面板】复检2（再等3秒）→ " + (second ? "播放中" : "未播放"));
        return second;
    }


    /** 用截图找面板里的黑色视频区（暂停时是纯黑+白三角），返回其矩形 */
    /**
     * 定位视频播放器矩形（屏幕坐标）。
     * 换设备/换分辨率后不能再用固定比例，这里每次重新算四角：
     * ① 截图黑区「行/列投影」找视频画面边界（最准）
     * ② 节点树里找面板内的大尺寸容器（视频是白底幻灯片时兜底）
     * ③ 都失败才按面板顶边估算
     * 截图分辨率可能与屏幕分辨率不同（平板常见），所有换算都经 screenToShot/shotToScreen。
     */
    private Rect locateVideo(SelectToSpeakService s, List<ScreenClassifier.Node> nodes, int panelTop) {
        Rect v = findDarkVideoArea(s, panelTop);
        if (v != null) {
            log("【面板】视频区[黑区投影] " + v + " 中心(" + v.centerX() + "," + v.centerY() + ")");
            return v;
        }
        // 无障碍截图有频率限制，失败多半是刚截过一张被限流 → 等 1.2 秒再试
        safeSleepQuiet(1200);
        v = findDarkVideoArea(s, panelTop);
        if (v != null) {
            log("【面板】视频区[黑区投影·重试] " + v + " 中心(" + v.centerX() + "," + v.centerY() + ")");
            return v;
        }
        log("【面板】黑区投影未识别（截图被限流或画面非深色）");
        v = findVideoByNodes(nodes, panelTop);
        if (v != null) {
            log("【面板】视频区[节点容器] " + v + " 中心(" + v.centerX() + "," + v.centerY() + ")");
            return v;
        }
        return null;
    }

    /**
     * 在面板节点树里找「大播放按钮」控件。
     *
     * <p>实测（MI 8 半屏面板）：它就是视频画面正中一个可点击的
     * android.widget.TextView，text/desc 都为空、边界近似正方形
     * （如 [434,1416][646,1625]，约屏幕宽的 20%）。找到后直接
     * performAction(ACTION_CLICK)，不必再截图算中心点。</p>
     *
     * <p>约束条件同时满足才算命中，避免误点左下角小三角、整块视频容器或列表行：
     * 微信包名、可点击、无文字无描述、宽高近似相等、宽度占屏 10%～36%、
     * 水平居中（偏差 &lt; 屏宽 10%）、落在面板区域内。</p>
     */
    private ScreenClassifier.Node findPanelPlayButton(List<ScreenClassifier.Node> nodes, int panelTop) {
        if (nodes == null)
            return null;
        int minW = (int) (screenW * 0.10f);
        int maxW = (int) (screenW * 0.36f);
        int centerTol = (int) (screenW * 0.10f);
        int panelY = panelTop >= 0 ? panelTop : (int) (screenH * 0.45f);
        ScreenClassifier.Node best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (ScreenClassifier.Node n : nodes) {
            if (n == null || n.bounds == null)
                continue;
            if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg))
                continue;
            if (!n.clickable)
                continue;
            if (!n.text.trim().isEmpty() || !n.desc.trim().isEmpty())
                continue;
            Rect b = n.bounds;
            int w = b.width(), h = b.height();
            if (w < minW || w > maxW || h <= 0)
                continue;
            float ratio = h / (float) w;
            if (ratio < 0.55f || ratio > 1.80f)
                continue;
            if (Math.abs(b.centerX() - screenW / 2) > centerTol)
                continue;
            if (b.centerY() < panelY || b.centerY() > screenH * 0.92f)
                continue;
            int diff = Math.abs(w - h) + Math.abs(screenW / 2 - b.centerX());
            if (diff < bestDiff) {
                bestDiff = diff;
                best = n;
            }
        }
        return best;
    }

    /**
     * 实时重采一次节点树：视频子树的 a11y 节点是懒加载的，
     * 轮首快照经常还没有大播放按钮。第一次没采到就等 600ms 再采一次。
     * 返回的快照由调用方用完后 {@link ScreenClassifier#recycle} 回收。
     */
    private List<ScreenClassifier.Node> collectFreshNodes(SelectToSpeakService s, int panelTop) {
        for (int i = 0; i < 2; i++) {
            if (i > 0)
                safeSleepQuiet(600);
            List<ScreenClassifier.Node> fresh;
            synchronized (ScreenClassifier.A11Y_LOCK) {
                // 用不 refresh 的通道：root.refresh() 会让 XWeb 视频播放按钮的虚拟节点消失
                fresh = ScreenClassifier.collectNoRefresh(s);
            }
            if (findPanelPlayButton(fresh, panelTop) != null)
                return fresh;
            ScreenClassifier.recycle(fresh);
        }
        return null;
    }

    /** 节点树兜底：面板区域内、接近满宽、高宽比像 16:9 的最大容器 */
    private Rect findVideoByNodes(List<ScreenClassifier.Node> nodes, int panelTop) {
        if (nodes == null)
            return null;
        int top = panelTop >= 0 ? panelTop : (int) (screenH * 0.50f);
        Rect best = null;
        for (ScreenClassifier.Node n : nodes) {
            if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg) || n.bounds == null)
                continue;
            Rect b = n.bounds;
            if (b.top < top - 8 || b.bottom > screenH + 4)
                continue;
            if (b.width() < screenW * 0.55f || b.width() > screenW)
                continue;
            int hh = b.height();
            if (hh < screenH * 0.12f || hh > screenH * 0.62f)
                continue;
            float ar = hh / (float) Math.max(1, b.width());
            if (ar < 0.25f || ar > 1.30f)
                continue;
            // 容器常把下方「课件名/日期」也框进来 → 按 16:9 截掉多余高度，中心才不会落到画面外
            Rect fit = new Rect(b);
            int maxH = (int) (b.width() * 0.62f);
            if (fit.height() > maxH) {
                fit.bottom = fit.top + maxH;
                if (fit.bottom > screenH)
                    continue;
            }
            if (best == null || fit.width() * (long) fit.height() > best.width() * (long) best.height())
                best = fit;
        }
        return best;
    }

    /** 黑区投影：先按行找视频上下边，再在行段内按列找左右边 */
    private Rect findDarkVideoArea(SelectToSpeakService s, int panelTop) {
        if (s == null)
            return null;
        Bitmap bmp = s.takeScreenshotNow();
        if (bmp == null)
            return null;
        try {
            // 不依赖节点树给的面板顶边（换设备后常不准/滞后）：直接在全屏下半部扫最大黑区。
            // 视频画面是屏内最大的深色矩形，列表/文档背景都是浅色，不会误配。
            int hintTop = panelTop >= 0 ? panelTop : (int) (screenH * 0.45f);
            int top = Math.max((int) (screenH * 0.18f),
                    Math.min(hintTop - (int) (screenH * 0.05f), (int) (screenH * 0.45f)));
            Rect search = new Rect((int) (screenW * 0.01f), top,
                    (int) (screenW * 0.99f), screenH - 4);
            Rect band = screenToShot(bmp, search);
            if (band == null)
                return null;
            band.left = Math.max(0, band.left);
            band.top = Math.max(0, band.top);
            band.right = Math.min(bmp.getWidth(), band.right);
            band.bottom = Math.min(bmp.getHeight(), band.bottom);
            int w = band.width(), h = band.height();
            if (w < 60 || h < 60)
                return null;

            int stepX = Math.max(1, w / 180);
            int stepY = Math.max(1, h / 180);
            int gw = (w + stepX - 1) / stepX;
            int gh = (h + stepY - 1) / stepY;
            int[] buf = new int[w];

            // ① 行投影
            float[] rowDark = new float[gh];
            for (int gy = 0; gy < gh; gy++) {
                int y = band.top + gy * stepY;
                bmp.getPixels(buf, 0, w, band.left, y, w, 1);
                int d = 0;
                for (int gx = 0; gx < gw; gx++) {
                    if (lum(buf[Math.min(w - 1, gx * stepX)]) < 55)
                        d++;
                }
                rowDark[gy] = gw == 0 ? 0f : (float) d / gw;
            }
            int[] rows = longestRun(rowDark, 0.45f);
            if (rows == null || rows[1] - rows[0] < gh * 0.15f)
                return null; // 高度太小 → 不是视频

            // ② 列投影（只在视频行段内）
            int gy0 = rows[0], gy1 = rows[1];
            int rowCount = 0;
            int[] colHits = new int[gw];
            for (int gy = gy0; gy < gy1; gy++) {
                int y = band.top + gy * stepY;
                bmp.getPixels(buf, 0, w, band.left, y, w, 1);
                for (int gx = 0; gx < gw; gx++) {
                    if (lum(buf[Math.min(w - 1, gx * stepX)]) < 55)
                        colHits[gx]++;
                }
                rowCount++;
            }
            if (rowCount == 0)
                return null;
            float[] colDark = new float[gw];
            for (int gx = 0; gx < gw; gx++)
                colDark[gx] = (float) colHits[gx] / rowCount;
            int[] cols = longestRun(colDark, 0.45f);
            if (cols == null || cols[1] - cols[0] < gw * 0.30f)
                return null; // 宽度不足 → 不是视频

            Rect shot = new Rect(
                    band.left + cols[0] * stepX,
                    band.top + gy0 * stepY,
                    band.left + Math.min(w, (cols[1] - 1) * stepX),
                    band.top + Math.min(h, (gy1 - 1) * stepY));
            Rect r = shotToScreen(bmp, shot);
            if (r == null || r.width() < screenW * 0.35f || r.height() < screenH * 0.08f)
                return null;
            return r;
        } catch (Throwable t) {
            return null;
        } finally {
            bmp.recycle();
        }
    }

    private int lum(int c) {
        return (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255)) / 3;
    }

    /** 在数组里找最长的连续满足阈值段，返回 [起, 止) 下标 */
    private int[] longestRun(float[] arr, float thr) {
        int bestA = -1, bestB = -1, cur = -1;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] >= thr) {
                if (cur < 0)
                    cur = i;
            } else if (cur >= 0) {
                if (bestA < 0 || i - cur > bestB - bestA) {
                    bestA = cur;
                    bestB = i;
                }
                cur = -1;
            }
        }
        if (cur >= 0 && (bestA < 0 || arr.length - cur > bestB - bestA)) {
            bestA = cur;
            bestB = arr.length;
        }
        return bestA < 0 ? null : new int[] { bestA, bestB };
    }

    /** 屏幕坐标 → 截图坐标（平板截图分辨率常与 display 指标不同） */
    private Rect screenToShot(Bitmap b, Rect r) {
        if (b == null || r == null)
            return null;
        float sx = b.getWidth() / (float) Math.max(1, screenW);
        float sy = b.getHeight() / (float) Math.max(1, screenH);
        return new Rect(Math.round(r.left * sx), Math.round(r.top * sy),
                Math.round(r.right * sx), Math.round(r.bottom * sy));
    }

    /** 截图坐标 → 屏幕坐标 */
    private Rect shotToScreen(Bitmap b, Rect r) {
        if (b == null || r == null)
            return null;
        float sx = Math.max(1, screenW) / (float) b.getWidth();
        float sy = Math.max(1, screenH) / (float) b.getHeight();
        return new Rect(Math.round(r.left * sx), Math.round(r.top * sy),
                Math.round(r.right * sx), Math.round(r.bottom * sy));
    }

    /** 两帧差异（视频区取样）。返回 null 表示截图失败 */
    private Float motionRatio(SelectToSpeakService s, int panelTop) {
        if (s == null)
            return null;
        Bitmap a = s.takeScreenshotNow();
        if (a == null)
            return null;
        safeSleepQuiet(1200);
        Bitmap b = s.takeScreenshotNow();
        if (b == null) {
            a.recycle();
            return null;
        }
        int top = panelTop >= 0 ? panelTop : (int) (screenH * 0.45);
        Rect region = new Rect((int) (screenW * 0.05f), top + (screenH - top) / 10,
                (int) (screenW * 0.95f), top + (int) ((screenH - top) * 0.60f));
        // 截图分辨率可能与屏幕分辨率不同（平板），换算后再取样
        Rect shotRegion = screenToShot(a, region);
        if (shotRegion == null)
            shotRegion = region;
        float ratio = diffRatio(a, b, shotRegion);
        log("【面板】截图 " + a.getWidth() + "x" + a.getHeight() + " 区=" + region
                + " 亮度A=" + meanLum(a, shotRegion) + " 亮度B=" + meanLum(b, shotRegion)
                + " 帧差=" + String.format(Locale.CHINA, "%.4f", ratio));
        a.recycle();
        b.recycle();
        return ratio;
    }

    /** 区域平均亮度（0-255），用于诊断截图是否为黑屏/静止 */
    private int meanLum(Bitmap b, Rect region) {
        try {
            int x0 = Math.max(0, region.left);
            int y0 = Math.max(0, region.top);
            int x1 = Math.min(b.getWidth(), region.right);
            int y1 = Math.min(b.getHeight(), region.bottom);
            int w = x1 - x0;
            if (w <= 0 || y1 <= y0)
                return -1;
            int[] row = new int[w];
            long sum = 0;
            long n = 0;
            for (int y = y0; y < y1; y += 8) {
                b.getPixels(row, 0, w, x0, y, w, 1);
                for (int x = 0; x < w; x += 8) {
                    int c = row[x];
                    sum += (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255)) / 3;
                    n++;
                }
            }
            return n == 0 ? -1 : (int) (sum / n);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 区域帧差比例（按 6px 网格采样） */
    private float diffRatio(Bitmap a, Bitmap b, Rect region) {
        try {
            int x0 = Math.max(0, region.left);
            int y0 = Math.max(0, region.top);
            int x1 = Math.min(Math.min(a.getWidth(), b.getWidth()), region.right);
            int y1 = Math.min(Math.min(a.getHeight(), b.getHeight()), region.bottom);
            int w = x1 - x0;
            int h = y1 - y0;
            if (w <= 0 || h <= 0)
                return 0f;
            int stepX = 6, stepY = 6;
            int[] ra = new int[w];
            int[] rb = new int[w];
            long changed = 0, total = 0;
            for (int y = y0; y < y1; y += stepY) {
                a.getPixels(ra, 0, w, x0, y, w, 1);
                b.getPixels(rb, 0, w, x0, y, w, 1);
                for (int x = 0; x < w; x += stepX) {
                    int ca = ra[x], cb = rb[x];
                    int d = Math.abs(((ca >> 16) & 255) - ((cb >> 16) & 255))
                            + Math.abs(((ca >> 8) & 255) - ((cb >> 8) & 255))
                            + Math.abs((ca & 255) - (cb & 255));
                    if (d > 45)
                        changed++;
                    total++;
                }
            }
            return total == 0 ? 0f : (float) changed / total;
        } catch (Throwable t) {
            return 0f;
        }
    }

    private void dumpPanelNodes(List<ScreenClassifier.Node> nodes) {
        int c = 0;
        for (ScreenClassifier.Node n : nodes) {
            if (c >= 15)
                break;
            if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg))
                continue;
            Rect b = n.bounds;
            String t = n.text == null ? "" : n.text.trim();
            String d = n.desc == null ? "" : n.desc.trim();
            if (b.width() <= 0 || b.height() <= 0)
                continue;
            if (t.isEmpty() && d.isEmpty() && !n.clickable)
                continue;
            log("【面板】节点 " + shortCls(n.cls) + " text=" + ScreenClassifier.quote(t)
                    + " desc=" + ScreenClassifier.quote(d) + " " + b + " click=" + n.clickable);
            c++;
        }
        if (c == 0)
            log("【面板】节点树没有可见文本节点（可能是原生视频层）");
    }

    // ==================== 文档学习 ====================

    private void onDoc(List<ScreenClassifier.Node> nodes) throws InterruptedException {
        long now = System.currentTimeMillis();
        if (docEnterAt == 0) {
            docEnterAt = now;
            if (pendingOpen) {
                openedCount++;
                pendingOpen = false;
                log("【文档】确认课程已打开，累计 " + openedCount + " 个");
            }
            log("【文档】进入文档学习页，开始计时");
            dumpTopLeft(nodes);
        }
        long elapsed = now - docEnterAt;
        android.content.Context ctx = svc != null ? svc : SelectToSpeakService.INSTANCE;
        long docLimit = AppSettings.docMs(ctx);
        if (elapsed >= docLimit) {
            // 返回按钮定位：优先节点树里的左上返回钮；否则按「课件学习」标题行换算（换设备自适应）
            int x = -1, y = -1;
            String how;
            ScreenClassifier.Node title = findDocTitle(nodes);
            ScreenClassifier.Node back = findTopLeftBack(nodes);
            if (back != null) {
                x = back.bounds.centerX();
                y = back.bounds.centerY();
                how = "[树·返回钮] " + describe(back);
            } else if (title != null) {
                y = title.bounds.centerY();
                x = (int) (title.bounds.left * 0.5f);
                if (x < screenW * 0.03f)
                    x = (int) (screenW * 0.05f);
                how = "[按课件学习行换算] 标题" + title.bounds + " → (" + x + "," + y + ")";
            } else {
                x = (int) (screenW * 0.05f);
                y = (int) (screenH * 0.12f);
                how = "[末级估算] (" + x + "," + y + ")";
            }
            log("【文档】点左上返回 " + how);
            tap(x, y);
            docEnterAt = 0;
            long waitMs = AppSettings.backToListMs(ctx);
            cooldownUntil = System.currentTimeMillis() + waitMs;
            log("【文档】已返回列表，等待" + (waitMs / 1000) + "秒后重新扫描");
            safeSleep(waitMs);
            return;
        }
        if (now - lastHeartbeat > 10000) {
            lastHeartbeat = now;
            log("【文档】进行中 剩" + ((docLimit - elapsed) / 1000) + "s");
        }
        safeSleep(1500);
    }

    /** 文档页第二行的「课件学习」标题节点（返回钮与它同一行） */
    private ScreenClassifier.Node findDocTitle(List<ScreenClassifier.Node> nodes) {
        for (ScreenClassifier.Node n : nodes) {
            if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg) || n.text == null)
                continue;
            if (!n.text.contains("课件学习"))
                continue;
            Rect b = n.bounds;
            if (b.width() <= 0 || b.height() <= 0)
                continue;
            if (b.centerY() > screenH * 0.25f)
                continue;
            return n;
        }
        return null;
    }

    private ScreenClassifier.Node findTopLeftBack(List<ScreenClassifier.Node> nodes) {
        ScreenClassifier.Node best = null;
        int bestScore = 99;
        for (ScreenClassifier.Node n : nodes) {
            if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg))
                continue;
            Rect b = n.bounds;
            if (b.width() <= 0 || b.height() <= 0)
                continue;
            if (b.centerY() > screenH * 0.20f || b.centerX() > screenW * 0.32f)
                continue;
            String t = (n.text + " " + n.desc).trim();
            int score = 99;
            if (t.contains("返回"))
                score = 0;
            else if (t.contains("‹") || t.contains("〈") || t.contains("＜") || t.contains("<"))
                score = 1;
            else if (n.clickable)
                score = 2;
            if (score < bestScore || (score == bestScore && best != null
                    && b.centerY() < best.bounds.centerY())) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    private void dumpTopLeft(List<ScreenClassifier.Node> nodes) {
        int c = 0;
        for (ScreenClassifier.Node n : nodes) {
            if (c >= 6)
                break;
            if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg))
                continue;
            Rect b = n.bounds;
            if (b.width() <= 0 || b.height() <= 0)
                continue;
            if (b.centerY() > screenH * 0.25f || b.centerX() > screenW * 0.35f)
                continue;
            log("【文档】左上候选 " + shortCls(n.cls) + " text=" + ScreenClassifier.quote(n.text)
                    + " desc=" + ScreenClassifier.quote(n.desc) + " " + b + " click=" + n.clickable);
            c++;
        }
    }

    // ==================== 课程列表 ====================

    /** 列表标题里的课程总数：「24个课件」 */
    private static final Pattern COURSE_TOTAL_TEXT = Pattern.compile("(\\d{1,3})个课件");

    /** 列表页整屏都是「完成」、且已滑到底 → 判定全部学完，弹一次气泡 */
    private void onAllCoursesDone(List<ScreenClassifier.Node> nodes) throws InterruptedException {
        int total = courseTotalCount(nodes);
        if (!allDoneAnnounced) {
            allDoneAnnounced = true;
            int n = Math.max(total, 0);
            String msg = "所有课程已经完成，共 " + n + " 个";
            log("【列表】" + msg + "（连续上滑到底且整屏无%）");
            showToast(msg);
        }
        listScrollTries = 0;
        noMoreScroll = 0;
        safeSleep(60000);        // 每分钟复核一次，万一又出现新课程会自动继续
    }

    /** 课程总数：优先读列表标题里的「N个课件」，读不到就数 .mp4/.pdf 行 */
    private int courseTotalCount(List<ScreenClassifier.Node> nodes) {
        int declared = -1;
        if (nodes != null) {
            for (ScreenClassifier.Node n : nodes) {
                if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg) || n.text == null)
                    continue;
                java.util.regex.Matcher m = COURSE_TOTAL_TEXT.matcher(n.text.replaceAll("\\s", ""));
                if (!m.find())
                    continue;
                try {
                    declared = Math.max(declared, Integer.parseInt(m.group(1)));
                } catch (Throwable ignored) {
                }
            }
        }
        if (declared > 0)
            return declared;
        Set<String> titles = new HashSet<>();
        if (nodes != null) {
            for (ScreenClassifier.Node n : nodes) {
                if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg) || n.text == null)
                    continue;
                String t = n.text.replaceAll("\\s", "");
                if (t.contains(".mp4") || t.contains(".pdf"))
                    titles.add(t);
            }
        }
        return titles.size();
    }

    /** 主线程弹气泡（Toast），引擎线程不能直接弹 */
    private void showToast(final String msg) {
        try {
            final android.content.Context ctx = svc != null ? svc : SelectToSpeakService.INSTANCE;
            if (ctx == null)
                return;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void onList(List<ScreenClassifier.Node> nodes) throws InterruptedException {
        long now = System.currentTimeMillis();
        // 点了课程却没进面板 → 说明点到的是已完成课程或坐标已过期，别在原地反复点
        if (pendingOpen && lastListTapAt > 0 && now - lastListTapAt > 8000) {
            log("【列表】点击后8秒仍未进入课程 → 判为未命中，刷新节点树重新定位");
            pendingOpen = false;
            pendingRemainMs = -1;
            ghostStreak = 0;
            emptyStreak = 0;
            nudge(true);
            return;
        }
        List<ScreenClassifier.Node> pct = new ArrayList<>();
        Set<String> pctSeen = new HashSet<>();
        for (ScreenClassifier.Node n : nodes) {
            if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg) || n.text == null)
                continue;
            String t = n.text.replaceAll("\\s", "");
            if (t.contains("%") && !t.contains("100%")) {
                if (pctSeen.add(t + "@" + n.bounds.left + "," + n.bounds.top))
                    pct.add(n);
            }
        }

        String sig = "pct=" + pct.size() + " all=" + nodes.size();
        if (!sig.equals(lastListSig) || now - lastListLogAt > 10000) {
            lastListSig = sig;
            lastListLogAt = now;
            log("【列表】扫描 节点=" + nodes.size() + " 未完成%=" + pct.size());
        }

        // 顶部安全区从节点树动态取：只要有窗口顶部固定栏（导航条）的真实下沿就用它，取不到才按屏高估算
        int safeTop = listContentTop();

        if (pct.isEmpty()) {
            // 从面板返回瞬间拿到的是旧节点树快照：先等它刷新，再轻推催微信重排，
            // 都不行才真正上滑半屏翻页，避免把屏内已显示的未完成课程划走
            if (emptyStreak < 2) {
                emptyStreak++;
                log("【列表】树里暂时没有%，先等节点树刷新 " + emptyStreak + "/2");
                safeSleep(2000);
                return;
            }
            if (emptyStreak < 2 + REFRESH_WAIT_TRIES) {
                emptyStreak++;
                log("【列表】轻推刷新节点树 " + (emptyStreak - 2) + "/" + REFRESH_WAIT_TRIES);
                nudge(true);
                return;
            }
            listScrollTries++;
            if (listScrollTries > MAX_LIST_SCROLL) {
                // 已经连续上滑 15 次（约 7 屏），列表早就扫到底了，仍然一个 % 都没有
                log("【列表】连续" + MAX_LIST_SCROLL + "次上滑仍无%未完成，判定已扫到列表末尾");
                listScrollTries = 0;
                noMoreScroll = 0;
                onAllCoursesDone(nodes);
                return;
            }
            log("【列表】本屏确认无%节点，上滑半屏翻页 (第" + listScrollTries + "次)");
            boolean moved = scrollList(true);
            if (moved) {
                noMoreScroll = 0;
            } else if (++noMoreScroll >= 2) {
                // 连着两次上滑都滑不动 = 已经到底，且整屏没有任何 % → 全部课程已完成
                onAllCoursesDone(nodes);
                return;
            }
            return;
        }
        emptyStreak = 0;
        noMoreScroll = 0;
        allDoneAnnounced = false;

        int rowMaxH = (int) (screenH * 0.23f);
        int viewBottom = (int) (screenH * 0.92f);
        boolean allGhost = true;
        ScreenClassifier.Node pickPct = null;
        Rect pickRect = null;
        String pickTitle = "";
        List<Rect> outRects = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ScreenClassifier.Node n : pct) {
            Rect pr = n.bounds;
            // XWeb 虚拟列表里 % 徽章节点的 bounds 永远是塌陷的(高0、贴底边 y=2182)，
            // 不能用它自身的几何判断可见性。改用它外层行容器的 bounds：
            // 行容器在可见行上有真实尺寸，在屏外行上也塌陷为高0。
            int pctMinH = Math.max(20, (int) (screenH * 0.015f));
            boolean pctVisible = pr.height() >= pctMinH && pr.width() > 0
                    && pr.centerY() > safeTop && pr.centerY() < viewBottom
                    && pr.centerX() > 0 && pr.centerX() < screenW;

            // 行容器 = % 节点的祖先里第一个尺寸合理的可点击节点或行节点。
            // 注意：XWeb 的 % 节点 bounds 永远塌陷在容器底边(y=2182)，不在行容器 bounds 内，
            // 所以不能用 containsNode 校验；祖先链本身是正确的(已验证 %→标题的对应关系)，
            // 直接取祖先行容器，再用其 bounds 是否在屏内来判断课程是否可见。
            ScreenClassifier.Node holder = clickableAncestor(n);
            if (holder == null)
                holder = rowContainer(n);
            Rect row = holder != null ? new Rect(holder.bounds) : null;
            boolean rowSane = row != null && row.width() > 0 && row.height() > 0
                    && row.height() <= rowMaxH
                    && row.centerY() > safeTop && row.centerY() < viewBottom;

            // 以行容器的 bounds 为准：行容器有真实尺寸且在屏内 → 该未完成课程可见可点；
            // 行容器塌陷或屏外 → 课程不在屏内，跳过。不再依赖 % 节点自身的塌陷坐标。
            Rect geo = rowSane ? row : null;
            if (geo != null)
                allGhost = false;

            String title = rowTitle(nodes, holder != null ? holder : n, n);
            if (!seen.add(title + "@" + (geo == null ? "off" : geo.left + "," + geo.top + "," + geo.centerY())))
                continue;

            log("【列表】候选 %=" + ScreenClassifier.quote(n.text)
                    + " 标题=" + ScreenClassifier.quote(title)
                    + " %节点=" + pr + (pctVisible ? "(可见)" : "(塌陷)")
                    + " 行=" + (rowSane ? row : "不可信")
                    + " 可点=" + (geo != null));
            if (geo == null) {
                outRects.add(new Rect(pr));
                continue;
            }
            if (pickPct == null) {
                pickPct = n;
                pickRect = geo;
                pickTitle = title;
            }
        }

        // 列表行里常带「已播 / 总长」进度文本（如 0:32 / 29:06），
        // 视频面板的控件时间是 canvas 画的、节点树里读不到，
        // 所以进面板前先把这一行的时间记下来，作为倒计时依据
        ScreenClassifier.Node anchor = pickPct != null ? pickPct : (pct.isEmpty() ? null : pct.get(0));
        pendingRemainMs = anchor != null ? rowRemainMs(nodes, anchor) : -1;
        if (pendingRemainMs > 0)
            log("【列表】" + ScreenClassifier.quote(pickTitle.isEmpty() ? rowTitle(nodes, clickableAncestor(anchor), anchor) : pickTitle)
                    + " 行内进度 → 剩余 " + fmtDur(pendingRemainMs) + "，进面板后按此倒计时（+1分钟）");

        // 优先用无障碍 ACTION_CLICK 直接点击，不依赖屏幕坐标。
        // XWeb 虚拟列表里 % 徽章和行的 bounds 经常塌陷，但节点本身在树中存在，
        // performAction(ACTION_CLICK) 可直接触发行点击，即使行不在屏内也能生效。
        ScreenClassifier.Node actionNode = pickPct != null ? pickPct : (pct.isEmpty() ? null : pct.get(0));
        if (actionNode != null) {
            String actionTitle = pickPct != null ? pickTitle
                    : rowTitle(nodes, null, actionNode);
            log("【列表】尝试无障碍ACTION_CLICK " + ScreenClassifier.quote(actionTitle));
            if (clickByAction(actionNode)) {
                log("【列表】ACTION_CLICK 成功，等待课程面板打开");
                pendingOpen = true;
                lastListTapAt = System.currentTimeMillis();
                listScrollTries = 0;
                ghostStreak = 0;
                emptyStreak = 0;
                cooldownUntil = System.currentTimeMillis() + 6000;
                safeSleep(3000);
                return;
            }
            log("【列表】ACTION_CLICK 失败，改用坐标点击");
        }

        if (pickPct != null) {
            int cx = pickRect.centerX();
            cx = Math.max((int) (screenW * 0.12f), Math.min((int) (screenW * 0.88f), cx));
            int cy = Math.max(pickRect.centerY(), safeTop + (int) (screenH * 0.02f));
            cy = Math.min(cy, viewBottom - (int) (screenH * 0.02f));
            log("【列表】坐标点击未完成课程 (" + cx + "," + cy + ") " + ScreenClassifier.quote(pickTitle)
                    + " 行=" + pickRect);
            tap(cx, cy);
            pendingOpen = true;
            lastListTapAt = System.currentTimeMillis();
            listScrollTries = 0;
            ghostStreak = 0;
            emptyStreak = 0;
            cooldownUntil = System.currentTimeMillis() + 6000;
            safeSleep(3000);
            return;
        }

        // 树里有 % 但坐标全都退化或落在屏外
        if (allGhost) {
            // ACTION_CLICK 已经试过且失败，说明该节点不可点击或未预加载。
            // 零高的 % 是微信虚拟列表的预加载行，坐标会被压到容器底边（如 y=2182），
            // 先等树刷新 + 轻推，多次无效后才整页翻页。
            Rect g0 = pct.get(0).bounds;
            log("【列表】%全部退化坐标 " + g0 + "，ACTION_CLICK也无效，尝试滑动找可见行");
            ghostStreak++;
            if (ghostStreak <= 2) {
                log("【列表】先等节点树更新 " + ghostStreak + "/2，不滑动");
                if (ghostStreak == 1)
                    dumpAncestors(pct.get(0), "幽灵%");
                safeSleep(2000);
                return;
            }
            if (ghostStreak <= 2 + REFRESH_WAIT_TRIES) {
                log("【列表】轻推刷新节点树 " + (ghostStreak - 2) + "/" + REFRESH_WAIT_TRIES);
                nudge(false);
                return;
            }
            ghostStreak = 0;
            listScrollTries++;
            if (listScrollTries > MAX_LIST_SCROLL) {
                log("【列表】连续" + MAX_LIST_SCROLL + "次滚动仍拿不到可见%，本阶段暂停等待");
                listScrollTries = 0;
                safeSleep(5000);
                return;
            }
            boolean up = g0.top > safeTop + (int) (screenH * 0.02f);
            log("【列表】" + (up ? "上" : "下") + "滑半屏继续找 (第" + listScrollTries + "次)");
            scrollList(up);
            return;
        }
        ghostStreak = 0;

        if (outRects.isEmpty()) {
            // 屏内候选都被处理完（正常不会发生）→ 翻页避免死循环
            log("【列表】屏内候选均已处理，上滑半屏继续找");
            scrollList(true);
            return;
        }

        // 按坐标判断方向：被顶部固定栏遮住 → 下滑；在下方 → 上滑；幽灵节点默认上滑翻页
        Rect r0 = outRects.get(0);
        int cy = r0.centerY();
        boolean up;
        String why;
        if (r0.height() <= 0) {
            up = true;
            why = "幽灵节点(高度0)，按翻页处理";
        } else if (r0.top < safeTop) {
            up = false;
            why = "被顶部固定栏遮住 top=" + r0.top + " < " + safeTop;
        } else if (cy > screenH * 0.96f) {
            up = true;
            why = "在视口下方 cy=" + cy;
        } else {
            up = true;
            why = "需上移 cy=" + cy;
        }
        String dir = up ? "上滑" : "下滑";
        listScrollTries++;
        log("【列表】未完成课程不在屏内 " + why + " → " + dir + " (第" + listScrollTries + "次)");
        scrollList(up);
    }

    /**
     * 列表翻页：一次调用只滑一次，滑完必须等到节点树真的刷新再重新定位。
     * 节点树没变化时不再"加大幅度重滑"——那会在树陈旧时多滑 1-2 屏，直接把未完成课程滑过去；
     * 改为继续等（微信刷新可能滞后十几秒），最后用列表滚动动作兜底。
     */
    private boolean scrollList(boolean up) {
        SelectToSpeakService s = svc != null ? svc : SelectToSpeakService.INSTANCE;
        if (s == null)
            return false;
        String before = listSignature(lastNodes);
        int x = screenW / 2;

        int y1 = up ? (int) (screenH * 0.78f) : (int) (screenH * 0.30f);
        int y2 = up ? (int) (screenH * 0.30f) : (int) (screenH * 0.78f);
        s.swipeDirect(x, y1, x, y2, 700);
        log("👆 " + (up ? "上滑半屏" : "下滑半屏") + " (" + y1 + "→" + y2 + ")，等 "
                + (SCROLL_SETTLE_MS / 1000) + " 秒待界面动画与节点树刷新");
        cooldownUntil = System.currentTimeMillis() + SCROLL_SETTLE_MS;
        safeSleepQuiet(SCROLL_SETTLE_MS);
        if (awaitTreeChanged(s, before, 3))
            return true;

        // 树还没变：可能只是微信刷新滞后，继续等，绝不重复滑动
        log("👆 节点树暂未变化 → 继续等刷新，不再重复滑动");
        if (awaitTreeChanged(s, before, 4))
            return true;

        // 最后才用可滚动容器的无障碍滚动动作兜底（这一步不产生触摸位移，不会多滑）
        log("👆 等不到刷新 → 改用列表滚动动作");
        if (scrollByAction(up)) {
            safeSleepQuiet(2000);
            if (awaitTreeChanged(s, before, 4))
                return true;
        }
        log("👆 本次翻页没拿到新信息，下一轮重新采集");
        return false;
    }

    /** 先立即采集一次，再每秒重采，直到列表签名变化（说明真的滚动了且信息已刷新） */
    private boolean awaitTreeChanged(SelectToSpeakService s, String before, int maxSeconds) {
        for (int i = 0; i <= maxSeconds; i++) {
            List<ScreenClassifier.Node> fresh;
            synchronized (ScreenClassifier.A11Y_LOCK) {
                fresh = ScreenClassifier.collect(s);
            }
            if (!listSignature(fresh).equals(before)) {
                ScreenClassifier.recycle(lastNodes);
                lastNodes = fresh;
                log("👆 节点树已刷新（" + i + "秒），重新定位未完成课程");
                return true;
            }
            ScreenClassifier.recycle(fresh);
            if (i == maxSeconds)
                break;
            try {
                safeSleep(1000);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    /** 列表是否滚动/刷新的判据：% 节点位置 + 前几行标题 */
    private String listSignature(List<ScreenClassifier.Node> nodes) {
        if (nodes == null)
            return "";
        StringBuilder sb = new StringBuilder();
        int titles = 0;
        for (ScreenClassifier.Node n : nodes) {
            if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg) || n.text == null)
                continue;
            String t = n.text.replaceAll("\\s", "");
            if (t.isEmpty())
                continue;
            if (t.contains("%") && !t.contains("100%")) {
                sb.append(t).append('@').append(n.bounds.top).append('@').append(n.bounds.height()).append(',');
            } else if (titles < 6 && t.length() > 6) {
                sb.append(t, 0, 8).append('|');
                titles++;
            }
            if (sb.length() > 300)
                break;
        }
        return sb.toString();
    }

    /** 兜底：找到列表的可滚动容器并执行滚动动作 */
    private boolean scrollByAction(boolean up) {
        SelectToSpeakService s = svc != null ? svc : SelectToSpeakService.INSTANCE;
        if (s == null)
            return false;
        int action = up ? android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        try {
            for (android.view.accessibility.AccessibilityNodeInfo root : s.collectRoots()) {
                if (root == null)
                    continue;
                ArrayList<android.view.accessibility.AccessibilityNodeInfo> queue = new ArrayList<>();
                queue.add(root);
                int guard = 0;
                while (!queue.isEmpty() && guard++ < 400) {
                    android.view.accessibility.AccessibilityNodeInfo cur = queue.remove(0);
                    Rect b = new Rect();
                    cur.getBoundsInScreen(b);
                    if (cur.isScrollable() && b.height() > screenH * 0.30f) {
                        boolean ok = cur.performAction(action);
                        log("👆 滚动动作 " + (up ? "FORWARD" : "BACKWARD") + " → " + ok + " 容器=" + b);
                        return ok;
                    }
                    for (int i = 0; i < cur.getChildCount(); i++) {
                        android.view.accessibility.AccessibilityNodeInfo ch = cur.getChild(i);
                        if (ch != null)
                            queue.add(ch);
                    }
                }
            }
        } catch (Throwable t) {
            log("👆 滚动动作异常 " + t);
        }
        return false;
    }

    /** 行容器是否真的包含这个节点（容忍 2% 屏高的误差） */
    private boolean containsNode(Rect outer, Rect inner) {
        if (outer == null || inner == null)
            return false;
        int tol = (int) (screenH * 0.02f);
        return inner.centerX() >= outer.left - tol && inner.centerX() <= outer.right + tol
                && inner.centerY() >= outer.top - tol && inner.centerY() <= outer.bottom + tol;
    }

    /** 从节点往上找第一个有真实尺寸的行容器（取最内层，排除整块列表容器） */
    private ScreenClassifier.Node rowContainer(ScreenClassifier.Node n) {
        ScreenClassifier.Node p = n.parent;
        int depth = 0;
        while (p != null && depth < 12) {
            Rect b = p.bounds;
            if (b.height() >= screenH * 0.053f && b.height() <= screenH * 0.23f && b.width() >= screenW * 0.70f)
                return p;
            p = p.parent;
            depth++;
        }
        return null;
    }

    private void dumpAncestors(ScreenClassifier.Node n, String tag) {
        ScreenClassifier.Node p = n;
        int d = 0;
        while (p != null && d < 8) {
            log("【诊断】" + tag + " 第" + d + "层 " + shortCls(p.cls) + " text="
                    + ScreenClassifier.quote(p.text) + " desc=" + ScreenClassifier.quote(p.desc)
                    + " " + p.bounds + " click=" + p.clickable);
            p = p.parent;
            d++;
        }
    }

    private ScreenClassifier.Node clickableAncestor(ScreenClassifier.Node n) {
        ScreenClassifier.Node p = n.parent;
        int depth = 0;
        while (p != null && depth < 12) {
            Rect b = p.bounds;
            if (p.clickable && b.height() > screenH * 0.027f && b.height() <= screenH * 0.27f
                    && b.width() >= screenW / 2 && b.height() > 0) {
                return p;
            }
            p = p.parent;
            depth++;
        }
        return null;
    }

    /** 课程标题 = 卡片内最长的非百分比文本 */
    private String rowTitle(List<ScreenClassifier.Node> all, ScreenClassifier.Node holder,
                            ScreenClassifier.Node pctNode) {
        String best = "";
        for (String t : ScreenClassifier.textsUnder(all, holder)) {
            String s = t.replaceAll("\\s", "");
            if (s.isEmpty() || s.contains("%") || s.length() < 2)
                continue;
            if (s.length() > best.length())
                best = s;
        }
        if (best.isEmpty())
            best = pctNode.text == null ? "" : pctNode.text.replaceAll("\\s", "");
        return best;
    }

    // ==================== 基础操作 ====================

    private void tap(int x, int y) {
        SelectToSpeakService s = svc != null ? svc : SelectToSpeakService.INSTANCE;
        if (s == null)
            return;
        s.tapDirect(x, y);
    }

    /**
     * 通过无障碍 ACTION_CLICK 直接点击节点，不依赖屏幕坐标。
     * XWeb 虚拟列表的 % 徽章 bounds 永远塌陷，无法用坐标点击；
     * 但节点本身在树里存在，直接 performAction(ACTION_CLICK) 可触发其行的点击事件，
     * 即使该行当前不在屏内也能生效（虚拟列表已预加载该节点）。
     * 优先点节点自身，否则往上找最近的可点击祖先。
     */
    private boolean clickByAction(ScreenClassifier.Node n) {
        if (n == null)
            return false;
        AccessibilityNodeInfo target = n.raw;
        // 自身可点击就点自身，否则往上找第一个可点击祖先
        int depth = 0;
        while (target != null && depth < 15) {
            if (target.isClickable()) {
                try {
                    boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (ok)
                        return true;
                } catch (Throwable ignored) {
                }
            }
            try {
                target = target.getParent();
            } catch (Throwable ignored) {
                break;
            }
            depth++;
        }
        return false;
    }

    /**
     * 列表内容区顶部：从节点树里找顶部固定栏（导航条，宽、贴在屏幕最上）的真实下沿。
     * 取不到就按屏高 10% 估算，避免把导航条正下方第一门课误判成屏外。
     */
    private int listContentTop() {
        int fallback = (int) (screenH * 0.10f);
        List<ScreenClassifier.Node> nodes = lastNodes;
        if (nodes == null || nodes.isEmpty())
            return fallback;
        int best = 0;
        for (ScreenClassifier.Node n : nodes) {
            if (!ScreenClassifier.WECHAT_PKG.equals(n.pkg))
                continue;
            Rect b = n.bounds;
            if (b.top < 0 || b.top > screenH * 0.06f)
                continue;
            if (b.width() < screenW * 0.6f || b.height() <= 0 || b.height() > screenH * 0.12f)
                continue;
            best = Math.max(best, b.bottom);
        }
        if (best <= 0)
            return fallback;
        return Math.min(best + (int) (screenH * 0.004f), (int) (screenH * 0.20f));
    }

    /**
     * 轻推刷新：小幅上滑后原路滑回，净位移 0。
     * 只为逼微信重新渲染、把节点树 bounds 刷新出来，不会把屏内可见课程划走。
     */
    private void nudge(boolean thenIdle) {
        SelectToSpeakService s = svc != null ? svc : SelectToSpeakService.INSTANCE;
        if (s == null)
            return;
        int x = screenW / 2;
        int yMid = (int) (screenH * 0.52f);
        int dy = (int) (screenH * 0.055f);
        s.swipeDirect(x, yMid, x, yMid - dy, 260);
        log("👆 轻推刷新节点树 (上" + dy + "px)");
        safeSleepQuiet(500);
        s.swipeDirect(x, yMid - dy, x, yMid, 260);
        cooldownUntil = System.currentTimeMillis() + 1500;
        if (thenIdle)
            log("👆 已滑回原位，等界面稳定");
        safeSleepQuiet(1500);
    }

    private void swipeUp() {
        SelectToSpeakService s = svc != null ? svc : SelectToSpeakService.INSTANCE;
        if (s == null)
            return;
        int x = screenW / 2;
        int y1 = (int) (screenH * 0.78f);
        int y2 = (int) (screenH * 0.28f);
        s.swipeDirect(x, y1, x, y2, 400);
        log("👆 上滑半屏 (" + y1 + "→" + y2 + ")，等动画结束");
        cooldownUntil = System.currentTimeMillis() + 2000;
    }

    private void swipeDown() {
        SelectToSpeakService s = svc != null ? svc : SelectToSpeakService.INSTANCE;
        if (s == null)
            return;
        int x = screenW / 2;
        s.swipeDirect(x, (int) (screenH * 0.28f), x, (int) (screenH * 0.78f), 400);
        log("👆 下滑半屏，等动画结束");
        cooldownUntil = System.currentTimeMillis() + 2000;
    }

    private String describe(ScreenClassifier.Node n) {
        String t = (n.text + " " + n.desc).trim();
        return ScreenClassifier.quote(t) + ScreenClassifier.at(n) + " " + shortCls(n.cls);
    }

    private String shortCls(String cls) {
        if (cls == null || cls.isEmpty())
            return "";
        int i = cls.lastIndexOf('.');
        return i >= 0 ? cls.substring(i + 1) : cls;
    }

    private void safeSleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private void safeSleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
