const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const VARIANTS = ['MINIMAL', 'DATE_COLUMN', 'MILESTONE_CARD']
const DIRECTIONS = ['HORIZONTAL', 'VERTICAL']
const clone = value => JSON.parse(JSON.stringify(value))
const configFor = (variant, direction, count) => ({
  variant, direction, title: '历程', showKicker: true,
  minGapRpx: { horizontal: 320, vertical: 200 }, textStyles: {},
  nodes: Array.from({ length: count }, (_, index) => ({
    nodeKey: `node_${index}`, datePrecision: 'CUSTOM', date: index === count - 1 ? '至今' : '启程',
    title: `第 ${index + 1} 段经历`, description: index === count - 1 ? '故事仍在继续。'.repeat(30) : '一段经历'
  }))
})
const measurementsFor = (count, scale) => Array.from({ length: count }, (_, index) => ({
  dateWidth: (index === count - 1 ? 280 : 100) * scale,
  dateHeight: 40 * scale, dateFirstLine: 40 * scale,
  bodyWidth: (index === count - 1 ? 560 : 240) * scale,
  bodyHeight: (index === count - 1 ? 880 : 100) * scale,
  height: (index === count - 1 ? 960 : 180) * scale
}))

function renderer(directory, config) {
  const filename = require.resolve(`../pages/${directory}/components/timeline/timeline`)
  let definition
  const previous = global.Component
  global.Component = value => { definition = value }
  delete require.cache[filename]
  try { require(filename) } finally { global.Component = previous }
  const queries = []
  const properties = Object.fromEntries(Object.entries(definition.properties).map(([key, property]) => [key, clone(property.value)]))
  const instance = { ...definition.methods, _alive: true, properties: { ...properties, config }, data: clone(definition.data),
    setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback() },
    createSelectorQuery() {
      return { select() { return this }, selectAll() { return this }, boundingClientRect(callback) { if (callback) this.callback = callback; return this },
        exec(callback) { queries.push(callback || this.callback) } }
    }
  }
  return { instance, queries }
}

function styleValue(style, property) {
  const match = style.match(new RegExp(`(?:^|;)${property}:([\\d.]+)px`))
  return match ? Number(match[1]) : undefined
}

for (const directory of ['portfolios', 'team-portfolios']) {
  const api = require(`../pages/${directory}/utils/portfolio-timeline`)

  for (const variant of VARIANTS) for (const direction of DIRECTIONS) {
    test(`${directory} ${variant} ${direction} 单节点和多节点尾虚线延伸出长内容且完整计入画布`, () => {
      for (const count of [1, 3]) for (const scale of [320 / 750, 0.5, 430 / 750]) {
        const config = configFor(variant, direction, count)
        const before = clone(config)
        const viewportWidth = 686 * scale
        const layout = api.layoutTimeline(config, viewportWidth, scale, measurementsFor(count, scale))
        assert.ok(layout)
        assert.deepEqual(config, before, '装饰性尾线不得写入保存配置')
        const last = layout.nodes.at(-1)
        assert.equal(last.last, true)
        assert.equal(last.lineLength, 0, '末节点不生成通往虚构节点的实线')
        assert.ok(last.tailLength >= 96 * scale)
        assert.ok(layout.nodes.slice(0, -1).every(node => !node.last && !node.tailLength))
        if (direction === 'HORIZONTAL') {
          const tailEnd = last.left + 6 * scale + last.tailLength
          assert.ok(last.tailLength >= last.width + 80 * scale - 0.001, '长末节点正文之外仍保留向右延伸')
          assert.ok(layout.width >= tailEnd - 0.001, '横向滚动范围包含虚线端点')
          assert.equal(layout.overflow, layout.width > viewportWidth)
        } else {
          const tailEnd = last.top + last.dotOffset + last.tailLength
          assert.ok(tailEnd >= last.top + last.height + 80 * scale - 0.001, '虚线延伸出最后正文的下缘')
          assert.ok(layout.height >= tailEnd - 0.001, '纵向画布包含虚线端点')
          assert.equal(layout.width, viewportWidth)
          assert.equal(layout.overflow, false)
        }
        for (let index = 0; index < layout.nodes.length - 1; index += 1) {
          assert.equal(layout.nodes[index].lineLength, layout.nodes[index + 1].dot - layout.nodes[index].dot, '原有节点之间保持实线连接')
        }
      }
    })
  }

  test(`${directory} 空时间轴不产生末节点、尾线或额外空间，自然回退仍准确标记最后节点`, () => {
    for (const variant of VARIANTS) for (const direction of DIRECTIONS) {
      const empty = configFor(variant, direction, 0)
      const view = api.presentTimeline(empty)
      const layout = api.layoutTimeline(empty, 350, 0.5, [])
      assert.deepEqual(view.nodes, [])
      assert.deepEqual(layout.nodes, [])
      assert.equal(layout.height, 0)
      assert.equal(layout.width, direction === 'HORIZONTAL' ? 0 : 350)
      assert.equal(layout.overflow, false)
      for (const count of [1, 3]) {
        const config = configFor(variant, direction, count)
        for (const theme of ['light', 'dark']) {
          const nodes = api.presentTimeline(config, 'USER', theme).nodes
          assert.deepEqual(nodes.map(node => node.last), Array.from({ length: count }, (_, index) => index === count - 1))
          assert.equal(nodes.at(-1).nodeKey, config.nodes.at(-1).nodeKey)
          assert.equal(nodes.at(-1).description, config.nodes.at(-1).description)
        }
      }
    }
  })

  test(`${directory} 日期分栏的纵向尾虚线对齐日期列后的轴线，不回到页面左侧`, () => {
    const previous = global.wx
    global.wx = { getWindowInfo: () => ({ windowWidth: 375 }), nextTick: callback => callback() }
    try {
      const { instance, queries } = renderer(directory, configFor('DATE_COLUMN', 'VERTICAL', 2))
      instance.scheduleLayout()
      queries.shift()({ width: 350, height: 0 })
      queries.shift()([
        [{ width: 60, height: 22 }, { width: 60, height: 22 }],
        [{ width: 300, height: 60 }, { width: 300, height: 60 }],
        [{ width: 350, height: 100 }, { width: 350, height: 100 }]
      ])
      queries.shift()([
        [{ width: 60, height: 22 }, { width: 60, height: 22 }],
        [{ width: 262, height: 100 }, { width: 262, height: 320 }],
        [{ width: 350, height: 100 }, { width: 350, height: 320 }]
      ])
      assert.equal(instance.data.natural, false)
      assert.equal(instance.data.compact, false)
      const last = instance.data.nodes.at(-1)
      assert.equal(styleValue(last.tailStyle, 'left'), styleValue(last.lineStyle, 'left'))
      assert.equal(styleValue(last.tailStyle, 'left'), 69)
      assert.equal(styleValue(last.tailStyle, 'top'), styleValue(last.lineStyle, 'top'))
      assert.ok(styleValue(last.tailStyle, 'height') > 320 - styleValue(last.tailStyle, 'top'))
      assert.deepEqual(last.tailDashes, api.buildTimelineTailDashes(styleValue(last.tailStyle, 'height') / 0.5, false, 'light'), '实测完成后按尾线长度生成渐变短划')
      assert.ok(styleValue(instance.data.frameStyle, 'height') >= styleValue(last.style, 'top') + styleValue(last.tailStyle, 'top') + styleValue(last.tailStyle, 'height'))
    } finally { global.wx = previous }
  })
}

for (const directory of ['portfolios', 'team-portfolios']) {
  test(`${directory} 尾虚线按短划渲染连续渐变，回退布局不隐藏或裁切尾线`, () => {
    const base = path.join(__dirname, '../pages', directory, 'components/timeline/timeline')
    const wxml = fs.readFileSync(base + '.wxml', 'utf8')
    const css = fs.readFileSync(base + '.wxss', 'utf8')
    const rule = selector => {
      const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const match = css.match(new RegExp(`${escaped}\\s*\\{([^}]*)}`))
      assert.ok(match, `缺少 ${selector}`)
      return match[1]
    }
    assert.match(wxml, /<view wx:if="{{!item.last}}" class="timeline-line"[^>]*><\/view>\s*<view wx:else class="timeline-tail" style="{{item.tailStyle}}" aria-hidden="true">/)
    assert.match(wxml, /wx:for="{{item.tailDashes}}"[^>]*wx:for-item="dash"[^>]*wx:key="key"[^>]*class="timeline-tail-dash"[^>]*style="{{dash.style}}"/)
    assert.doesNotMatch(wxml + css, /timeline-tail-segment|tail-middle|tail-end/)
    assert.match(rule('.timeline-tail-dash'), /position:\s*absolute/)
    assert.match(rule('.timeline-horizontal .timeline-tail-dash'), /height:\s*2rpx/)
    assert.match(rule('.timeline-vertical .timeline-tail-dash'), /width:\s*2rpx/)
    assert.doesNotMatch(rule('.timeline-tail-dash'), /opacity:/, '透明度由全局位置对应的渐变计算，不设恒定分段透明度')
    assert.match(rule('.timeline-theme-dark'), /--timeline-line:/)
    const naturalHorizontal = rule('.timeline-natural.timeline-horizontal .timeline-tail')
    assert.match(naturalHorizontal, /position:\s*relative/)
    assert.match(naturalHorizontal, /width:\s*calc\(100% - 6rpx\)/)
    assert.match(naturalHorizontal, /margin-top:\s*-6rpx/)
    assert.match(rule('.timeline-natural.timeline-vertical .timeline-node:last-child'), /padding-bottom:\s*80rpx/)
    assert.match(rule('.timeline-natural.timeline-vertical .timeline-tail'), /height:\s*calc\(100% - 20rpx\)/)
    assert.doesNotMatch(css, /\.timeline-tail\s*\{[^}]*display:\s*none/)
  })
}


for (const directory of ['portfolios', 'team-portfolios']) {
  const api = require(`../pages/${directory}/utils/portfolio-timeline`)
  const near = (actual, expected, message) => assert.ok(Math.abs(actual - expected) < 0.0001, `${message}: ${actual} != ${expected}`)

  test(`${directory} 尾线每条短划共享全局透明度斜率，跨越三分之一位置没有台阶`, () => {
    for (const length of [96, 320, 1280, 12000]) for (const horizontal of [true, false]) for (const theme of ['light', 'dark']) {
      const dashes = api.buildTimelineTailDashes(length, horizontal, theme)
      assert.ok(dashes.length > 3 && dashes.length <= 256)
      assert.equal(new Set(dashes.map(dash => dash.key)).size, dashes.length)
      near(dashes[0].start, 0, '短划从尾线起点开始')
      let previous
      for (const dash of dashes) {
        assert.ok(dash.start >= 0 && dash.end > dash.start && dash.end <= 1)
        near(dash.startAlpha, 0.85 * (1 - dash.start), '起始透明度使用全局位置')
        near(dash.endAlpha, 0.85 * (1 - dash.end), '结束透明度使用全局位置')
        assert.ok(dash.startAlpha > dash.endAlpha, '每条短划内部也连续变淡')
        near((dash.startAlpha - dash.endAlpha) / (dash.end - dash.start), 0.85, '整条尾线使用同一条连续斜率')
        assert.match(dash.style, horizontal ? /linear-gradient\((?:to right|90deg),/ : /linear-gradient\((?:to bottom|180deg),/)
        const stops = [...dash.style.matchAll(/rgba\(\s*(\d+),\s*(\d+),\s*(\d+),\s*([\d.]+)\)/g)]
        assert.equal(stops.length, 2, '每条短划的两端使用透明度渐变')
        const rgb = theme === 'light' ? [207, 198, 186] : [155, 141, 122]
        stops.forEach(stop => assert.deepEqual(stop.slice(1, 4).map(Number), rgb))
        near(Number(stops[0][4]), dash.startAlpha, '渲染起始透明度与计算值一致')
        near(Number(stops[1][4]), dash.endAlpha, '渲染结束透明度与计算值一致')
        if (previous) {
          assert.ok(dash.start > previous.end, '短划之间保持透明间隙')
          near(previous.endAlpha - dash.startAlpha, 0.85 * (dash.start - previous.end), '间隙两侧也遵循同一透明度曲线')
        }
        previous = dash
      }
      assert.ok(dashes.at(-1).endAlpha < 0.06, '远端接近完全透明')
    }
  })

  test(`${directory} 尾线无效长度回退且数量有界，自然布局仅在末节点提供渐变短划`, () => {
    const defaults = api.buildTimelineTailDashes()
    for (const length of [0, -1, NaN, Infinity]) assert.deepEqual(api.buildTimelineTailDashes(length), defaults)
    for (const length of [0.001, 1, 1000000]) {
      const dashes = api.buildTimelineTailDashes(length)
      assert.ok(dashes.length >= 1 && dashes.length <= 256)
      dashes.forEach(dash => assert.ok(Number.isFinite(dash.startAlpha) && Number.isFinite(dash.endAlpha)))
    }
    for (const direction of DIRECTIONS) for (const theme of ['light', 'dark']) {
      const view = api.presentTimeline(configFor('MINIMAL', direction, 3), 'USER', theme)
      assert.ok(view.nodes.slice(0, -1).every(node => !node.tailDashes || node.tailDashes.length === 0))
      assert.deepEqual(view.nodes.at(-1).tailDashes, api.buildTimelineTailDashes(undefined, direction === 'HORIZONTAL', theme))
    }
  })
}
