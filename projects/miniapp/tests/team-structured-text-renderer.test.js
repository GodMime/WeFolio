const test = require('node:test')
const assert = require('node:assert/strict')

function renderer(config, themeMode = 'light', repairMode = false) {
  const modulePath = '../pages/team-portfolios/components/structured-text-section/structured-text-section'
  const previous = global.Component
  let definition
  global.Component = value => { definition = value }
  try {
    delete require.cache[require.resolve(modulePath)]
    require(modulePath)
  } finally {
    if (previous === undefined) delete global.Component
    else global.Component = previous
  }
  const instance = {
    properties: { config, themeMode, repairMode },
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch) }
  }
  for (const [key, method] of Object.entries(definition.methods)) instance[key] = method.bind(instance)
  instance.updatePresentation()
  return instance
}

function blocks() {
  return [
    { blockKey: 'heading', type: 'TITLE', content: '标题', color: 'AUTO', marginBottomRpx: 16 },
    { blockKey: 'gap', type: 'SPACER', heightRpx: 32 },
    { blockKey: 'list', type: 'LIST', items: ['经历一', '经历二'], color: '#123456', marginTopRpx: 24 }
  ]
}

test('团队结构化展示保留独立样式与留白，切换主题不改持久化颜色', () => {
  const config = { blocks: blocks() }
  const before = JSON.stringify(config)
  const component = renderer(config)
  assert.equal(component.data.blocks[0].displayColor, '#212529')
  assert.match(component.data.blocks[0].style, /margin-bottom:16rpx/)
  assert.match(component.data.blocks[1].style, /height:32rpx/)
  assert.doesNotMatch(component.data.blocks[1].style, /font-size|line-height/)
  assert.match(component.data.blocks[2].style, /margin-top:24rpx/)
  assert.deepEqual(component.data.blocks[2].listItems, [{ key: 'list-0', content: '经历一' }, { key: 'list-1', content: '经历二' }])
  component.properties.themeMode = 'dark'
  component.updatePresentation()
  assert.equal(component.data.blocks[0].displayColor, '#F8F9FA')
  assert.equal(component.data.blocks[2].displayColor, '#123456')
  assert.equal(JSON.stringify(config), before)
})

test('团队动图背景使用原比例最小高度，失败后保留文字和修复提示', () => {
  const config = { blocks: blocks(), backgroundEnabled: true, backgroundWorkId: 71,
    backgroundWork: { workId: 71, mediaType: 'ANIMATION', url: 'https://example.test/a.gif', width: 1000, height: 1600 } }
  const component = renderer(config, 'light', true)
  assert.equal(component.data.background.imageUrl, 'https://example.test/a.gif')
  assert.equal(component.data.frameStyle, 'min-height:1200rpx;')
  assert.equal(component.data.blocks[0].displayColor, '#F8F9FA')
  component.handleBackgroundError()
  assert.equal(component.data.imageFailed, true)
  assert.equal(component.data.showRepairHint, true)
  assert.equal(component.data.blocks.length, 3)
  assert.equal(config.backgroundEnabled, true)
  component.properties.config = { ...config, backgroundEnabled: false }
  component.updatePresentation()
  assert.equal(component.data.background.enabled, false)
  assert.equal(component.data.frameStyle, '')
  assert.equal(component.data.showRepairHint, false)
  assert.equal(component.data.blocks[0].displayColor, '#212529')
})

test('团队访客失效背景不产生图片地址，也不丢失区块', () => {
  const component = renderer({ blocks: blocks(), backgroundEnabled: true, backgroundWorkId: 71, backgroundInvalid: true, backgroundWork: null })
  assert.equal(component.data.background.imageUrl, '')
  assert.equal(component.data.background.enabled, true)
  assert.equal(component.data.blocks.length, 3)
  assert.equal(component.data.blocks[0].displayColor, '#F8F9FA')
  assert.equal(component.data.showRepairHint, false)
})

test('团队预览和访客页面将结构化文字放入实际展示分组', () => {
  const paths = [
    '../pages/team-portfolios/standard-preview/team-portfolio-standard-preview',
    '../pages/team-portfolios/visitor-portfolio/team-visitor-portfolio'
  ]
  const previous = global.Page
  const item = { componentKey: 'structured', componentType: 'STRUCTURED_TEXT_SECTION', sortOrder: 2000, data: { blocks: blocks() } }
  for (const modulePath of paths) {
    let definition
    global.Page = value => { definition = value }
    try {
      delete require.cache[require.resolve(modulePath)]
      require(modulePath)
    } finally {
      if (previous === undefined) delete global.Page
      else global.Page = previous
    }
    const page = { data: JSON.parse(JSON.stringify(definition.data)), setData(patch) { Object.assign(this.data, patch) } }
    for (const [key, method] of Object.entries(definition)) {
      if (typeof method === 'function') page[key] = method.bind(page)
    }
    if (page.applyPortfolio) page.applyPortfolio({ activeComponents: [item], themeMode: 'light', style: { backgroundColor: '#FFFFFF' } })
    else page.applySession({ renderData: { components: [item] } })
    assert.equal(page.data.componentBuckets.structuredText.length, 1)
    assert.equal(page.data.componentBuckets.structuredText[0].componentKey, 'structured')
  }
})
