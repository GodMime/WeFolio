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
  assert.match(visitsWxss, /\.visit-list/)
  assert.match(visitsWxss, /\.trend-bars/)
})

test('visit records detail rows keep avatar, copy and follow status in one scan row', () => {
  const visitRowRule = readVisitsRule('.visit-row')
  const visitorIconRule = readVisitsRule('.visitor-icon')
  const visitCopyRule = readVisitsRule('.visit-copy')

  assert.match(visitRowRule, /display:\s*grid/)
  assert.match(visitRowRule, /grid-template-columns:\s*76rpx\s+minmax\(0,\s*1fr\)\s+auto/)
  assert.match(visitorIconRule, /border-radius:\s*50%/)
  assert.match(visitCopyRule, /min-width:\s*0/)
})
