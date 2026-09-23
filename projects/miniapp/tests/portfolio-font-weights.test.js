const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { PORTFOLIO_FONT_PHYSICAL_WEIGHTS, resolvePortfolioFontWeight, buildRemoteFontStyle } = require('../utils/portfolio-text-typography')

test('fixed physical weights remain identical to the backend version registry', () => {
  const backend = JSON.parse(fs.readFileSync(path.join(__dirname, '../../java/wefolio-java-runtime/src/main/resources/fonts/physical-weights.json'), 'utf8'))
  assert.deepEqual(PORTFOLIO_FONT_PHYSICAL_WEIGHTS, backend)
  assert.equal(resolvePortfolioFontWeight('ALLURA', 'gf-809e4d8b8d7e-r1', 700), 400)
  assert.equal(resolvePortfolioFontWeight('ALLURA', 'future', 700), 700)
  assert.equal(buildRemoteFontStyle({ fontId: 'ALLURA', fontWeight: 'BOLD' }, {
    versions: { ALLURA: { fontVersion: 'future' } }, families: { 'ALLURA:future:400': 'WF_wrong' }
  }), 'font-family: sans-serif;')
})
