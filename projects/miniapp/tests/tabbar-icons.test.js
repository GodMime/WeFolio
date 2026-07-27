const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const tabbarPages = [
  ['mine', 'pages/index/index'],
  ['schedule', 'pages/schedule/schedule'],
  ['works', 'pages/works/works'],
  ['portfolios', 'pages/portfolios/portfolios']
].map(([name, basePath]) => ({
  name,
  wxml: fs.readFileSync(path.join(__dirname, '..', `${basePath}.wxml`), 'utf8'),
  wxss: fs.readFileSync(path.join(__dirname, '..', `${basePath}.wxss`), 'utf8')
}))

const mockTabbar = {
  name: 'mock',
  wxml: fs.readFileSync(
    path.join(__dirname, '../components/mock/tabbar/tabbar.wxml'),
    'utf8'
  ),
  wxss: fs.readFileSync(
    path.join(__dirname, '../components/mock/tabbar/tabbar.wxss'),
    'utf8'
  )
}

function readRule(content, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matches = Array.from(content.matchAll(
    new RegExp(`(?:^|\\n)\\s*${escapedSelector}\\s*\\{([^}]*)\\}`, 'g')
  ))
  const match = matches[matches.length - 1]
  return match ? match[1] : ''
}

test('bottom tabs keep the selected icon pill separate from its label', () => {
  ;[...tabbarPages, mockTabbar].forEach(({ name: pageName, wxml, wxss: pageWxss }) => {
    const tabbarRule = readRule(pageWxss, '.tabbar')
    const tabRule = readRule(pageWxss, '.tab')
    const activeTabRule = readRule(pageWxss, '.tab.active')
    const iconWrapRule = readRule(pageWxss, '.tab-icon-wrap')
    const activeIconWrapRule = readRule(pageWxss, '.tab.active .tab-icon-wrap')
    const iconImageRule = readRule(pageWxss, '.tab-icon-image')
    const tabLabelRule = readRule(pageWxss, '.tab-label')

    assert.match(tabbarRule, /left:\s*28rpx/)
    assert.match(tabbarRule, /right:\s*28rpx/)
    assert.match(
      tabbarRule,
      /bottom:\s*calc\(28rpx \+ env\(safe-area-inset-bottom\)\)/
    )
    assert.match(tabbarRule, /height:\s*116rpx/)
    assert.match(tabbarRule, /padding:\s*0/)
    assert.match(tabbarRule, /border-radius:\s*999rpx/)
    assert.match(tabbarRule, /background:\s*#ffffff/)
    assert.match(tabbarRule, /border:\s*2rpx solid #e9ecef/)
    assert.match(tabbarRule, /box-shadow:\s*0 24rpx 56rpx rgba\(33,\s*37,\s*41,\s*0\.1\)/)
    assert.match(
      wxml,
      /class="tab-icon-wrap"[\s\S]*class="tab-icon-image"[^>]*src="\/assets\/system\/tabbar\/\{\{item\.icon\}\}\{\{item\.active \? '-active' : ''\}\}\.svg"/,
      `${pageName} should isolate the icon background from the label`
    )
    assert.match(tabRule, /height:\s*116rpx/)
    assert.match(tabRule, /color:\s*#868e96/)
    assert.match(activeTabRule, /color:\s*#212529/)
    assert.match(iconWrapRule, /width:\s*68rpx/)
    assert.match(iconWrapRule, /height:\s*52rpx/)
    assert.match(iconWrapRule, /border-radius:\s*999rpx/)
    assert.match(activeIconWrapRule, /background:\s*#212529/)
    assert.match(iconImageRule, /width:\s*38rpx/)
    assert.match(iconImageRule, /height:\s*38rpx/)
    assert.match(tabLabelRule, /margin-top:\s*4rpx/)
    assert.match(tabLabelRule, /font-size:\s*18rpx/)
    assert.match(tabLabelRule, /font-weight:\s*500/)
    assert.match(tabLabelRule, /line-height:\s*1\.2/)
    assert.doesNotMatch(pageWxss, /\.tab\.active::before/)
    assert.doesNotMatch(pageWxss, /#b88a44/)
  })
})
