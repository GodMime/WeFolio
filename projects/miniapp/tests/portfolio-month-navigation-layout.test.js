const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '..')
const COMPONENTS = [
  'pages/portfolios/components/portfolio-schedule-query/portfolio-schedule-query',
  'pages/team-portfolios/components/schedule-query/schedule-query',
  'components/mock/portfolio-schedule-query/portfolio-schedule-query',
]

function readRule(wxss, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = wxss.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))
  assert.ok(match, `缺少样式 ${selector}`)
  return match[1]
}

for (const component of COMPONENTS) {
  test(`${component} 的月份箭头完整显示并继承主题颜色`, () => {
    const wxml = fs.readFileSync(path.join(ROOT, `${component}.wxml`), 'utf8')
    const wxss = fs.readFileSync(path.join(ROOT, `${component}.wxss`), 'utf8')
    const button = readRule(wxss, '.schedule-query-month-button')
    const arrow = readRule(wxss, '.schedule-query-month-arrow')
    const dimension = (rule, property) => Number(rule.match(new RegExp(`${property}:\\s*([\\d.]+)rpx`))[1])

    assert.match(button, /display:\s*flex;/)
    assert.match(button, /align-items:\s*center;/)
    assert.match(button, /justify-content:\s*center;/)
    assert.match(button, /flex:\s*none;/)
    assert.match(arrow, /flex:\s*none;/)
    assert.match(arrow, /box-sizing:\s*border-box;/)
    assert.match(arrow, /border-right:\s*3rpx solid currentColor;/)
    assert.match(arrow, /border-bottom:\s*3rpx solid currentColor;/)
    assert.doesNotMatch(arrow, /(?:^|;)\s*color:/)

    for (const [direction, handler, label, angle] of [
      ['prev', 'handlePrevMonth', '上个月', 135],
      ['next', 'handleNextMonth', '下个月', -45],
    ]) {
      const trigger = wxml.match(new RegExp(`<view\\s+([^>]*catchtap="${handler}"[^>]*)>\\s*<view\\s+([^>]*)><\\/view>\\s*<\\/view>`))
      assert.ok(trigger, `${label}必须保留 catchtap 和独立图标`)
      assert.match(trigger[1], /class="schedule-query-month-button"/)
      assert.match(trigger[1], /aria-role="button"/)
      assert.ok(trigger[1].includes(`aria-label="${label}"`))
      assert.ok(trigger[2].includes(`class="schedule-query-month-arrow ${direction}"`))
      assert.match(trigger[2], /aria-hidden="true"/)

      const transform = readRule(wxss, `.schedule-query-month-arrow.${direction}`)
      assert.ok(transform.includes(`rotate(${angle}deg)`))
      const offset = Math.abs(Number(transform.match(/translateX\((-?[\d.]+)rpx\)/)[1]))
      const border = dimension(button, 'border')
      const available = Math.min(dimension(button, 'width'), dimension(button, 'height')) - border * 2
      // 把旋转后的外接范围及居中偏移算入，避免图标超出圆形点击区域。
      assert.ok(Math.hypot(dimension(arrow, 'width'), dimension(arrow, 'height')) + offset * 2 < available)
    }
  })
}

test('所有月份切换入口都不再使用文字箭头，防止遗漏其它页面', () => {
  const files = fs.readdirSync(ROOT, { recursive: true }).filter(file => file.endsWith('.wxml'))
  let controls = 0
  for (const file of files) {
    const wxml = fs.readFileSync(path.join(ROOT, file), 'utf8')
    for (const match of wxml.matchAll(/<(view|button)\b[^>]*(?:bind|catch)tap="handle(?:Prev|Next)Month"[^>]*>([\s\S]*?)<\/\1>/g)) {
      controls += 1
      assert.doesNotMatch(match[2], /[‹›]/, file)
      assert.match(match[2], /<view\b[^>]*aria-hidden="true"/, file)
    }
  }
  assert.ok(controls >= 10, '应覆盖正式档期、体验档期和三份作品集档期查询组件')
})
