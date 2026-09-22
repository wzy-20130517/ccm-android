/**
 * CCM Node 侧桥接 —— 把原生能力暴露给 Node 内核。
 *
 * 【两种运行模式】
 *
 * 模式 A：Node 跑在 proot Ubuntu 里（推荐）
 *   - Kotlin 启动 proot → node web/server.mjs
 *   - 原生调用走 HTTP 127.0.0.1:3457
 *   - 需要 rootfs 里装了 Node
 *
 * 模式 B：Node 跑在 Termux 里（过渡期）
 *   - 用户在 Termux 里 npm run web
 *   - 原生调用同样走 127.0.0.1:3457（CCM App 的桥）
 *   - 好处：不用等 rootfs 装好就能用
 *
 * 本文件负责：
 * 1. 探测原生桥是否可用
 * 2. 提供与原 tools-phone.mjs / termux-tools.mjs 兼容的工具实现
 * 3. 桥不可用时优雅降级（回退到 rish / termux-* 命令）
 */

import { hasNativeBridge, nativeCall, runtimeStatus } from './ccm-bridge.mjs'

/**
 * 环境探测 —— 决定用哪套工具实现。
 *
 * @returns {Promise<{native: boolean, mode: string, details: object}>}
 */
export async function detectEnvironment() {
  const native = await hasNativeBridge(true)

  if (native) {
    const status = await runtimeStatus()
    return {
      native: true,
      mode: 'ccm-native',
      details: {
        rootfs: status.rootfs_installed,
        accessibility: status.accessibility,
        proot: status.proot_exists,
        sdk: status.sdk,
      },
    }
  }

  // 回退：检查是不是在 Termux 里
  const inTermux = !!process.env.PREFIX?.includes('com.termux')
  return {
    native: false,
    mode: inTermux ? 'termux-legacy' : 'standalone',
    details: { prefix: process.env.PREFIX || null },
  }
}

/**
 * 环境诊断 —— 给用户看的报告。
 */
export async function diagnose() {
  const env = await detectEnvironment()
  const lines = []

  lines.push('## CCM 环境诊断')
  lines.push('')
  lines.push(`运行模式: **${env.mode}**`)
  lines.push('')

  if (env.native) {
    lines.push('| 组件 | 状态 |')
    lines.push('|---|---|')
    lines.push(`| Linux 环境 | ${env.details.rootfs ? '✅ 已安装' : '❌ 未安装'} |`)
    lines.push(`| proot | ${env.details.proot ? '✅ 就绪' : '❌ 缺失'} |`)
    lines.push(`| 无障碍服务 | ${env.details.accessibility ? '✅ 已开启' : '❌ 未开启'} |`)
    lines.push(`| Android SDK | ${env.details.sdk || '?'} |`)
    lines.push('')
    lines.push('手机操作走**无障碍服务**（原生，快且稳定）。')
  } else {
    lines.push('⚠️ 未检测到 CCM 原生桥（127.0.0.1:3457）')
    lines.push('')
    lines.push('可能原因：')
    lines.push('1. CCM App 未启动，或核心服务未开启')
    lines.push('2. 桥接端口被占用')
    lines.push('')
    if (env.mode === 'termux-legacy') {
      lines.push('当前在 Termux 里运行，将回退到 rish/Shizuku 方案。')
    }
  }

  return lines.join('\n')
}

/**
 * 工具实现的统一分发层。
 *
 * 调用方（工具定义）只写一次逻辑，这里根据环境选实现：
 *   - native 可用 → nativeCall
 *   - 否则 → 回退函数
 *
 * @param {string} nativeMethod 原生方法名
 * @param {object} params 参数
 * @param {Function} fallback 回退实现（async () => result）
 */
export async function callWithFallback(nativeMethod, params, fallback) {
  if (await hasNativeBridge()) {
    const r = await nativeCall(nativeMethod, params)
    if (r.ok) return { ok: true, output: r.message || r.output || '完成', _native: true }
    // 原生失败 → 尝试回退
    if (typeof fallback === 'function') {
      try {
        const f = await fallback()
        return { ...f, _fallback: true, _nativeError: r.error }
      } catch (e) {
        return { ok: false, error: `${r.error}（回退也失败: ${e.message}）` }
      }
    }
    return { ok: false, error: r.error }
  }

  if (typeof fallback === 'function') {
    return await fallback()
  }
  return { ok: false, error: `原生桥不可用，且没有回退实现: ${nativeMethod}` }
}

export default { detectEnvironment, diagnose, callWithFallback }
