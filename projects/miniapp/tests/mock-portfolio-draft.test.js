const test = require('node:test')
const assert = require('node:assert/strict')
const mock = require('../pages/mock/utils/mock-experience')

test('体验草稿按样本隔离，旧缓存与无参数调用保留在9001', () => {
  const storage = new Map()
  global.wx = {
    getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
    removeStorageSync: key => storage.delete(key)
  }
  try {
    const original = mock.getMockPortfolioDraft()
    original.config.share.title = '保留旧草稿'
    mock.saveMockPortfolioDraft(original)
    const published = mock.getMockPortfolioDraft(9002)
    published.config.share.title = '新样本编辑'
    mock.saveMockPortfolioDraft(published, 9002)
    assert.equal(storage.has(mock.MOCK_PORTFOLIO_DRAFT_STORAGE_KEY), true)
    assert.equal(mock.getMockPortfolioDraft().config.share.title, '保留旧草稿')
    assert.equal(mock.getMockPortfolioDraft(9002).config.share.title, '新样本编辑')
    mock.resetMockPortfolioDraft(9002)
    assert.equal(mock.getMockPortfolioDraft().config.share.title, '保留旧草稿')
    assert.notEqual(mock.getMockPortfolioDraft(9002).config.share.title, '新样本编辑')
    mock.resetMockPortfolioDraft()
    assert.equal(storage.size, 0)
    storage.set(mock.MOCK_PORTFOLIO_DRAFT_STORAGE_KEY, { config: original.config })
    const restored = mock.getMockPortfolioDraft()
    assert.equal(restored.config.share.title, '保留旧草稿')
    assert.equal(restored.portfolio.portfolioId, 9001)
    assert.equal(storage.get(mock.MOCK_PORTFOLIO_DRAFT_STORAGE_KEY).portfolio, undefined)
  } finally { delete global.wx }
})

test('个人和团队发布快照独立且不可被编辑或返回对象污染', () => {
  const personal = mock.getMockPortfolioPreview(9002, 'published')
  const team = mock.getMockPortfolioPreview(9902, 'published')
  assert.equal(personal.portfolio.ownerType, 'USER')
  assert.equal(team.portfolio.ownerType, 'TEAM')
  assert.notEqual(personal.config.share.title, team.config.share.title)
  assert.notDeepEqual(mock.getMockPortfolioPreview(9002, 'draft').config, personal.config)
  const original = structuredClone(personal)
  personal.config.share.title = '禁止修改快照'
  assert.deepEqual(mock.getMockPortfolioPreview(9002, 'published'), original)
  for (const [id, scope] of [[9001, 'published'], [12345, 'draft'], [9902, 'bad']]) {
    assert.throws(() => mock.getMockPortfolioPreview(id, scope), /暂不可用/)
  }
})
test('revision2草稿无损升级且不插入新组件', () => {
  const before = { editorSchemaRevision: 2, style: {backgroundColor:'#151515'}, components:[{componentKey:'custom',componentType:'TEXT_SECTION',sortOrder:1000,config:{content:'保留内容',fontSizeRpx:26}}],bottomNav:{enabled:false} }
  const result = mock.normalizeMockPortfolioConfig(before)
  assert.equal(result.editorSchemaRevision,3)
  assert.equal(result.style.componentSpacingRpx,32)
  assert.equal(result.components.length,1)
  assert.equal(result.components[0].config.content,'保留内容')
  assert.equal(result.components[0].config.lineHeight,undefined)
  assert.equal(result.backgroundAudio.enabled,false)
  assert.equal(before.editorSchemaRevision,2)
})
test('默认覆盖16类，未知组件保留并使用明确占位', () => {
  const draft = mock.buildStandardPortfolio()
  const components = draft.config.components.concat(...draft.config.bottomNav.items.slice(1).map(x=>x.components))
  assert.equal(new Set(components.map(x=>x.componentType)).size,16)
  const render = mock.buildMockPortfolioRenderData({components:[{componentKey:'unknown',componentType:'FUTURE',config:{foo:1}}]})
  assert.equal(render.components[0].unsupported,true)
  assert.equal(render.components[0].contactForm,undefined)
})
test('组件校验失败不修改原草稿，成功保留选择顺序', () => {
  const draft = mock.addMockComponent(mock.buildStandardPortfolio(),'VIDEO_CAROUSEL')
  const key = draft.config.components.at(-1).componentKey
  const bad = mock.applyMockComponentConfig(draft,key,{workIds:[107,110]})
  assert.equal(bad.valid,false)
  assert.equal(bad.draft,draft)
  const good = mock.applyMockComponentConfig(draft,key,{workIds:[111,107,110],displayStyle:'STACKED',title:'视频'})
  assert.equal(good.valid,true)
  assert.deepEqual(good.draft.renderData.components.at(-1).works.map(w=>w.workId),[111,107,110])
})
