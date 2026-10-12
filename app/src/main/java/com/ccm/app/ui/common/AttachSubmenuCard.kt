package com.ccm.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 「添加附件」二级菜单卡片（2026-10-12 抽成共享组件）。
 *
 * ## 为什么要抽出来
 *
 * 首页（LandingScreen）和对话页（ChatScreenConnected）都要这个菜单 ——
 * 原来只在对话页里内联实现，首页的「添加文件或照片」**直接开图片选择器**
 * （没有拍照/文件入口），同一 App 两个行为。
 *
 * 抽成组件后两处共用，样式与行为不会再漂移。
 *
 * ## 视觉
 *
 * 与 [PlusMenu] / ModelPickerMenu 同一套（shadow 12dp → clip → background
 * → border → 内边距），三处菜单视觉统一 —— 改一处要同步另两处。
 *
 * ## 用法
 *
 * 调用方负责 Popup 的定位（锚定 + 按钮），本组件只画卡片内容。
 */
@Composable
fun AttachSubmenuCard(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onImages: () -> Unit,
    onFiles: () -> Unit,
) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .width(200.56.dp)                                    // 与 PlusMenu 同宽
            .shadow(12.dp, RoundedCornerShape(11.04.dp))
            .clip(RoundedCornerShape(11.04.dp))
            .background(colors.input)
            .border(1.dp, colors.border, RoundedCornerShape(11.04.dp))
            .padding(horizontal = 6.44.dp)
            .padding(top = 6.44.dp, bottom = 1.dp),
    ) {
        Text(
            "添加附件",
            style = CCMText.body14,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.border),
        )
        listOf(
            // 【2026-10-12 文案精简】原来带括号说明：
            //   「图片（可多选，随消息发给模型看）」「文件（文本类直接读内容，其他用 Read）」
            // —— 菜单项是**动作入口**，不是说明文档。一屏 3 行的菜单里塞
            // 20 字长句，读起来像段落不像菜单（用户报「文案太详细」）。
            // 对齐 Web：那边就是短词（添加文件或照片 / 截取屏幕 / 添加到项目）。
            "拍照" to onCamera,
            "从相册选择" to onImages,
            "选择文件" to onFiles,
        ).forEach { (label, act) ->
            Text(
                text = label,
                style = CCMText.body13,
                color = colors.textMain,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { act() }
                    .padding(vertical = 11.dp, horizontal = 4.dp),
            )
        }
    }
}
