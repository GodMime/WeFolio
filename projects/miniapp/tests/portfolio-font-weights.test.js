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

test('远程字体上下文显式为空时回退系统字体，字体就绪后恢复正确字重', () => {
  const node = { fontId: 'ALLURA', fontWeight: 'BOLD' }
  for (const context of [null, undefined, {}, { versions: null, families: null }]) {
    assert.equal(buildRemoteFontStyle(node, context), 'font-family: sans-serif;')
    assert.equal(buildRemoteFontStyle({ fontFamily: 'SYSTEM' }, context), '')
  }
  assert.equal(buildRemoteFontStyle(node, {
    versions: { ALLURA: { fontVersion: 'gf-809e4d8b8d7e-r1' } },
    families: { 'ALLURA:gf-809e4d8b8d7e-r1:400': 'WF_allura_registered' }
  }), 'font-family: "WF_allura_registered", sans-serif;')
})
