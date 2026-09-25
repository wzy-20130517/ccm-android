package com.ccm.app.bridge;

/**
 * 跑在 Shizuku shell uid 下的 phone use 服务。
 *
 * 虚拟副屏的创建、元素树抓取、坐标输入都在这个进程里完成，
 * 因为它有 shell uid 才能建 TRUSTED 虚拟屏、连 UiAutomation。
 * 普通 app 进程没有这些权限。
 *
 * 【为什么要 runShell】
 * 无障碍服务已移除，而有些能力（当前前台应用、dumpsys 类查询）既不是
 * 「虚拟屏上点一下」也不是「读元素树」，本质就是「跑条 shell 命令」。
 * 给它一条通用出口，比每加一个能力就改一次 AIDL 省事得多，
 * 也让 APK 侧和 Termux 侧（rash/adb 通道）的能力面保持一致。
 *
 * destroy() 的 code 必须是 16777114，这是 Shizuku 服务端约定的销毁方法编号。
 */
interface IPhoneUseService {

    void destroy() = 16777114;

    /** 虚拟副屏的 displayId，未创建时返回 -1。 */
    int displayId() = 1;

    /** 元素树文本，格式与原来 phone_snapshot 的输出一致。 */
    String dumpTree(boolean interactiveOnly, int maxNodes) = 2;

    /** 在副屏上点击。 */
    boolean tap(int x, int y) = 3;

    /** 按上次 dump 缓存的 ref 点击，ref 失效返回 false。 */
    boolean tapRef(String ref) = 8;

    /** 在副屏上滑动。 */
    boolean swipe(int x1, int y1, int x2, int y2, int durationMs) = 4;

    /** 按方向滑动，方向 up/down/left/right，坐标由服务按副屏尺寸算。 */
    boolean swipeDir(String direction, int durationMs) = 9;

    /** 在副屏上按键，keyCode 用 Android KeyEvent 常量。 */
    boolean key(int keyCode) = 5;

    /** 把文字写入副屏当前焦点输入框。 */
    boolean typeText(String text) = 6;

    /** 副屏最新帧的 JPEG 字节。 */
    byte[] latestFrame() = 7;

    /** 以 shell 身份跑一条命令，返回 "exitCode\n---stdout---\n<内容>"。 */
    String runShell(String cmd, int timeoutMs) = 10;

    /** 滚动：有 ref 就滚那个节点，没有就按方向滑副屏。 */
    boolean scroll(String ref, String direction) = 11;
}
