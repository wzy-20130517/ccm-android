import fs from 'fs';
const dump = JSON.parse(fs.readFileSync('/data/data/com.termux/files/home/perf-test/policy-full.json','utf8'));
const KS = (s) => '"' + String(s).replace(/\\/g,'\\\\').replace(/"/g,'\\"').replace(/\$/g,'\\$') + '"';

function entryKt(v) {
  const parts = [];
  if (v.allowAnyArgs) parts.push('allowAnyArgs = true');
  if (v.commandOnly) parts.push('commandOnly = true');
  if (v.compactCount) parts.push('allowCompactNumericCountFlag = true');
  if (v.noDoubleDash) parts.push('respectsDoubleDash = false');
  if (v.regex) parts.push('regex = ' + KS(v.regex));
  if (v.safeFlags) parts.push('safeFlagsJson = ' + KS(JSON.stringify(v.safeFlags)));
  if (v.cb) parts.push('callback = Callbacks::' + (v.cb === 'INLINE' ? 'gitRemoteCommandIsDangerous' : v.cb));
  return 'Policy(' + parts.join(', ') + ')';
}
let out = '';
for (const [name, g] of [['COMMAND_POLICIES','commands'],['GIT_SUBCOMMAND_POLICIES','gitSub'],['MULTIWORD_POLICIES','multiword']]) {
  out += `    private val ${name}: Map<String, Policy> = buildMap {\n`;
  for (const [k, v] of Object.entries(dump[g])) out += `        put(${KS(k)}, ${entryKt(v)})\n`;
  out += '    }\n\n';
}
out += '    private val ALLOW_ANY_ARG_COMMANDS: Set<String> = setOf(\n';
const aaa = [...new Set(dump.allowAnyArg.flatMap(s => s.split(',').map(x=>x.trim())))].sort();
for (let i=0;i<aaa.length;i+=5) out += '        ' + aaa.slice(i,i+5).map(KS).join(', ') + ',\n';
out += '    )\n\n';
out += '    private val ALLOW_ANY_ARG_PREFIXES: List<List<String>> = listOf(\n';
for (const p of dump.allowAnyArgPrefixes) out += `        listOf(${p.map(KS).join(', ')}),\n`;
out += '    )\n\n';
out += '    private val GIT_GLOBAL_DANGEROUS_FLAGS: Set<String> = setOf(\n';
const dg = dump.gitGlobal.dangerous;
for (let i=0;i<dg.length;i+=5) out += '        ' + dg.slice(i,i+5).map(KS).join(', ') + ',\n';
out += '    )\n\n';
out += `    private val GIT_GLOBAL_NO_VALUE_FLAGS: Set<String> = setOf(${dump.gitGlobal.noValue.map(KS).join(', ')})\n\n`;
out += `    private val GIT_GLOBAL_VALUE_FLAGS: Set<String> = setOf(${dump.gitGlobal.value.map(KS).join(', ')})\n`;
fs.writeFileSync('/data/data/com.termux/files/home/perf-test/policy-tables3.kt', out);
console.log('生成:', out.split('\n').length, '行');

// 替换到 Kotlin 文件
const p = '/data/data/com.termux/files/home/ccm-android/app/src/main/java/com/ccm/app/tools/bash/ReadonlyPolicy.kt';
let src = fs.readFileSync(p, 'utf8');
const S = src.indexOf('    private val COMMAND_POLICIES: Map<String, Policy> = buildMap {');
const EM = '    private val GIT_GLOBAL_VALUE_FLAGS: Set<String> = setOf()';
const E = src.indexOf(EM);
if (S < 0 || E < 0) { console.error('定位失败', S, E); process.exit(1); }
src = src.slice(0, S) + out.trimEnd() + src.slice(E + EM.length);
fs.writeFileSync(p, src);
console.log('替换完成');
