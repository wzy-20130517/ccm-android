#!/usr/bin/env python3
"""
ToolDiffView 的 LCS diff 算法回归测试。

## 为什么要这个
`ToolDiffView.kt` 的 `computeDiff` 是**纯算法**，且是「工具卡片显示得对不对」
的核心 —— diff 算错时，界面上只是加号减号位置怪怪的，**不会报错**，
肉眼很容易忽略。

项目禁止本地 kotlinc，所以这里用 Python 复刻同一套 LCS 做回归。

## ⚠️ 维护契约
本文件与 `ToolDiffView.kt` 的 `computeDiff` / `simpleDiff` /
`numberedDiff` **必须逻辑同步**。改 Kotlin 那份时同步改这里。

用法：
    python3 tools/test_diff_algorithm.py
"""
import sys

# ── 与 ToolDiffView.kt 逐行对应 ──────────────────────────────────


def compute_diff(old: list, new: list) -> list:
    """对应 Kotlin `computeDiff`：LCS 表 + 回溯，大输入降级"""
    m, n = len(old), len(new)
    # 降级阈值必须与 Kotlin 一致（防止 DP 表爆内存）
    if m * n > 500000:
        return simple_diff(old, new)

    dp = [[0] * (n + 1) for _ in range(m + 1)]
    for i in range(1, m + 1):
        for j in range(1, n + 1):
            if old[i - 1] == new[j - 1]:
                dp[i][j] = dp[i - 1][j - 1] + 1
            else:
                dp[i][j] = max(dp[i - 1][j], dp[i][j - 1])

    stack = []
    i, j = m, n
    while i > 0 or j > 0:
        if i > 0 and j > 0 and old[i - 1] == new[j - 1]:
            stack.append(('context', old[i - 1]))
            i -= 1
            j -= 1
        elif j > 0 and (i == 0 or dp[i][j - 1] >= dp[i - 1][j]):
            stack.append(('added', new[j - 1]))
            j -= 1
        else:
            stack.append(('removed', old[i - 1]))
            i -= 1
    return list(reversed(stack))


def simple_diff(old: list, new: list) -> list:
    """对应 Kotlin `simpleDiff`：全删 + 全加"""
    return [('removed', l) for l in old] + [('added', l) for l in new]


def numbered_diff(old: list, new: list) -> list:
    """对应 Kotlin `numberedDiff`：加新旧行号。

    行号规则（源码 `numberedLines`）：
      context → 新旧都递增
      removed → 只递增旧
      added   → 只递增新
    """
    raw = compute_diff(old, new)
    out = []
    old_num = new_num = 1
    for t, content in raw:
        if t == 'context':
            out.append((t, content, old_num, new_num))
            old_num += 1
            new_num += 1
        elif t == 'removed':
            out.append((t, content, old_num, None))
            old_num += 1
        else:
            out.append((t, content, None, new_num))
            new_num += 1
    return out


# ── 测试用例 ──────────────────────────────────────────────────────
CASES = [
    # (说明, 旧行, 新行, 期望的类型序列)
    ("纯替换", ['a', 'b', 'c'], ['a', 'x', 'c'],
     ['context', 'removed', 'added', 'context']),
    ("纯新增（中间插入）", ['a', 'c'], ['a', 'b', 'c'],
     ['context', 'added', 'context']),
    ("纯删除", ['a', 'b', 'c'], ['a', 'c'],
     ['context', 'removed', 'context']),
    ("全不同", ['a'], ['b'], ['removed', 'added']),
    ("完全相同", ['a', 'b'], ['a', 'b'], ['context', 'context']),
    ("旧为空", [], ['a'], ['added']),
    ("新为空", ['a'], [], ['removed']),
    ("两边都空", [], [], []),
    ("头部新增", ['b'], ['a', 'b'], ['added', 'context']),
    ("尾部新增", ['a'], ['a', 'b'], ['context', 'added']),
    ("多行重排", ['a', 'b', 'c'], ['c', 'a', 'b'],
     ['removed', 'context', 'context', 'added']),
]


def main() -> int:
    ok = fail = 0
    print("ToolDiffView LCS diff 算法回归测试")
    print("─" * 60)
    for name, old, new, expect in CASES:
        got = [t for t, _ in compute_diff(old, new)]
        if got == expect:
            ok += 1
            print(f"  ✓ {name}")
        else:
            fail += 1
            print(f"  ✗ {name}")
            print(f"      期望: {expect}")
            print(f"      实际: {got}")

    # ── 行号规则验证（独立的正确性维度）─────────────────────────
    print("─" * 60)
    print("行号规则：")
    numbered = numbered_diff(['a', 'b', 'c'], ['a', 'x', 'c'])
    # a(context: 旧1 新1) / b(removed: 旧2) / x(added: 新2) / c(context: 旧3 新3)
    expect_nums = [
        ('context', 'a', 1, 1),
        ('removed', 'b', 2, None),
        ('added', 'x', None, 2),
        ('context', 'c', 3, 3),
    ]
    if numbered == expect_nums:
        ok += 1
        print("  ✓ context 新旧同增 / removed 只增旧 / added 只增新")
    else:
        fail += 1
        print("  ✗ 行号规则不符")
        for got, exp in zip(numbered, expect_nums):
            mark = " " if got == exp else "≠"
            print(f"      {mark} 实际 {got}  期望 {exp}")

    # ── 降级路径验证 ────────────────────────────────────────────
    print("─" * 60)
    print("大输入降级（m×n > 500000）：")
    big_old = [f"old{i}" for i in range(800)]     # 800 × 800 = 640000 > 500000
    big_new = [f"new{i}" for i in range(800)]
    degraded = compute_diff(big_old, big_new)
    if len(degraded) == 1600 and degraded[0][0] == 'removed' and degraded[-1][0] == 'added':
        ok += 1
        print(f"  ✓ 800×800 走降级路径（{len(degraded)} 行 = 800 删 + 800 加）")
    else:
        fail += 1
        print(f"  ✗ 降级路径异常：{len(degraded)} 行")

    print("─" * 60)
    print(f"{ok} 通过 / {fail} 失败")
    if fail:
        print("\n⚠️ 若 Kotlin 侧刚改过 diff，请同步本文件（见文件头契约）")
    return 1 if fail else 0


if __name__ == '__main__':
    sys.exit(main())
