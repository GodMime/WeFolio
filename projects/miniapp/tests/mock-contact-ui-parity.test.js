const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const MINIAPP_ROOT = path.resolve(__dirname, '..')
const LIGHT_CLICK_ICON = 'https://cdn2.we-folio.dingchenyong.top/system/click-tap-icon.gif'
const DARK_CLICK_ICON = 'https://cdn2.we-folio.dingchenyong.top/system/click-tap-icon-dark.gif'

function loadComponent(relativePath, properties = {}) {
  const entry = path.join(MINIAPP_ROOT, relativePath)
  const previousComponent = global.Component
  let definition
  global.Component = value => { definition = value }
  try {
    delete require.cache[require.resolve(entry)]
    require(entry)
  } finally {
    if (previousComponent === undefined) delete global.Component
    else global.Component = previousComponent
  }
  const defaults = Object.fromEntries(Object.entries(definition.properties || {}).map(([key, value]) => [key, value.value]))
  const events = []
  const instance = {
    data: { ...defaults, ...(definition.data || {}), ...properties },
    setData(values) { Object.assign(this.data, values) },
    triggerEvent(name, detail) { events.push({ name, detail }) },
    ...(definition.methods || {})
  }
  return { definition, instance, events }
}

function read(relativePath) {
  return fs.readFileSync(path.join(MINIAPP_ROOT, relativePath), 'utf8')
}

test('mock 联系信息关闭边框时不保留外侧留白', () => {
  const { definition, instance } = loadComponent('components/mock/contact-info/contact-info.js', {
    config: {
      contactPhone: '示例电话',
      contactBorder: false,
      horizontalMarginRpx: 24,
      verticalMarginRpx: 36
    },
    themeMode: 'light'
  })

  definition.observers['config, themeMode'].call(instance, instance.data.config, instance.data.themeMode)

  assert.equal(instance.data.spacingStyle, '')
  assert.equal(instance.data.borderStyle, '')
})

test('mock 联系信息编辑器按正式顺序展示并仅在开启边框后显示外观设置', () => {
  const markup = read('components/mock/contact-info-editor/contact-info-editor.wxml')
  const borderStart = markup.indexOf('class="contact-border-settings"')
  const vertical = markup.indexOf('上下留白', borderStart)
  const horizontal = markup.indexOf('左右留白', borderStart)
  const width = markup.indexOf('线框粗细', borderStart)
  const color = markup.indexOf('边框颜色', borderStart)

  assert.match(markup, /class="contact-border-settings" wx:if="\{\{config\.contactBorder\}\}"/)
  assert.ok(borderStart >= 0 && vertical > borderStart && horizontal > vertical && width > horizontal && color > width)
  assert.match(markup, />联系手机</)
  assert.match(markup, />联系微信</)
  assert.match(markup, />效果预览</)
})

test('mock 联系信息边框关闭时忽略已隐藏外观控件的迟到事件', () => {
  const { instance, events } = loadComponent('components/mock/contact-info-editor/contact-info-editor.js', {
    config: { contactPhone: '示例电话', contactBorder: false, verticalMarginRpx: 0 }
  })

  instance.handleNumber({ currentTarget: { dataset: { field: 'verticalMarginRpx' } }, detail: { value: 48 } })
  instance.handleBorderColor({ detail: { color: '#ABCDEF' } })

  assert.deepEqual(events, [])
})

test('mock 联系信息展示沿用正式字段文案和复制状态结构', () => {
  const markup = read('components/mock/contact-info/contact-info.wxml')

  assert.match(markup, />联系手机</)
  assert.match(markup, />联系微信</)
  assert.match(markup, /class="copy-button-face"/)
  assert.match(markup, /class="copy-icon"/)
})

test('mock 超链接按主题使用正式点击图标资源', () => {
  const { definition, instance } = loadComponent('components/mock/hyperlink/hyperlink.js', {
    config: { showClickIcon: true, iconPosition: 'BELOW' },
    themeMode: 'light'
  })

  const syncIcon = definition.observers && definition.observers['config.showClickIcon, config.iconPosition, themeMode']
  assert.equal(typeof syncIcon, 'function')
  syncIcon.call(instance, true, 'BELOW', 'light')
  assert.equal(instance.data.clickIconUrl, LIGHT_CLICK_ICON)
  assert.equal(instance.data.clickIconInside, false)

  instance.data.themeMode = 'dark'
  syncIcon.call(instance, true, 'BELOW', 'dark')
  assert.equal(instance.data.clickIconUrl, DARK_CLICK_ICON)
})

test('mock 超链接展示与正式版一样只渲染图标而不追加动作文字', () => {
  const markup = read('components/mock/hyperlink/hyperlink.wxml')

  assert.match(markup, /class="click-icon-image"/)
  assert.doesNotMatch(markup, /<text>↗/)
  assert.doesNotMatch(markup, /点击\{\{config\.actionType/)
})

test('mock 超链接编辑器仅在明确选择外部动作时显示复制字段', () => {
  const markup = read('components/mock/hyperlink-editor/hyperlink-editor.wxml')
  const { instance } = loadComponent('components/mock/hyperlink-editor/hyperlink-editor.js')

  assert.match(markup, /wx:if="\{\{config\.actionType === 'EXTERNAL_LINK'\}\}"/)
  assert.doesNotMatch(markup, /<view wx:else>\s*<text class="field-label">链接或分享内容/)
  assert.deepEqual(instance.data.iconPositions.map(item => item.label), [
    '图标悬浮于图片内右下角',
    '图标悬浮于图片内下方',
    '图标悬浮于图片内正中',
    '图标置于图片下方'
  ])
})
