"""用 Python 复刻 StreamingMarkdown 算法，验证逻辑正确性。"""
class SM:
    def __init__(self):
        self.stable = []
        self.pending = []
        self.tail = ''          # ★ 尾部半截行（独立字段，避免重复追加）
        self.consumed = 0
        self.in_fence = False
        self.fence_marker = ""
    def feed(self, full):
        last_nl = full.rfind('\n')
        if last_nl < self.consumed:
            self.tail = full[self.consumed:]
            return ''.join(self.stable), ''.join(self.pending) + self.tail
        chunk = full[self.consumed:last_nl+1]
        self.consumed = last_nl + 1
        lines = chunk.split('\n')[:-1]      # 丢掉尾部空串
        for line in lines:
            t = line.lstrip()
            if t.startswith('```') or t.startswith('~~~'):
                marker = t[:3]
                if not self.in_fence:
                    self.in_fence = True; self.fence_marker = marker
                    self.pending.append(line + '\n')
                elif marker == self.fence_marker:
                    self.in_fence = False
                    self.pending.append(line + '\n')
                    self.stable.extend(self.pending); self.pending = []
                else:
                    self.pending.append(line + '\n')
                continue
            if self.in_fence:
                self.pending.append(line + '\n'); continue
            if t.startswith('|'):
                self.pending.append(line + '\n'); continue
            if self.pending:
                self.stable.extend(self.pending); self.pending = []
            self.stable.append(line + '\n')
        self.tail = full[last_nl+1:]
        return ''.join(self.stable), ''.join(self.pending) + self.tail
    def flush(self):
        # ★ 不变量 3：flush 时必须把 pending 和 tail 全部落地
        if self.pending:
            self.stable.extend(self.pending); self.pending = []
        if self.tail:
            self.stable.append(self.tail); self.tail = ''
        self.in_fence = False
        return ''.join(self.stable)

def check(name, steps, expect_final=None, expect_stable_contains=None):
    sm = SM()
    last = None
    for s in steps:
        last = sm.feed(s)
    final = sm.flush()
    ok = True
    msgs = []
    if expect_final is not None and final != expect_final:
        ok = False; msgs.append(f'最终内容不符\n    期望: {expect_final!r}\n    实际: {final!r}')
    if expect_stable_contains:
        for frag in expect_stable_contains:
            if frag not in final:
                ok = False; msgs.append(f'缺少片段: {frag!r}')
    print(f'  {"✓" if ok else "✗"} {name}')
    for m in msgs: print(f'     {m}')
    return ok

print("=== StreamingMarkdown 算法验证 ===")
allok = True

# 1. 纯文本，末尾无换行
allok &= check("纯文本流式（末尾无换行）",
    ['你好', '你好世界', '你好世界\n', '你好世界\n第二行'],
    expect_final='你好世界\n第二行')

# 2. 代码块：未闭合时不吐，闭合后整块吐
sm = SM()
sm.feed('前文\n```js\ncode\n')
stable, pending = sm.feed('前文\n```js\ncode\n```\n')
ok = 'code' not in stable.split('```')[0] if '```' in stable else True
print(f'  {"✓" if ok and "```" in stable else "✗"} 代码块：闭合后才落地')
if '```' not in stable: allok = False

# 3. 未闭合 fence + flush 不丢内容
sm2 = SM()
sm2.feed('开头\n```python\nprint(1)\n')
r = sm2.flush()
ok3 = 'print(1)' in r and '开头' in r
print(f'  {"✓" if ok3 else "✗"} 未闭合 fence + flush 不丢内容')
allok &= ok3

# 4. 表格整体落地
sm3 = SM()
sm3.feed('| a | b |\n| - | - |\n| 1 | 2 |\n')
s3, p3 = sm3.feed('| a | b |\n| - | - |\n| 1 | 2 |\n普通文字\n')
ok4 = '| 1 | 2 |' in s3 and '普通文字' in s3
print(f'  {"✓" if ok4 else "✗"} 表格整体落地（遇到普通行才 flush）')
allok &= ok4

# 5. 幂等：同一文本重复 feed
sm4 = SM()
sm4.feed('abc\n')
a1, _ = sm4.feed('abc\n')
a2, _ = sm4.feed('abc\n')
ok5 = a1 == a2
print(f'  {"✓" if ok5 else "✗"} 幂等（重复 feed 不重复消费）')
allok &= ok5

# 6. 分片到达（模拟真实流式）
sm5 = SM()
full = ''
target = "标题\n\n正文一段。\n\n```js\nconsole.log(1)\n```\n\n结尾"
for i in range(1, len(target)+1):
    full = target[:i]
    sm5.feed(full)
final5 = sm5.flush()
ok6 = final5 == target
print(f'  {"✓" if ok6 else "✗"} 逐字符分片到达 → 最终内容与原文一致')
if not ok6:
    print(f'     期望 {target!r}')
    print(f'     实际 {final5!r}')
allok &= ok6

# 7. 表格后接 fence
sm6 = SM()
sm6.feed('| a |\n| - |\n')
sm6.feed('| a |\n| - |\n```\ncode\n```\n')
f6 = sm6.flush()
ok7 = '| - |' in f6 and 'code' in f6
print(f'  {"✓" if ok7 else "✗"} 表格后接代码块')
allok &= ok7

print(f'\n{"✓ 全部通过" if allok else "✗ 有失败项"}')
