// 复刻 ChannelFs 里生成的命令串，在本地 Termux 上真跑一遍
import { execSync } from 'node:child_process';
import fs from 'node:fs';

const STAT_PREFIX = "CCM_S:", OK_PREFIX = "CCM_OK:", ERR_PREFIX = "CCM_E:";
const MAX_OUTPUT_BYTES = 256*1024;
const quote = s => "'" + s.replace(/'/g, "'\\''") + "'";

function sh(cmd) {
  try {
    const stdout = execSync(cmd, {shell:'/data/data/com.termux/files/usr/bin/bash', encoding:'utf8', stdio:['ignore','pipe','pipe']});
    return {exitCode:0, stdout, stderr:''};
  } catch(e) {
    return {exitCode: e.status ?? -1, stdout: e.stdout?.toString() ?? '', stderr: e.stderr?.toString() ?? ''};
  }
}

// ---- read 命令 ----
function readCmd(path, startLine, endLine) {
  const p = quote(path);
  const range = startLine != null ? (endLine != null ? `${startLine},${endLine}p` : `${startLine},$p`) : null;
  const sedBody = range != null ? `sed -n ${quote(range)} "\$p"` : `cat "\$p"`;
  let cmd = `p=${p}; `;
  cmd += `if [ ! -e "\$p" ]; then echo '${STAT_PREFIX}N'; exit 0; fi; `;
  cmd += `if [ -d "\$p" ]; then echo '${STAT_PREFIX}D'; exit 0; fi; `;
  cmd += `sz=\$(wc -c < "\$p" 2>/dev/null | tr -d ' '); [ -z "\$sz" ] && sz=0; `;
  cmd += `mt=\$(stat -c '%Y' "\$p" 2>/dev/null || echo 0); `;
  if (range == null) {
    cmd += `if [ "\$sz" -gt ${MAX_OUTPUT_BYTES} ]; then echo '${STAT_PREFIX}T' \$sz; exit 0; fi; `;
    cmd += `echo "${STAT_PREFIX}F \$sz \$mt"; `;
    cmd += sedBody;
  } else {
    cmd += `n=\$(${sedBody} 2>/dev/null | head -c ${MAX_OUTPUT_BYTES+1} | wc -c | tr -d ' '); `;
    cmd += `[ -z "\$n" ] && n=0; `;
    cmd += `if [ "\$n" -gt ${MAX_OUTPUT_BYTES} ]; then echo '${STAT_PREFIX}T' \$sz; exit 0; fi; `;
    cmd += `echo "${STAT_PREFIX}F \$sz \$mt"; `;
    cmd += sedBody;
  }
  return cmd;
}

// ---- write 命令 ----
function writeChunks(path, content) {
  const bytes = Buffer.from(content, 'utf8');
  const CHUNK = 64*1024;
  let chunks = [];
  if (bytes.length === 0) chunks = [''];
  else for (let off=0; off<bytes.length; off+=CHUNK) chunks.push(bytes.subarray(off, Math.min(off+CHUNK, bytes.length)).toString('base64'));
  const p = quote(path);
  const token = 'ccm' + Date.now().toString(36) + Math.floor(Math.random()*0xFFFFFF).toString(36);
  const head = `p=${p}; d=\$(dirname "\$p"); ` +
    `mkdir -p "\$d" 2>/dev/null || { echo '${ERR_PREFIX}mkdir'; exit 11; }; ` +
    `t="\$d/.${token}.tmp"; ` +
    `x=0; [ -x "\$p" ] && x=1; `;
  const tail = `mv -f "\$t" "\$p" || { rm -f "\$t"; echo '${ERR_PREFIX}mv'; exit 13; }; ` +
    `[ "\$x" = 1 ] && chmod +x "\$p" 2>/dev/null; ` +
    `echo "${OK_PREFIX}\$(wc -c < "\$p" 2>/dev/null | tr -d ' '):\$(stat -c '%Y' "\$p" 2>/dev/null || echo 0)"`;
  const out = [];
  chunks.forEach((b64, i) => {
    const redir = i === 0 ? '>' : '>>';
    const isLast = i === chunks.length-1;
    out.push(head + `printf '%s' ${quote(b64)} | base64 -d ${redir} "\$t" ` +
      `|| { rm -f "\$t"; echo '${ERR_PREFIX}b64'; exit 12; }; ` + (isLast ? tail : ''));
  });
  return {cmds: out, chunks: chunks.length};
}

// ---- grep 命令 ----
function grepCmd(pattern, root, maxMatches=200, ignoreCase=false) {
  const r = quote(root), pat = quote(pattern);
  const fileFlags = ignoreCase ? '-inH' : '-nH';
  const dirFlags = ignoreCase ? '-rinH' : '-rnH';
  return `r=${r}; ` +
    `G='-P'; echo x | grep -P x >/dev/null 2>&1 || G='-E'; ` +
    `E=''; grep --exclude-dir=.ccmprobe x /dev/null >/dev/null 2>&1 && ` +
    `E='--exclude-dir=.git --exclude-dir=node_modules --exclude-dir=.gradle --exclude-dir=build'; ` +
    `if [ -f "\$r" ]; then ` +
    `grep \$G ${fileFlags} -- ${pat} "\$r" 2>/dev/null | head -n ${maxMatches}; ` +
    `elif [ -d "\$r" ]; then ` +
    `grep \$G ${dirFlags} \$E -- ${pat} "\$r" 2>/dev/null | head -n ${maxMatches}; ` +
    `else echo '${ERR_PREFIX}nodir'; exit 4; fi`;
}

// ---- listFiles ----
function listCmd(root, maxFiles=50000) {
  const r = quote(root);
  return `r=${r}; ` +
    `if [ -f "\$r" ]; then printf '%s\\n' "\$r"; exit 0; fi; ` +
    `if [ ! -d "\$r" ]; then echo '${ERR_PREFIX}nodir'; exit 4; fi; ` +
    `find "\$r" -type f 2>/dev/null | head -n ${maxFiles+1}`;
}

// ================= 测试 =================
let pass=0, fail=0;
const ok=(n,c)=>{ if(c){pass++;console.log('  ✓ '+n);} else {fail++;console.log('  ✗ '+n);} };
// eq(name, got, want) —— 值比较版，失败时把 got/want 都打出来（否则只看到「错了」）
//
// ⚠️ 用 JSON.stringify 归一化再比，而不是 `a === b`：
// 行号一致性那组测试的返回值是**数组**，`['3| c'] === ['3| c']` 恒为 false
// （两个不同对象），会报「假失败」—— 而打印出来的 got/want 看着一模一样，
// 极容易误判成代码有问题。JSON 化后数组按内容比，符合直觉。
const eq=(n,a,b)=>{
  const sa=JSON.stringify(a), sb=JSON.stringify(b);
  if(sa===sb){pass++;console.log('  ✓ '+n);}
  else {fail++;console.log(`  ✗ ${n}\n      got:  ${sa}\n      want: ${sb}`);}
};

console.log('\n=== 1. read 命令 ===');
fs.writeFileSync('t1.txt','line1\nline2\nline3\nline4\nline5\n');
let r = sh(readCmd('t1.txt'));
ok('read 首行是状态行', r.stdout.startsWith('CCM_S:F '));
ok('read 内容正确', r.stdout.split('\n').slice(1).join('\n') === 'line1\nline2\nline3\nline4\nline5\n');
console.log('    stdout repr:', JSON.stringify(r.stdout));

r = sh(readCmd('t1.txt', 2, 4));
console.log('    分段读 stdout:', JSON.stringify(r.stdout));
ok('分段读首行是状态行', r.stdout.startsWith('CCM_S:F '));
ok('分段读只含 2-4 行', r.stdout.includes('line2') && r.stdout.includes('line4') && !r.stdout.includes('line1'));

r = sh(readCmd('t1.txt', 3, null));
ok('startLine-only 读到末尾', r.stdout.includes('line3') && r.stdout.includes('line5') && !r.stdout.includes('line2'));

r = sh(readCmd('nope.txt'));
ok('不存在的文件 → CCM_S:N', r.stdout.trim() === 'CCM_S:N');

fs.mkdirSync('adir',{recursive:true});
r = sh(readCmd('adir'));
ok('目录 → CCM_S:D', r.stdout.trim() === 'CCM_S:D');

// 空文件
fs.writeFileSync('empty.txt','');
r = sh(readCmd('empty.txt'));
console.log('    空文件 stdout:', JSON.stringify(r.stdout));
ok('空文件 → 状态行 + 无内容', r.stdout.startsWith('CCM_S:F 0 '));

// 特殊字符路径
fs.writeFileSync("we ird'name.txt",'hello\n');
r = sh(readCmd("we ird'name.txt"));
console.log("    特殊路径 stdout:", JSON.stringify(r.stdout));
ok('带空格+单引号的路径能读', r.stdout.includes('hello'));

// 大文件保护
fs.writeFileSync('big.txt','x'.repeat(300*1024));
r = sh(readCmd('big.txt'));
ok('大文件 → CCM_S:T', r.stdout.trim().startsWith('CCM_S:T'));

// ⚠️ 开区间分段读（只给 start_line）也必须挡 —— 否则尾部能撑爆 Binder
//
// 注意用**多行**大文件：big.txt 是单行 300KB，`sed -n '1,5p'` 会原样返回
// 那一整行（300KB），被拦是对的、但验不出「行范围」的逻辑。
fs.writeFileSync('biglines.txt',
  Array.from({length: 3000}, (_, i) => `line${i}`.padEnd(100, 'y')).join('\n') + '\n');

r = sh(readCmd('biglines.txt', 1, null));
ok('开区间分段读大文件 → CCM_S:T', r.stdout.trim().startsWith('CCM_S:T'));

r = sh(readCmd('biglines.txt', 1, 5));
ok('有界分段读 → 成功', r.stdout.startsWith('CCM_S:F '));
ok('有界分段读只回 5 行', r.stdout.split('\n').slice(1).filter(x=>x.length>0).length === 5);

r = sh(readCmd('biglines.txt', 2998, 3000));
ok('读文件末尾几行 → 成功', r.stdout.startsWith('CCM_S:F ') && r.stdout.includes('line2999'));

console.log('\n=== 2. write 命令 ===');
let w = writeChunks('out1.txt', 'hello world\n');
ok('单块', w.chunks===1);
r = sh(w.cmds[0]);
console.log('    write stdout:', JSON.stringify(r.stdout));
ok('write 返回 OK', r.stdout.includes('CCM_OK:'));
ok('文件内容正确', fs.readFileSync('out1.txt','utf8') === 'hello world\n');
ok('无残留 tmp', fs.readdirSync('.').filter(f=>f.includes('.tmp')).length===0);

// 空内容
w = writeChunks('out-empty.txt', '');
r = sh(w.cmds[0]);
ok('写空文件 OK', r.stdout.includes('CCM_OK:'));
ok('空文件真的建出来了', fs.existsSync('out-empty.txt') && fs.statSync('out-empty.txt').size===0);

// 多块
const bigContent = 'A'.repeat(64*1024) + 'B'.repeat(100*1024);
w = writeChunks('out-big.txt', bigContent);
ok('大内容分 3 块', w.chunks===3);
let lastOut='';
for (const c of w.cmds) lastOut = sh(c);
ok('多块最后返回 OK', lastOut.stdout.includes('CCM_OK:'));
ok('多块内容完整', fs.readFileSync('out-big.txt','utf8') === bigContent);

// 内容里带 heredoc 定界符 / 特殊字符
const nasty = 'EOF\nCCM_EOF\n`rm -rf /`\n$(whoami)\n\'"\\\n\x00after-null\n';
w = writeChunks('out-nasty.txt', nasty);
for (const c of w.cmds) lastOut = sh(c);
ok('nasty 内容写入 OK', lastOut.stdout.includes('CCM_OK:'));
ok('nasty 内容字节级一致', fs.readFileSync('out-nasty.txt','utf8') === nasty);

// 可执行位保留
fs.writeFileSync('script.sh','#!/bin/sh\necho hi\n'); fs.chmodSync('script.sh',0o755);
w = writeChunks('script.sh','#!/bin/sh\necho hi2\n');
for (const c of w.cmds) lastOut = sh(c);
ok('覆盖写保留 +x', (fs.statSync('script.sh').mode & 0o111) !== 0);
ok('覆盖写内容正确', fs.readFileSync('script.sh','utf8').includes('hi2'));

// 子目录自动创建
w = writeChunks('newdir/sub/deep.txt','deep\n');
for (const c of w.cmds) lastOut = sh(c);
ok('自动创建父目录', fs.readFileSync('newdir/sub/deep.txt','utf8')==='deep\n');

console.log('\n=== 3. grep 命令 ===');
fs.mkdirSync('gdir/sub',{recursive:true});
fs.writeFileSync('gdir/a.txt','alpha\nbeta\ngamma\n');
fs.writeFileSync('gdir/sub/b.txt','beta two\nnothing\n');
fs.writeFileSync('gdir/num.txt','value=123\nother\n');

r = sh(grepCmd('beta','gdir'));
console.log('    grep 目录 stdout:', JSON.stringify(r.stdout));
ok('grep 目录命中 2 处', (r.stdout.match(/beta/g)||[]).length===2);
ok('grep 带行号', /:\d+:/.test(r.stdout));

r = sh(grepCmd('alpha','gdir/a.txt'));
console.log('    grep 单文件 stdout:', JSON.stringify(r.stdout));
ok('grep 单文件命中', r.stdout.includes('alpha'));

r = sh(grepCmd('value=\\d+','gdir'));
console.log('    grep PCRE stdout:', JSON.stringify(r.stdout));
ok('PCRE \\d+ 生效（-P 可用）', r.stdout.includes('value=123'));

r = sh(grepCmd('BETA','gdir',200,true));
ok('ignoreCase 生效', r.stdout.toLowerCase().includes('beta'));

r = sh(grepCmd('x','nodir_xyz'));
ok('不存在路径 → 报错', r.stdout.includes('CCM_E:nodir'));

r = sh(grepCmd('zzzznotfound','gdir'));
ok('无匹配 → 空输出', r.stdout.trim()==='');

console.log('\n=== 4. listFiles 命令 ===');
fs.mkdirSync('ldir',{recursive:true});
fs.writeFileSync('ldir/f1.txt','1');
fs.writeFileSync('ldir/f2.txt','2');
fs.mkdirSync('ldir/sub',{recursive:true});
fs.writeFileSync('ldir/sub/f3.txt','3');
r = sh(listCmd('ldir'));
console.log('    list stdout:', JSON.stringify(r.stdout));
ok('find 列出 3 个文件', r.stdout.trim().split('\n').length===3);
ok('find 递归', r.stdout.includes('ldir/sub/f3.txt'));

r = sh(listCmd('ldir/f1.txt'));
ok('单文件路径 → 回显自身', r.stdout.trim()==='ldir/f1.txt');

r = sh(listCmd('nodir_xyz'));
ok('不存在目录 → CCM_E:nodir', r.stdout.includes('CCM_E:nodir'));



// ══════════════════════════════════════════════════════════════
//  5. canonicalKey —— 纯逻辑，验边界
// ══════════════════════════════════════════════════════════════
//
// 这段是 ChannelFs.canonicalKey 的 JS 复刻。它是并发写保护的 key，
// 一旦算错（同一个文件算出两个 key），保护会**静默失效** —— 不报错、
// 不崩溃，只是「有时拦得住有时拦不住」，属于最难查的那类 bug。
// 所以边界必须逐条钉死。

const TERMUX_HOME = '/data/data/com.termux/files/home';
function canonicalKey(path) {
  const abs = path.startsWith('/') ? path : `${TERMUX_HOME}/${path}`;
  const parts = abs.split('/').filter(s => s.length > 0 && s !== '.');
  return '/' + parts.join('/');
}

console.log('\n=== 5. canonicalKey ===');
eq('相对路径 → 拼 HOME', canonicalKey('a.txt'), TERMUX_HOME + '/a.txt');
eq('绝对路径原样', canonicalKey('/sdcard/x.txt'), '/sdcard/x.txt');
eq('./ 被清掉', canonicalKey('./a.txt'), TERMUX_HOME + '/a.txt');
eq('连续 ./././ 全清', canonicalKey('./././a.txt'), TERMUX_HOME + '/a.txt');
eq('中间 ./ 被清掉', canonicalKey('sub/./b.txt'), TERMUX_HOME + '/sub/b.txt');
eq('重复斜杠折叠', canonicalKey('sub//b.txt'), TERMUX_HOME + '/sub/b.txt');
eq('多重斜杠折叠', canonicalKey('/a//b///c'), '/a/b/c');
eq('根目录', canonicalKey('/'), '/');
eq('空串 → HOME', canonicalKey(''), TERMUX_HOME);
eq('./ → HOME', canonicalKey('./'), TERMUX_HOME);
eq('. → HOME', canonicalKey('.'), TERMUX_HOME);
eq('.. 不解析（已知局限）', canonicalKey('a/../b'), TERMUX_HOME + '/a/../b');
eq('HOME 内绝对路径', canonicalKey(TERMUX_HOME + '/x'), TERMUX_HOME + '/x');
eq('等价性：x == ./x', canonicalKey('x'), canonicalKey('./x'));
eq('等价性：绝对路径规范化', canonicalKey('/sdcard/a'), canonicalKey('/sdcard//./a'));
eq('混合 ./ 与 ..', canonicalKey('./sub/./deep/../x.txt'), TERMUX_HOME + '/sub/deep/../x.txt');

// ══════════════════════════════════════════════════════════════
//  6. 行号计算 —— 通道路径 vs Android 路径必须逐字符一致
// ══════════════════════════════════════════════════════════════
//
// 这是「同语义不同实现」的经典坑：通道模式把 sed 切好的片段拿回来，
// 行号要加 (startLine-1)；Android 模式拿全文再 subList，行号是 start+1。
// 两条式子写法不同、结果必须相同 —— 错一位用户看到的就是「行号对不上」，
// 而且只在 Termux 模式下出现（最难发现的那类）。

// 通道模式（sed 已切片，content 从 startLine 开始）
function channelRead(content, fileSize, startLine, endLine) {
  const lines = content.split('\n');
  const isRanged = startLine != null && fileSize > 0;
  if (isRanged && content === '') return 'OUT_OF_RANGE';
  const base = isRanged ? startLine - 1 : 0;
  let end;
  if (isRanged && endLine != null) end = endLine - startLine + 1;
  else end = endLine != null ? endLine : lines.length;
  if (!isRanged && 0 >= lines.length) return 'OUT_OF_RANGE';
  const slice = lines.slice(0, Math.min(end, lines.length));
  return slice.map((l, i) => String(base + i + 1).padStart(5) + '| ' + l);
}

// Android 模式（全文 + subList）
function androidRead(all, startLine, endLine) {
  const lines = all;
  const start = Math.max((startLine ?? 1) - 1, 0);
  const end = endLine != null ? endLine : lines.length;
  if (start >= lines.length) return 'OUT_OF_RANGE';
  const slice = lines.slice(start, Math.min(end, lines.length));
  return slice.map((l, i) => String(start + i + 1).padStart(5) + '| ' + l);
}

console.log('\n=== 6. 行号一致性（通道 vs Android）===');
const full = ['a', 'b', 'c', 'd', 'e'];

eq('整文件读', channelRead(full.join('\n'), 10, null, null), androidRead(full, null, null));
eq('读 2-4 行', channelRead(['b', 'c', 'd'].join('\n'), 10, 2, 4), androidRead(full, 2, 4));
eq('读 3-末尾', channelRead(['c', 'd', 'e'].join('\n'), 10, 3, null), androidRead(full, 3, null));
eq('读 1-1 行', channelRead('a', 10, 1, 1), androidRead(full, 1, 1));
eq('读末行', channelRead('e', 10, 5, 5), androidRead(full, 5, 5));
eq('越界范围两边一致', channelRead('', 10, 10, 12), androidRead(full, 10, 12));

console.log(`\n===== ${pass} passed, ${fail} failed =====`);
process.exit(fail ? 1 : 0);
