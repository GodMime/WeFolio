const assert = require('node:assert/strict')
const test = require('node:test')

const {
  formatLunarDayMeta,
  formatLunarFullText,
  toLunarDate
} = require('../utils/lunar')

test('formats lunar dates and festivals', () => {
  assert.equal(formatLunarDayMeta(toLunarDate('2026-06-19')), '端午节')
  assert.equal(formatLunarFullText(toLunarDate('2026-06-28')), '五月十四')
})

test('caches repeated lunar date calculations', () => {
  assert.equal(toLunarDate('2026-06-28'), toLunarDate('2026-06-28'))
})
