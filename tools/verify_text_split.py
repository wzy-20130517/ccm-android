#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验证「工具区 + 正文区 = 全文」这个不变量（2026-10-11）。

## 为什么要这个脚本
APK 2026-10-10 出过「正文一突然消失」：正文区被 toolTextEndOffset 切走，
工具区又不渲染 textBefore → 工具前的正文两边都看不见。
正确修法是「两处都显示」，但**互补性**（不丢、不重）必须能被验证 ——
肉眼看代码看不出边界差一字符这类问题。

## 本脚本复刻的两段算法（与 Kotlin 侧逐行对应）
工具区（ToolCard.kt ToolCallGroup）：
    var prev = ""
    for card in visible:                       # visible = 过滤 HiddenToolNames
        raw = card.textBefore.strip()
        delta = "" if raw=="" else (raw.removeprefix(prev).strip() if prev=="" or raw.startswith(prev) else raw)
        渲染 delta
        if raw: prev = raw
正文区（MessageBubble.kt finalTextAfterTools）：
    offset = max(len(card.textBefore.strip()) for card in visible)   # consumedTextLength
    若 offset 不在 (0, len) → None（兜底显示全文）
    否则 text[offset:].strip()

## 判据
把两边渲染出的片段按顺序拼起来，应等于全文（空白归一化后）。
"""

import re

HIDDEN = {"WebSearch", "WebFetch"}


def consumed_len(cards):
    """复刻 Kotlin consumedTextLength：可见工具的 textBefore 最大长度。"""
    lens = [len(c.textBefore.strip()) for c in cards if c.name not in HIDDEN]
    return max(lens) if lens else 0


def render_tool_area(cards):
    """复刻 Kotlin 工具区的 textBefore 渲染（前缀差）。返回片段列表。"""
    out = []
    prev = ""
    for c in cards:
        if c.name in HIDDEN:
            continue
        raw = c.textBefore.strip()
        if raw == "":
            delta = ""
        elif prev == "" or raw.startswith(prev):
            delta = raw[len(prev):].strip()
        else:
            delta = raw
        if delta:
            out.append(delta)
        if raw:
            prev = raw
    return out


def render_body_area(full_text, cards):
    """复刻 Kotlin finalTextAfterTools。返回 (片段, 是否接管)。"""
    if not cards:
        return None, False
    text = full_text.strip()
    if text == "":
        return None, False
    offset = consumed_len(cards)
    if offset <= 0 or offset > len(text):
        return None, False          # 兜底：不接管，照旧显示全文
    return text[offset:].strip(), True


class Card:
    def __init__(self, name, text_before):
        self.name = name
        self.textBefore = text_before


def norm(s):
    return re.sub(r"\s+", "", s)


def check(label, full_text, cards, expect_ok=True):
    tool_parts = render_tool_area(cards)
    body, took_over = render_body_area(full_text, cards)
    if took_over:
        body_parts = [body] if body else []
    else:
        body_parts = [full_text.strip()] if full_text.strip() else []
    combined = norm("".join(tool_parts) + "".join(body_parts))
    target = norm(full_text)
    ok = combined == target
    status = "PASS" if ok == expect_ok else "FAIL"
    print(f"[{status}] {label}")
    if ok != expect_ok:
        print(f"        期望 ok={expect_ok}，实际 ok={ok}")
        print(f"        工具区片段: {tool_parts}")
        print(f"        正文区: {body!r} (接管={took_over})")
        print(f"        拼接后: {combined!r}")
        print(f"        全文:   {target!r}")
    return ok == expect_ok


def main():
    results = []

    # 1. 单工具：正文1 + 工具1 + 收尾正文
    t = "正文一。\n收尾正文。"
    results.append(check(
        "单工具（正文1 → 工具 → 收尾）",
        t, [Card("Read", "正文一。")]))

    # 2. 双工具：验证不重复（这是 2026-10-11 Web 修的坑）
    t = "A段。B段。C段。"
    results.append(check(
        "双工具（A → 工具1 → B → 工具2 → C）",
        t, [Card("Read", "A段。"), Card("Bash", "A段。B段。")]))

    # 3. 三工具
    t = "一二三四五六"
    results.append(check(
        "三工具（累计值前缀差）",
        t, [Card("Read", "一二"), Card("Bash", "一二三"), Card("Edit", "一二三四五六")]))

    # 4. 最后一个是隐藏工具（WebSearch）—— core 的 offset 会算错，我们用可见口径
    t = "A段。B段。"
    results.append(check(
        "末尾隐藏工具（WebSearch 被过滤）",
        t, [Card("Read", "A段。"), Card("WebSearch", "A段。B段。")]))

    # 5. 中间有隐藏工具
    t = "A段。B段。C段。"
    results.append(check(
        "中间隐藏工具",
        t, [Card("Read", "A段。"), Card("WebSearch", "A段。B段。"), Card("Bash", "A段。B段。C段。")]))

    # 6. 工具后无正文（offset == len）
    t = "A段。"
    results.append(check(
        "工具后无正文（offset==len → 正文区空）",
        t, [Card("Read", "A段。")]))

    # 7. 工具前无正文（第一个工具前什么都没说）
    t = "工具后才说的。"
    results.append(check(
        "工具前无正文（textBefore 全空 → 兜底全文）",
        t, [Card("Read", "")]))

    # 8. 纯工具轮次（全文为空）
    results.append(check(
        "纯工具轮次（全文为空）",
        "", [Card("Read", "")]))

    # 9. 无工具（纯正文）
    results.append(check(
        "无工具（纯正文）",
        "就一段话。", []))

    # 10. 模型输出带前导换行（trim 边界）
    t = "\n\n开头有换行。后面。"
    results.append(check(
        "带前导换行（trim 边界）",
        t, [Card("Read", "\n\n开头有换行。")]))

    # 11. textBefore 不是前缀（重试/历史回放异常数据）
    t = "X段。Y段。"
    results.append(check(
        "非前缀异常（重试场景）",
        t, [Card("Read", "X段。"), Card("Bash", "完全不同的内容")], expect_ok=False))

    # 12. 空 textBefore 混在中间
    t = "A段。B段。"
    results.append(check(
        "中间卡 textBefore 为空",
        t, [Card("Read", "A段。"), Card("Bash", ""), Card("Edit", "A段。B段。")]))

    # 13. 中文 + 标点（真实场景）
    t = "我先看看这个文件。\n\n好的，我改一下。\n\n改完了。"
    results.append(check(
        "真实中文多段",
        t, [Card("Read", "我先看看这个文件。"),
            Card("Edit", "我先看看这个文件。\n\n好的，我改一下。")]))

    # 14-16. 内联 artifact（2026-10-11 坐标对齐）—— 见下方 artifact 专项
    results.extend(check_artifact_cases())

    passed = sum(1 for r in results if r)
    print(f"\n{passed}/{len(results)} 通过")
    return 0 if passed == len(results) else 1


def parse_inline_artifacts(text):
    """复刻 Kotlin parseInlineArtifacts（无 artifact 时原样返回）。"""
    if "<cp_artifact" not in text:
        return text
    cleaned = text
    while True:
        m = re.search(r"<cp_artifact\s+([^>]*)>", cleaned, re.I)
        if not m:
            break
        body_start = m.end()
        close = cleaned.find("</cp_artifact>", body_start)
        if close == -1:
            cleaned = cleaned[: m.start()].strip()
            break
        cleaned = (cleaned[: m.start()] + cleaned[close + len("</cp_artifact>"):]).strip()
        cleaned = re.sub(r"\n{3,}", "\n\n", cleaned)
    return cleaned


def check_artifact_cases():
    """
    artifact 剥离会**改变正文长度** —— core 必须对每个卡的 textBefore
    做同样的剥离，否则 UI 的 offset 与正文不同坐标系 → 重复或丢失。
    """
    cases = [
        ("artifact 在工具前",
         '先看。\n<cp_artifact title="T" format="markdown">\n# 内容\n</cp_artifact>\n收尾。',
         [Card("Read", '先看。\n<cp_artifact title="T" format="markdown">\n# 内容\n</cp_artifact>')]),
        ("artifact 在工具后",
         '工具前的话。\n<cp_artifact title="T">\n# 内容\n</cp_artifact>\n',
         [Card("Read", '工具前的话。')]),
        ("两个 artifact",
         'A。\n<cp_artifact title="1">\nX\n</cp_artifact>\nB。\n<cp_artifact title="2">\nY\n</cp_artifact>\nC。',
         [Card("Read", 'A。\n<cp_artifact title="1">\nX\n</cp_artifact>')]),
    ]
    out = []
    for label, raw, cards_raw in cases:
        final_text = parse_inline_artifacts(raw)
        cards = [Card(c.name, parse_inline_artifacts(c.textBefore) if c.textBefore.strip() else c.textBefore)
                 for c in cards_raw]
        tool_parts = render_tool_area(cards)
        body, took = render_body_area(final_text, cards)
        body_parts = ([body] if body else []) if took else ([final_text.strip()] if final_text.strip() else [])
        got = norm("".join(tool_parts) + "".join(body_parts))
        want = norm(final_text)
        ok = got == want
        print(f"[{'PASS' if ok else 'FAIL'}] {label}（artifact 坐标对齐）")
        if not ok:
            print(f"        finalText={final_text!r}\n        工具区={tool_parts}")
            print(f"        正文区={body!r} 接管={took}\n        拼接={got!r}\n        期望={want!r}")
        out.append(ok)
    return out


if __name__ == "__main__":
    raise SystemExit(main())
