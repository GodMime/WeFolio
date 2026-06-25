const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const indexWxss = fs.readFileSync(
  path.join(__dirname, '../pages/index/index.wxss'),
  'utf8'
)

function readRule(selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = indexWxss.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : ''
}

test('bottom tabs use design draft composite icons', () => {
  const activeTabRule = readRule('.tab.active')
  const scheduleBeforeRule = readRule('.schedule-tab-icon::before')
  const scheduleAfterRule = readRule('.schedule-tab-icon::after')
  const workAfterRule = readRule('.work-tab-icon::after')
  const portfolioBeforeRule = readRule('.portfolio-tab-icon::before')
  const portfolioAfterRule = readRule('.portfolio-tab-icon::after')
  const mineBeforeRule = readRule('.mine-tab-icon::before')
  const mineAfterRule = readRule('.mine-tab-icon::after')

  assert.match(activeTabRule, /color:\s*#b88a44/)

  assert.match(scheduleBeforeRule, /background:[\s\S]*linear-gradient\(currentColor,\s*currentColor\)/)
  assert.match(scheduleAfterRule, /border-radius:\s*50%/)
  assert.match(scheduleAfterRule, /background:\s*currentColor/)

  assert.match(workAfterRule, /border-left:\s*4rpx\s+solid\s+currentColor/)
  assert.match(workAfterRule, /border-bottom:\s*4rpx\s+solid\s+currentColor/)
  assert.match(workAfterRule, /transform:\s*rotate\(-45deg\)/)

  assert.match(portfolioBeforeRule, /z-index:\s*2/)
  assert.match(portfolioAfterRule, /z-index:\s*1/)

  assert.match(mineBeforeRule, /border-radius:\s*50%/)
  assert.match(mineAfterRule, /border-radius:\s*20rpx\s+20rpx\s+8rpx\s+8rpx/)
  assert.match(mineAfterRule, /border-bottom-width:\s*3rpx/)
})
