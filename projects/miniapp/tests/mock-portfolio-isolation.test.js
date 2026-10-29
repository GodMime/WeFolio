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
  'STRUCTURED_TEXT_SECTION', 'TEXT_GRID', 'CONTACT_INFO', 'HYPERLINK', 'TIMELINE']
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
  const checkKey = key => assert.ok([STORAGE_KEY, `${STORAGE_KEY}_9002`, `${STORAGE_KEY}_9902`].includes(key), key)
  const wx = {
    request: forbidden, uploadFile: forbidden, login: forbidden,
    getStorageSync(key) { checkKey(key); return storage.get(key) },
    setStorageSync(key, value) { checkKey(key); storage.set(key, copy(value)) },
    removeStorageSync(key) { checkKey(key); storage.delete(key) },
    showToast(options) { calls.push(options.title) },
    navigateTo(options) { assert.match(options.url, /^\/pages\/mock\//) },
    createVideoContext() { return { pause() {} } },
    getWindowInfo() { return { windowWidth: 375, windowHeight: 812 } },
    createSelectorQuery() {
      let callback
      return { in() { return this }, select() { return this }, boundingClientRect(value) { callback = value; return this },
        exec() { callback({ left: 130, right: 230, top: 700, bottom: 746, width: 100, height: 46 }) } }
    }
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

test('已发布个人与团队双版本全流程仅使用各自mock数据，保存发布仍锁定', () => {
  const runtime = createRuntime()
  const edit = runtime.page('portfolio-standard-edit')
  edit.onLoad({ portfolioId: 9002 })
  edit.data.draft.config.share.title = '本地版本选择体验'
  edit.handlePreview()
  edit.handlePreviewVersionSelect(event({ scope: 'published' }))
  assert.equal(runtime.storage.size, 0)
  const published = runtime.page('portfolio-standard-preview')
  published.onLoad({ portfolioId: 9002, scope: 'published' }); published.onShow()
  assert.equal(published.data.portfolio.title, '婚礼纪实个人作品集')
  published.onUnload(); edit.onShow()
  assert.equal(edit.data.draft.config.share.title, '本地版本选择体验')
  edit.handlePreview()
  edit.handlePreviewVersionSelect(event({ scope: 'draft' }))
  assert.deepEqual([...runtime.storage.keys()], [`${STORAGE_KEY}_9002`])
  const draft = runtime.page('portfolio-standard-preview')
  draft.onLoad({ portfolioId: 9002, scope: 'draft' }); draft.onShow()
  assert.equal(draft.data.portfolio.title, '本地版本选择体验')
  draft.onUnload(); edit.onShow()
  const team = runtime.page('portfolio-standard-preview')
  team.onLoad({ portfolioId: 9902, scope: 'published' }); team.onShow()
  assert.equal(team.data.portfolio.title, '城市婚礼影像团队作品集')
  team.onUnload()
  edit.handleSaveDraft(); edit.handlePublish()
  assert.deepEqual(runtime.calls.slice(-2), ['请去“我的”页面注册登录', '请去“我的”页面注册登录'])
  assert.equal(runtime.storage.has(STORAGE_KEY), false)
  edit.onUnload()
})

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
    else if (type === 'TIMELINE') {
      const config = copy(edit.data.complexConfig)
      config.nodes[0] = { ...config.nodes[0], date: '2026', title: '本地历程' }
      edit.handleTimelineConfirm({ detail: config })
    } else if (type === 'TEXT_GRID') {
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

test('mock 个人和团队独立筛选，草稿兼容旧样例且统计保持完整', async () => {
  const runtime = createRuntime()
  const page = runtime.page('portfolios')
  const original = copy([page.data.portfolioList, page.data.teamPortfolioList])
  await page.handleFilterSelect(event({ value: 'DRAFT_ONLY' }))
  assert.equal(page.data.displayPortfolios.length, 1)
  assert.equal(page.data.displayPortfolios[0].publicationStatus, 'DRAFT')
  assert.equal(page.data.portfolioList.summary.publishedText, '1 已发布')
  page.handleOwnerTypeTap(event({ type: 'TEAM' }))
  assert.equal(page.data.teamPublicationFilter, '')
  await page.handleFilterSelect(event({ value: 'DRAFT_ONLY' }))
  assert.equal(page.data.teamDisplayPortfolios.length, 0)
  assert.equal(page.data.teamPortfolioList.summary.publishedText, '1 已发布')
  page.handleOwnerTypeTap(event({ type: 'USER' }))
  assert.equal(page.data.publicationFilter, 'DRAFT_ONLY')
  await page.handleFilterSelect(event({ value: '' }))
  assert.equal(page.data.displayPortfolios.length, 2)
  assert.deepEqual(copy([page.data.portfolioList, page.data.teamPortfolioList]), original)
  assert.deepEqual(runtime.calls, [])
})

test('mock 筛选菜单与预览菜单互斥并在切换分类时关闭', () => {
  const page = createRuntime().page('portfolios')
  page.setData({ previewVersionMenu: { options: [] } })
  page.handleFilterTap()
  assert.equal(page.data.previewVersionMenu, null)
  assert.equal(page.data.filterMenu.ownerType, 'USER')
  page.handleOwnerTypeTap(event({ type: 'TEAM' }))
  assert.equal(page.data.filterMenu, null)
  page.handleFilterTap()
  assert.equal(page.data.filterMenu.ownerType, 'TEAM')
  page.onHide()
  assert.equal(page.data.filterMenu, null)
})

test('mock 筛选显示加载过渡，快速切换和离开页面不回写旧结果', async () => {
  const runtime = createRuntime()
  const page = runtime.page('portfolios')
  const first = page.handleFilterSelect(event({ value: 'PUBLISHED' }))
  assert.equal(page.data.filterLoading, true)
  const second = page.handleFilterSelect(event({ value: 'DRAFT_ONLY' }))
  await Promise.all([first, second])
  assert.equal(page.data.publicationFilter, 'DRAFT_ONLY')
  assert.equal(page.data.filterLoading, false)
  assert.equal(page.data.displayPortfolios[0].publicationStatus, 'DRAFT')
  const third = page.handleFilterSelect(event({ value: '' }))
  page.onHide()
  assert.equal(page.data.filterLoading, false)
  await third
  assert.equal(page.data.publicationFilter, 'DRAFT_ONLY')
  assert.deepEqual(runtime.calls, [])
})

for (const [portfolioId, menuIndex] of [[9001, 0], [9002, 1]]) {
  test(`时间轴在样本 ${portfolioId} 菜单 ${menuIndex} 中取消恢复快照，确认后仅通过本地草稿预览`, () => {
    const runtime = createRuntime()
    const edit = runtime.page('portfolio-standard-edit')
    edit.onLoad({ portfolioId })
    const menuKey = edit.data.draft.config.bottomNav.items[menuIndex].key
    if (menuIndex) edit.handleEditorMenuTap(event({ key: menuKey }))
    const before = copy(edit.data.draft.config)
    edit.handleSelectComponent(event({ type: 'TIMELINE' }))
    const firstSession = edit.data.mockTimelineSessionId
    const canceled = copy(edit.data.complexConfig)
    canceled.title = '不会保存的标题'
    edit.handleTimelineFontDraft({ detail: { config: canceled, sessionId: firstSession } })
    edit.handleCloseComponentEditSheet()
    assert.deepEqual(copy(edit.data.draft.config), before)
    assert.equal(runtime.storage.size, 0)

    edit.handleSelectComponent(event({ type: 'TIMELINE' }))
    const key = edit.data.selectedComponentKey
    const sessionId = edit.data.mockTimelineSessionId
    assert.notEqual(sessionId, firstSession)
    const initial = copy(edit.data.complexConfig)
    edit.handleTimelineFontDraft({ detail: { config: canceled, sessionId: firstSession } })
    assert.deepEqual(copy(edit.data.complexConfig), initial, '旧编辑会话不能写入新时间轴')
    const candidate = copy(initial)
    candidate.title = '婚礼影像历程'
    candidate.variant = 'MILESTONE_CARD'
    candidate.direction = 'VERTICAL'
    candidate.nodes[0] = { ...candidate.nodes[0], datePrecision: 'DAY', date: '2024-02-29', title: '开始记录', description: '第一场婚礼' }
    candidate.nodes.push({ nodeKey: 'local_future', datePrecision: 'CUSTOM', date: '持续创作', title: '未来', description: '留下更多温暖瞬间' })
    edit.handleTimelineFontDraft({ detail: { config: candidate, sessionId } })
    const unconfirmed = menuIndex ? edit.data.draft.config.bottomNav.items[menuIndex].components : edit.data.draft.config.components
    assert.equal(unconfirmed.find(item => item.componentKey === key).config.title, initial.title)
    edit.handleTimelineConfirm({ detail: candidate })
    assert.equal(edit.data.componentEditSheetVisible, false, edit.data.complexError)
    const confirmed = menuIndex ? edit.data.draft.config.bottomNav.items[menuIndex].components : edit.data.draft.config.components
    assert.deepEqual(copy(confirmed.find(item => item.componentKey === key).config), candidate)
    assert.equal(runtime.storage.size, 0)
    edit.handleTimelineFontDraft({ detail: { config: canceled, sessionId } })
    assert.deepEqual(copy(confirmed.find(item => item.componentKey === key).config), candidate)

    edit.handlePreview()
    if (portfolioId === 9002) edit.handlePreviewVersionSelect(event({ scope: 'draft' }))
    edit.onHide()
    assert.deepEqual([...runtime.storage.keys()], [portfolioId === 9001 ? STORAGE_KEY : `${STORAGE_KEY}_${portfolioId}`])
    const preview = runtime.page('portfolio-standard-preview')
    preview.onLoad({ portfolioId, scope: 'draft' }); preview.onShow()
    if (menuIndex) preview.handlePortfolioMenuChange({ detail: { menuKey } })
    const rendered = preview.data.portfolio.activeComponents.find(item => item.componentKey === key)
    assert.equal(rendered.componentType, 'TIMELINE')
    assert.notEqual(rendered.unsupported, true)
    assert.deepEqual(copy(rendered.config), candidate)
    preview.onHide(); preview.onUnload(); edit.onShow()
    const restored = menuIndex ? edit.data.draft.config.bottomNav.items[menuIndex].components : edit.data.draft.config.components
    assert.deepEqual(copy(restored.find(item => item.componentKey === key).config), candidate)
    edit.handleComponentTap(event({ key }))
    const changed = copy(candidate)
    changed.nodes.reverse()
    edit.handleTimelineFontDraft({ detail: { config: changed, sessionId: edit.data.mockTimelineSessionId } })
    edit.handleCloseComponentEditSheet()
    const retained = menuIndex ? edit.data.draft.config.bottomNav.items[menuIndex].components : edit.data.draft.config.components
    assert.deepEqual(copy(retained.find(item => item.componentKey === key).config), candidate)
    edit.handleSaveDraft(); edit.handlePublish()
    assert.deepEqual(runtime.calls.slice(-2), ['请去“我的”页面注册登录', '请去“我的”页面注册登录'])
    edit.onUnload()
  })
}

test('时间轴非法日期拒绝确认且保留编辑，改为有效日期后可确认', () => {
  const runtime = createRuntime()
  const edit = runtime.page('portfolio-standard-edit')
  edit.onLoad()
  edit.handleSelectComponent(event({ type: 'TIMELINE' }))
  const candidate = copy(edit.data.complexConfig)
  candidate.nodes[0] = { ...candidate.nodes[0], datePrecision: 'DAY', date: '2025-02-29', title: '无效闰日' }
  edit.handleTimelineConfirm({ detail: candidate })
  assert.equal(edit.data.componentEditSheetVisible, true)
  assert.match(edit.data.complexError, /日期/)
  assert.equal(runtime.storage.size, 0)
  candidate.nodes[0].date = '2024-02-29'
  edit.handleTimelineConfirm({ detail: candidate })
  assert.equal(edit.data.componentEditSheetVisible, false)
  edit.onUnload()
})

test('个人与团队发布和草稿预览包含独立的本地时间轴，团队编辑仍提示登录', () => {
  const runtime = createRuntime()
  for (const portfolioId of [9002, 9902]) {
    for (const scope of ['draft', 'published']) {
      const page = runtime.page('portfolio-standard-preview')
      page.onLoad({ portfolioId, scope }); page.onShow()
      assert.equal(page.data.errorMessage, '')
      const timeline = page.data.portfolio.activeComponents.find(item => item.componentType === 'TIMELINE')
      assert.ok(timeline)
      assert.notEqual(timeline.unsupported, true)
      assert.equal(timeline.ownerType, portfolioId === 9902 ? 'TEAM' : 'USER')
      assert.equal(timeline.config.title, portfolioId === 9902 ? '团队里程碑' : '我的历程')
      assert.equal(timeline.config.nodes.length, 3)
      page.onHide(); page.onUnload()
    }
  }
  const edit = runtime.page('portfolio-standard-edit')
  edit.onLoad({ portfolioId: 9902 })
  assert.equal(edit.mockPreviewUnavailable, true)
  assert.equal(runtime.calls.at(-1), '请去“我的”页面注册登录')
  assert.equal(runtime.storage.size, 0)
  edit.onUnload()
})
