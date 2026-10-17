// Mock 独立的静态字体会话；只注册公开静态资源，不访问业务接口。
const { PORTFOLIO_TEXT_SELECTION_OPTIONS } = require('../../../utils/portfolio-text-typography')
const DEFAULT_CATALOG = require('./mock-portfolio-font-catalog')
const copy = value => JSON.parse(JSON.stringify(value || {}))
const LOAD_TIMEOUT_MS = 10000
// 与正式版一致，预览在 WebView 和 Skyline 中均可使用已选字体。
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

const FONT_URL = /^https:\/\/[^\s"'()]+\.woff$/
const SHA = /^[a-f0-9]{64}$/

/** 遍历组件与全部菜单；版本表仅在根对象维护。 */
function collectMockFontNodes(config) {
  const nodes = []
  function visit(value) {
    if (!value || typeof value !== 'object') return
    if (typeof value.fontId === 'string' && value.fontId) nodes.push(value)
    Object.entries(value).forEach(([key, child]) => { if (key !== 'fonts') visit(child) })
  }
  visit(config)
  return nodes
}

/** 一个页面一份实例，失败也保留尝试记录，卸载后的回调不再更新页面。 */
function createMockFontSession({wxApi, catalog = DEFAULT_CATALOG, onChange = () => {}} = {}) {
  const states = new Map()
  const queue = []
  let active = false
  const scopeOptions = fontScopeOptions(wxApi || {})
  let alive = true
  const supported = Boolean(wxApi && typeof wxApi.loadFontFace === 'function')
  const find = id => catalog.find(font => font.fontId === id)
  const valid = font => Boolean(font && font.fontVersion && font.assets && [400,700].every(weight => {
    const physical = font.physicalWeights && font.physicalWeights[weight]
    const asset = font.assets[physical]
    return [400,700].includes(physical) && asset && FONT_URL.test(asset.url) && SHA.test(asset.sha256)
  }))
  const identity = (font, weight) => `${font.fontId}:${font.fontVersion}:${weight}`
  const resourceKey = asset => `${asset.url}:${asset.sha256}`
  function options({expanded = false, selected = '', builtIn = {SYSTEM:true}, versions = {}} = {}) {
    const source=PORTFOLIO_TEXT_SELECTION_OPTIONS.slice()
    if(selected && !source.some(item=>item.value===selected))source.push({value:selected,label:selected,remote:true})
    return source.filter(item => expanded || !item.remote || item.value === selected).map(item => {
      if (!item.remote) return {...item, selectable:item.value === 'SYSTEM' || builtIn[item.value] === true,available:item.value === 'SYSTEM' || builtIn[item.value] === true,loadState:'ready'}
      const font = find(item.value)
      const resourceAvailable = supported && valid(font)
      const loadStates = Object.fromEntries([400,700].map(weight => {
        const asset=valid(font) && font.assets[font.physicalWeights[weight]]
        const state=asset && states.get(resourceKey(asset))
        return [weight,state?state.status:'idle']
      }))
      const loadState = loadStates[400]
      const version = versions[item.value] && versions[item.value].fontVersion
      const versionValid = Boolean(version && font && version === font.fontVersion)
      const versionInvalid = (Object.prototype.hasOwnProperty.call(versions,item.value) || item.value === selected) && !versionValid
      const selectable = resourceAvailable && !versionInvalid
      const hint = !valid(font) ? '静态字体暂不可用，当前使用系统字体' : !supported ? '当前设备不支持，当前使用系统字体' : versionInvalid ? '字体版本不可用，当前使用系统字体' : loadState === 'failed' ? '字体加载失败，当前使用系统字体' : loadState === 'loading' ? '字体加载中，当前使用系统字体' : ''
      return {...item,selectable,available:selectable,loadState,loadStates,hint,repairable:resourceAvailable && !versionValid,versionValid,sample:font && font.sample || item.sample,sampleUrl:font && font.sampleUrl || ''}
    })
  }
  function context(versions = {}) {
    const families = {}
    catalog.filter(valid).forEach(font => Object.entries(font.assets).forEach(([weight,asset]) => {
      const state = states.get(resourceKey(asset))
      if (state && state.status === 'ready') families[identity(font,weight)] = state.family
    }))
    return {versions:copy(versions),families}
  }
  // 与正式版一致逐个注册，避免安卓并发注册时部分字体回调超时。
  function pump() {
    if (!alive || active || !queue.length) return
    const { state, asset, weight } = queue.shift()
    active = true
    let finished = false
    const finish = status => {
      if (finished) return
      finished = true
      clearTimeout(timer)
      active = false
      state.status = status
      state.resolve()
      if (alive) onChange()
      pump()
    }
    const timer = setTimeout(() => finish('failed'), LOAD_TIMEOUT_MS)
    state.cancel = () => finish('failed')
    try {
      wxApi.loadFontFace({ family: state.family, source: `url("${asset.url}")`, global: false,
        ...scopeOptions, desc: { weight: String(weight), style: 'normal' },
        success: () => finish('ready'), fail: () => finish('failed') })
    } catch (_) { finish('failed') }
  }
  function load(config) {
    if (!alive || !supported) return Promise.resolve()
    const pending = []
    collectMockFontNodes(config).forEach(node => {
      const font = find(node.fontId)
      const version = config.fonts && config.fonts[node.fontId]
      if (!valid(font) || !version || version.fontVersion !== font.fontVersion) return
      const weight = font.physicalWeights[node.fontWeight === 'BOLD' ? 700 : 400]
      const asset = font.assets[weight], key = resourceKey(asset)
      if (states.has(key)) { pending.push(states.get(key).promise); return }
      const state = {status:'loading',family:`WF_MOCK_${asset.sha256}`}
      state.promise = new Promise(resolve => { state.resolve = resolve })
      states.set(key,state)
      queue.push({ state, asset, weight })
      if(alive)onChange()
      pump()
      pending.push(state.promise)
    })
    return Promise.all(pending)
  }
  /** 只为首次选择补版本；已固定旧版本由显式修复入口更新。 */
  function versions(config, previous = {}, {repair = ''} = {}) {
    const result = copy(previous)
    collectMockFontNodes(config).forEach(node => {
      const font = find(node.fontId)
      if (font && valid(font) && (!result[node.fontId] || repair === node.fontId)) result[node.fontId] = {fontVersion:font.fontVersion}
    })
    return result
  }
  return {options,context,load,versions,destroy(){
    alive = false
    queue.length = 0
    states.forEach(state => {
      if (state.cancel) state.cancel()
      else { state.status = 'failed'; state.resolve() }
    })
    states.clear()
  }}
}
module.exports = {createMockFontSession,collectMockFontNodes}
