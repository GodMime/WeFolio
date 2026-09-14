const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const ROOT = path.resolve(__dirname, '..')
function loadPage(relative) {
  const file = path.join(ROOT, relative); let definition
  global.Page = value => { definition = value }; global.wx = { getSystemInfoSync() { return {} } }
  delete require.cache[require.resolve(file)]; require(file); delete global.Page
  return definition
}
for (const packageName of ['portfolios', 'team-portfolios']) {
  const team = packageName === 'team-portfolios'
  const detailName = team ? 'team-portfolio-work-detail' : 'portfolio-work-detail'
  test(`${packageName} detail is in its own package and cold start has no data request`, () => {
    const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json')))
    assert.ok(app.subPackages.find(item => item.root === `pages/${packageName}`).pages.includes(`work-detail/${detailName}`))
    const definition = loadPage(`pages/${packageName}/work-detail/${detailName}.js`)
    assert.equal(definition.data.available, false)
    assert.equal(definition.onShareAppMessage, undefined); assert.equal(definition.onShareTimeline, undefined)
    const wxml = fs.readFileSync(path.join(ROOT, `pages/${packageName}/work-detail/${detailName}.wxml`), 'utf8')
    assert.match(wxml, /作品暂不可用/); assert.ok(wxml.includes('delta="{{0}}"'))
    assert.match(wxml, /wx:if="\{\{loadingContext\}\}"/)
    assert.match(wxml, /wx:elif="\{\{available\}\}"/)
    delete global.wx
  })
  for (const view of ['standard-preview', 'visitor-portfolio']) {
    const file = team ? (view === 'standard-preview' ? 'team-portfolio-standard-preview' : 'team-visitor-portfolio')
      : (view === 'standard-preview' ? 'portfolio-standard-preview' : 'visitor-portfolio')
    test(`${packageName} ${view} opens selected menu snapshot without duplicate cover event`, async () => {
      const definition = loadPage(`pages/${packageName}/${view}/${file}.js`)
      let navigation, payload, records = 0
      global.wx.navigateTo = value => { navigation = value; value.success({ eventChannel: { emit(name, value) { payload = value } } }); value.complete() }
      const data = { openMode: 'DETAIL_PAGE', detailOptions: { showTitle: false, showDescription: true }, work: { workId: 7, memberUserId: 2, mediaType: 'IMAGE', mediaUrl: 'https://example.test/image' } }
      const component = Object.assign({ componentKey: 'single', componentType: 'SINGLE_WORK' }, team ? { data } : data)
      const page = { data: { portfolio: { activeComponents: [component], components: [], style: { backgroundColor: '#111111' }, themeMode: 'dark' } },
        browserContext: { idempotencyKey: 'keep-context' }, pauseBackgroundAudio() {}, stopActiveSingleWorkVideo() {}, stopSingleWorkVideos() {},
        recordWorkEvent() { records++ }, sendEvent() { records++ } }
      assert.equal(definition.handleWorkDetail.call(page, { detail: { componentKey: 'single' } }), true)
      assert.equal(navigation.url, `/pages/${packageName}/work-detail/${detailName}`)
      assert.equal(payload.work.workId, 7); assert.equal(payload.detailOptions.showTitle, false); assert.equal(records, 0)
      if (view === 'visitor-portfolio') { await payload.onWorkEvent(payload.work); assert.equal(records, 1); assert.equal(payload.browserContext, page.browserContext) }
      else { assert.equal(payload.preview, true); assert.equal(payload.onWorkEvent, null) }
      const wxml = fs.readFileSync(path.join(ROOT, `pages/${packageName}/${view}/${file}.wxml`), 'utf8')
      assert.match(wxml, /binddetail="handleWorkDetail"/); assert.match(wxml, /open-mode=/)
      const carousel = wxml.match(/<(?:team-)?video-carousel\b[^>]*>/)
      assert.ok(carousel)
      assert.match(carousel[0], /display-style="\{\{item\.(?:data\.)?displayStyle\}\}"/)
      assert.match(carousel[0], /show-description="\{\{item\.(?:data\.)?showDescription\}\}"/)
      delete global.wx
    })
  }
}
