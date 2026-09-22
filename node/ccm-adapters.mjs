/**
 * 原生桥适配层 —— 让现有工具在 CCM 原生环境下自动走无障碍服务。
 *
 * 【设计原则：包装而非重写】
 *
 * 现有 core/tools-phone.mjs 的实现是「rish + dumpsys/uiautomator」路线，
 * 它在 Termux 里工作良好。我们不想删掉它（Termux 模式还要用），
 * 也不想 fork 一份（两份代码会漂移）。
 *
 * 所以用**运行时适配**：
 *   1. 启动时探测原生桥
 *   2. 可用 → 用原生实现替换工具实例的 execute
 *   3. 不可用 → 保持原样（Termux 模式）
 *
 * 这样：
 *   - 同一份代码，两种环境都能跑
 *   - Agent 看到的工具名/参数/返回格式完全一致
 *   - 原生实现出问题时可以一键回退
 *
 * 【用法】
 *   import { applyNativeAdapters } from './ccm-adapters.mjs'
 *   const toolkit = createEngineToolkit(...)
 *   await applyNativeAdapters(toolkit)   // 原地替换，返回是否生效
 */

import { hasNativeBridge, nativeCall } from './ccm-bridge.mjs'

/** 标记：是否已应用过原生适配（幂等） */
let _applied = false

/**
 * 把 toolkit 里的 phone 工具替换成原生实现。
 *
 * @param {object} toolkit 引擎工具集（有 .tools() 方法返回工具数组）
 * @returns {Promise<{applied: boolean, replaced: string[], reason?: string}>}
 */
export async function applyNativeAdapters(toolkit) {
  if (_applied) {
    return { applied: true, replaced: [], reason: '已应用过' }
  }

  const available = await hasNativeBridge()
  if (!available) {
    return {
      applied: false,
      replaced: [],
      reason: '原生桥不可用（127.0.0.1:3457 无响应），保持 Termux 实现',
    }
  }

  const tools = typeof toolkit.tools === 'function' ? toolkit.tools() : []
  const replaced = []

  for (const tool of tools) {
    const impl = NATIVE_IMPLS[tool.name]
    if (!impl) continue

    // 保存原实现（便于回退 / 调试）
    tool._originalExecute = tool.execute
    tool.execute = impl
    replaced.push(tool.name)
  }

  _applied = true
  return { applied: true, replaced, reason: `已用原生实现替换 ${replaced.length} 个工具` }
}

/** 撤销适配（回退到原实现） */
export function revertNativeAdapters(toolkit) {
  const tools = typeof toolkit.tools === 'function' ? toolkit.tools() : []
  let n = 0
  for (const tool of tools) {
    if (tool._originalExecute) {
      tool.execute = tool._originalExecute
      delete tool._originalExecute
      n++
    }
  }
  _applied = false
  return n
}

// ═══════════════════════════════════════════════════
//  原生实现
// ═══════════════════════════════════════════════════

const NATIVE_IMPLS = {

  // ── 界面快照 ────────────────────────────────
  async phone_snapshot(input = {}) {
    const r = await nativeCall('phone.snapshot', {
      interactive_only: input.interactive_only !== false,
      max_nodes: input.max_nodes || 300,
    })
    if (!r.ok) return { ok: false, error: r.error }

    const lines = [`包名: ${r.package}`, `元素数: ${r.count}`, '']
    for (const n of r.nodes || []) {
      const parts = [`[${n.ref}]`, n.cls]
      if (n.text) parts.push(`"${n.text}"`)
      if (n.desc) parts.push(`(${n.desc})`)
      if (n.id) parts.push(`#${String(n.id).split('/').pop()}`)
      parts.push(n.bounds)
      if (n.clickable) parts.push('可点击')
      if (n.editable) parts.push('可输入')
      if (n.scrollable) parts.push('可滚动')
      if (n.checked !== undefined) parts.push(n.checked ? '已选中' : '未选中')
      lines.push(parts.join(' '))
    }
    if (!r.count) lines.push('（没有找到可交互元素）')
    return { ok: true, output: lines.join('\n') }
  },

  // ── 点击 ────────────────────────────────────
  async phone_click(input = {}) {
    const r = await nativeCall('phone.click', {
      ref: input.ref,
      long_press: !!input.long_press,
    })
    return r.ok ? { ok: true, output: r.message } : { ok: false, error: r.error }
  },

  async phone_tap_xy(input = {}) {
    const r = await nativeCall('phone.tap', {
      x: input.x, y: input.y,
      long_press: !!input.long_press,
    })
    return r.ok ? { ok: true, output: r.message } : { ok: false, error: r.error }
  },

  // ── 输入 ────────────────────────────────────
  async phone_type(input = {}) {
    const r = await nativeCall('phone.type', {
      text: input.text,
      ref: input.ref || '',
    })
    return r.ok ? { ok: true, output: r.message } : { ok: false, error: r.error }
  },

  // ── 滑动 / 按键 ─────────────────────────────
  async phone_swipe(input = {}) {
    const params = { duration: input.duration || 300 }
    if (input.direction) params.direction = input.direction
    if (input.x1 !== undefined) {
      params.x1 = input.x1; params.y1 = input.y1
      params.x2 = input.x2; params.y2 = input.y2
    }
    const r = await nativeCall('phone.swipe', params)
    return r.ok ? { ok: true, output: r.message } : { ok: false, error: r.error }
  },

  async phone_key(input = {}) {
    const r = await nativeCall('phone.key', { key: input.key })
    return r.ok ? { ok: true, output: r.message } : { ok: false, error: r.error }
  },

  // ── 应用 ────────────────────────────────────
  async phone_app(input = {}) {
    const r = await nativeCall('phone.app', {
      action: input.action || 'launch',
      package: input.package || '',
      filter: input.filter || '',
    })
    if (!r.ok) return { ok: false, error: r.error }
    if (r.apps) return { ok: true, output: r.apps.join('\n') }
    return { ok: true, output: r.message }
  },

  // ── 截图 ────────────────────────────────────
  async phone_screenshot(input = {}) {
    const r = await nativeCall('phone.screenshot', {
      save_path: input.save_path || '',
      quality: input.quality || 85,
    })
    if (!r.ok) return { ok: false, error: r.error }
    return { ok: true, output: `截图已保存: ${r.path}`, imagePath: r.path }
  },

  // ── 系统能力（原 termux-* 命令的替代）────────
  async Notify(input = {}) {
    const r = await nativeCall('sys.notify', {
      title: input.title || 'CCM',
      content: input.content || '',
    })
    return r.ok ? { ok: true, output: r.message } : { ok: false, error: r.error }
  },

  async ClipboardGet() {
    const r = await nativeCall('sys.clipboard.get')
    return r.ok ? { ok: true, output: r.text || '（剪贴板为空）' } : { ok: false, error: r.error }
  },

  async ClipboardSet(input = {}) {
    const r = await nativeCall('sys.clipboard.set', { text: input.text })
    return r.ok ? { ok: true, output: r.message } : { ok: false, error: r.error }
  },

  async Toast(input = {}) {
    const r = await nativeCall('sys.toast', { text: input.text })
    return r.ok ? { ok: true, output: r.message } : { ok: false, error: r.error }
  },

  async Vibrate(input = {}) {
    const r = await nativeCall('sys.vibrate', { duration: input.duration || 200 })
    return r.ok ? { ok: true, output: r.message } : { ok: false, error: r.error }
  },

  async Battery() {
    const r = await nativeCall('sys.battery')
    return r.ok ? { ok: true, output: `电量: ${r.level}%` } : { ok: false, error: r.error }
  },

  async OpenUrl(input = {}) {
    const r = await nativeCall('sys.openUrl', { url: input.url })
    return r.ok ? { ok: true, output: r.message } : { ok: false, error: r.error }
  },

  async Share(input = {}) {
    const r = await nativeCall('sys.share', { text: input.text })
    return r.ok ? { ok: true, output: r.message } : { ok: false, error: r.error }
  },

  async TTS(input = {}) {
    const r = await nativeCall('sys.tts', {
      text: input.text,
      flush: input.flush !== false,
      rate: input.rate || 1.0,
      pitch: input.pitch || 1.0,
    })
    return r.ok ? { ok: true, output: r.message } : { ok: false, error: r.error }
  },
}

export default { applyNativeAdapters, revertNativeAdapters }
