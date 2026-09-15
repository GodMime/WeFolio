const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const gridApi = require('../pages/portfolios/utils/portfolio-text-grid')
const clone = value => JSON.parse(JSON.stringify(value))
const contactDefaults = { contactBorder: false, horizontalMarginRpx: 0, verticalMarginRpx: 0, contactBorderWidthRpx: 1, contactBorderColor: 'AUTO' }
function loadEditor(team = false) {
  const filename = path.join(__dirname, '../pages', team ? 'team-portfolios/standard-edit/team-portfolio-standard-edit.js' : 'portfolios/standard-edit/portfolio-standard-edit.js')
  let definition
  const toasts = [], requests = []
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { Page: value => { definition = value }, wx: { showToast(value) { toasts.push(value) } }, setTimeout, clearTimeout, getApp: () => ({ globalData: {} }),
    require(relative) { if (/\/request(?:\.js)?$/.test(relative)) return { request: args => { requests.push(args); return Promise.resolve({}) } }; return require(path.resolve(path.dirname(filename), relative)) } }, { filename })
  const instance = { ...definition, toasts, requests, data: clone(definition.data), setData(values, callback) { Object.assign(this.data, values); if (callback) callback() } }
  return instance
}
for (const team of [false, true]) {
  const scope = team ? 'team' : 'personal'
  test(`${scope}: page and grid black presets render the same color while saved dark gray stays unchanged`, () => {
    const page = loadEditor(team)
    const folder = team ? 'team-portfolios' : 'portfolios'
    const api = require(`../pages/${folder}/utils/portfolio-text-grid`)
    const normalizeConfig = team
      ? require('../pages/team-portfolios/utils/team-portfolios').normalizeTeamPortfolioConfig
      : require('../utils/portfolios').normalizePortfolioConfig
    const oldConfig = normalizeConfig({ ...page.data.config, style: { backgroundColor: '#151515' } })
    assert.equal(oldConfig.style.backgroundColor, '#151515')
    page.setData({ config: oldConfig, canMaintain: true })

    page.handleBackgroundColorTap({ currentTarget: { dataset: { color: page.data.backgroundColorOptions[0] } } })
    assert.equal(page.data.config.style.backgroundColor, '#000000')
    const wxml = fs.readFileSync(path.join(__dirname, `../pages/${folder}/components/text-grid-editor/text-grid-editor.wxml`), 'utf8')
    const backgroundEditor = wxml.match(/<text-color-editor\b[^>]*label="格子底色"[^>]*>/)
    assert.ok(backgroundEditor, 'grid background picker must exist')
    const blackColor = backgroundEditor[0].match(/\bblack-color="([^"]+)"/)
    assert.ok(blackColor, 'grid background picker must declare its black preset')
    assert.equal(blackColor[1], page.data.config.style.backgroundColor)
    const grid = api.normalizeTextGrid({ ...api.createTextGrid(), cellBackground: blackColor[1] })
    for (const theme of ['light', 'dark']) {
      const cells = api.presentTextGrid(grid, api.layoutTextGrid(grid, 375, 0.5), theme)
      assert.ok(cells.length > 0)
      for (const cell of cells) assert.ok(cell.style.includes(`background:${page.data.config.style.backgroundColor};`))
    }
    assert.equal(api.normalizeTextGrid({ ...grid, cellBackground: '#151515' }).cellBackground, '#151515')
  })
  for (const type of ['TEXT_GRID', 'CONTACT_INFO']) for (const menu of ['root', 'second']) {
    test(`${scope}: tapping existing ${type} in ${menu} menu opens original config and reopens after cancellation`, () => {
      const page = loadEditor(team)
      const value = type === 'TEXT_GRID' ? gridApi.createTextGrid(3, 1) : { contactPhone: '13800000000', contactWechat: 'saved-wechat' }
      if (type === 'TEXT_GRID') {
        value.cellBackground = '#ABCDEF'
        Object.assign(value.cells[0].blocks[0].runs[0], { text: '已保存文字', fontSizeRpx: 48, fontWeight: 'BOLD' })
      }
      const component = { componentKey: 'saved-component', componentType: type, sortOrder: 1000, enabled: true, config: value }
      let config = clone(page.data.config)
      let activeMenuKey = ''
      if (menu === 'second') {
        config = team
          ? require('../pages/team-portfolios/utils/team-portfolios').setTeamBottomNavigationCount(config, 2)
          : require('../utils/portfolios').setBottomNavigationCount(config, 2)
        activeMenuKey = config.bottomNav.items[1].key
        config.bottomNav.items[1].components = [component]
      } else {
        config.components = [component]
        config.bottomNav = { enabled: false }
      }
      page.setData({ config, activeMenuKey })
      const original = clone(page.data.config)
      const event = { currentTarget: { dataset: { key: component.componentKey, type } } }

      page.handleComponentTap(event)
      assert.equal(page.data.newComponentSheetVisible, true)
      assert.equal(page.data.newComponentType, type)
      assert.equal(page.data.newComponentKey, component.componentKey)
      assert.equal(page.newComponentMenuKey, activeMenuKey)
      assert.deepEqual(clone(page.data.newComponentConfig), type === 'CONTACT_INFO' ? { ...value, ...contactDefaults } : value)
      if (type === 'TEXT_GRID') page.data.newComponentConfig.cells[0].blocks[0].runs[0].text = '未保存文字'
      else page.data.newComponentConfig.contactWechat = 'unsaved-wechat'
      page.handleCancelNewComponent()
      assert.equal(page.data.newComponentSheetVisible, false)
      assert.deepEqual(page.data.config, original)

      page.handleComponentTap(event)
      assert.equal(page.data.newComponentSheetVisible, true)
      assert.equal(page.data.newComponentKey, component.componentKey)
      assert.deepEqual(clone(page.data.newComponentConfig), type === 'CONTACT_INFO' ? { ...value, ...contactDefaults } : value)
      assert.deepEqual(page.data.config, original)
      assert.equal(page.requests.length, 0)
    })
  }
  test(`${scope}: new grid cancellation creates no temporary component and confirm saves into active menu`, () => {
    const page = loadEditor(team), initial = clone(page.data.config)
    page.openNewComponentSheet('TEXT_GRID'); page.handleCancelNewComponent(); assert.deepEqual(page.data.config, initial)
    const config = team
      ? require('../pages/team-portfolios/utils/team-portfolios').setTeamBottomNavigationCount(page.data.config, 2)
      : require('../utils/portfolios').setBottomNavigationCount(page.data.config, 2)
    page.setData({ config, activeMenuKey: config.bottomNav.items[1].key })
    page.openNewComponentSheet('TEXT_GRID'); const grid = clone(page.data.newComponentConfig); grid.cells[0].blocks[0].runs[0].text = '10年'
    page.handleConfirmNewComponent({ detail: grid })
    const added = page.data.config.bottomNav.items[1].components.find(item => item.componentType === 'TEXT_GRID')
    assert.ok(added); assert.equal(added.config.cells[0].blocks[0].runs[0].text, '10年')
    page.openNewComponentSheet('TEXT_GRID', added.componentKey); page.data.newComponentConfig.cells[0].blocks[0].runs[0].text = '未保存'
    page.handleCancelNewComponent(); assert.equal(added.config.cells[0].blocks[0].runs[0].text, '10年')
  })
  test(`${scope}: contact confirmation fixes independent snapshot; later edits and cancellation do not change it`, () => {
    const page = loadEditor(team)
    page.openNewComponentSheet('CONTACT_INFO'); page.handleConfirmNewComponent({ detail: { contactPhone: '13800000000', contactWechat: 'wechat' } })
    const added = page.data.config.components.find(item => item.componentType === 'CONTACT_INFO')
    assert.deepEqual(clone(added.config), { contactPhone: '13800000000', contactWechat: 'wechat', ...contactDefaults })
    page.openNewComponentSheet('CONTACT_INFO', added.componentKey); page.data.newComponentConfig.contactPhone = '13900000000'; page.handleCancelNewComponent()
    assert.equal(added.config.contactPhone, '13800000000')
  })
  test(`${scope}: stale component editors never re-add, overwrite or silently discard a moved target`, () => {
    for (const type of ['TEXT_GRID', 'CONTACT_INFO']) for (const change of ['delete', 'move', 'replace-type', 'remove-menu']) {
      const page = loadEditor(team)
      const config = team ? require('../pages/team-portfolios/utils/team-portfolios').setTeamBottomNavigationCount(page.data.config, 2)
        : require('../utils/portfolios').setBottomNavigationCount(page.data.config, 2)
      page.setData({ config, activeMenuKey: config.bottomNav.items[1].key })
      const value = type === 'TEXT_GRID' ? gridApi.createTextGrid() : { contactPhone: '原联系', contactWechat: '' }
      if (type === 'TEXT_GRID') value.cells[0].blocks[0].runs[0].text = '原文字'
      page.openNewComponentSheet(type); page.handleConfirmNewComponent({ detail: value })
      const target = page.data.config.bottomNav.items[1].components[0]
      page.openNewComponentSheet(type, target.componentKey)
      const result = clone(page.data.newComponentConfig)
      if (type === 'TEXT_GRID') result.cells[0].blocks[0].runs[0].text = '编辑中的文字'
      else result.contactPhone = '编辑中的联系'
      if (change === 'delete') page.data.config.bottomNav.items[1].components = []
      if (change === 'move') { page.data.config.bottomNav.items[1].components = []; page.data.config.components.push(target) }
      if (change === 'replace-type') target.componentType = 'DIVIDER'
      if (change === 'remove-menu') page.data.config.bottomNav = { enabled: false }
      const before = clone(page.data.config)
      page.handleConfirmNewComponent({ detail: result })
      assert.deepEqual(page.data.config, before, `${type}: ${change}`)
      assert.equal(page.data.newComponentSheetVisible, false); assert.match(page.toasts.at(-1).title, /原菜单或组件已变化/)
    }
  })
  test(`${scope}: new component confirmation rejects a removed originating menu`, () => {
    const page = loadEditor(team)
    const config = team ? require('../pages/team-portfolios/utils/team-portfolios').setTeamBottomNavigationCount(page.data.config, 2)
      : require('../utils/portfolios').setBottomNavigationCount(page.data.config, 2)
    page.setData({ config, activeMenuKey: config.bottomNav.items[1].key })
    page.openNewComponentSheet('TEXT_GRID')
    const result = clone(page.data.newComponentConfig); result.cells[0].blocks[0].runs[0].text = '新文字'
    page.data.config.bottomNav = { enabled: false }
    const before = clone(page.data.config); page.handleConfirmNewComponent({ detail: result })
    assert.deepEqual(page.data.config, before); assert.match(page.toasts.at(-1).title, /原菜单或组件已变化/)
  })
  test(`${scope}: publishing blank and structurally invalid grids shows a rejection without requests`, async () => {
    for (const variant of ['blank', 'nonbreaking-space', 'over-limit', 'negative-padding']) {
      const page = loadEditor(team), grid = gridApi.createTextGrid()
      if (variant === 'nonbreaking-space') grid.cells[0].blocks[0].runs[0].text = '\u00a0'
      if (variant === 'over-limit') grid.cells[0].blocks[0].runs[0].text = '😀'.repeat(2001)
      if (variant === 'negative-padding') { grid.cells[0].blocks[0].runs[0].text = '文字'; grid.cellPaddingRpx = -1 }
      page.setData({ canMaintain: true, config: { ...page.data.config, components: [{ componentKey: 'c_grid', componentType: 'TEXT_GRID', sortOrder: 1000, enabled: true, config: grid }], bottomNav: { enabled: false } } })
      await (team ? page.handlePublishTap() : page.handlePublish())
      assert.match(page.toasts.at(-1).title, variant === 'over-limit' ? /2000/ : variant === 'negative-padding' ? /内边距/ : /至少添加一处文字/)
      assert.equal(page.requests.length, 0); assert.equal(page.data[team ? 'highlightedComponentKey' : 'validationComponentKey'], 'c_grid')
    }
  })
}
test('new components survive personal render normalization and team data normalization in menus', () => {
  const grid = gridApi.createTextGrid(); grid.cells[0].blocks[0].runs[0].text = '内容'
  const contactInfo = { contactPhone: '123', contactWechat: '微信' }
  const personal = require('../utils/visitor-portfolio')
  assert.deepEqual(personal.normalizeRenderComponent({ componentType: 'TEXT_GRID', config: grid, textGrid: grid }).textGrid, grid)
  assert.deepEqual(personal.normalizeRenderComponent({ componentType: 'CONTACT_INFO', contactInfo }).contactInfo, { ...contactInfo, ...contactDefaults })
  const team = require('../pages/team-portfolios/utils/team-visitor-portfolio')
  const portfolio = team.normalizeTeamVisitorPortfolio({ renderData: { components: [
    { componentKey: 'grid', componentType: 'TEXT_GRID', data: grid }, { componentKey: 'contact', componentType: 'CONTACT_INFO', data: contactInfo }
  ] } })
  assert.equal(portfolio.components.length, 2); assert.deepEqual(portfolio.components[0].data, grid); assert.deepEqual(portfolio.components[1].data, contactInfo)
})
test('all four display pages register and consume both new component render structures', () => {
  for (const scope of ['portfolios', 'team-portfolios']) for (const section of ['standard-preview', 'visitor-portfolio']) {
    const folder = path.join(__dirname, '../pages', scope, section)
    const jsonFile = fs.readdirSync(folder).find(name => name.endsWith('.json')), wxmlFile = fs.readdirSync(folder).find(name => name.endsWith('.wxml'))
    const config = JSON.parse(fs.readFileSync(path.join(folder, jsonFile))), wxml = fs.readFileSync(path.join(folder, wxmlFile), 'utf8')
    for (const name of ['text-grid', 'contact-info']) assert.equal(config.usingComponents[`portfolio-${name}`], `../components/${name}/${name}`)
    assert.match(wxml, /<portfolio-text-grid[^>]+config="\{\{item\.(textGrid|data)\}\}"/)
    assert.match(wxml, /<portfolio-contact-info[^>]+config="\{\{item\.(contactInfo|data)\}\}"/)
  }
})
