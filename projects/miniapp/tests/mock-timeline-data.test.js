const test = require('node:test')
const assert = require('node:assert/strict')
const mock = require('../pages/mock/utils/mock-experience')
const { createMockComponentConfig, validateMockComponentConfig } = require('../pages/mock/utils/mock-portfolio-components')
const { collectMockFontDemands, createMockFontSession } = require('../pages/mock/utils/mock-portfolio-fonts')

function timelineConfig() {
  return { ...createMockComponentConfig('TIMELINE'), title: '我的历程', nodes: [
    { nodeKey: 'first', datePrecision: 'YEAR', date: '2022', title: '开始记录', description: '珍藏每个温暖瞬间' },
    { nodeKey: 'next', datePrecision: 'CUSTOM', date: '未来', title: '继续前行', description: '' }
  ] }
}

test('mock 时间轴个人和团队示例可直接展示，新组件保持独立空白草稿', () => {
  for (const [id, ownerType, title] of [[9001, 'USER', '我的历程'], [9902, 'TEAM', '团队里程碑']]) {
    const draft = mock.getMockPortfolioDraft(id)
    const sample = draft.config.components.find(item => item.componentType === 'TIMELINE')
    assert.equal(sample.config.title, title)
    assert.equal(sample.config.nodes.length, 3)
    assert.equal(validateMockComponentConfig('TIMELINE', sample.config).valid, true)
    const added = mock.addMockComponent(draft, 'TIMELINE')
    const created = added.config.components.at(-1)
    assert.equal(created.config.title, title)
    assert.equal(created.config.nodes.length, 1)
    assert.equal(created.config.nodes[0].date, '')
    assert.equal(created.config.nodes[0].title, '')
    assert.equal(added.renderData.components.at(-1).ownerType, ownerType)
    assert.equal(sample.config.nodes.length, 3)
  }
})

test('mock 时间轴使用正式校验规则，失败不污染草稿或发布快照', () => {
  const draft = mock.getMockPortfolioDraft(9002)
  const key = draft.config.components.find(item => item.componentType === 'TIMELINE').componentKey
  const before = structuredClone(draft)
  const config = timelineConfig()
  config.nodes[0].datePrecision = 'DAY'
  config.nodes[0].date = '2023-02-29'
  assert.equal(mock.applyMockComponentConfig(draft, key, config).valid, false)
  assert.deepEqual(draft, before)
  config.nodes[0].date = '2024-02-29'
  const result = mock.applyMockComponentConfig(draft, key, config)
  assert.equal(result.valid, true)
  assert.deepEqual(result.draft.config.components.find(item => item.componentKey === key).config, config)
  assert.deepEqual(draft, before)
  assert.notDeepEqual(mock.getMockPortfolioPreview(9002, 'published').config.components.find(item => item.componentKey === key).config, config)
})

test('mock 时间轴所有菜单携带独立配置、所属类型、背景与字体上下文', () => {
  const config = timelineConfig()
  const component = { componentKey: 'timeline', componentType: 'TIMELINE', config }
  const fontContext = { versions: { TEST: { fontVersion: 'v1' } }, families: {} }
  const rendered = mock.buildMockPortfolioRenderData({ ownerType: 'TEAM', style: { backgroundColor: '#151515' },
    components: [component], bottomNav: { enabled: true, items: [{ key: 'home', title: '主页' }, { key: 'more', title: '更多', components: [component] }] } }, fontContext)
  for (const item of [rendered.components[0], rendered.bottomNav.items[1].components[0]]) {
    assert.equal(item.ownerType, 'TEAM')
    assert.equal(item.backgroundColor, '#151515')
    assert.deepEqual(item.fontContext, fontContext)
    assert.deepEqual(item.config, config)
    assert.notEqual(item.unsupported, true)
  }
  rendered.components[0].config.title = '已修改'
  assert.equal(config.title, '我的历程')
  const invalid = mock.buildMockPortfolioRenderData({ components: [{ ...component, config: { ...config, nodes: null } }] })
  assert.equal(invalid.components[0].unsupported, true)
  assert.match(invalid.components[0].errorMessage, /时间轴/)
})

test('mock 时间轴按实际角色继承加载粗细字重，节点覆盖不误加载统一字体', async () => {
  const timeline = timelineConfig()
  timeline.textStyles = { heading: { fontId: 'TEST' }, title: { fontId: 'TEST' }, description: { fontId: 'TEST' } }
  timeline.nodes.forEach(node => { node.textStyles = { title: { fontFamily: 'SYSTEM' } } })
  const config = { fonts: { TEST: { fontVersion: 'v1' } }, components: [{ componentType: 'TIMELINE', config: timeline }] }
  const demands = collectMockFontDemands(config)
  assert.deepEqual(demands.filter(item => item.fontId === 'TEST').map(item => [item.content, item.fontWeight]), [
    ['我的历程', 'BOLD'], ['珍藏每个温暖瞬间', 'NORMAL']
  ])
  const calls = []
  const session = createMockFontSession({ catalog: [{ fontId: 'TEST', fontVersion: 'v1', physicalWeights: { 400: 400, 700: 700 }, assets: {
    400: { url: 'https://example.test/normal.woff', sha256: 'a'.repeat(64) },
    700: { url: 'https://example.test/bold.woff', sha256: 'b'.repeat(64) }
  } }], wxApi: { loadFontFace(options) { calls.push(options); options.success() } } })
  try {
    await session.load(config)
    assert.deepEqual(calls.map(item => item.desc.weight), ['700', '400'])
    assert.deepEqual(Object.keys(session.context(config.fonts).families).sort(), ['TEST:v1:400', 'TEST:v1:700'])
    assert.deepEqual(session.versions(config), config.fonts)
  } finally { session.destroy() }
})

test('mock 团队历史缓存保留原组件，仅恢复本地所属类型供时间轴展示', () => {
  const previousWx = global.wx
  const stored = mock.getMockPortfolioDraft(9902)
  delete stored.config.ownerType
  stored.config.components = stored.config.components.filter(item => item.componentType !== 'TIMELINE')
  const before = structuredClone(stored)
  global.wx = { getStorageSync: () => stored }
  try {
    const restored = mock.getMockPortfolioDraft(9902)
    assert.equal(restored.renderData.ownerType, 'TEAM')
    assert.deepEqual(restored.config.components, stored.config.components)
    assert.deepEqual(stored, before)
    const added = mock.addMockComponent(restored, 'TIMELINE')
    assert.equal(added.config.components.at(-1).config.title, '团队里程碑')
    assert.equal(added.renderData.components.at(-1).ownerType, 'TEAM')
  } finally { if (previousWx === undefined) delete global.wx; else global.wx = previousWx }
})

test('mock 渲染器开场等待时间轴布局，并将超时回退委派给时间轴', async () => {
  const file = require.resolve('../components/mock/portfolio-renderer/portfolio-renderer')
  const previousComponent = global.Component
  let definition
  global.Component = value => { definition = value }
  delete require.cache[file]
  try { require(file) } finally {
    if (previousComponent === undefined) delete global.Component
    else global.Component = previousComponent
  }
  let resolveLayout, completed = false, fallback = 0
  const timeline = { waitForFontLayout: () => new Promise(resolve => { resolveLayout = resolve }),
    useNaturalFontLayout: () => { fallback++; return Promise.resolve() } }
  const renderer = { selectComponent: selector => selector === '.mock-opening-timeline' ? timeline : null }
  const waiting = definition.methods.waitForFontLayout.call(renderer).then(() => { completed = true })
  await Promise.resolve()
  assert.equal(completed, false)
  resolveLayout()
  await waiting
  assert.equal(completed, true)
  await definition.methods.useNaturalFontLayout.call(renderer)
  assert.equal(fallback, 1)
})
