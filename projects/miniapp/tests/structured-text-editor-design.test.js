const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function rule(css, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = css.match(new RegExp(`(?:^|\\n)${escaped}\\s*\\{([^}]+)\\}`))
  assert.ok(match, `缺少样式 ${selector}`)
  return Object.fromEntries(match[1].split(';').filter(value => value.includes(':'))
    .map(value => value.trim().split(/:(.*)/s).slice(0, 2).map(part => part.trim())))
}

for (const directory of ['portfolios', 'team-portfolios']) {
  const base = path.join(__dirname, `../pages/${directory}/components/structured-text-editor/structured-text-editor`)
  const css = fs.readFileSync(`${base}.wxss`, 'utf8')
  const template = fs.readFileSync(`${base}.wxml`, 'utf8')

  test(`${directory} 拖动露出的占位底色不是删除红色，拖动卡片不透底`, () => {
    assert.equal(rule(css, '.structured-row').background, '#F8F9FA')
    assert.equal(rule(css, '.structured-row-main.dragging').opacity, '1')
    assert.equal(rule(css, '.structured-delete').background, '#C8493B')
    assert.match(template, /class="structured-delete \{\{!draggingKey && revealedKey === item.blockKey \? 'delete-visible' : ''\}\}"/)
  })

  test(`${directory} 添加类型引导与选项同组显隐，说明位于可点击选项之前`, () => {
    const picker = template.match(/<view class="structured-type-picker \{\{showTypes \? 'is-expanded' : ''\}\}" aria-hidden="\{\{!showTypes\}\}">([\s\S]*?)\s*<view class="structured-meta">\{\{count\}\}/)
    assert.ok(picker, '引导和类型选项应一起展开、收起')
    assert.match(picker[1], /class="structured-label">[^<]+<\/view>\s*<view class="structured-meta">[^<]+<\/view>[\s\S]*?class="structured-options"[\s\S]*?bindtap="handleAddType"/)
  })

  test(`${directory} 详情层常驻且退出过渡不被主弹层样式覆盖`, () => {
    assert.match(template, /<view class="structured-detail-mask \{\{blockDraft && !detailClosing \? 'detail-visible' : ''\}\}/)
    const hidden = rule(css, '.structured-detail-mask .detail-panel')
    const visible = rule(css, '.structured-detail-mask.detail-visible .detail-panel')
    assert.notEqual(hidden.transform, visible.transform)
    assert.equal(rule(css, '.structured-detail-mask').opacity, '0')
    assert.equal(rule(css, '.structured-detail-mask.detail-visible').opacity, '1')
    assert.match(hidden.transition, /transform/)
    assert.match(rule(css, '.structured-type-picker').transition, /max-height/)
    assert.match(rule(css, '.structured-tab-body').transition, /opacity/)
  })

  test(`${directory} 字号使用带边界禁用和即时输入的间距同款步进器`, () => {
    const font = template.match(/<view class="structured-spacing-row structured-font-size">([\s\S]*?)<view class="structured-label">字重/)
    assert.ok(font)
    assert.match(font[1], /class="structured-stepper"/)
    assert.match(font[1], /disabled="\{\{fontDecreaseDisabled \|\| detailClosing\}\}"/)
    assert.match(font[1], /disabled="\{\{fontIncreaseDisabled \|\| detailClosing\}\}"/)
    assert.match(font[1], /bindinput="handleFontSizeInput"/)
    assert.match(font[1], /\{\{fontSizeError\}\}/)
    assert.doesNotMatch(template, /wx:for="\{\{sizes\}\}"/)
  })

  test(`${directory} 添加区块覆盖全局按钮重置，保持整行虚线按钮与触摸高度`, () => {
    const add = rule(css, '.structured-body .structured-add')
    assert.equal(add.width, '100%')
    assert.equal(add['box-sizing'], 'border-box')
    assert.ok(parseInt(add['min-height']) >= 80)
    assert.ok(parseInt(add.padding) >= 20)
    assert.match(add.border, /dashed/)
    assert.equal(add.background, 'transparent')
  })

  test(`${directory} 内容背景页签与作品集页使用一致的居中短下划线`, () => {
    const reference = fs.readFileSync(path.join(__dirname, '../pages/portfolios/portfolios.wxss'), 'utf8')
    const pairs = [
      ['.structured-tabs', '.segment', ['position', 'display', 'min-height', 'border-bottom']],
      ['.structured-tab', '.segment-item', ['flex', 'height', 'color', 'font-size', 'font-weight', 'line-height']],
      ['.structured-tabs .active', '.segment-item.active', ['color', 'font-weight', 'background']],
      ['.structured-tab-indicator', '.segment-indicator', ['position', 'left', 'bottom', 'width', 'height', 'margin-left', 'border-radius', 'background', 'pointer-events', 'transition']],
      ['.structured-tab-indicator.background-active', '.segment-indicator.team-active', ['left']]
    ]
    for (const [selector, referenceSelector, properties] of pairs) {
      const actual = rule(css, selector)
      const expected = rule(reference, referenceSelector)
      for (const property of properties) {
        assert.equal(actual[property], expected[property], `${selector} 的 ${property} 应与作品集页一致`)
      }
    }
    assert.match(template, /class="structured-tab-indicator \{\{tab === 'background' \? 'background-active' : ''\}\}"/)
    assert.doesNotMatch(css, /\.structured-tabs view\s*\{/)
    assert.equal(rule(css, '.structured-tabs .active')['border-bottom'], undefined)
  })

  test(`${directory} 区块计数与标题同行，列表使用白底细分隔线`, () => {
    assert.match(template, /class="structured-header">\s*<view class="structured-title">[\s\S]*?class="structured-count">\{\{draft.blocks.length\}\} \/ 20<\/view>\s*<\/view>/)
    const row = rule(css, '.structured-row-main')
    assert.equal(row.background, '#FFFFFF')
    assert.equal(row.border, '0')
    assert.match(row['border-bottom'], /solid/)
  })

  test(`${directory} 取消完成按钮独立定义尺寸，不被全局零内边距压扁`, () => {
    const action = rule(css, '.structured-actions button')
    assert.ok(parseInt(action['min-height']) >= 80)
    assert.ok(parseInt(action.padding) >= 20)
    assert.equal(action['box-sizing'], 'border-box')
    assert.equal(action['min-width'], '0')
    assert.match(action.border, /solid/)
  })
}
