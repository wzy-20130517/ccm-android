#!/usr/bin/env node
/**
 * 策略表再生成流水线（4 步）
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么需要这个
 * ══════════════════════════════════════════════════════════════
 *
 * APK 侧的只读策略表（ReadonlyPolicy.kt 里的 108 条命令策略 +
 * 1882 条安全 flag）是**从 CLI 侧脚本生成的**，不是手抄的。
 *
 * 理由：
 *   1. 1882 条 flag 手抄必然出错，且错在哪看不出来
 *   2. CLI 侧策略表更新后，APK 侧需要能**重新生成**而不是重新抄
 *
 * ══════════════════════════════════════════════════════════════
 *  怎么用
 * ══════════════════════════════════════════════════════════════
 *
 * ```bash
 * cd ~/ccm-android/tools/bash-policy-gen
 * node run.mjs                    # 全流程：dump → 生成 → 打补丁 → 验证
 * node run.mjs --verify-only      # 只验证现有 Kotlin 表与 CLI 是否一致
 * ```
 *
 * 前置条件：
 *   · CLI 侧代码在 `~/claude-code-mobile/core/permission/bash-ast/policy/`
 *   · APK 侧 ReadonlyPolicy.kt 在 `~/ccm-android/app/src/main/java/com/ccm/app/tools/bash/`
 *
 * ══════════════════════════════════════════════════════════════
 *  改了 CLI 策略表之后
 * ══════════════════════════════════════════════════════════════
 *
 * 跑一次 `node run.mjs`，它会：
 *   1. 从 CLI 的 policy/*.mjs 导出全部策略数据
 *   2. 生成 Kotlin 的 buildMap 块
 *   3. 用生成的块**替换** ReadonlyPolicy.kt 里对应的段（保留其余代码）
 *   4. 逐条比对，输出一致性报告
 *
 * 之后**必须**重跑 Kotlin 测试（tests 里的用例），因为策略表变了行为就变了。
 */

import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const CLI_POLICY_DIR = join(process.env.HOME, 'claude-code-mobile/core/permission/bash-ast/policy')
const KT_PATH = join(process.env.HOME, 'ccm-android/app/src/main/java/com/ccm/app/tools/bash/ReadonlyPolicy.kt')
const DUMP_PATH = join(HERE, 'policy-dump.json')

const verifyOnly = process.argv.includes('--verify-only')

main()

async function main() {
  if (!existsSync(CLI_POLICY_DIR)) {
    fail(`找不到 CLI 策略目录：${CLI_POLICY_DIR}`)
  }
  if (!existsSync(KT_PATH)) {
    fail(`找不到 Kotlin 策略文件：${KT_PATH}`)
  }

  if (!verifyOnly) {
    console.log('① 从 CLI 导出策略数据…')
    const dump = await dumpCliPolicy()
    writeFileSync(DUMP_PATH, JSON.stringify(dump, null, 1))
    reportDump(dump)

    console.log('\n② 生成 Kotlin 策略表…')
    const tables = genKotlinTables(dump)

    console.log('\n③ 打补丁到 ReadonlyPolicy.kt…')
    patchKotlin(tables)
  } else {
    if (!existsSync(DUMP_PATH)) fail(`--verify-only 需要先跑一次完整流程（缺 ${DUMP_PATH}）`)
  }

  console.log('\n④ 一致性验证…')
  const dump = JSON.parse(readFileSync(DUMP_PATH, 'utf8'))
  const ok = verifyKotlin(dump)
  process.exit(ok ? 0 : 1)
}

// ══════════════════════════════════════════════════════════════
//  ① dump
// ══════════════════════════════════════════════════════════════

async function dumpCliPolicy() {
  const c = await import(join(CLI_POLICY_DIR, 'commands.mjs'))
  const toObj = (coll) => {
    const out = {}
    for (const [k, v] of (coll instanceof Map ? coll : Object.entries(coll))) {
      const e = {}
      if (v.allowAnyArgs) e.allowAnyArgs = true
      if (v.commandOnly) e.commandOnly = true
      if (v.allowCompactNumericCountFlag) e.compactCount = true
      if (v.respectsDoubleDash === false) e.noDoubleDash = true
      if (v.regex) e.regex = v.regex.source
      if (v.safeFlags) e.safeFlags = v.safeFlags
      if (v.additionalCommandIsDangerousCallback) {
        const n = v.additionalCommandIsDangerousCallback.name
        // 内联箭头函数没有有意义的 name（dump 出来是 additionalCommandIsDangerousCallback）
        e.cb = n === 'additionalCommandIsDangerousCallback' ? 'INLINE' : n
      }
      out[k] = e
    }
    return out
  }
  return {
    commands: toObj(c.READONLY_COMMAND_POLICIES),
    gitSub: toObj(c.GIT_READONLY_SUBCOMMAND_POLICIES),
    multiword: toObj(c.READONLY_MULTIWORD_COMMAND_POLICIES),
    allowAnyArg: [...c.READONLY_ALLOW_ANY_ARG_COMMANDS],
    allowAnyArgPrefixes: c.READONLY_ALLOW_ANY_ARG_COMMAND_PREFIXES,
    gitGlobal: {
      dangerous: [...c.GIT_GLOBAL_DANGEROUS_FLAGS],
      noValue: [...c.GIT_GLOBAL_NO_VALUE_FLAGS],
      value: [...c.GIT_GLOBAL_VALUE_FLAGS],
    },
  }
}

function reportDump(d) {
  const n = (g) => Object.keys(d[g]).length
  let flags = 0
  for (const g of ['commands', 'gitSub', 'multiword']) {
    for (const k of Object.keys(d[g])) flags += Object.keys(d[g][k].safeFlags ?? {}).length
  }
  console.log(`   命令策略 ${n('commands')} 条 · git 子命令 ${n('gitSub')} 条 · 多词 ${n('multiword')} 条`)
  console.log(`   任意参数命令 ${d.allowAnyArg.length} 条 · 安全 flag ${flags} 条`)
}

// ══════════════════════════════════════════════════════════════
//  ② 生成 Kotlin
// ══════════════════════════════════════════════════════════════

const KS = (s) => '"' + String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\$/g, '\\$') + '"'

function entryKt(v) {
  const parts = []
  if (v.allowAnyArgs) parts.push('allowAnyArgs = true')
  if (v.commandOnly) parts.push('commandOnly = true')
  if (v.compactCount) parts.push('allowCompactNumericCountFlag = true')
  if (v.noDoubleDash) parts.push('respectsDoubleDash = false')
  if (v.regex) parts.push('regex = ' + KS(v.regex))
  if (v.safeFlags) parts.push('safeFlagsJson = ' + KS(JSON.stringify(v.safeFlags)))
  if (v.cb) parts.push('callback = Callbacks::' + (v.cb === 'INLINE' ? 'gitRemoteCommandIsDangerous' : v.cb))
  return 'Policy(' + parts.join(', ') + ')'
}

function genKotlinTables(dump) {
  let out = ''
  const groups = [
    ['COMMAND_POLICIES', 'commands'],
    ['GIT_SUBCOMMAND_POLICIES', 'gitSub'],
    ['MULTIWORD_POLICIES', 'multiword'],
  ]
  for (const [name, g] of groups) {
    out += `    private val ${name}: Map<String, Policy> = buildMap {\n`
    for (const [k, v] of Object.entries(dump[g])) out += `        put(${KS(k)}, ${entryKt(v)})\n`
    out += '    }\n\n'
  }

  out += '    private val ALLOW_ANY_ARG_COMMANDS: Set<String> = setOf(\n'
  // 注意：CLI 侧 dump 出来的集合里有 "cmp, column" 这种被逗号粘在一起的脏数据，
  // 这里拆开（真实意图是两个独立命令）。见简报「发现的数据问题」。
  const aaa = [...new Set(dump.allowAnyArg.flatMap((s) => s.split(',').map((x) => x.trim())))].sort()
  for (let i = 0; i < aaa.length; i += 5) out += '        ' + aaa.slice(i, i + 5).map(KS).join(', ') + ',\n'
  out += '    )\n\n'

  out += '    private val ALLOW_ANY_ARG_PREFIXES: List<List<String>> = listOf(\n'
  for (const p of dump.allowAnyArgPrefixes) out += `        listOf(${p.map(KS).join(', ')}),\n`
  out += '    )\n\n'

  out += '    private val GIT_GLOBAL_DANGEROUS_FLAGS: Set<String> = setOf(\n'
  const dg = dump.gitGlobal.dangerous
  for (let i = 0; i < dg.length; i += 5) out += '        ' + dg.slice(i, i + 5).map(KS).join(', ') + ',\n'
  out += '    )\n\n'
  out += `    private val GIT_GLOBAL_NO_VALUE_FLAGS: Set<String> = setOf(${dump.gitGlobal.noValue.map(KS).join(', ')})\n\n`
  out += `    private val GIT_GLOBAL_VALUE_FLAGS: Set<String> = setOf(${dump.gitGlobal.value.map(KS).join(', ')})\n`
  return out
}

// ══════════════════════════════════════════════════════════════
//  ③ 打补丁
// ══════════════════════════════════════════════════════════════

function patchKotlin(tables) {
  let src = readFileSync(KT_PATH, 'utf8')
  const S = src.indexOf('    private val COMMAND_POLICIES: Map<String, Policy> = buildMap {')
  const EM = '    private val GIT_GLOBAL_VALUE_FLAGS: Set<String> = setOf()'
  const E = src.indexOf(EM)
  if (S < 0) fail('在 ReadonlyPolicy.kt 里找不到 COMMAND_POLICIES 段的起点')
  if (E < 0) fail('在 ReadonlyPolicy.kt 里找不到 GIT_GLOBAL_VALUE_FLAGS 段的终点（占位符）')

  const before = src.length
  src = src.slice(0, S) + tables.trimEnd() + src.slice(E + EM.length)
  writeFileSync(KT_PATH, src)
  console.log(`   ReadonlyPolicy.kt：${before} → ${src.length} 字节`)
}

// ══════════════════════════════════════════════════════════════
//  ④ 验证
// ══════════════════════════════════════════════════════════════

function verifyKotlin(dump) {
  const src = readFileSync(KT_PATH, 'utf8')
  const puts = {}
  for (const line of src.split('\n')) {
    const m = line.match(/^\s*put\("((?:[^"\\]|\\.)*)", Policy\((.*)\)\)\s*$/)
    if (!m) continue
    const key = m[1].replace(/\\"/g, '"')
    const body = m[2]
    // Kotlin 字符串字面量的反转义：\X → X（Kotlin 里 "\\s" 表示 \s）
    const unescape = (s) => s.replace(/\\(.)/g, '$1')
    let flags = null
    const fm = body.match(/safeFlagsJson = "((?:[^"\\]|\\.)*)"/)
    if (fm) flags = unescape(fm[1])
    const rm = body.match(/regex = "((?:[^"\\]|\\.)*)"/)
    puts[key] = {
      flags,
      allowAnyArgs: /allowAnyArgs = true/.test(body),
      commandOnly: /commandOnly = true/.test(body),
      compactCount: /allowCompactNumericCountFlag = true/.test(body),
      noDoubleDash: /respectsDoubleDash = false/.test(body),
      regex: rm ? unescape(rm[1]) : null,
      cb: (body.match(/Callbacks::(\w+)/) || [])[1] ?? null,
    }
  }

  let ok = 0
  const bad = []
  const miss = []
  for (const g of ['commands', 'gitSub', 'multiword']) {
    for (const [k, v] of Object.entries(dump[g])) {
      const p = puts[k]
      if (!p) { miss.push(k); continue }
      // INLINE 回调在 Kotlin 侧有具体函数名（gitRemoteCommandIsDangerous），
      // 比对时把两边都归一化成一个标记。
      const expectCb = v.cb === 'INLINE' ? 'gitRemoteCommandIsDangerous' : (v.cb ?? null)
      const checks = [
        [!!v.allowAnyArgs, p.allowAnyArgs, 'allowAnyArgs'],
        [!!v.commandOnly, p.commandOnly, 'commandOnly'],
        [!!v.compactCount, p.compactCount, 'compactCount'],
        [!!v.noDoubleDash, p.noDoubleDash, 'noDoubleDash'],
        [v.regex ?? null, p.regex, 'regex'],
        [expectCb, p.cb, 'callback'],
      ]
      let badOne = null
      for (const [expect, got, label] of checks) {
        if (expect !== got) { badOne = `${label}: 期望 ${JSON.stringify(expect)} 实际 ${JSON.stringify(got)}`; break }
      }
      if (!badOne && v.safeFlags) {
        if (p.flags !== JSON.stringify(v.safeFlags)) badOne = 'safeFlags 内容不一致'
      }
      if (badOne) bad.push(`${k} → ${badOne}`)
      else ok++
    }
  }

  const total = ok + bad.length + miss.length
  console.log(`   ✅ ${ok} / ${total} 条完全一致`)
  if (miss.length) console.log(`   ⚠ 缺失：${miss.join(', ')}`)
  if (bad.length) {
    console.log(`   ⚠ ${bad.length} 条不一致：`)
    for (const b of bad.slice(0, 20)) console.log(`      ${b}`)
    if (bad.length > 20) console.log(`      …还有 ${bad.length - 20} 条`)
  }

  // 集合类校验
  const seg = src.match(/private val ALLOW_ANY_ARG_COMMANDS[\s\S]*?\n    \)/)
  if (seg) {
    const got = [...seg[0].matchAll(/"([^"]+)"/g)].map((s) => s[1])
    const expect = [...new Set(dump.allowAnyArg.flatMap((s) => s.split(',').map((x) => x.trim())))]
    const missing = expect.filter((x) => !got.includes(x))
    const extra = got.filter((x) => !expect.includes(x))
    if (missing.length || extra.length) {
      console.log(`   ⚠ ALLOW_ANY_ARG_COMMANDS 集合不一致（缺 ${missing.length} / 多 ${extra.length}）`)
      bad.push('ALLOW_ANY_ARG_COMMANDS')
    } else {
      console.log(`   ✅ ALLOW_ANY_ARG_COMMANDS ${got.length} 条一致`)
    }
  }

  return bad.length === 0 && miss.length === 0
}

function fail(msg) {
  console.error(`✗ ${msg}`)
  process.exit(1)
}
