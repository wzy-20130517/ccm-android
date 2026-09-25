package com.ccm.app.bridge;

/**
 * 跑在 Shizuku shell uid 下的 phone use 服务。
 *
 * 【设计来源】
 * 能力面按 AcidGr/agent-mobile-use 的 vd-tool-java 仿写（MIT）——
 * 它那套在真机上跑得很稳，关键设计都保留：
 *   · 副屏全分辨率 JPEG 帧缓存（截图 ~60ms，而不是 screencap 的 ~1.8s）
 *   · 平铺式元素树（一行一元素，带点击中心，模型不用自己算坐标）
 *   · 确定性文字注入（单次 SET_TEXT + 回读校验，不做瞎猜的兜底）
 *   · 数字节点 id（比 e42 这种字符串 ref 短，且能和 dump 输出直接对应）
 *
 * 【为什么必须有 shell uid】
 * VirtualDisplay 建 TRUSTED 屏、UiAutomation.connect 都要 shell 权限。
 * 普通 app uid 做不到，所以这个服务由 Shizuku 以 shell 身份拉起。
 *
 * destroy() 的 code 必须是 16777114，Shizuku 服务端约定的销毁编号。
 */
interface IPhoneUseService {

    void destroy() = 16777114;

    /** 虚拟副屏的 displayId，未创建时返回 -1。 */
    int displayId() = 1;

    /**
     * 元素树。平铺格式（仿 agent-mobile-use）：
     *   首行状态（display/尺寸/count/has_more）
     *   次行列头
     *   之后一行一元素：#id type name x1,y1,x2,y2 flags...
     *
     * 参数 noSystemUi=true 时滤掉状态栏/导航栏/输入法这类系统外壳窗口。
     */
    String dumpTree(boolean interactiveOnly, int maxNodes, boolean noSystemUi) = 2;

    /** 在副屏上点击坐标。 */
    boolean tap(int x, int y) = 3;

    /** 按节点 id 点击（id 来自最近一次 dumpTree；失效返回 false）。 */
    boolean tapRef(String ref) = 8;

    /** 在副屏上滑动（坐标版）。 */
    boolean swipe(int x1, int y1, int x2, int y2, int durationMs) = 4;

    /** 按方向滑动，坐标由服务按副屏尺寸算。 */
    boolean swipeDir(String direction, int durationMs) = 9;

    /** 按键，keyCode 用 Android KeyEvent 常量。 */
    boolean key(int keyCode) = 5;

    /**
     * 文字注入。确定性单路径（仿 agent-mobile-use，不做多级兜底）：
     *   定位目标 → 一次 ACTION_SET_TEXT → 回读校验 → 分类返回。
     * 返回 JSON：{ok, mode, verified, verified_text, error, reason, focus_hint}
     */
    String typeText(String text) = 6;

    /**
     * 带目标定位的注入。
     * target 空/"focused" = 用当前焦点框；"e12"/"12"/"node:12" = 上次 dump 里的节点。
     * 节点失效会明确报 target_stale/target_not_found，不退回坐标点击。
     */
    String typeTextAt(String text, String target) = 15;

    /** 副屏最新帧的 JPEG 字节（守护侧持续更新，调用方几乎零等待）。 */
    byte[] latestFrame() = 7;

    /** 以 shell 身份跑一条命令，返回 "exitCode\n---\nstdout"。 */
    String runShell(String cmd, int timeoutMs) = 10;

    /** 滚动：有 id 就滚那个节点，否则按方向滑副屏。 */
    boolean scroll(String ref, String direction) = 11;

    /**
     * 在副屏启动应用。
     * 若该应用已在别的屏运行，用 move-stack 平滑搬运（不重启、不丢状态）。
     * action: "launch" | "current" | "list" | "stop"
     */
    String app(String action, String pkg, String filter) = 12;

    /** 副屏当前尺寸 [宽, 高, dpi]，副屏未就绪时返回 [0,0,0]。 */
    int[] displayMetrics() = 13;

    /** 副屏状态 JSON：{running, display_id, width, height, dpi, frame_age_ms, frame_bytes} */
    String status() = 14;
}
