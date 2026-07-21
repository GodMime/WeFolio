const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const marqueeModulePath = path.join(__dirname, '../pages/points/points-marquee.js')

test('builds ledger marquee only when text exceeds its viewport', () => {
  assert.equal(fs.existsSync(marqueeModulePath), true)
  const { buildLedgerMarquee } = require(marqueeModulePath)

  assert.deepEqual(buildLedgerMarquee(200, 200), {
    marqueeEnabled: false,
    marqueeDistance: 0,
    marqueeDuration: 0
  })
  assert.deepEqual(buildLedgerMarquee(200, 280), {
    marqueeEnabled: true,
    marqueeDistance: 80,
    marqueeDuration: 6
  })
  assert.deepEqual(buildLedgerMarquee(200, 440), {
    marqueeEnabled: true,
    marqueeDistance: 240,
    marqueeDuration: 12
  })
})
