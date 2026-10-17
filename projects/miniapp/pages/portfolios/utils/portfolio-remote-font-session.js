// 个人与团队分包保留同源副本；仅保存页面运行态，不修改作品集配置。
let instanceSequence = 0
const moduleIdentity = Math.random().toString(36).slice(2, 10)
const LOAD_TIMEOUT_MS = 10000
// 安卓真机并发注册会出现部分回调超时；逐个注册已通过三款字体实测。
const CONCURRENCY = 1
// 开发者工具及兼容设备可能使用 WebView，两种渲染器都必须注册字体。
const FONT_SCOPES = Object.freeze(['webview', 'skyline'])
// 3.7.9 起默认覆盖全部渲染器；显式 scopes 在部分开发工具 WebView 中会挂起注册。
const DEFAULT_ALL_SCOPES_VERSION = [3, 7, 9]
function fontScopeOptions(api) {
  try {
    const info = typeof api.getAppBaseInfo === 'function' ? api.getAppBaseInfo()
      : typeof api.getSystemInfoSync === 'function' ? api.getSystemInfoSync() : {}
    const match = /^(\d+)\.(\d+)\.(\d+)$/.exec(info.SDKVersion || '')
    if (match) {
      const parts = match.slice(1).map(Number)
      const different = parts.findIndex((value, index) => value !== DEFAULT_ALL_SCOPES_VERSION[index])
      if (different < 0 || parts[different] > DEFAULT_ALL_SCOPES_VERSION[different]) return {}
    }
  } catch (_) { /* 无法识别基础库时保留显式作用域，兼容旧 Skyline。 */ }
  return { scopes: [...FONT_SCOPES] }
}

const { resolvePortfolioFontWeight } = require('../../../utils/portfolio-text-typography.js')

function createRemoteFontSession(api, onChange = () => {}) {
  const instance = `${moduleIdentity}_${++instanceSequence}`
  const scopeOptions = fontScopeOptions(api)
  const attempts = new Map()
  const pending = []
  let alive = true
  let active = 0
  let familySequence = 0
  let revision = 0
  let epoch = 0
  let families = {}
  let versions = {}
  let failed = []
  function context() { return { families: { ...families }, versions: { ...versions }, failed: [...failed], revision } }
  function pump() {
    while (alive && active < CONCURRENCY && pending.length) {
      const task = pending.shift()
      active++
      const state = task.state
      let settled = false
      const finish = success => {
        if (settled) return
        settled = true
        clearTimeout(state.timer)
        active--
        state.status = success ? 'ready' : 'failed'
        state.resolve()
        pump()
      }
      state.finish = finish
      state.timer = setTimeout(() => finish(false), LOAD_TIMEOUT_MS)
      try {
        api.loadFontFace({ family: state.family, source: `url("${task.asset.url}")`, global: false,
          ...scopeOptions, desc: { style: 'normal', weight: String(task.asset.fontWeight || 400) },
          success: () => finish(true), fail: () => finish(false) })
      } catch (_) { finish(false) }
    }
  }

  const assetKey = asset => [asset.assetId, asset.subsetHash, asset.url].join('|')
  function attempt(asset) {
    const key = assetKey(asset)
    if (attempts.has(key)) return attempts.get(key)
    const state = { status: 'pending', family: `WF_${instance}_${++familySequence}` }
    state.promise = new Promise(resolve => { state.resolve = resolve })
    attempts.set(key, state)
    pending.push({ asset, state })
    pump()
    return state
  }

  async function load(manifest, nodes = []) {
    const current = ++epoch
    versions = manifest && manifest.versions || {}
    families = {}
    failed = []
    revision++
    if (!alive) return context()
    manifest = manifest || {}
    const requested = new Set(nodes.filter(node => node && node.fontId).map(node =>
      `${node.fontId}:${resolvePortfolioFontWeight(node.fontId, (versions[node.fontId] || {}).fontVersion, node.fontWeight === 'BOLD' ? 700 : 400)}`))
    const assets = (manifest.assets || []).filter(asset => asset && asset.status === 'READY'
      && requested.has(`${asset.fontId}:${asset.fontWeight || 400}`) && versions[asset.fontId]
      && versions[asset.fontId].fontVersion === asset.fontVersion
      && typeof asset.url === 'string' && /^https:\/\/[^\s"'()]+$/.test(asset.url))
    // 取消或切换候选后只移除未开始的需求；在途注册无法撤销，由 epoch 丢弃回调。
    const demanded = new Set(assets.map(assetKey))
    for (let index = pending.length - 1; index >= 0; index--) {
      const task = pending[index]
      if (demanded.has(assetKey(task.asset))) continue
      pending.splice(index, 1)
      attempts.delete(assetKey(task.asset))
      task.state.status = 'cancelled'
      task.state.resolve()
    }
    await Promise.all(assets.map(async asset => {
      const state = attempt(asset)
      await state.promise
      if (!alive || current !== epoch) return
      if (state.status !== 'ready') { failed.push(asset.fontId); onChange(context()); return }
      families[`${asset.fontId}:${asset.fontVersion}:${asset.fontWeight || 400}`] = state.family
      revision++
      onChange(context())
    }))
    return context()
  }

  function dispose() {
    alive = false
    epoch++
    families = {}
    pending.splice(0).forEach(task => { task.state.status = 'failed'; task.state.resolve() })
    attempts.forEach(state => { if (state.finish) state.finish(false) })
  }
  return { load, context, dispose }
}

/** 只从正文、结构化块和网格片段收集节点，忽略标题等非适用文字。 */
function collectFontNodes(components = []) {
  const result = []
  components.forEach(component => {
    if (!component || component.enabled === false) return
    const type = component.componentType || component.type
    const rendered = { TEXT_SECTION: 'textSection', STRUCTURED_TEXT_SECTION: 'structuredTextSection', TEXT_GRID: 'textGrid' }
    const config = component[rendered[type]] || component.config || component.data || {}
    if (type === 'TEXT_SECTION') result.push(config)
    if (type === 'STRUCTURED_TEXT_SECTION') (config.blocks || []).forEach(block => {
      if (block.type !== 'SPACER') result.push(block)
    })
    if (type === 'TEXT_GRID') (config.cells || []).forEach(cell => (cell.blocks || []).forEach(block => result.push(...(block.runs || []))))
  })
  return result
}

module.exports = { createRemoteFontSession, collectFontNodes }
