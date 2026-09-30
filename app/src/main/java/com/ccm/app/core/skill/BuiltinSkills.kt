package com.ccm.app.core.skill

/**
 * 内置技能清单（2026-09-29 第33批）。
 *
 * ## 为什么是清单而不是可执行技能
 * APK 侧没有 skills 运行时（CLI 的 Skill 工具机制没搬）。定制页需要的
 * 是「有哪些技能、各自干什么」的展示数据 —— 先给真实清单，让页面有内容；
 * 技能执行（注入 SKILL.md 正文到对话）后续按需接。
 *
 * ## 与 CLI 的关系
 * 名字与 CLI 的项目技能对齐（.claude/skills/ 下的那批）。CLI 侧技能会变，
 * 这里保持「常用核心集」；将来可从 workspace/.claude/skills 动态扫描。
 */
object BuiltinSkills {

    data class Skill(
        val id: String,
        val name: String,
        val description: String,
    )

    fun all(): List<Skill> = listOf(
        Skill(
            "anti-ai-slop",
            "anti-ai-slop",
            "消除视觉产物的「AI 味」：诊断成因 + 13 条自检清单 + 配色/字体替代方案",
        ),
        Skill(
            "termux-video",
            "termux-video",
            "在 Termux/Android 上做动态视频：HTML/CSS 画面 + headless Chromium 逐帧截图 + ffmpeg 合成",
        ),
        Skill(
            "apple-design",
            "apple-design",
            "Apple 式界面与物理动效：手势驱动 UI、spring 动画、拖拽/滑动/面板交互",
        ),
        Skill(
            "review-animations",
            "review-animations",
            "按高工艺标准审查动画代码：默认挑刺，批准需挣得",
        ),
        Skill(
            "improve-animations",
            "improve-animations",
            "以资深动效顾问视角审计代码库动效，产出优先级排序的改进方案",
        ),
        Skill(
            "find-animation-opportunities",
            "find-animation-opportunities",
            "在代码库里找「该动却没动」的位置，同时否掉「不该动」的",
        ),
        Skill(
            "animation-vocabulary",
            "animation-vocabulary",
            "把模糊的动效描述翻译成准确术语（「弹出时那个弹一下」→ Pop in）",
        ),
        Skill(
            "emil-design-eng",
            "emil-design-eng",
            "Emil Kowalski 的 UI 打磨哲学：组件设计与让软件「手感好」的隐形细节",
        ),
        Skill(
            "pick-ui-library",
            "pick-ui-library",
            "为前端任务挑合适的库：数字输入、OTP、图表、命令菜单、虚拟列表、拖拽、toast 等",
        ),
    )
}
