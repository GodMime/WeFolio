const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function read(relativePath) {
  return fs.readFileSync(path.join(__dirname, '..', relativePath), 'utf8')
}

function readRule(content, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matches = Array.from(content.matchAll(
    new RegExp(`(?:^|\\n)\\s*${escapedSelector}\\s*\\{([^}]*)\\}`, 'g')
  ))
  const match = matches[matches.length - 1]
  return match ? match[1] : ''
}

const cases = [
  {
    name: '正式档期页',
    file: 'pages/schedule/schedule.wxss',
    markupFile: 'pages/schedule/schedule.wxml',
    container: '.schedule-segmented',
    item: '.segment',
    active: '.segment.active',
    indicator: '.segment-indicator',
    indicatorActive: '.segment-indicator.second-active',
    indicatorMarkup: /class="segment-indicator \{\{activeMode === 'definitions' \? 'second-active' : ''\}\}"/,
    legacyIndicator: '.segment.active::after'
  },
  {
    name: 'Mock 档期页',
    file: 'pages/mock/styles/schedule.wxss',
    markupFile: 'pages/mock/schedule/schedule.wxml',
    container: '.schedule-segmented',
    item: '.segment',
    active: '.segment.active',
    indicator: '.segment-indicator',
    indicatorActive: '.segment-indicator.second-active',
    indicatorMarkup: /class="segment-indicator \{\{activeMode === 'definitions' \? 'second-active' : ''\}\}"/,
    legacyIndicator: '.segment.active::after'
  },
  {
    name: '正式作品集页',
    file: 'pages/portfolios/portfolios.wxss',
    markupFile: 'pages/portfolios/portfolios.wxml',
    container: '.segment',
    item: '.segment-item',
    active: '.segment-item.active',
    indicator: '.segment-indicator',
    indicatorActive: '.segment-indicator.team-active',
    indicatorMarkup: /class="segment-indicator \{\{ownerType === 'TEAM' \? 'team-active' : ''\}\}"/,
    legacyIndicator: '.segment-item.active::after'
  },
  {
    name: 'Mock 作品集页',
    file: 'pages/mock/styles/portfolios.wxss',
    markupFile: 'pages/mock/portfolios/portfolios.wxml',
    container: '.segment',
    item: '.segment-item',
    active: '.segment-item.active',
    indicator: '.segment-indicator',
    indicatorActive: '.segment-indicator.team-active',
    indicatorMarkup: /class="segment-indicator"/,
    legacyIndicator: '.segment-item.active::after'
  }
]

test('四个顶部双项 Tab 使用真实节点渲染一致的下划线', () => {
  cases.forEach(({
    name,
    file,
    markupFile,
    container,
    item,
    active,
    indicator,
    indicatorActive,
    indicatorMarkup,
    legacyIndicator
  }) => {
    const wxss = read(file)
    const wxml = read(markupFile)
    const containerRule = readRule(wxss, container)
    const itemRule = readRule(wxss, item)
    const activeRule = readRule(wxss, active)
    const indicatorRule = readRule(wxss, indicator)
    const indicatorActiveRule = readRule(wxss, indicatorActive)
    const legacyIndicatorRule = readRule(wxss, legacyIndicator)

    assert.match(wxml, indicatorMarkup, `${name} 应包含真实指示线节点`)
    assert.match(containerRule, /position:\s*relative/, `${name} 的容器应作为指示线定位基准`)
    assert.match(containerRule, /display:\s*flex/, `${name} 应使用 Flex`)
    assert.match(containerRule, /border-bottom:\s*1rpx solid #e9ecef/, `${name} 应显示底部分隔线`)
    assert.match(itemRule, /flex:\s*1(?:\s+1\s+0)?/, `${name} 的两个选项应等宽`)
    assert.match(itemRule, /height:\s*72rpx/, `${name} 的选项高度应为 72rpx`)
    assert.match(itemRule, /color:\s*#868e96/, `${name} 的未选中文字颜色不正确`)
    assert.match(itemRule, /font-size:\s*26rpx/, `${name} 的文字大小不正确`)
    assert.match(itemRule, /font-weight:\s*500/, `${name} 的未选中文字字重不正确`)
    assert.match(activeRule, /color:\s*#212529/, `${name} 的选中文字颜色不正确`)
    assert.match(activeRule, /font-weight:\s*600/, `${name} 的选中文字字重不正确`)
    assert.match(activeRule, /background:\s*transparent/, `${name} 的选中项背景应透明`)
    assert.match(indicatorRule, /position:\s*absolute/, `${name} 的指示线应绝对定位`)
    assert.match(indicatorRule, /left:\s*25%/, `${name} 的指示线默认应位于第一项`)
    assert.match(indicatorRule, /bottom:\s*-1rpx/, `${name} 的指示线应贴合分隔线`)
    assert.match(indicatorRule, /width:\s*48rpx/, `${name} 的指示线宽度不正确`)
    assert.match(indicatorRule, /height:\s*5rpx/, `${name} 的指示线高度不正确`)
    assert.match(indicatorRule, /background:\s*#212529/, `${name} 的指示线颜色不正确`)
    assert.match(indicatorRule, /margin-left:\s*-24rpx/, `${name} 的指示线应在选项内居中`)
    assert.match(indicatorActiveRule, /left:\s*75%/, `${name} 的第二项指示线位置不正确`)
    assert.doesNotMatch(indicatorRule, /display:\s*none/, `${name} 的真实指示线不能隐藏`)
    assert.equal(legacyIndicatorRule, '', `${name} 不应继续依赖 button 伪元素指示线`)
  })
})
