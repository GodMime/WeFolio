const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const appJson = fs.readFileSync(path.join(__dirname, '../app.json'), 'utf8')
const indexJs = fs.readFileSync(path.join(__dirname, '../pages/index/index.js'), 'utf8')
const visitsJsPath = path.join(__dirname, '../pages/visits/visits.js')
const visitsJsonPath = path.join(__dirname, '../pages/visits/visits.json')
const visitsWxmlPath = path.join(__dirname, '../pages/visits/visits.wxml')
const visitsWxssPath = path.join(__dirname, '../pages/visits/visits.wxss')

function readVisitsRule(selector) {
  const visitsWxss = fs.readFileSync(visitsWxssPath, 'utf8')
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = visitsWxss.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : ''
}

test('mine visit entry navigates to visit records page', () => {
  assert.match(appJson, /"pages\/visits\/visits"/)
  assert.match(indexJs, /if\s*\(\s*type\s*===\s*'visits'\s*\)/)
  assert.match(indexJs, /url:\s*'\/pages\/visits\/visits'/)
})

test('visit records page files match prototype structure and backend endpoint', () => {
  assert.equal(fs.existsSync(visitsJsPath), true)
  assert.equal(fs.existsSync(visitsJsonPath), true)
  assert.equal(fs.existsSync(visitsWxmlPath), true)
  assert.equal(fs.existsSync(visitsWxssPath), true)

  const visitsJs = fs.readFileSync(visitsJsPath, 'utf8')
  const visitsWxml = fs.readFileSync(visitsWxmlPath, 'utf8')
  const visitsWxss = fs.readFileSync(visitsWxssPath, 'utf8')

  assert.match(visitsJs, /url:\s*'\/api\/mine\/visits'/)
  assert.match(visitsJs, /normalizeVisitRecords/)
  assert.match(visitsWxml, /navigation-bar title="访问记录" back="\{\{true\}\}"/)
  assert.match(visitsWxml, /class="metric-grid"/)
  assert.match(visitsWxml, /近 7 日访问趋势/)
  assert.match(visitsWxml, /访问明细/)
  assert.match(visitsWxml, /class="\{\{item\.followToneClass\}\}"/)
  assert.match(visitsWxml, /<canvas[^>]*id="trendLineCanvas"[^>]*class="trend-line-canvas"[^>]*type="2d"/)
  assert.match(visitsJs, /drawTrendLineChart/)
  assert.match(visitsJs, /createSelectorQuery\(\)\.in\(this\)/)
  assert.match(visitsJs, /ctx\.lineTo/)
  assert.match(visitsJs, /ctx\.arc/)
  assert.match(visitsJs, /ctx\.fillText\(String\(point\.value\)/)
  assert.match(visitsWxss, /\.visit-list/)
  assert.match(visitsWxss, /\.trend-line-canvas/)
  assert.doesNotMatch(visitsWxss, /\.trend-bars/)
})

test('visit records detail rows keep avatar, copy and follow status in one scan row', () => {
  const visitRowRule = readVisitsRule('.visit-row')
  const visitorIconRule = readVisitsRule('.visitor-icon')
  const visitCopyRule = readVisitsRule('.visit-copy')

  assert.match(visitRowRule, /display:\s*flex/)
  assert.match(visitRowRule, /align-items:\s*center/)
  assert.doesNotMatch(visitRowRule, /display:\s*grid/)
  assert.doesNotMatch(visitRowRule, /grid-template-columns/)
  assert.match(visitorIconRule, /flex:\s*none/)
  assert.match(visitorIconRule, /border-radius:\s*50%/)
  assert.match(visitCopyRule, /flex:\s*1/)
  assert.match(visitCopyRule, /min-width:\s*0/)
})

test('visit records summary and trend labels avoid CSS grid on Skyline', () => {
  const metricGridRule = readVisitsRule('.metric-grid')
  const metricRule = readVisitsRule('.metric')
  const trendLabelRowRule = readVisitsRule('.trend-label-row')
  const trendLabelRule = readVisitsRule('.trend-label')

  assert.match(metricGridRule, /display:\s*flex/)
  assert.match(metricGridRule, /gap:\s*16rpx/)
  assert.doesNotMatch(metricGridRule, /display:\s*grid/)
  assert.match(metricRule, /flex:\s*1\s+1\s+0/)
  assert.match(metricRule, /min-width:\s*0/)
  assert.match(trendLabelRowRule, /display:\s*flex/)
  assert.match(trendLabelRowRule, /gap:\s*8rpx/)
  assert.doesNotMatch(trendLabelRowRule, /display:\s*grid/)
  assert.match(trendLabelRule, /flex:\s*1\s+1\s+0/)
  assert.match(trendLabelRule, /min-width:\s*0/)
})
