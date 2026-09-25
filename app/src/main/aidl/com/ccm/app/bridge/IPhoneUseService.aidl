package com.ccm.app.bridge;

/**
 * 跑在 Shizuku shell uid 下的 phone use 服务。
 *
 * 虚拟副屏的创建、元素树抓取、坐标输入都在这个进程里完成，
 * 因为它有 shell uid 才能建 TRUSTED 虚拟屏、连 UiAutomation。
 * 普通 app 进程没有这些权限。
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

    /** 在副屏上滑动。 */
    boolean swipe(int x1, int y1, int x2, int y2, int durationMs) = 4;

    /** 在副屏上按键，keyCode 用 Android KeyEvent 常量。 */
    boolean key(int keyCode) = 5;

    /** 把文字写入副屏当前焦点输入框。 */
    boolean typeText(String text) = 6;

    /** 副屏最新帧的 JPEG 字节。 */
    byte[] latestFrame() = 7;
}
