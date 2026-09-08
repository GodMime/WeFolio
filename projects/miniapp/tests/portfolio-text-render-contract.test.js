const test = require('node:test')
const assert = require('node:assert/strict')
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
    component(data, key = 'structured') {
      return { componentKey: key, componentType: 'STRUCTURED_TEXT_SECTION', sortOrder: 1000, data }
    },
    normalize: renderData => normalizeTeamVisitorPortfolio({ renderData }),
    switchMenu: switchTeamPortfolioMenu,
    data: component => component.data
  }
]

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

for (const context of contexts) {
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
