const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')
const { normalizePortfolioRender, switchPortfolioMenu } = require('../utils/visitor-portfolio')
const {
  normalizeTeamVisitorPortfolio,
  switchTeamPortfolioMenu
} = require('../pages/team-portfolios/utils/team-visitor-portfolio')

// 按服务端约定手写响应，不使用被测规范化函数生成期望值。
function structuredData() {
  return {
    backgroundEnabled: true,
    backgroundTreatment: 'GRADIENT',
    backgroundWorkId: 71,
    backgroundMemberUserId: 9,
    backgroundWork: { workId: 71, mediaType: 'ANIMATION', url: 'https://example.test/original.gif', width: 900, height: 1600 },
    blocks: [
      { blockKey: 'title', type: 'TITLE', content: '  主张\n😀', fontFamily: 'SYSTEM', fontSizeRpx: 44, fontWeight: 'BOLD', color: 'AUTO', alignment: 'LEFT', marginTopRpx: 24, marginBottomRpx: 16 },
      { blockKey: 'gap', type: 'SPACER', heightRpx: 32, marginTopRpx: 0, marginBottomRpx: 0 },
      { blockKey: 'list', type: 'LIST', items: ['第一项', ' 第二项 '], fontFamily: 'SYSTEM', fontSizeRpx: 26, fontWeight: 'NORMAL', color: '#FFFFFF', alignment: 'RIGHT', marginTopRpx: 24, marginBottomRpx: 0 }
    ]
  }
}

const contexts = [
  {
    name: '个人',
    directory: 'portfolios',
    configProperty: 'structuredTextSection',
    observer: 'structuredTextSection, themeMode',
    blocks: view => view.data.presentation.blocks,
    component(data, key = 'structured') {
      const personalData = { ...data }
      delete personalData.backgroundMemberUserId
      return { componentKey: key, componentType: 'STRUCTURED_TEXT_SECTION', sortOrder: 1000, structuredTextSection: personalData }
    },
    normalize: normalizePortfolioRender,
    switchMenu: switchPortfolioMenu,
    data: component => component.structuredTextSection
  },
  {
    name: '团队',
    directory: 'team-portfolios',
    configProperty: 'config',
    observer: 'config, themeMode, repairMode',
    blocks: view => view.data.blocks,
    component(data, key = 'structured') {
      return { componentKey: key, componentType: 'STRUCTURED_TEXT_SECTION', sortOrder: 1000, data }
    },
    normalize: renderData => normalizeTeamVisitorPortfolio({ renderData }),
    switchMenu: switchTeamPortfolioMenu,
    data: component => component.data
  }
]

/** 执行真实组件与属性观察器，验证访客数据最终落到模板所用的颜色字段。 */
function mountStructuredRenderer(context, config) {
  const file = path.join(__dirname, '..', 'pages', context.directory, 'components',
    'structured-text-section', 'structured-text-section.js')
  let definition
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    require: createRequire(file), Component(value) { definition = value }
  }, { filename: file })
  const view = {
    properties: { [context.configProperty]: config, themeMode: 'light', repairMode: false },
    data: structuredClone(definition.data),
    setData(patch) { Object.assign(this.data, patch) }
  }
  Object.entries(definition.methods).forEach(([name, method]) => { view[name] = method.bind(view) })
  return {
    view,
    setTheme(themeMode) {
      view.properties.themeMode = themeMode
      definition.observers[context.observer].call(view)
    }
  }
}

test('个人与团队普通文字接口的显式颜色经过访客适配后保持不变', () => {
  for (const color of ['AUTO', '#FFFFFF', '#212529', '#A1B2C3']) {
    const data = { content: '颜色说明', color, alignment: 'LEFT' }
    const personal = normalizePortfolioRender({ components: [{
      componentKey: 'text', componentType: 'TEXT_SECTION', textSection: data
    }] })
    const team = normalizeTeamVisitorPortfolio({ renderData: { components: [{
      componentKey: 'text', componentType: 'TEXT_SECTION', data
    }] } })
    assert.equal(personal.components[0].textSection.color, color)
    assert.equal(team.components[0].data.color, color)
    assert.equal(personal.components[0].textSection.content, '颜色说明')
    assert.equal(team.components[0].data.content, '颜色说明')
  }
})

test('个人与团队普通文字的新字号范围经过访客适配后保持不变', () => {
  for (const fontSizeRpx of [10, 11, 19, 49, 95, 96]) {
    const data = { content: '字号说明', fontSizeRpx }
    const personal = normalizePortfolioRender({ components: [{
      componentKey: 'text', componentType: 'TEXT_SECTION', textSection: data
    }] })
    const team = normalizeTeamVisitorPortfolio({ renderData: { components: [{
      componentKey: 'text', componentType: 'TEXT_SECTION', data
    }] } })
    assert.equal(personal.components[0].textSection.fontSizeStyle, `font-size: ${fontSizeRpx}rpx;`)
    assert.equal(team.components[0].data.fontSizeStyle, `font-size: ${fontSizeRpx}rpx;`)
  }
})

test('普通文字行距从接口适配到真实组件，缺省保持旧样式且非法值不生成样式', () => {
  for (const directory of ['portfolios', 'team-portfolios']) {
    const file = path.join(__dirname, '..', 'pages', directory, 'components/text-section/text-section.js')
    let definition
    vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
      module: { exports: {} }, require: createRequire(file), Component(value) { definition = value }
    }, { filename: file })
    for (const lineHeight of [undefined, 0.5, 1.8, 3, '0.5', 0.4, 3.1]) {
      const raw = { content: '第一行\n第二行', fontSizeRpx: 28,
        ...(lineHeight === undefined ? {} : { lineHeight }) }
      const personal = directory === 'portfolios'
      const data = personal
        ? normalizePortfolioRender({ components: [{ componentKey: 'text', componentType: 'TEXT_SECTION', textSection: raw }] }).components[0].textSection
        : normalizeTeamVisitorPortfolio({ renderData: { components: [{ componentKey: 'text', componentType: 'TEXT_SECTION', data: raw }] } }).components[0].data
      const view = { properties: { [personal ? 'textSection' : 'config']: data, themeMode: 'light' },
        data: structuredClone(definition.data), setData(patch) { Object.assign(this.data, patch) } }
      Object.entries(definition.methods).forEach(([name, method]) => { view[name] = method.bind(view) })
      view.updatePresentation()
      assert.equal(view.data.lineHeightStyle, [0.5, 1.8, 3].includes(lineHeight) ? `line-height: ${lineHeight};` : '')
      assert.equal(data.fontSizeStyle, 'font-size: 28rpx;')
    }
  }
})

for (const context of contexts) {
  test(`${context.name}结构化文字行距经过接口适配后仍作用于各区块`, () => {
    const data = structuredData()
    data.blocks[0].lineHeight = 0.5
    data.blocks[2].lineHeight = 3
    const normalized = context.normalize({ components: [context.component(data)] })
    const renderer = mountStructuredRenderer(context, context.data(normalized.components[0]))
    renderer.setTheme('light')
    const blocks = context.blocks(renderer.view)
    assert.equal(blocks[0].lineHeightStyle, 'line-height: 0.5;')
    assert.equal(blocks[2].lineHeightStyle, 'line-height: 3;')
    assert.equal(blocks[1].lineHeightStyle, undefined)
  })

  test(`${context.name}结构化文字所有类型在背景与主题切换后保留独立颜色`, () => {
    for (const backgroundEnabled of [false, true]) {
      for (const color of ['#000000', '#FFFFFF', '#CED4DA', '#ADB5BD', '#F5F6F8', '#A1B2C3', 'AUTO']) {
        const source = {
          ...structuredData(), backgroundEnabled,
          blocks: ['EYEBROW', 'TITLE', 'PARAGRAPH', 'LIST', 'HINT'].map(type => ({
            blockKey: type, type, color, fontFamily: 'SYSTEM', fontSizeRpx: 28,
            fontWeight: 'NORMAL', alignment: 'LEFT', marginTopRpx: 0, marginBottomRpx: 16,
            ...(type === 'LIST' ? { items: ['列表第一项', '列表第二项'] } : { content: `${type} 内容` })
          }))
        }
        const snapshot = JSON.stringify(source)
        const normalized = context.normalize({ components: [context.component(source)] })
        const renderer = mountStructuredRenderer(context, context.data(normalized.components[0]))
        for (const themeMode of ['light', 'dark', 'light']) {
          renderer.setTheme(themeMode)
          const blocks = context.blocks(renderer.view)
          assert.equal(blocks.length, 5)
          for (const block of blocks) {
            const expected = color === 'AUTO'
              ? backgroundEnabled || themeMode === 'dark' ? '#F8F9FA' : '#212529'
              : color
            const description = `${block.type} / ${color} / 背景 ${backgroundEnabled} / ${themeMode}`
            assert.equal(block.color, color, description)
            assert.equal(block.displayColor, expected, description)
            assert.ok(block.style.includes(`color:${expected};`), description)
            if (block.type === 'LIST') {
              assert.equal(block.listItems.length, 2)
              assert.equal(block.listItems[0].content, '列表第一项')
            } else assert.equal(block.content, `${block.type} 内容`)
          }
        }
        assert.equal(JSON.stringify(source), snapshot, '主题切换不能写回持久化颜色')
      }
    }
  })

  test(`${context.name}结构化正文和列表实际文字节点直接应用派生颜色`, () => {
    const file = path.join(__dirname, '..', 'pages', context.directory, 'components',
      'structured-text-section', 'structured-text-section.wxml')
    const nodes = [...fs.readFileSync(file, 'utf8').matchAll(/<text\b([^>]*)>([\s\S]*?)<\/text>/g)]
      .filter(([, , content]) => /\{\{(?:item|line)\.content\}\}/.test(content))
    assert.equal(nodes.length, 2, '正文与列表应分别有实际文字节点')
    for (const [, attributes, content] of nodes) {
      assert.match(attributes, /\bstyle="[^"]*color:\s*\{\{item\.displayColor\}\};[^"]*"/,
        `${content.trim()} 的颜色必须直接落到 text，不能仅依赖外层 view 继承`)
    }
  })

  test(`${context.name}服务端结构化响应保留原动图、区块身份与自动颜色语义`, () => {
    const source = structuredData()
    const snapshot = JSON.stringify(source)
    const result = context.normalize({ style: { backgroundColor: '#111111' }, components: [context.component(source)] })
    assert.equal(result.components.length, 1)
    const data = context.data(result.components[0])
    assert.deepEqual(data.blocks.map(block => block.blockKey), ['title', 'gap', 'list'])
    assert.equal(data.blocks[0].content, '  主张\n😀')
    assert.equal(data.blocks[0].color, 'AUTO')
    assert.equal(data.blocks[1].heightRpx, 32)
    assert.deepEqual(data.blocks[2].items, ['第一项', ' 第二项 '])
    assert.equal(data.blocks[2].color, '#FFFFFF')
    assert.equal(data.backgroundWork.url, 'https://example.test/original.gif')
    assert.equal(data.backgroundWork.width, 900)
    assert.equal(result.themeMode, 'dark')
    assert.equal(JSON.stringify(source), snapshot)
  })

  test(`${context.name}二级菜单的无效结构化背景仍保留文字并可切换展示`, () => {
    const data = structuredData()
    data.backgroundWork = null
    data.backgroundInvalid = true
    const portfolio = context.normalize({
      style: { backgroundColor: '#FFFFFF' },
      components: [],
      bottomNav: { enabled: true, items: [
        { key: 'nav_1', title: '首页' },
        { key: 'nav_2', title: '介绍', components: [context.component(data, 'second-menu-text')] }
      ] }
    })
    const switched = context.switchMenu(portfolio, 'nav_2')
    assert.equal(switched.activeComponents.length, 1)
    const displayed = context.data(switched.activeComponents[0])
    assert.equal(displayed.backgroundEnabled, true)
    assert.equal(displayed.backgroundInvalid, true)
    assert.equal(displayed.backgroundWork, null)
    assert.equal(displayed.blocks[0].content, '  主张\n😀')
    assert.equal(portfolio.activeMenuKey, 'nav_1')
  })
}
