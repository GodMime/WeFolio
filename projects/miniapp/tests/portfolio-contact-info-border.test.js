const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const clone = value => JSON.parse(JSON.stringify(value))
const CONTACT_TEXT = { contactPhone: '123456', contactWechat: 'example-wechat' }
const DEFAULT_BORDER = {
  contactBorder: false,
  horizontalMarginRpx: 0,
  verticalMarginRpx: 0,
  contactBorderWidthRpx: 1,
  contactBorderColor: 'AUTO'
}
const CUSTOM_BORDER = {
  contactBorder: true,
  horizontalMarginRpx: 24,
  verticalMarginRpx: 36,
  contactBorderWidthRpx: 4,
  contactBorderColor: '#AABBCC'
}
const inputEvent = (field, value) => ({ currentTarget: { dataset: { field } }, detail: { value } })
const stepEvent = (field, delta) => ({ currentTarget: { dataset: { field, delta } } })

function loadContactEditor(scope, config, platform = {}) {
  const filename = path.join(__dirname, '../pages', scope, 'components/contact-info-editor/contact-info-editor.js')
  let definition
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    Component: value => { definition = value },
    wx: {},
    require(relative) {
      const imported = require(path.resolve(path.dirname(filename), relative))
      return relative.includes('portfolio-component-platform') ? { ...imported, ...platform } : imported
    }
  }, { filename })
  const properties = Object.fromEntries(Object.entries(definition.properties).map(([key, value]) => [key, clone(value.value)]))
  Object.assign(properties, { config, visible: true })
  const editor = {
    properties,
    data: { ...clone(definition.data || {}), ...clone(properties) },
    events: [],
    setData(values, callback) { Object.assign(this.data, values); if (callback) callback() },
    triggerEvent(name, detail) { this.events.push({ name, detail: clone(detail || {}) }) },
    ...definition.methods
  }
  definition.observers.visible.call(editor, true)
  return { editor, definition }
}

function loadPage(scope) {
  const team = scope === 'team-portfolios'
  const filename = path.join(__dirname, '../pages', scope, 'standard-edit', `${team ? 'team-' : ''}portfolio-standard-edit.js`)
  let definition
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    Page: value => { definition = value },
    wx: { showToast() {} },
    setTimeout,
    clearTimeout,
    getApp: () => ({ globalData: {} }),
    require(relative) { return require(path.resolve(path.dirname(filename), relative)) }
  }, { filename })
  return {
    ...definition,
    data: clone(definition.data),
    setData(values, callback) { Object.assign(this.data, values); if (callback) callback() }
  }
}

function declarations(style) {
  return Object.fromEntries(String(style).split(';').filter(value => value.includes(':')).map(value => {
    const colon = value.indexOf(':')
    return [value.slice(0, colon).trim(), value.slice(colon + 1).trim()]
  }))
}

function styleFor(source, selector) {
  const style = {}
  for (const match of source.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    if (match[1].trim().split(',').some(value => value.trim() === selector)) Object.assign(style, declarations(match[2]))
  }
  return style
}

for (const scope of ['portfolios', 'team-portfolios']) {
  const api = require(`../pages/${scope}/utils/portfolio-contact-info`)

  test(`${scope}: legacy contacts keep their text and default to no border or added spacing`, () => {
    assert.deepEqual(api.normalizeContactInfo(CONTACT_TEXT), { ...CONTACT_TEXT, ...DEFAULT_BORDER })
    assert.deepEqual(api.normalizeContactInfo(), { contactPhone: '', contactWechat: '', ...DEFAULT_BORDER })
    assert.equal(api.validateContactInfo(CONTACT_TEXT, true), '')
    assert.deepEqual(api.buildContactInfoStyle(CONTACT_TEXT, 'light'), { spacingStyle: '', borderStyle: '' })
    assert.deepEqual(api.buildContactInfoStyle({ ...CONTACT_TEXT, ...CUSTOM_BORDER, contactBorder: false }, 'dark'), { spacingStyle: '', borderStyle: '' })
  })

  test(`${scope}: border ranges accept their exact limits and normalization safely replaces invalid values`, () => {
    for (const field of ['horizontalMarginRpx', 'verticalMarginRpx', 'contactBorderWidthRpx']) {
      const minimum = field === 'contactBorderWidthRpx' ? 1 : 0
      const maximum = field === 'contactBorderWidthRpx' ? 12 : 96
      for (const value of [minimum, maximum]) {
        const raw = { ...CONTACT_TEXT, ...CUSTOM_BORDER, [field]: value }
        assert.equal(api.validateContactInfo(raw, true), '', `${field}=${value}`)
        assert.equal(api.normalizeContactInfo(raw)[field], value)
      }
      for (const value of [minimum - 1, maximum + 1, 1.5, NaN, Infinity, 'invalid', null]) {
        const raw = { ...CONTACT_TEXT, ...CUSTOM_BORDER, [field]: value }
        assert.notEqual(api.validateContactInfo(raw, true), '', `${field}=${value}`)
        assert.equal(api.normalizeContactInfo(raw)[field], DEFAULT_BORDER[field])
      }
    }
    for (const value of ['true', 1, null]) {
      const raw = { ...CONTACT_TEXT, contactBorder: value }
      assert.notEqual(api.validateContactInfo(raw), '')
      assert.equal(api.normalizeContactInfo(raw).contactBorder, false)
    }
  })

  test(`${scope}: border colors allow AUTO and hex colors and reject unsupported CSS`, () => {
    for (const color of ['AUTO', '#000000', '#FFFFFF', '#AABBCC']) {
      const raw = { ...CONTACT_TEXT, ...CUSTOM_BORDER, contactBorderColor: color }
      assert.equal(api.validateContactInfo(raw), '')
      assert.equal(api.normalizeContactInfo(raw).contactBorderColor, color)
    }
    for (const color of ['', '#ABC', '#12345678', 'red', 'transparent', '#123456;background:red', null]) {
      const raw = { ...CONTACT_TEXT, ...CUSTOM_BORDER, contactBorderColor: color }
      assert.notEqual(api.validateContactInfo(raw), '', String(color))
      assert.equal(api.normalizeContactInfo(raw).contactBorderColor, 'AUTO')
    }
  })

  test(`${scope}: spacing stays outside the border and AUTO follows the page theme`, () => {
    const raw = { ...CONTACT_TEXT, ...CUSTOM_BORDER }
    const original = clone(raw)
    for (const theme of ['light', 'dark']) {
      const custom = api.buildContactInfoStyle(raw, theme)
      assert.equal(declarations(custom.spacingStyle).padding, '36rpx 24rpx')
      assert.equal(declarations(custom.borderStyle)['border-width'], '4rpx')
      assert.equal(declarations(custom.borderStyle)['border-color'], '#AABBCC')
      const automatic = api.buildContactInfoStyle({ ...raw, contactBorderColor: 'AUTO' }, theme)
      assert.equal(declarations(automatic.borderStyle)['border-color'], theme === 'dark' ? '#45464A' : '#D7DADD')
      assert.equal(declarations(automatic.spacingStyle).padding, '36rpx 24rpx')
    }
    assert.deepEqual(raw, original)
  })

  test(`${scope}: viewer binds outer spacing and redraws border colors when config or theme changes`, () => {
    const folder = path.join(__dirname, '../pages', scope, 'components/contact-info')
    const filename = path.join(folder, 'contact-info.js')
    let definition
    vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
      Component: value => { definition = value },
      require(relative) { return require(path.resolve(folder, relative)) }
    }, { filename })
    const config = { ...CONTACT_TEXT, ...CUSTOM_BORDER, contactBorderColor: 'AUTO' }, original = clone(config)
    const viewer = {
      properties: { config, themeMode: 'light' }, data: clone(definition.data), ...definition.methods,
      setData(values) { Object.assign(this.data, values) }
    }
    definition.lifetimes.attached.call(viewer)
    assert.equal(declarations(viewer.data.spacingStyle).padding, '36rpx 24rpx')
    assert.equal(declarations(viewer.data.borderStyle)['border-color'], '#D7DADD')
    viewer.properties.themeMode = 'dark'
    definition.observers['config, themeMode'].call(viewer)
    assert.equal(declarations(viewer.data.borderStyle)['border-color'], '#45464A')
    viewer.properties.config = { ...config, contactBorderColor: '#112233', contactBorderWidthRpx: 12 }
    definition.observers['config, themeMode'].call(viewer)
    assert.equal(declarations(viewer.data.borderStyle)['border-color'], '#112233')
    assert.equal(declarations(viewer.data.borderStyle)['border-width'], '12rpx')
    viewer.properties.config = { ...config, contactBorder: false }
    definition.observers['config, themeMode'].call(viewer)
    assert.equal(viewer.data.spacingStyle, '')
    assert.equal(viewer.data.borderStyle, '')
    assert.deepEqual(config, original)
    const markup = fs.readFileSync(path.join(folder, 'contact-info.wxml'), 'utf8')
    assert.match(markup, /<view\b[^>]*class="contact-spacing"[^>]*style="\{\{spacingStyle\}\}"[^>]*>\s*<view\b[^>]*class="contact-info[^>]*style="\{\{borderStyle\}\}"/)
  })

  test(`${scope}: contact border controls stay inside a scrollable body while header and actions remain fixed`, () => {
    const folder = path.join(__dirname, '../pages', scope, 'components')
    const markup = fs.readFileSync(path.join(folder, 'contact-info-editor/contact-info-editor.wxml'), 'utf8')
    const sharedStyles = fs.readFileSync(path.join(folder, 'text-grid-editor/text-grid-editor.wxss'), 'utf8')
    const ownStyles = fs.readFileSync(path.join(folder, 'contact-info-editor/contact-info-editor.wxss'), 'utf8')
    assert.match(ownStyles, /@import "\.\.\/text-grid-editor\/text-grid-editor\.wxss"/)
    const scrollStart = markup.indexOf('<scroll-view'), scrollEnd = markup.indexOf('</scroll-view>')
    assert.equal((markup.match(/<scroll-view\b/g) || []).length, 1)
    assert.ok(markup.indexOf('class="grid-header"') < scrollStart)
    assert.ok(markup.lastIndexOf('class="grid-actions"') > scrollEnd)
    const scroll = markup.slice(scrollStart, scrollEnd)
    assert.match(scroll, /scroll-y type="list"/)
    assert.match(scroll, /scroll-top="\{\{scrollTop\}\}" bindscroll="handleScroll"/)
    assert.match(scroll, /<switch\b[^>]*checked="\{\{draft.contactBorder\}\}"[^>]*bindchange="handleBorderToggle"/)
    const settingsTag = scroll.match(/<view\b[^>]*class="grid-border-settings \{\{draft.contactBorder \? 'is-visible' : ''\}\}"[^>]*>/)
    assert.ok(settingsTag)
    assert.doesNotMatch(settingsTag[0], /wx:if|hidden=/)
    const settings = scroll.slice(scroll.indexOf(settingsTag[0]), scroll.indexOf('<view wx:if="{{error}}"'))
    assert.match(settings, /wx:for="\{\{borderFields\}\}"/)
    assert.match(settings, /bindinput="handleBorderNumberInput" disabled="\{\{!draft.contactBorder\}\}"/)
    assert.equal((settings.match(/bindtap="handleBorderStep"/g) || []).length, 2)
    assert.match(settings, /<text-color-editor\b[^>]*inline="\{\{true\}\}"[^>]*color="\{\{draft.contactBorderColor\}\}"[^>]*active="\{\{visible && draft.contactBorder\}\}"[^>]*bindchange="handleBorderColor"/)
    for (const selector of ['.grid-header', '.grid-actions']) assert.equal(styleFor(sharedStyles, selector)['flex-shrink'], '0')
    const panel = styleFor(sharedStyles, '.grid-panel'), scrollStyle = styleFor(sharedStyles, '.grid-scroll')
    assert.equal(panel.display, 'flex'); assert.equal(panel['flex-direction'], 'column')
    assert.equal(scrollStyle.flex, '1'); assert.equal(scrollStyle['min-height'], '0'); assert.equal(scrollStyle.height, '0')
    const collapsed = styleFor(sharedStyles, '.grid-border-settings'), expanded = styleFor(sharedStyles, '.grid-border-settings.is-visible')
    assert.equal(collapsed['max-height'], '0'); assert.equal(collapsed['pointer-events'], 'none')
    assert.equal(expanded.opacity, '1')
    assert.equal(styleFor(sharedStyles, '.grid-mask.is-visible .grid-border-settings.is-visible')['pointer-events'], 'auto')
    const { editor } = loadContactEditor(scope, { ...CONTACT_TEXT, ...CUSTOM_BORDER })
    assert.deepEqual(editor.data.borderFields.map(({ field, min, max }) => ({ field, min, max })), [
      { field: 'verticalMarginRpx', min: 0, max: 96 },
      { field: 'horizontalMarginRpx', min: 0, max: 96 },
      { field: 'contactBorderWidthRpx', min: 1, max: 12 }
    ])
  })

  test(`${scope}: reopening resets scroll and disabled or closed border controls ignore late edits`, () => {
    const { editor, definition } = loadContactEditor(scope, { ...CONTACT_TEXT, ...CUSTOM_BORDER })
    editor.handleScroll({ detail: { scrollTop: 400 } })
    editor.handleCancel()
    definition.observers.visible.call(editor, true)
    assert.equal(editor.data.scrollTop, 0)
    assert.equal(editor._scrollTop, 0)
    editor.handleBorderToggle({ detail: { value: false } })
    const disabled = clone(editor.data.draft)
    editor.handleBorderNumberInput(inputEvent('horizontalMarginRpx', '88'))
    editor.handleBorderStep(stepEvent('contactBorderWidthRpx', 1))
    editor.handleBorderColor({ detail: { color: '#112233' } })
    assert.deepEqual(clone(editor.data.draft), disabled)
    editor.handleCancel()
    editor.handleBorderToggle({ detail: { value: true } })
    assert.deepEqual(clone(editor.data.draft), disabled)
    definition.observers.visible.call(editor, true)
    editor.properties.visible = false
    definition.observers.visible.call(editor, false)
    const closed = clone(editor.data.draft)
    editor.handleBorderNumberInput(inputEvent('horizontalMarginRpx', '88'))
    editor.handleBorderStep(stepEvent('contactBorderWidthRpx', 1))
    editor.handleBorderColor({ detail: { color: '#112233' } })
    assert.deepEqual(clone(editor.data.draft), closed)
  })

  test(`${scope}: editor toggles and changes each border setting without mutating the saved contact`, () => {
    const config = { ...CONTACT_TEXT, ...DEFAULT_BORDER }, original = clone(config)
    const { editor, definition } = loadContactEditor(scope, config)
    editor.handleBorderToggle({ detail: { value: true } })
    for (const [field, value] of Object.entries(CUSTOM_BORDER)) {
      if (typeof value === 'number') editor.handleBorderNumberInput(inputEvent(field, String(value)))
    }
    editor.handleBorderColor({ detail: { color: '#AABBCC' } })
    assert.deepEqual(clone(editor.data.draft), { ...CONTACT_TEXT, ...CUSTOM_BORDER })
    editor.handleConfirm()
    assert.equal(editor.events.at(-1).name, 'confirm')
    const saved = editor.events.at(-1).detail
    assert.deepEqual(saved, { ...CONTACT_TEXT, ...CUSTOM_BORDER })
    assert.deepEqual(config, original)

    editor.properties.config = saved
    definition.observers.visible.call(editor, true)
    assert.deepEqual(clone(editor.data.draft), saved)
    editor.handleBorderToggle({ detail: { value: false } })
    editor.handleConfirm()
    assert.deepEqual(editor.events.at(-1).detail, { ...saved, contactBorder: false })
    const disabled = editor.events.at(-1).detail
    editor.properties.config = disabled
    definition.observers.visible.call(editor, true)
    editor.handleBorderToggle({ detail: { value: true } })
    assert.deepEqual(clone(editor.data.draft), saved)
    editor.handleBorderNumberInput(inputEvent('contactBorderWidthRpx', '10'))
    editor.handleCancel()
    assert.deepEqual(saved, { ...CONTACT_TEXT, ...CUSTOM_BORDER })
    definition.observers.visible.call(editor, true)
    assert.deepEqual(clone(editor.data.draft), disabled)
  })

  test(`${scope}: border steppers move by one and stop at spacing and width limits`, () => {
    const { editor } = loadContactEditor(scope, { ...CONTACT_TEXT, ...CUSTOM_BORDER })
    for (const field of ['horizontalMarginRpx', 'verticalMarginRpx', 'contactBorderWidthRpx']) {
      const minimum = field === 'contactBorderWidthRpx' ? 1 : 0
      const maximum = field === 'contactBorderWidthRpx' ? 12 : 96
      for (const [value, delta, expected] of [[minimum, -1, minimum], [minimum, 1, minimum + 1], [maximum - 1, 1, maximum], [maximum, 1, maximum], [maximum, -1, maximum - 1]]) {
        editor.handleBorderNumberInput(inputEvent(field, String(value)))
        editor.handleBorderStep(stepEvent(field, delta))
        assert.equal(editor.data.draft[field], expected, `${field}: ${value} + ${delta}`)
      }
    }
  })

  test(`${scope}: invalid numeric input stays visible and blocks confirm until corrected`, () => {
    for (const field of ['horizontalMarginRpx', 'verticalMarginRpx', 'contactBorderWidthRpx']) {
      for (const value of ['', '-1', '1.5', '97', 'invalid']) {
        const { editor } = loadContactEditor(scope, { ...CONTACT_TEXT, ...CUSTOM_BORDER })
        editor.handleBorderNumberInput(inputEvent(field, value))
        assert.equal(String(editor.data.draft[field]), value)
        editor.handleConfirm()
        assert.equal(editor.events.length, 0, `${field}=${value}`)
        assert.notEqual(editor.data.error, '')
        editor.handleBorderNumberInput(inputEvent(field, '8'))
        editor.handleConfirm()
        assert.equal(editor.events.at(-1).name, 'confirm')
        assert.equal(editor.events.at(-1).detail[field], 8)
      }
    }
  })

  test(`${scope}: refilling the contact profile preserves border edits made during the request`, async () => {
    const config = { ...CONTACT_TEXT, ...CUSTOM_BORDER }, original = clone(config)
    let resolveProfile
    const { editor } = loadContactEditor(scope, config, { loadContactProfile: () => new Promise(resolve => { resolveProfile = resolve }) })
    const pending = editor.handleRefill()
    editor.handleBorderNumberInput(inputEvent('horizontalMarginRpx', '48'))
    editor.handleBorderNumberInput(inputEvent('verticalMarginRpx', '60'))
    editor.handleBorderNumberInput(inputEvent('contactBorderWidthRpx', '8'))
    editor.handleBorderColor({ detail: { color: '#112233' } })
    resolveProfile({ contactPhone: '654321', contactWechat: 'refilled-wechat', ...DEFAULT_BORDER })
    await pending
    assert.deepEqual(clone(editor.data.draft), {
      contactPhone: '654321', contactWechat: 'refilled-wechat', contactBorder: true,
      horizontalMarginRpx: 48, verticalMarginRpx: 60, contactBorderWidthRpx: 8, contactBorderColor: '#112233'
    })
    assert.deepEqual(config, original)
    assert.equal(editor.data.loading, false)
  })

  test(`${scope}: confirmed border settings survive both menu positions and reopen without touching saved values`, () => {
    for (const position of ['root', 'second']) {
      const page = loadPage(scope)
      const team = scope === 'team-portfolios'
      const portfolioApi = team ? require('../pages/team-portfolios/utils/team-portfolios') : require('../utils/portfolios')
      const normalize = team ? portfolioApi.normalizeTeamPortfolioConfig : portfolioApi.normalizePortfolioConfig
      let config = clone(page.data.config)
      if (position === 'second') {
        config = (team ? portfolioApi.setTeamBottomNavigationCount : portfolioApi.setBottomNavigationCount)(config, 2)
        page.setData({ config, activeMenuKey: config.bottomNav.items[1].key })
      } else page.setData({ config: { ...config, bottomNav: { enabled: false } }, activeMenuKey: '' })
      const snapshot = { ...CONTACT_TEXT, ...CUSTOM_BORDER }
      page.openNewComponentSheet('CONTACT_INFO')
      page.handleConfirmNewComponent({ detail: snapshot })
      const normalized = normalize(clone(page.data.config))
      const components = position === 'second' ? normalized.bottomNav.items[1].components : normalized.components
      const saved = components.find(item => item.componentType === 'CONTACT_INFO')
      assert.ok(saved)
      assert.deepEqual(saved.config, snapshot)
      page.setData({ config: normalized })
      page.openNewComponentSheet('CONTACT_INFO', saved.componentKey)
      assert.deepEqual(clone(page.data.newComponentConfig), snapshot)
      page.data.newComponentConfig.contactBorderWidthRpx = 12
      page.handleCancelNewComponent()
      assert.deepEqual(saved.config, snapshot)
    }
  })
}

test('personal and team visitor normalization retains all contact border settings', () => {
  const config = { ...CONTACT_TEXT, ...CUSTOM_BORDER }
  const personal = require('../utils/visitor-portfolio')
  for (const raw of [{ contactInfo: config }, { config }]) {
    const component = personal.normalizeRenderComponent({ componentType: 'CONTACT_INFO', ...raw })
    assert.deepEqual(component.contactInfo, config)
  }
  const team = require('../pages/team-portfolios/utils/team-visitor-portfolio')
  const rendered = team.normalizeTeamVisitorPortfolio({ renderData: { components: [{ componentType: 'CONTACT_INFO', componentKey: 'contact', data: config }] } })
  assert.deepEqual(rendered.components[0].data, config)
})

test('contact border utilities remain identical across isolated business subpackages', () => {
  const sources = ['portfolios', 'team-portfolios'].map(scope => fs.readFileSync(path.join(__dirname, '../pages', scope, 'utils/portfolio-contact-info.js'), 'utf8'))
  assert.equal(sources[0], sources[1])
})

test('main-package and subpackage contact normalization agree for legacy, customized and malformed configs', () => {
  const main = require('../utils/portfolios').normalizeContactInfoConfig
  const isolated = ['portfolios', 'team-portfolios'].map(scope => require(`../pages/${scope}/utils/portfolio-contact-info`).normalizeContactInfo)
  for (const raw of [undefined, null, {}, CONTACT_TEXT, { ...CONTACT_TEXT, ...CUSTOM_BORDER },
    { contactPhone: ' 123 ', contactWechat: ' wechat ', contactBorder: true, contactBorderWidthRpx: 12, horizontalMarginRpx: 96, verticalMarginRpx: 96, contactBorderColor: '#aabbcc' },
    { contactPhone: 123, contactWechat: null, contactBorder: 'true', contactBorderWidthRpx: -1, horizontalMarginRpx: 97, verticalMarginRpx: 1.5, contactBorderColor: 'red' }]) {
    for (const normalize of isolated) assert.deepEqual(main(raw), normalize(raw))
  }
})
