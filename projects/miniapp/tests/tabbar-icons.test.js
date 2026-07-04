const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const tabbarPageStyles = [
  ['mine', 'pages/index/index.wxss'],
  ['schedule', 'pages/schedule/schedule.wxss'],
  ['works', 'pages/works/works.wxss'],
  ['portfolios', 'pages/portfolios/portfolios.wxss']
].map(([name, filePath]) => [
  name,
  fs.readFileSync(path.join(__dirname, '..', filePath), 'utf8')
])

function readRule(content, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matches = Array.from(content.matchAll(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`, 'g')))
  const match = matches[matches.length - 1]
  return match ? match[1] : ''
}

test('bottom tabs use design draft composite icons', () => {
  tabbarPageStyles.forEach(([pageName, pageWxss]) => {
    const activeTabRule = readRule(pageWxss, '.tab.active')
    const scheduleBeforeRule = readRule(pageWxss, '.schedule-tab-icon::before')
    const scheduleAfterRule = readRule(pageWxss, '.schedule-tab-icon::after')
    const workAfterRule = readRule(pageWxss, '.work-tab-icon::after')
    const portfolioBeforeRule = readRule(pageWxss, '.portfolio-tab-icon::before')
    const portfolioAfterRule = readRule(pageWxss, '.portfolio-tab-icon::after')
    const mineBeforeRule = readRule(pageWxss, '.mine-tab-icon::before')
    const mineAfterRule = readRule(pageWxss, '.mine-tab-icon::after')

    assert.match(activeTabRule, /color:\s*#b88a44/, `${pageName} active tab should use gold tone`)

    assert.match(scheduleBeforeRule, /border:\s*4rpx\s+solid\s+currentColor/, `${pageName} schedule icon should draw the calendar frame`)
    assert.doesNotMatch(scheduleBeforeRule, /background/, `${pageName} schedule icon should avoid real-device unstable background drawing`)
    assert.doesNotMatch(scheduleBeforeRule, /linear-gradient/, `${pageName} schedule icon should avoid gradient shorthand`)
    assert.doesNotMatch(scheduleBeforeRule, /calc\(/, `${pageName} schedule icon should avoid calc in icon drawing`)
    assert.match(scheduleAfterRule, /left:\s*12rpx/, `${pageName} schedule icon line should stay inside the frame`)
    assert.match(scheduleAfterRule, /top:\s*18rpx/, `${pageName} schedule icon line should stay vertically stable`)
    assert.match(scheduleAfterRule, /width:\s*18rpx/, `${pageName} schedule icon line should not overflow the frame`)
    assert.match(scheduleAfterRule, /height:\s*4rpx/, `${pageName} schedule icon line should be a fixed stroke`)
    assert.match(scheduleAfterRule, /border-radius:\s*999rpx/, `${pageName} schedule icon line should have rounded caps`)
    assert.match(scheduleAfterRule, /background:\s*currentColor/, `${pageName} schedule icon line should inherit tab color`)

    assert.match(workAfterRule, /border-left:\s*4rpx\s+solid\s+currentColor/, `${pageName} work icon should keep check stroke`)
    assert.match(workAfterRule, /border-bottom:\s*4rpx\s+solid\s+currentColor/, `${pageName} work icon should keep check stroke`)
    assert.match(workAfterRule, /transform:\s*rotate\(-45deg\)/, `${pageName} work icon should keep rotation`)

    assert.match(portfolioBeforeRule, /z-index:\s*2/, `${pageName} portfolio icon foreground should stay above`)
    assert.match(portfolioAfterRule, /z-index:\s*1/, `${pageName} portfolio icon background should stay behind`)

    assert.match(mineBeforeRule, /border-radius:\s*50%/, `${pageName} mine icon head should stay circular`)
    assert.match(
      mineAfterRule,
      /border-radius:\s*20rpx\s+20rpx\s+8rpx\s+8rpx|border-top-left-radius:\s*18rpx/,
      `${pageName} mine icon body should keep shape`
    )
    assert.match(mineAfterRule, /border-bottom-width:\s*3rpx/, `${pageName} mine icon body should keep lighter base`)
  })
})
