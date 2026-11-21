package com.study.auto;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.android.accessibility.selecttospeak.SelectToSpeakService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 界面判别 + 节点快照（纯节点树，无图像匹配）。
 *
 * <p>所有判定用的都是「相对屏幕比例 + 文本特征」，不含任何固定像素坐标，
 * 所以换分辨率/换屏幕比例的手机都能直接复用。需要微调时只改下面的常量即可。</p>
 *
 * <p>判定规则（优先级从上到下，互斥）：</p>
 * <ol>
 *   <li><b>播放面板</b>：屏幕下半部（中心 Y &gt; {@link #PANEL_DATE_TOP_RATIO} 屏高）出现
 *       带时分秒的日期文本，如「2026-07-31 12:13:57」——这是半屏播放面板左下角的日期水印。
 *       视频控制条时间是 canvas 画的、节点树读不到，所以只能靠这块日期。</li>
 *   <li><b>文档学习</b>：屏幕上部（中心 Y &lt; {@link #DOC_TITLE_TOP_RATIO} 屏高）出现「课件学习」标题。</li>
 *   <li><b>课程列表</b>：能看到「学习时间」这一行（列表每一项都有）。</li>
 *   <li><b>未识别</b>：以上都没有（例如转场动画中、全屏横屏播放时）。</li>
 * </ol>
 *
 * <p>另外提供 {@link #isFullscreenVideo(List, int, int)}：判断当前是否处于
 * 「小程序视频横屏全屏」，用于横屏自动缩放回半屏。它同样只用相对比例 + 文本特征。</p>
 */
public class ScreenClassifier {

    public static final String PAGE_PANEL = "播放面板";
    public static final String PAGE_DOC = "文档学习";
    public static final String PAGE_LIST = "课程列表";
    public static final String PAGE_NONE = "未识别";

    public static final String WECHAT_PKG = "com.tencent.mm";

    /** 面板日期水印：2026-07-31 12:13:57（必须带时分秒，纯日期不算） */
    private static final Pattern DATE_TIME = Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{2}:\\d{2}");
    /** 纯日期：只用于日志提示，不计为面板 */
    private static final Pattern DATE_ONLY = Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}");

    /** 「课件学习」标题只可能出现在屏幕上部这个比例以内，超过就不算文档页 */
    private static final float DOC_TITLE_TOP_RATIO = 0.42f;
    /** 面板左下角日期一定在屏幕下半部，低于这个比例才计为面板（避免被列表上方的日期干扰） */
    private static final float PANEL_DATE_TOP_RATIO = 0.50f;

    /** 断网 / 网关错误「警告提示」弹窗：标题 + 确认按钮 */
    private static final String WARN_TITLE = "警告提示";
    private static final String WARN_OK = "确定";
    /** 标题识别不到时的正文兜底：网络类词 + 错误类词同时出现才算 */
    private static final String[] WARN_NET_WORDS = {"网络", "网关", "断网", "连接", "服务器"};
    private static final String[] WARN_ERR_WORDS = {"错误", "失败", "异常", "超时", "不可用", "无法"};

    /**
     * 节点树采集与手势下发的共享锁：引擎主循环和挂机检测线程会同时操作无障碍节点，
     * 统一用这把锁串行化，避免两边同时 getChild / recycle / 注入手势导致节点失效。
     */
    public static final Object A11Y_LOCK = new Object();

    public static class Node {
        public String text = "";
        public String desc = "";
        public String cls = "";
        public Rect bounds = new Rect();
        public String pkg = "";
        public boolean clickable = false;
        public Node parent = null;
        /** 原始无障碍节点（未回收），可用于 performAction 等直接操作。用完需 recycle。 */
        public AccessibilityNodeInfo raw;
    }

    public static class Result {
        public String page = PAGE_NONE;
        public String evidence = "";
        public int nodeCount = 0;
        public List<String> samples = new ArrayList<>();
        public Node hit = null;
    }

    /**
     * 采集当前屏幕的节点快照（只采微信窗口）。
     * 调用方必须持有 {@link #A11Y_LOCK}，用完统一 {@link #recycle(List)}。
     * 快照里每个 Node 都保留 raw 无障碍节点引用（未回收），可直接 performAction。
     */
    public static List<Node> collect(SelectToSpeakService svc) {
        return collect(svc, false);
    }

    /**
     * 不做 root.refresh() 的采集：XWeb 视频播放按钮这类虚拟节点在 refresh 后会消失，
     * 专供「找播放按钮」这种需要完整虚拟子树的场景。
     */
    public static List<Node> collectNoRefresh(SelectToSpeakService svc) {
        List<Node> out = new ArrayList<>();
        if (svc == null)
            return out;
        for (AccessibilityNodeInfo root : svc.collectRootsNoRefresh())
            walk(root, out, null);
        return out;
    }

    /**
     * 采集所有窗口（含系统弹窗）的节点快照，仅用于识别「警告提示」这类盖在微信上的弹窗。
     * 页面判别仍按 {@link #WECHAT_PKG} 过滤，不会受其它窗口文本影响。
     */
    public static List<Node> collectAll(SelectToSpeakService svc) {
        return collect(svc, true);
    }

    private static List<Node> collect(SelectToSpeakService svc, boolean allWindows) {
        List<Node> out = new ArrayList<>();
        if (svc == null)
            return out;
        List<AccessibilityNodeInfo> roots = allWindows ? svc.collectRootsAll() : svc.collectRoots();
        for (AccessibilityNodeInfo root : roots) {
            walk(root, out, null);
            // 不回收 root：节点树里的 raw 引用需要保持有效，由使用方在处理完后统一回收
        }
        return out;
    }

    /** 深度优先遍历：把无障碍节点转成轻量 Node（文本/描述/类名/边界/包名/可点击 + 父链） */
    private static void walk(AccessibilityNodeInfo n, List<Node> out, Node parent) {
        if (n == null)
            return;
        try {
            Node nd = new Node();
            nd.raw = n;
            CharSequence cs = n.getText();
            nd.text = cs == null ? "" : cs.toString();
            CharSequence cd = n.getContentDescription();
            nd.desc = cd == null ? "" : cd.toString();
            CharSequence cn = n.getClassName();
            nd.cls = cn == null ? "" : cn.toString();
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            nd.bounds = b;
            CharSequence p = n.getPackageName();
            nd.pkg = p == null ? "" : p.toString();
            nd.clickable = n.isClickable();
            nd.parent = parent;
            out.add(nd);
            int cnt = n.getChildCount();
            for (int i = 0; i < cnt; i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) {
                    walk(c, out, nd);
                    // 不回收子节点：保持 raw 引用有效
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 回收一批节点的 raw 引用，避免无障碍节点句柄泄漏。 */
    public static void recycle(List<Node> nodes) {
        if (nodes == null)
            return;
        Set<AccessibilityNodeInfo> seen = new HashSet<>();
        for (Node n : nodes) {
            if (n.raw != null && seen.add(n.raw)) {
                try { n.raw.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 主判别：根据节点树判定当前处于哪个页面。
     *
     * @param nodes   节点快照（{@link #collect(SelectToSpeakService)} 的结果）
     * @param screenH 当前真实屏高（像素），用来比较「上半部/下半部」
     * @return 页面名 + 依据文本 + 命中节点（面板页的日期节点用于反推面板顶边）
     */
    public static Result classify(List<Node> nodes, int screenH) {
        Result r = new Result();
        r.nodeCount = nodes.size();

        Node panelHit = null;
        Node docHit = null;
        Node listHit = null;
        String dateOnlyHint = null;

        for (Node n : nodes) {
            if (n == null)
                continue;
            String t = n.text == null ? "" : n.text.trim();
            if (!t.isEmpty() && r.samples.size() < 6 && t.length() <= 18)
                r.samples.add(t);
            if (!WECHAT_PKG.equals(n.pkg))
                continue;
            if (t.isEmpty())
                continue;

            if (panelHit == null && DATE_TIME.matcher(t).find()
                    && n.bounds.centerY() > screenH * PANEL_DATE_TOP_RATIO) {
                panelHit = n;
            }
            if (dateOnlyHint == null && DATE_ONLY.matcher(t).find())
                dateOnlyHint = t;
            if (docHit == null && t.contains("课件学习")
                    && n.bounds.centerY() < screenH * DOC_TITLE_TOP_RATIO) {
                docHit = n;
            }
            if (listHit == null && t.contains("学习时间")) {
                listHit = n;
            }
        }

        if (panelHit != null) {
            r.page = PAGE_PANEL;
            r.evidence = "下部日期 " + quote(panelHit.text) + at(panelHit);
            r.hit = panelHit;
        } else if (docHit != null) {
            r.page = PAGE_DOC;
            r.evidence = "上部标题 " + quote(docHit.text) + at(docHit);
            r.hit = docHit;
        } else if (listHit != null) {
            r.page = PAGE_LIST;
            r.evidence = "列表文本 " + quote(listHit.text) + at(listHit);
            r.hit = listHit;
        } else {
            r.page = PAGE_NONE;
            r.evidence = "三特征均未命中"
                    + (dateOnlyHint != null ? "（仅有纯日期 " + quote(dateOnlyHint) + "，未计为面板）" : "");
        }
        return r;
    }

    /**
     * 识别断网 / 网关错误时弹出的「警告提示」框，命中则返回「确定」按钮节点。
     *
     * <p>弹窗会盖住整个界面，必须点掉才能继续，所以挂机线程优先处理、引擎原地等待。</p>
     *
     * <p>识别条件（必须同屏存在可点的「确定」按钮才动手）：</p>
     * <ol>
     *   <li>出现标题「警告提示」；或</li>
     *   <li>正文同时含网络类词（网络/网关/断网/连接/服务器）和错误类词
     *       （错误/失败/异常/超时/不可用/无法），如「网络连接失败」「网关超时」。</li>
     * </ol>
     * <p>只在微信窗口还存在时才处理，避免在别的应用里误点「确定」。</p>
     *
     * @param screenH 真实屏高，用来校验按钮在屏内
     */
    public static Node findWarnOk(List<Node> nodes, int screenH) {
        if (nodes == null)
            return null;
        boolean hasWechat = false, title = false, net = false, err = false;
        for (Node n : nodes) {
            if (n.text == null)
                continue;
            if (WECHAT_PKG.equals(n.pkg))
                hasWechat = true;
            String t = n.text.replaceAll("\\s", "");
            if (t.isEmpty() || t.length() > 60)       // 正文不会有整段长文，避免误匹配
                continue;
            if (t.contains(WARN_TITLE))
                title = true;
            if (containsAny(t, WARN_NET_WORDS))
                net = true;
            if (containsAny(t, WARN_ERR_WORDS))
                err = true;
        }
        if (!hasWechat || (!title && !(net && err)))
            return null;
        for (Node n : nodes) {
            if (n.text == null)
                continue;
            String t = n.text.replaceAll("\\s", "");
            if (t.length() > 8 || !t.contains(WARN_OK))
                continue;
            Rect b = n.bounds;
            if (b.width() <= 0 || b.height() <= 0 || b.centerY() < 0 || b.centerY() > screenH)
                continue;
            return n;
        }
        return null;
    }

    private static boolean containsAny(String t, String[] words) {
        for (String w : words) {
            if (t.contains(w))
                return true;
        }
        return false;
    }

    /**
     * 判断当前是否「小程序视频横屏全屏」。
     *
     * <p>为什么不能只看「屏幕是横的」：横屏平板上微信本身就是横屏使用，
     * 如果只按方向判断，挂机线程会在普通聊天/列表界面乱点。
     * 这里要求同时满足：</p>
     * <ol>
     *   <li>屏幕横置（宽 &gt; 高）；</li>
     *   <li>节点树里没有列表页特征（「学习时间」）、没有文档页特征（「课件学习」）、
     *       没有半屏面板特征（带时分秒的日期文本）；</li>
     *   <li>存在课件标题节点（.mp4 / .pdf）：优先认「铺满整屏的标题节点」（XWeb 全屏时
     *       标题层会被撑到全屏，实测 bounds 就是整块屏幕），退而求其次：横屏且存在课件标题。</li>
     * </ol>
     * 实测小米8 全屏时的节点：标题「xx.mp4」bounds=[0,0][2248,1080]，可直接判定。
     */
    public static boolean isFullscreenVideo(List<Node> nodes, int screenW, int screenH) {
        if (screenW <= screenH || nodes == null)
            return false;
        boolean courseTitle = false;
        for (Node n : nodes) {
            if (n == null || !WECHAT_PKG.equals(n.pkg) || n.text == null)
                continue;
            String t = n.text.replaceAll("\\s", "");
            if (t.isEmpty())
                continue;
            // 出现列表 / 文档 / 半屏面板的特征，就说明不是全屏播放
            if (t.contains("学习时间") || t.contains("课件学习"))
                return false;
            if (DATE_TIME.matcher(t).find())
                return false;
            if (!t.endsWith(".mp4") && !t.endsWith(".pdf"))
                continue;
            courseTitle = true;
            Rect b = n.bounds;
            if (b.width() >= screenW * 0.85f && b.height() >= screenH * 0.45f)
                return true;
        }
        // 没有铺满整屏的标题节点时（个别机型 XWeb 不暴露该层）：横屏 + 有课件标题也按全屏处理
        return courseTitle;
    }

    /**
     * 从命中节点向上找面板容器，返回面板顶边 Y；找不到返回 -1。
     * 容器特征：宽度接近整屏、高度占 20%~80%、底边贴近屏幕底部（全部按屏幕比例，换设备自适应）。
     */
    public static int panelTop(Node hit, int screenW, int screenH) {
        Node p = hit;
        int depth = 0;
        while (p != null && depth < 14) {
            Rect b = p.bounds;
            if (b.width() >= screenW * 0.90f
                    && b.height() >= screenH * 0.20f
                    && b.height() <= screenH * 0.80f
                    && b.bottom >= screenH * 0.85f) {
                return Math.max(0, b.top);
            }
            p = p.parent;
            depth++;
        }
        return -1;
    }

    public static String quote(String s) {
        if (s == null)
            return "\"\"";
        String t = s.trim().replace('\n', ' ');
        if (t.length() > 26)
            t = t.substring(0, 26) + "…";
        return "\"" + t + "\"";
    }

    public static String at(Node n) {
        return " @(" + n.bounds.centerX() + "," + n.bounds.centerY() + ")";
    }

    /** 收集某节点下的所有文本（含自身），用于取课程标题 */
    public static List<String> textsUnder(List<Node> all, Node card) {
        List<String> out = new ArrayList<>();
        if (card == null)
            return out;
        for (Node n : all) {
            if (n.text == null || n.text.trim().isEmpty())
                continue;
            Node p = n;
            int d = 0;
            while (p != null && d < 8) {
                if (p == card) {
                    out.add(n.text.trim());
                    break;
                }
                p = p.parent;
                d++;
            }
        }
        return out;
    }
}
