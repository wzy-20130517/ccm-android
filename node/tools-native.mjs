/**
 * CCM 原生手机操作工具 —— 用无障碍服务替代 Shizuku/rish。
 *
 * 【与原 tools-phone.mjs 的区别】
 *
 * 原方案（Termux）：
 *   phone_snapshot → rish -c "uiautomator dump" → 读 XML 文件 → 正则解析 → 得到 ref
 *   phone_click    → 按 ref 找到 bounds → 算中心坐标 → rish -c "input tap x y"
 *   问题：慢（每次 2~5 秒）、依赖 Shizuku 存活、WebView/Canvas 拿不到内容
 *
 * 新方案（原生）：
 *   phone_snapshot → HTTP → 无障碍遍历节点树 → 直接返回结构化 JSON
 *   phone_click    → HTTP → node.performAction(ACTION_CLICK)
 *   优势：快（几十毫秒）、不依赖外部 App、节点树是结构化的
 *
 * 【兼容性】
 * 工具名、参数名、返回格式全部对齐原 tools-phone.mjs，
 * 所以 Agent 的提示词和调用习惯不用改。
 */

import { nativeCall, hasNativeBridge } from './ccm-bridge.mjs'

/** 统一的结果包装 */
function ok(output) { return { ok: true, output } }
function fail(error) { return { ok: false, error } }

/** 检查桥可用性，不可用时给出明确指引 */
async function requireBridge() {
  if (await hasNativeBridge()) return null
  return '原生桥未连接。请确认：① CCM 核心服务已启动 ② 无障碍服务已开启（设置 → 无障碍 → CCM）'
}

// ═══════════════════════════════════════════════════
//  工具定义
// ═══════════════════════════════════════════════════

export const nativePhoneTools = [
  {
    name: 'phone_snapshot',
    description: '获取当前手机屏幕的元素树（带 ref，用于后续点击/输入）。比截图快一个数量级，优先用它。',
    parameters: {
      type: 'object',
      properties: {
        interactive_only: { type: 'boolean', description: '只列可点击/可输入的元素（默认 true）' },
        max_nodes: { type: 'number', description: '最多返回多少个节点（默认 300）' },
      },
    },
    async execute(input = {}) {
      const err = await requireBridge()
      if (err) return fail(err)

      const r = await nativeCall('phone.snapshot', {
        interactive_only: input.interactive_only !== false,
        max_nodes: input.max_nodes || 300,
      })
      if (!r.ok) return fail(r.error)

      // 转成人类可读的文本（对齐原 phone_snapshot 的输出格式）
      const lines = [`包名: ${r.package}`, `元素数: ${r.count}`, '']
      for (const n of r.nodes || []) {
        const parts = [`[${n.ref}]`, n.cls]
        if (n.text) parts.push(`"${n.text}"`)
        if (n.desc) parts.push(`(${n.desc})`)
        if (n.id) parts.push(`#${n.id.split('/').pop()}`)
        parts.push(n.bounds)
        if (n.clickable) parts.push('可点击')
        if (n.editable) parts.push('可输入')
        if (n.scrollable) parts.push('可滚动')
        if (n.checked !== undefined) parts.push(n.checked ? '已选中' : '未选中')
        lines.push(parts.join(' '))
      }
      if (!r.count) lines.push('（没有找到可交互元素）')
      return ok(lines.join('\n'))
    },
  },

  {
    name: 'phone_click',
    description: '点击手机屏幕上的元素（按 phone_snapshot 给出的 ref）。',
    parameters: {
      type: 'object',
      properties: {
        ref: { type: 'string', description: '元素 ref，如 e12' },
        long_press: { type: 'boolean', description: '长按（默认 false）' },
      },
      required: ['ref'],
    },
    async execute(input = {}) {
      const err = await requireBridge()
      if (err) return fail(err)
      const r = await nativeCall('phone.click', {
        ref: input.ref,
        long_press: !!input.long_press,
      })
      return r.ok ? ok(r.message) : fail(r.error)
    },
  },

  {
    name: 'phone_tap_xy',
    description: '按绝对坐标点击屏幕。仅在拿不到 ref 时使用（如 Canvas、游戏界面）。',
    parameters: {
      type: 'object',
      properties: {
        x: { type: 'number', description: '横坐标' },
        y: { type: 'number', description: '纵坐标' },
        long_press: { type: 'boolean', description: '长按（默认 false）' },
      },
      required: ['x', 'y'],
    },
    async execute(input = {}) {
      const err = await requireBridge()
      if (err) return fail(err)
      const r = await nativeCall('phone.tap', {
        x: input.x, y: input.y,
        long_press: !!input.long_press,
      })
      return r.ok ? ok(r.message) : fail(r.error)
    },
  },

  {
    name: 'phone_type',
    description: '在手机上输入文本。先给 ref 聚焦输入框，再输入。',
    parameters: {
      type: 'object',
      properties: {
        text: { type: 'string', description: '要输入的文本' },
        ref: { type: 'string', description: '输入框的 ref（可选，不给则用当前焦点）' },
      },
      required: ['text'],
    },
    async execute(input = {}) {
      const err = await requireBridge()
      if (err) return fail(err)
      const r = await nativeCall('phone.type', {
        text: input.text,
        ref: input.ref || '',
      })
      return r.ok ? ok(r.message) : fail(r.error)
    },
  },

  {
    name: 'phone_swipe',
    description: '滑动屏幕：翻页、滚动列表、下拉刷新。',
    parameters: {
      type: 'object',
      properties: {
        direction: { type: 'string', description: 'up=内容上移（向下翻）/down/left/right' },
        duration: { type: 'number', description: '毫秒，默认 300' },
      },
    },
    async execute(input = {}) {
      const err = await requireBridge()
      if (err) return fail(err)
      const r = await nativeCall('phone.swipe', {
        direction: input.direction || 'up',
        duration: input.duration || 300,
      })
      return r.ok ? ok(r.message) : fail(r.error)
    },
  },

  {
    name: 'phone_key',
    description: '按系统按键：back/home/recent/notifications/quick_settings/lock/screenshot。',
    parameters: {
      type: 'object',
      properties: {
        key: { type: 'string', description: 'back|home|recent|notifications|quick_settings|lock|screenshot' },
      },
      required: ['key'],
    },
    async execute(input = {}) {
      const err = await requireBridge()
      if (err) return fail(err)
      const r = await nativeCall('phone.key', { key: input.key })
      return r.ok ? ok(r.message) : fail(r.error)
    },
  },

  {
    name: 'phone_app',
    description: '启动应用，或列出已安装应用。',
    parameters: {
      type: 'object',
      properties: {
        action: { type: 'string', description: 'launch（默认）| list | current' },
        package: { type: 'string', description: '包名，如 com.android.settings' },
        filter: { type: 'string', description: 'list 时按关键词过滤' },
      },
    },
    async execute(input = {}) {
      const err = await requireBridge()
      if (err) return fail(err)
      const r = await nativeCall('phone.app', {
        action: input.action || 'launch',
        package: input.package || '',
        filter: input.filter || '',
      })
      if (!r.ok) return fail(r.error)
      if (r.apps) return ok(r.apps.join('\n'))
      return ok(r.message)
    },
  },

  {
    name: 'phone_status',
    description: '检查手机操作能力是否就绪（无障碍服务状态）。',
    parameters: { type: 'object', properties: {} },
    async execute() {
      const r = await nativeCall('phone.status')
      return ok(r.message || JSON.stringify(r))
    },
  },
]

// ═══════════════════════════════════════════════════
//  系统能力工具（替代 termux-* 命令）
// ═══════════════════════════════════════════════════

export const nativeSystemTools = [
  {
    name: 'Notify',
    description: '发送 Android 系统通知。',
    parameters: {
      type: 'object',
      properties: {
        title: { type: 'string', description: '标题' },
        content: { type: 'string', description: '内容' },
      },
      required: ['title'],
    },
    async execute(input = {}) {
      const r = await nativeCall('sys.notify', {
        title: input.title, content: input.content || '',
      })
      return r.ok ? ok(r.message) : fail(r.error)
    },
  },
  {
    name: 'ClipboardGet',
    description: '读取系统剪贴板内容。',
    parameters: { type: 'object', properties: {} },
    async execute() {
      const r = await nativeCall('sys.clipboard.get')
      return r.ok ? ok(r.text || '（剪贴板为空）') : fail(r.error)
    },
  },
  {
    name: 'ClipboardSet',
    description: '写入文本到系统剪贴板。',
    parameters: {
      type: 'object',
      properties: { text: { type: 'string', description: '要写入的文本' } },
      required: ['text'],
    },
    async execute(input = {}) {
      const r = await nativeCall('sys.clipboard.set', { text: input.text })
      return r.ok ? ok(r.message) : fail(r.error)
    },
  },
  {
    name: 'Toast',
    description: '显示 Android Toast 短消息。',
    parameters: {
      type: 'object',
      properties: { text: { type: 'string', description: '提示内容' } },
      required: ['text'],
    },
    async execute(input = {}) {
      const r = await nativeCall('sys.toast', { text: input.text })
      return r.ok ? ok(r.message) : fail(r.error)
    },
  },
  {
    name: 'Vibrate',
    description: '让手机震动指定毫秒。',
    parameters: {
      type: 'object',
      properties: { duration: { type: 'number', description: '毫秒，默认 200' } },
    },
    async execute(input = {}) {
      const r = await nativeCall('sys.vibrate', { duration: input.duration || 200 })
      return r.ok ? ok(r.message) : fail(r.error)
    },
  },
  {
    name: 'Battery',
    description: '获取电池电量。',
    parameters: { type: 'object', properties: {} },
    async execute() {
      const r = await nativeCall('sys.battery')
      return r.ok ? ok(`电量: ${r.level}%`) : fail(r.error)
    },
  },
  {
    name: 'OpenUrl',
    description: '在浏览器中打开 URL。',
    parameters: {
      type: 'object',
      properties: { url: { type: 'string', description: '要打开的地址' } },
      required: ['url'],
    },
    async execute(input = {}) {
      const r = await nativeCall('sys.openUrl', { url: input.url })
      return r.ok ? ok(r.message) : fail(r.error)
    },
  },
  {
    name: 'Share',
    description: '通过系统分享菜单分享文本。',
    parameters: {
      type: 'object',
      properties: { text: { type: 'string', description: '要分享的文本' } },
      required: ['text'],
    },
    async execute(input = {}) {
      const r = await nativeCall('sys.share', { text: input.text })
      return r.ok ? ok(r.message) : fail(r.error)
    },
  },
]

export default { nativePhoneTools, nativeSystemTools }
