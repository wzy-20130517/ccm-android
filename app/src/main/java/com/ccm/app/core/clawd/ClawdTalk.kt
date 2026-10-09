package com.ccm.app.core.clawd

/**
 * Clawd 的「拟人化」内容库（2026-10-09）。
 *
 * ## 需求
 * 「点击之后随机触发动作，并从词库里调一句话对主人撒娇什么的。」
 *
 * ## 设计
 *
 * - [TAP_REACTIONS]：点击时随机播的**反应动画**（都是从素材库挑的
 *   有「情绪」的：生气/害羞/星星眼/晕/打哈欠/杂耍）
 * - [SWEET_LINES]：撒娇台词，点击时随机挑一句显示在气泡里
 *
 * ## 为什么台词要短
 * 气泡最多显示 3 行（overlay.html 的 CSS 限制），且是瞬时展示
 *（[ACTION_HOLD_MS] 后消失）。超过 15 个字的句子显示不全，
 * 写成短句 + 语气词最自然。
 */
object ClawdTalk {

    /**
     * 点击时的反应动画池。
     *
     * 刻意混入「不太正面」的反应（annoyed / dizzy / yawn）——
     * 每次都是爱心眼会腻，随机性才像活的。
     */
    val TAP_REACTIONS: List<String> = listOf(
        "clawd-react-annoyed.svg",      // 生气（被戳烦了）
        "clawd-react-left.svg",         // 往左探头
        "clawd-react-right.svg",        // 往右探头
        "clawd-heart-eyes.svg",         // 星星眼
        "clawd-aegyo-shy.svg",          // 害羞
        "clawd-dizzy.svg",              // 被戳晕
        "clawd-idle-yawn.svg",          // 打哈欠（好无聊）
        "clawd-working-juggling.svg",   // 手忙脚乱地杂耍
    )

    /**
     * 撒娇台词池。
     *
     * 语气设定：一只住在手机里、有点傲娇但很黏人的小螃蟹。
     * 不用 emoji（项目约定），靠语气词和标点撑住情绪。
     */
    val SWEET_LINES: List<String> = listOf(
        "别戳啦，痒痒的～",
        "在忙呢！……不过摸摸头也行",
        "主人～我在这儿呢",
        "诶嘿，被你抓到啦",
        "戳我干嘛，我又不会跑",
        "哇！吓我一跳",
        "干嘛呀，想我了？",
        "哼哼，就许你点一下哦",
        "再戳我就夹你！…开玩笑的",
        "呼……工作好累，抱抱我",
        "我一直看着你哦",
        "别急，慢慢来～",
        "点我干嘛，快去做正事啦",
        "嘿咻！我在这儿！",
        "你忙你的，我陪着你",
        "嗯？有什么吩咐吗",
    )

    /** 随机挑一个反应动画。 */
    fun randomReaction(): String = TAP_REACTIONS.random()

    /** 随机挑一句撒娇台词。 */
    fun randomLine(): String = SWEET_LINES.random()
}
