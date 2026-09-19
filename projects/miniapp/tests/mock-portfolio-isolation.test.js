const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '..')
const STORAGE_KEY = 'wefolio_mock_portfolio_draft'
const MOCK_ROOTS = ['pages/mock/', 'components/mock/']
// 冻结原有纯展示依赖；新业务模块不能经组件注册或传递依赖绕过隔离检查。
const DISPLAY_BASELINE = new Set([
  ...['navigation-bar', 'portfolio-bottom-nav', 'portfolio-carousel'].flatMap(name =>
    ['js', 'json', 'wxml', 'wxss'].map(ext => `components/${name}/${name}.${ext}`)),
  ...['portfolio-editor-foundation', 'portfolio-menu-transition', 'portfolio-text-section-editor',
    'portfolio-text-typography', 'portfolio-theme'].map(name => `styles/${name}.wxss`),
  'utils/navigation-bar-layout.js', 'utils/portfolio-text-typography.js'
])
const TYPES = ['CAROUSEL', 'PROFILE', 'SCHEDULE_QUERY', 'WORK_GRID', 'WORK_LIST', 'SINGLE_WORK',
  'QR_CONTACT', 'CONTACT_FORM', 'TEXT_SECTION', 'DIVIDER', 'VIDEO_CAROUSEL',
  'STRUCTURED_TEXT_SECTION', 'TEXT_GRID', 'CONTACT_INFO', 'HYPERLINK']
const event = (dataset = {}, value) => ({ currentTarget: { dataset }, detail: { value } })
const copy = value => JSON.parse(JSON.stringify(value))
const isMock = relative => MOCK_ROOTS.some(prefix => relative.startsWith(prefix))

function filesIn(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry =>
    entry.isDirectory() ? filesIn(path.join(directory, entry.name)) : [path.join(directory, entry.name)])
}

test('mock 注册组件、样式及 JS 的完整依赖闭包仅包含冻结展示基线', () => {
  const pending = MOCK_ROOTS.flatMap(dir => filesIn(path.join(ROOT, dir)))
    .filter(file => /\.(js|json|wxml|wxss)$/.test(file))
  const visited = new Set()
  const enqueue = (file, target, suffix = '') => {
    const resolved = target.startsWith('/') ? path.join(ROOT, target) : path.resolve(path.dirname(file), target)
    assert.ok(fs.existsSync(resolved + suffix), `${file}: ${target}${suffix}`)
    pending.push(resolved + suffix)
  }
  while (pending.length) {
    const file = pending.pop()
    if (visited.has(file)) continue
    visited.add(file)
    const relative = path.relative(ROOT, file)
    assert.ok(isMock(relative) || DISPLAY_BASELINE.has(relative), `未经允许的依赖：${relative}`)
    const source = fs.readFileSync(file, 'utf8')
    assert.doesNotMatch(source, /wx\.(?:request|uploadFile|login)\s*\(|utils\/(?:request|session|visitor-session|avatar|team-avatar)|\/api\/|wefolio_token/, relative)
    if (file.endsWith('.js')) {
      for (const match of source.matchAll(/require\(['"]([^'"]+)['"]\)/g)) {
        const resolved = path.resolve(path.dirname(file), match[1])
        if (relative.startsWith('components/')) assert.ok(!resolved.includes('/pages/'), `组件反向依赖分包：${relative}`)
        enqueue(file, match[1], '.js')
      }
    }
    if (file.endsWith('.json')) {
      for (const target of Object.values(JSON.parse(source).usingComponents || {})) {
        for (const ext of ['.js', '.json', '.wxml', '.wxss']) enqueue(file, target, ext)
      }
    }
    if (file.endsWith('.wxss')) {
      for (const match of source.matchAll(/@import\s+['"]([^'"]+)['"]/g)) enqueue(file, match[1])
    }
    if (file.endsWith('.wxml')) {
      for (const match of source.matchAll(/<(?:import|include)\b[^>]*src=['"]([^'"]+)['"]/g)) enqueue(file, match[1])
    }
  }
  assert.deepEqual(new Set([...visited].map(file => path.relative(ROOT, file)).filter(file => !isMock(file))), DISPLAY_BASELINE)
})

/** 在同一个隔离运行时加载页面及本地依赖，正式缓存和后端能力一旦触达即失败。 */
function createRuntime() {
  const storage = new Map(), calls = [], modules = new Map()
  let definition
  const forbidden = () => { throw new Error('mock 不允许访问后端或身份能力') }
  const checkKey = key => assert.equal(key, STORAGE_KEY)
  const wx = {
    request: forbidden, uploadFile: forbidden, login: forbidden,
    getStorageSync(key) { checkKey(key); return storage.get(key) },
    setStorageSync(key, value) { checkKey(key); storage.set(key, copy(value)) },
    removeStorageSync(key) { checkKey(key); storage.delete(key) },
    showToast(options) { calls.push(options.title) },
    navigateTo(options) { assert.match(options.url, /^\/pages\/mock\//) },
    createVideoContext() { return { pause() {} } },
    getWindowInfo() { return { windowWidth: 375 } }
  }
  const context = vm.createContext({ wx, Page: value => { definition = value }, setTimeout, clearTimeout })
  function load(file) {
    if (modules.has(file)) return modules.get(file).exports
    const relative = path.relative(ROOT, file)
    assert.ok(isMock(relative) || DISPLAY_BASELINE.has(relative), relative)
    const module = { exports: {} }
    modules.set(file, module)
    const factory = vm.runInContext(`(function(require,module,exports){${fs.readFileSync(file, 'utf8')}\n})`, context, { filename: file })
    factory(target => load(path.resolve(path.dirname(file), target) + '.js'), module, module.exports)
    return module.exports
  }
  return { calls, storage, page(name) {
    const file = path.join(ROOT, `pages/mock/${name}/${name}.js`)
    modules.delete(file)
    load(file)
    return { ...definition, data: copy(definition.data), setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback() } }
  } }
}

for (const type of TYPES) {
  test(`${type} 创建、编辑、确认、预览和返回均只使用 mock 草稿`, () => {
    const runtime = createRuntime()
    const edit = runtime.page('portfolio-standard-edit')
    edit.onLoad()
    if (type === 'PROFILE') {
      const existing = edit.data.activeComponents.find(item => item.componentType === 'PROFILE')
      edit.handleRemoveComponent(event({ key: existing.componentKey }))
    }
    edit.handleSelectComponent(event({ type }))
    const key = edit.data.selectedComponentKey
    const original = copy(edit.data.componentConfig)
    if (type === 'SINGLE_WORK') edit.handleWorkToggle(event({ id: 109 }))
    else if (type === 'CAROUSEL') edit.handleWorkOrder(event({ index: 0, offset: 1 }))
    else if (type === 'QR_CONTACT') edit.handleQrWork(event({ id: 101 }))
    else if (type === 'TEXT_GRID') {
      const config = copy(edit.data.complexConfig)
      config.cells[0].blocks[0].runs[0].text = '本地网格编辑'
      edit.handleGridChange({ detail: { config } })
    } else if (['VIDEO_CAROUSEL', 'STRUCTURED_TEXT_SECTION', 'CONTACT_INFO', 'HYPERLINK'].includes(type)) {
      const config = copy(edit.data.complexConfig)
      if (type === 'VIDEO_CAROUSEL') config.workIds.reverse()
      if (type === 'STRUCTURED_TEXT_SECTION') config.blocks[0].content = '本地结构化编辑'
      if (type === 'CONTACT_INFO') config.contactWechat = 'demo_local'
      if (type === 'HYPERLINK') config.iconPosition = 'BELOW'
      edit.handleComplexChange({ detail: { config } })
    } else {
      const fields = { PROFILE: ['layout', 'HORIZONTAL'], SCHEDULE_QUERY: ['title', '本地档期'],
        WORK_GRID: ['showDescription', true], WORK_LIST: ['showDescription', true],
        CONTACT_FORM: ['title', '本地联系'], TEXT_SECTION: ['color', '#123456'], DIVIDER: ['heightPx', 48] }
      const [field, value] = fields[type]
      edit.handleConfigInput(event({ field }, value))
    }
    edit.handleConfirmComponentEditSheet()
    assert.equal(edit.data.componentEditSheetVisible, false, edit.data.complexError)
    const component = edit.data.draft.config.components.find(item => item.componentKey === key)
    assert.notDeepEqual(copy(component.config), original)
    const expected = copy(component.config)
    edit.handlePreview()
    edit.onHide()
    assert.equal(runtime.storage.size, 1)
    const preview = runtime.page('portfolio-standard-preview')
    preview.onLoad({}); preview.onShow()
    assert.equal(preview.data.errorMessage, '')
    const rendered = preview.data.portfolio.activeComponents.find(item => item.componentKey === key)
    assert.equal(rendered.componentType, type)
    assert.notEqual(rendered.unsupported, true)
    preview.onHide(); preview.onUnload(); edit.onShow()
    assert.deepEqual(copy(edit.data.draft.config.components.find(item => item.componentKey === key).config), expected)
    edit.handleSaveDraft(); edit.handlePublish()
    assert.deepEqual(runtime.calls.slice(-2), ['请去“我的”页面注册登录', '请去“我的”页面注册登录'])
    edit.onUnload()
  })
}
