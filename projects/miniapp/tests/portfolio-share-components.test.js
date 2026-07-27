const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const MINIAPP_ROOT = path.resolve(__dirname, '..')
const MAX_STATIC_ASSET_BYTES = 200 * 1024

function read(relativePath) {
  return fs.readFileSync(path.join(MINIAPP_ROOT, relativePath), 'utf8')
}

function loadComponent(relativePath, propertyValues = {}) {
  const componentPath = path.join(MINIAPP_ROOT, 'components', `${relativePath}.js`)
  const previousComponent = global.Component
  let definition
  delete require.cache[require.resolve(componentPath)]
  try {
    global.Component = (value) => { definition = value }
    require(componentPath)
  } finally {
    if (previousComponent === undefined) delete global.Component
    else global.Component = previousComponent
  }
  const data = Object.assign({}, definition.data || {})
  for (const [name, property] of Object.entries(definition.properties || {})) {
    data[name] = Object.prototype.hasOwnProperty.call(propertyValues, name)
      ? propertyValues[name]
      : property.value
  }
  const events = []
  const component = {
    data,
    events,
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name, detail) { events.push([name, detail]) }
  }
  for (const [name, method] of Object.entries(definition.methods || {})) {
    component[name] = method.bind(component)
  }
  for (const [name, lifetime] of Object.entries(definition.lifetimes || {})) {
    component[name] = lifetime.bind(component)
  }
  return component
}

test('share channel sheet provides native friend share and timeline event', () => {
  const wxml = read('components/share-channel-sheet/share-channel-sheet.wxml')
  const wxss = read('components/share-channel-sheet/share-channel-sheet.wxss')
  const json = JSON.parse(read('components/share-channel-sheet/share-channel-sheet.json'))
  const maskRule = wxss.match(/\.share-sheet-mask\s*\{([^}]*)\}/)?.[1] || ''

  assert.equal(json.component, true)
  assert.match(wxml, /<root-portal\s+wx:if="\{\{visible\}\}">/)
  assert.match(wxml, /<view class="share-sheet-mask"/)
  assert.match(wxml, /<\/root-portal>/)
  assert.match(wxml, /open-type="share"/)
  assert.match(wxml, /disabled="\{\{disabled\}\}"/)
  assert.match(wxml, /bindtap="handleTimeline"/)
  assert.match(wxml, /bindtap="handleClose"/)
  assert.match(wxml, /catchtouchmove="noop"/)
  assert.match(wxss, /env\(safe-area-inset-bottom\)/)
  assert.match(wxss, /225ms/)
  assert.match(maskRule, /left:\s*0/)
  assert.match(maskRule, /right:\s*0/)
  assert.match(maskRule, /top:\s*0/)
  assert.match(maskRule, /bottom:\s*0/)
  assert.doesNotMatch(maskRule, /inset:/)

  const component = loadComponent('share-channel-sheet/share-channel-sheet')
  component.handleTimeline()
  component.handleClose()
  assert.deepEqual(component.events, [['timeline', {}], ['close', {}]])
})

test('share channel sheet keeps the floating panel and uses full-width branded action rows', () => {
  const wxml = read('components/share-channel-sheet/share-channel-sheet.wxml')
  const wxss = read('components/share-channel-sheet/share-channel-sheet.wxss')

  assert.match(wxml, /src="\/assets\/system\/share-friend-gold\.png"/)
  assert.match(wxml, /src="\/assets\/system\/share-timeline-gold\.png"/)
  assert.match(wxml, /发送给微信好友/)
  assert.match(wxml, /进入作品集后分享/)
  assert.doesNotMatch(wxml, /friend-bubble|timeline-ring|timeline-dot|share-sheet-action-arrow/)

  assert.match(wxss, /\.share-sheet-mask\s*\{[^}]*padding:\s*0/s)
  assert.match(wxss, /\.share-sheet-panel\s*\{[^}]*border-radius:\s*56rpx 56rpx 0 0/s)
  assert.match(wxss, /\.share-sheet-action\s*\{[^}]*width:\s*100%[^}]*min-width:\s*100%[^}]*max-width:\s*100%/s)
  assert.match(wxss, /\.share-sheet-action\s*\{[^}]*height:\s*136rpx/s)
  assert.match(wxss, /\.share-sheet-icon\s*\{[^}]*width:\s*88rpx[^}]*height:\s*88rpx/s)
  assert.doesNotMatch(wxss, /#d9a84a|#b88a44|#15945f|#e8f7ef/i)
})

test('share channel icons stay within the WeChat static asset size limit', () => {
  const iconPaths = [
    'assets/system/share-friend-gold.png',
    'assets/system/share-timeline-gold.png'
  ]

  for (const iconPath of iconPaths) {
    const iconSize = fs.statSync(path.join(MINIAPP_ROOT, iconPath)).size
    assert.ok(
      iconSize <= MAX_STATIC_ASSET_BYTES,
      `${iconPath} is ${iconSize} bytes, max ${MAX_STATIC_ASSET_BYTES} bytes`
    )
  }
})

test('share channel sheet blocks close and timeline actions while disabled', () => {
  const component = loadComponent('share-channel-sheet/share-channel-sheet', { disabled: true })

  component.handleTimeline()
  component.handleClose()

  assert.deepEqual(component.events, [])
})

test('timeline share guide emits close and back while using a clear menu arrow', () => {
  const wxml = read('components/timeline-share-guide/timeline-share-guide.wxml')
  const wxss = read('components/timeline-share-guide/timeline-share-guide.wxss')
  const json = JSON.parse(read('components/timeline-share-guide/timeline-share-guide.json'))
  const maskRule = wxss.match(/\.timeline-guide-mask\s*\{([^}]*)\}/)?.[1] || ''

  assert.equal(json.component, true)
  assert.match(wxml, /<root-portal\s+wx:if="\{\{visible\}\}">/)
  assert.match(wxml, /<view class="timeline-guide-mask"/)
  assert.match(wxml, /<\/root-portal>/)
  assert.match(wxml, /点击右上角/)
  assert.match(wxml, /分享到朋友圈/)
  assert.match(wxml, /我知道了/)
  assert.match(wxml, /wx:if="\{\{back\}\}"/)
  assert.match(wxml, /bindtap="handleBack"/)
  assert.match(wxml, /class="timeline-guide-back"[^>]*style="\{\{navigationBackStyle\}\}"/s)
  assert.match(wxml, /class="timeline-guide-menu-arrow"[^>]*style="\{\{menuArrowStyle\}\}"/)
  assert.doesNotMatch(wxml, /timeline-guide-menu-dots|timeline-guide-pointer/)
  assert.match(wxml, /catchtouchmove="noop"/)
  assert.match(wxss, /env\(safe-area-inset-bottom\)/)
  assert.match(wxss, /225ms/)
  assert.match(maskRule, /left:\s*0/)
  assert.match(maskRule, /right:\s*0/)
  assert.match(maskRule, /top:\s*0/)
  assert.match(maskRule, /bottom:\s*0/)
  assert.doesNotMatch(maskRule, /inset:/)
  assert.match(wxss, /\.timeline-guide-mask\s*\{[^}]*padding:\s*0/s)
  assert.match(wxss, /\.timeline-guide-panel\s*\{[^}]*border-radius:\s*56rpx 56rpx 0 0/s)
  assert.doesNotMatch(wxss, /#d9a84a|#b88a44/i)

  const component = loadComponent('timeline-share-guide/timeline-share-guide', { back: true })
  component.handleBack()
  component.handleClose()
  assert.deepEqual(component.events, [['back', {}], ['close', {}]])
})

test('timeline share guide aligns overlay controls to the native menu in simulator coordinates', () => {
  const previousWx = global.wx
  global.wx = {
    getSystemInfoSync() { throw new Error('不应调用已废弃的 wx.getSystemInfoSync') },
    getWindowInfo() { return { windowWidth: 375 } },
    getMenuButtonBoundingClientRect() {
      return { top: 50, bottom: 82, right: 363, height: 32 }
    }
  }

  try {
    const component = loadComponent('timeline-share-guide/timeline-share-guide', { back: true })
    component.attached()

    assert.equal(component.data.navigationBackStyle, 'top: 50px; width: 32px; height: 32px;')
    assert.equal(component.data.menuArrowStyle, 'top: 88px; right: 20px;')
  } finally {
    if (previousWx === undefined) delete global.wx
    else global.wx = previousWx
  }
})
