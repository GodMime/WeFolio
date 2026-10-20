const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const { buildPreviewVersionOptions, calculatePreviewVersionMenuLayout } = require('../pages/portfolios/utils/preview-version-menu')

function pixels(style) {
  return Object.fromEntries(Array.from(style.matchAll(/([a-z]+):\s*([\d.-]+)px/g), (match) => [match[1], Number(match[2])]))
}

test('预览选项保持草稿在前，并准确承接正式和体验版的说明', () => {
  for (const draftDescription of ['最近保存的内容', '仅包含已保存内容', '本地体验草稿', '当前编辑的体验内容']) {
    const publishedDescription = draftDescription.includes('体验') ? '固定的演示发布内容' : '访客当前看到的内容'
    assert.deepEqual(buildPreviewVersionOptions({ draftDescription, publishedDescription }), [
      { scope: 'draft', label: '草稿版', description: draftDescription },
      { scope: 'published', label: '发布版', description: publishedDescription }
    ])
  }
})

test('列表菜单在常用窄屏下与按钮右侧对齐，箭头指向按钮中心', () => {
  for (const windowWidth of [320, 375, 390]) {
    const rect = { left: windowWidth - 72, right: windowWidth - 20, top: 210, bottom: 244 }
    const layout = calculatePreviewVersionMenuLayout(rect, { windowWidth, windowHeight: 700 })
    const menu = pixels(layout.menuStyle)
    assert.equal(layout.placement, 'below')
    assert.equal(menu.width, 238)
    assert.equal(menu.left + menu.width, rect.right)
    assert.equal(menu.top, rect.bottom + 10)
    assert.equal(menu.left + pixels(layout.arrowStyle).left + 4, (rect.left + rect.right) / 2)
  }
})

test('列表末项向上展开，编辑底栏优先向上且避开安全区', () => {
  const viewport = { windowWidth: 375, windowHeight: 812, safeArea: { top: 44, bottom: 778 } }
  const rect = { left: 20, right: 120, top: 712, bottom: 758 }
  for (const preferAbove of [false, true]) {
    const layout = calculatePreviewVersionMenuLayout(rect, viewport, { preferAbove })
    const menu = pixels(layout.menuStyle)
    assert.equal(layout.placement, 'above')
    assert.equal(menu.top + 104 + 10, rect.top)
    assert.equal(menu.left, 12)
    assert.equal(menu.left + pixels(layout.arrowStyle).left + 4, 70)
  }
})

test('上方不够时向下，菜单不越过左右安全边界', () => {
  const layout = calculatePreviewVersionMenuLayout(
    { left: 8, right: 50, top: 40, bottom: 84 },
    { windowWidth: 240, windowHeight: 400, safeArea: { left: 10, right: 230, top: 20, bottom: 380 } },
    { preferAbove: true }
  )
  const menu = pixels(layout.menuStyle)
  assert.equal(layout.placement, 'below')
  assert.equal(menu.left, 22)
  assert.equal(menu.width, 196)
  assert.ok(menu.top + 104 <= 368)
})

test('无效、不可见锚点或容不下完整菜单时不打开，支持平台返回的宽高矩形', () => {
  const viewport = { windowWidth: 375, windowHeight: 700 }
  for (const rect of [null, {}, { left: 1, top: 2, width: 0, height: 20 }, { left: NaN, top: 2, width: 20, height: 20 }, { left: 10, right: 40, top: 710, bottom: 744 }]) {
    assert.equal(calculatePreviewVersionMenuLayout(rect, viewport), null)
  }
  const rect = { left: 20, top: 50, width: 60, height: 40 }
  assert.ok(calculatePreviewVersionMenuLayout(rect, viewport))
  assert.equal(calculatePreviewVersionMenuLayout(rect, { windowWidth: 0, windowHeight: 700 }), null)
  assert.equal(calculatePreviewVersionMenuLayout(rect, { windowWidth: 375, windowHeight: 110 }), null)
})

test('三个分包的预览菜单纯逻辑保持一致，不跨包引用', () => {
  const read = (subpackage) => fs.readFileSync(path.join(__dirname, `../pages/${subpackage}/utils/preview-version-menu.js`), 'utf8')
  assert.equal(read('portfolios'), read('team-portfolios'))
  assert.equal(read('portfolios'), read('mock'))
  assert.doesNotMatch(read('portfolios'), /require\(|\bwx\./)
})

test('五个入口使用同款独立菜单和透明承接层，菜单行满足触摸高度', () => {
  const entries = [
    'portfolios/portfolios',
    'portfolios/standard-edit/portfolio-standard-edit',
    'team-portfolios/standard-edit/team-portfolio-standard-edit',
    'mock/portfolios/portfolios',
    'mock/portfolio-standard-edit/portfolio-standard-edit'
  ]
  let menuStyles
  for (const entry of entries) {
    const read = (ext) => fs.readFileSync(path.join(__dirname, `../pages/${entry}.${ext}`), 'utf8')
    const wxml = read('wxml')
    const wxss = read('wxss')
    const menuPortal = wxml.match(/<root-portal wx:if="\{\{previewVersionMenu\}\}">[\s\S]*?<\/root-portal>/)?.[0]
    assert.ok(menuPortal, entry)
    assert.match(menuPortal, /class="preview-version-menu-layer"[^>]*catchtap="((?:handleClose|close)PreviewVersionMenu)"[^>]*catchtouchmove="\1"/)
    assert.match(menuPortal, /wx:key="scope"/)
    assert.match(wxss, /\.preview-version-menu-layer\s*\{[^}]*background:\s*transparent;/)
    assert.match(wxss, /\.preview-version-menu__option\s*\{[^}]*height:\s*47px;[^}]*min-height:\s*44px;/)
    const styles = [...wxss.matchAll(/^\.preview-version-menu[^\{]*\{[^}]*\}/gm)].map(match => match[0]).join('\n')
    if (!menuStyles) menuStyles = styles
    else assert.equal(styles, menuStyles, `${entry} 的新增菜单样式应保持一致`)
  }
})

test('预览菜单使用有明确尺寸的单层固定容器，选项随菜单在容器内定位', () => {
  for (const entry of [
    'portfolios/portfolios',
    'portfolios/standard-edit/portfolio-standard-edit',
    'team-portfolios/standard-edit/team-portfolio-standard-edit',
    'mock/portfolios/portfolios',
    'mock/portfolio-standard-edit/portfolio-standard-edit'
  ]) {
    const wxss = fs.readFileSync(path.join(__dirname, `../pages/${entry}.wxss`), 'utf8')
    const layer = wxss.match(/\.preview-version-menu-layer\s*\{([^}]*)\}/)[1]
    const menu = wxss.match(/\.preview-version-menu\s*\{([^}]*)\}/)[1]
    assert.match(layer, /position:\s*fixed;/, entry)
    assert.match(layer, /width:\s*100%;/, `${entry} 承接层必须明确覆盖视口宽度`)
    assert.match(layer, /height:\s*100%;/, `${entry} 承接层不能依赖脱离文档流的子元素撑高`)
    assert.match(menu, /position:\s*absolute;/, `${entry} 菜单需位于承接层坐标系内，避免父子双 fixed`)
    assert.doesNotMatch(menu, /position:\s*fixed;/, entry)
  }
})
