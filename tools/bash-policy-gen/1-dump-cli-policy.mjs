const base = '/data/data/com.termux/files/home/claude-code-mobile/core/permission/bash-ast/policy/';
const c = await import(base + 'commands.mjs');
import fs from 'fs';
const toObj = (coll) => {
  const out = {};
  for (const [k, v] of (coll instanceof Map ? coll : Object.entries(coll))) {
    const e = {};
    if (v.allowAnyArgs) e.allowAnyArgs = true;
    if (v.commandOnly) e.commandOnly = true;
    if (v.allowCompactNumericCountFlag) e.compactCount = true;
    if (v.respectsDoubleDash === false) e.noDoubleDash = true;
    if (v.regex) e.regex = v.regex.source;
    if (v.safeFlags) e.safeFlags = v.safeFlags;
    if (v.additionalCommandIsDangerousCallback) {
      const n = v.additionalCommandIsDangerousCallback.name;
      e.cb = (n === 'additionalCommandIsDangerousCallback') ? 'INLINE' : n;
    }
    out[k] = e;
  }
  return out;
};
const data = {
  commands: toObj(c.READONLY_COMMAND_POLICIES),
  gitSub: toObj(c.GIT_READONLY_SUBCOMMAND_POLICIES),
  multiword: toObj(c.READONLY_MULTIWORD_COMMAND_POLICIES),
  allowAnyArg: [...c.READONLY_ALLOW_ANY_ARG_COMMANDS],
  allowAnyArgPrefixes: c.READONLY_ALLOW_ANY_ARG_COMMAND_PREFIXES,
  gitGlobal: { dangerous:[...c.GIT_GLOBAL_DANGEROUS_FLAGS], noValue:[...c.GIT_GLOBAL_NO_VALUE_FLAGS], value:[...c.GIT_GLOBAL_VALUE_FLAGS] },
};
fs.writeFileSync('/data/data/com.termux/files/home/perf-test/policy-full.json', JSON.stringify(data, null, 1));
// 报告新发现的字段
console.log('=== 带 compactCount 的命令 ===');
for (const g of ['commands','gitSub','multiword']) for (const [k,v] of Object.entries(data[g])) if (v.compactCount) console.log(' ', g, k);
console.log('=== 带 noDoubleDash 的命令 ===');
for (const g of ['commands','gitSub','multiword']) for (const [k,v] of Object.entries(data[g])) if (v.noDoubleDash) console.log(' ', g, k);
console.log('=== 带 regex 的命令 ===');
for (const g of ['commands','gitSub','multiword']) for (const [k,v] of Object.entries(data[g])) if (v.regex) console.log(' ', g, k, '→', v.regex);
console.log('=== 内联回调 ===');
for (const g of ['commands','gitSub','multiword']) for (const [k,v] of Object.entries(data[g])) if (v.cb==='INLINE') console.log(' ', g, k);
