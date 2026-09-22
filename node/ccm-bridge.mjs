/**
 * CCM 原生桥客户端 —— Node 内核调用 Kotlin 原生能力的统一入口。
 *
 * 【架构】
 *   Node（跑在 proot 里）
 *     ↓ HTTP POST 127.0.0.1:3457/native/call
 *   Kotlin（CcmService 的桥接服务器）
 *     ↓ 直接调
 *   无障碍服务 / 系统 API
 *
 * 【为什么是 127.0.0.1:3457】
 * proot 里没有网络隔离（默认共享宿主网络栈），所以直接连宿主 localhost 就行。
 * 3456 是 Node 自己的 web server，3457 是 Kotlin 的桥。
 *
 * 【用法】
 *   import { nativeCall, hasNativeBridge } from './ccm-bridge.mjs'
 *
 *   if (await hasNativeBridge()) {
 *     const r = await nativeCall('phone.snapshot', { interactive_only: true })
 *     // r = { ok: true, nodes: [...] }
 *   }
 */

const BRIDGE_HOST = process.env.CCM_BRIDGE_HOST || '127.0.0.1'
const BRIDGE_PORT = Number(process.env.CCM_BRIDGE_PORT || 3457)
const BRIDGE_URL = `http://${BRIDGE_HOST}:${BRIDGE_PORT}`
const TIMEOUT_MS = 30000

/** 桥是否可用（带缓存，避免每次工具调用都探测） */
let _available = null
let _lastCheck = 0
const CHECK_TTL = 10000   // 10 秒内不重复探测

/**
 * 检测原生桥是否可用。
 * @param {boolean} force 强制重新探测
 */
export async function hasNativeBridge(force = false) {
  const now = Date.now()
  if (!force && _available !== null && now - _lastCheck < CHECK_TTL) {
    return _available
  }
  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), 3000)
    const res = await fetch(`${BRIDGE_URL}/ping`, { signal: ctrl.signal })
    clearTimeout(timer)
    _available = res.ok
  } catch {
    _available = false
  }
  _lastCheck = now
  return _available
}

/**
 * 调用原生方法。
 *
 * @param {string} method 方法名，如 'phone.snapshot'
 * @param {object} params 参数
 * @returns {Promise<object>} 结果对象（失败时含 { ok:false, error }）
 */
export async function nativeCall(method, params = {}) {
  try {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS)
    const res = await fetch(`${BRIDGE_URL}/native/call`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ method, params }),
      signal: ctrl.signal,
    })
    clearTimeout(timer)
    if (!res.ok) {
      return { ok: false, error: `桥接服务返回 HTTP ${res.status}` }
    }
    return await res.json()
  } catch (e) {
    if (e.name === 'AbortError') {
      return { ok: false, error: `原生调用超时（${TIMEOUT_MS}ms）: ${method}` }
    }
    return { ok: false, error: `原生调用失败: ${e.message}` }
  }
}

/** 拿运行时状态（环境是否就绪） */
export async function runtimeStatus() {
  return nativeCall('runtime.status')
}

/**
 * 把 nativeCall 的结果转成工具返回值格式。
 * 成功 → { ok: true, output: "..." }
 * 失败 → { ok: false, error: "..." }
 */
export function toToolResult(r, okField = 'message') {
  if (r && r.ok) {
    return { ok: true, output: r[okField] || r.output || '完成' }
  }
  return { ok: false, error: (r && r.error) || '未知错误' }
}

export default { hasNativeBridge, nativeCall, runtimeStatus, toToolResult }
