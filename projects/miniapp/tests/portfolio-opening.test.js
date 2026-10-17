const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const root = path.resolve(__dirname, '..')
const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve() }
function fixture(pkg, grids = []) {
  let now = 0, nextId = 0
  const timers = new Map(), calls = [], updates = [], ready = [], activity = []
  const api = { getAppBaseInfo: () => ({ SDKVersion: '3.17.3' }), getWindowInfo: () => ({ statusBarHeight: 24 }),
    getDeviceInfo: () => ({ platform: 'android' }), nextTick: fn => Promise.resolve().then(fn), loadFontFace: options => calls.push(options) }
  const clock = { setTimeout(fn, delay) { const id = ++nextId; timers.set(id, { fn, at: now + delay }); return id }, clearTimeout(id) { timers.delete(id) } }
  const cache = new Map()
  function load(name) {
    if (cache.has(name)) return cache.get(name)
    const filename = path.join(root, `pages/${pkg}/utils/${name}.js`)
    const module = { exports: {} }
    vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { module, exports: module.exports, wx: api, ...clock,
      require(id) {
        if (id === '../../../utils/request.js') return { request() { throw Error('opening must not request font catalog') } }
        const base = id.replace(/^\.\//, '')
        return ['portfolio-opening', 'portfolio-remote-font-page', 'portfolio-remote-font-session'].includes(base) ? load(base) : require(path.resolve(path.dirname(filename), id))
      }
    }, { filename })
    cache.set(name, module.exports)
    return module.exports
  }
  const page = { data: { portfolio: {} }, setData(patch, cb) { Object.assign(this.data, patch); updates.push(patch); if (cb) cb() },
    onPortfolioFontsReady() { ready.push(true) }, selectAllComponents(selector) { assert.equal(selector, '.portfolio-font-grid'); return grids },
    browserContext: { setDisplayable(value) { activity.push(value) } } }
  return { page, calls, updates, ready, activity, load, timers, async tick(ms) {
    now += ms
    for (const [id, timer] of [...timers]) if (timer.at <= now) { timers.delete(id); timer.fn() }
    await flush()
  } }
}
const view = (ids = ['ALLURA', 'MANROPE', 'ZCOOL_XIAOWEI']) => ({ fonts: {
  versions: Object.fromEntries(ids.map(fontId => [fontId, { fontVersion: 'v1' }])),
  assets: ids.map(fontId => ({ assetId: fontId, subsetHash: fontId, status: 'READY', fontId, fontVersion: 'v1', fontWeight: 400, url: `https://fonts.example/${fontId}.woff` }))
}, activeComponents: ids.map(fontId => ({ componentType: 'TEXT_SECTION', textSection: { fontId, content: 'text' } })) })
for (const pkg of ['portfolios', 'team-portfolios']) {
  test(`${pkg}: 三款字体仍串行注册，最后一款与网格排版均完成才显示`, async () => {
    let layoutDone
    const f = fixture(pkg, [{ waitForFontLayout: () => new Promise(resolve => { layoutDone = resolve }) }])
    const helper = f.load('portfolio-remote-font-page').installRemoteFontPage(f.page)
    f.page.setData({ portfolio: view() })
    assert.equal(f.page.data.fontOpening, true)
    assert.deepEqual(f.activity, [false])
    assert.equal(f.calls.length, 1)
    f.calls[0].success(); await flush()
    assert.equal(f.calls.length, 2)
    assert.equal(f.page.data.fontContext, undefined, '注册期间不逐款改变正文')
    f.calls[1].success(); await flush()
    f.calls[2].success(); await flush()
    assert.equal(Object.keys(f.page.data.fontContext.families).length, 3)
    assert.equal(f.page.data.fontOpening, true)
    assert.equal(f.ready.length, 0)
    layoutDone(); await flush()
    assert.equal(f.page.data.fontOpening, false)
    assert.equal(f.ready.length, 1)
    assert.equal(f.timers.size, 0)
    helper.dispose()
  })
  test(`${pkg}: 三秒为整批预算，超时取消队列，迟到成功和同页再加载不改系统回退`, async () => {
    let natural = 0
    const f = fixture(pkg, [{ useNaturalFontLayout() { natural++; return Promise.resolve() } }])
    const helper = f.load('portfolio-remote-font-page').installRemoteFontPage(f.page)
    f.page.setData({ portfolio: view() })
    await f.tick(2999)
    assert.equal(f.page.data.fontOpening, true)
    await f.tick(1)
    assert.equal(f.page.data.fontOpening, false)
    assert.equal(natural, 1)
    assert.equal(Object.keys(f.page.data.fontContext.families).length, 0)
    assert.equal(f.calls.length, 1)
    f.calls[0].success(); await flush()
    f.page.setData({ portfolio: view(['CORMORANT_GARAMOND']) }); await flush()
    assert.equal(f.calls.length, 1)
    assert.equal(Object.keys(f.page.data.fontContext.families).length, 0)
    assert.equal(f.timers.size, 0)
    helper.dispose()
  })
  test(`${pkg}: 失败立即系统回退，无字体不等待测量，不额外延迟`, async () => {
    const f = fixture(pkg)
    const helper = f.load('portfolio-remote-font-page').installRemoteFontPage(f.page)
    f.page.setData({ portfolio: view(['ALLURA']) })
    f.calls[0].fail(); await flush()
    assert.equal(f.page.data.fontOpening, false)
    assert.equal(Object.keys(f.page.data.fontContext.families).length, 0)
    f.page.selectAllComponents = () => { throw Error('无远程字体不阻塞网格') }
    f.page.setData({ portfolio: view([]) }); await flush()
    assert.equal(f.page.data.fontOpening, false)
    assert.equal(f.timers.size, 0)
    helper.dispose()
  })
  test(`${pkg}: 同批状态更新不重置预算，卸载丢弃迟到完成和定时器`, async () => {
    const f = fixture(pkg)
    const helper = f.load('portfolio-remote-font-page').installRemoteFontPage(f.page)
    f.page.setData({ portfolio: view() })
    await f.tick(2000)
    f.page.setData({ portfolio: view() })
    await f.tick(1000)
    assert.equal(f.page.data.fontOpening, false)
    helper.dispose()
    const updates = f.updates.length
    f.calls[0].success(); await f.tick(10000)
    assert.equal(f.updates.length, updates)
    assert.equal(f.timers.size, 0)
  })
  test(`${pkg}: 新菜单替换旧批次，旧完成不关闭当前遮罩`, async () => {
    const f = fixture(pkg)
    const helper = f.load('portfolio-remote-font-page').installRemoteFontPage(f.page)
    f.page.setData({ portfolio: view(['ALLURA']) })
    f.page.setData({ portfolio: view(['MANROPE']) })
    f.calls[0].success(); await flush()
    assert.equal(f.page.data.fontOpening, true)
    assert.equal(f.ready.length, 0)
    f.calls[1].success(); await flush()
    assert.equal(f.page.data.fontOpening, false)
    assert.equal(Object.keys(f.page.data.fontContext.families).length, 1)
    assert.ok(Object.keys(f.page.data.fontContext.families)[0].startsWith('MANROPE:'))
    helper.dispose()
  })
}
for (const pkg of ['portfolios', 'team-portfolios', 'mock']) {
  test(`${pkg}: 隐藏页完成不启动播放，返回通知就绪，错误或退出取消遮罩`, async () => {
    const f = fixture(pkg)
    const opening = f.load('portfolio-opening').createPortfolioOpening(f.page)
    assert.equal(f.page.data.fontOpeningTop, 72)
    const ticket = opening.begin(true)
    opening.hide(); opening.commit(ticket, {})
    await flush()
    assert.equal(f.ready.length, 0)
    opening.show()
    assert.equal(f.ready.length, 1)
    const cancelled = opening.begin(true)
    opening.cancel(); opening.commit(cancelled, {}); await f.tick(3000)
    assert.equal(f.ready.length, 1)
    assert.equal(f.page.data.fontOpening, false)
    opening.begin(true); opening.dispose()
    const updates = f.updates.length
    await f.tick(3000)
    assert.equal(f.updates.length, updates)
  })
  test(`${pkg}: 最终网格测量挂起仍受三秒约束，不能因注册成功无限等待`, async () => {
    let finish
    const f = fixture(pkg, [{ waitForFontLayout: () => new Promise(resolve => { finish = resolve }), useNaturalFontLayout: () => Promise.resolve() }])
    const opening = f.load('portfolio-opening').createPortfolioOpening(f.page)
    const ticket = opening.begin(true)
    opening.commit(ticket, { fontContext: { families: { font: 'registered' } } }); await flush()
    assert.equal(f.page.data.fontOpening, true)
    await f.tick(3000)
    assert.equal(f.page.data.fontOpening, false)
    assert.equal(Object.keys(f.page.data.fontContext.families).length, 0)
    finish(); await flush()
    assert.equal(f.ready.length, 1)
    opening.dispose()
  })
}
test('正式双分包等待器与网格副本一致，五个打开入口使用正式 Logo 与媒体暂停', () => {
  for (const relative of ['utils/portfolio-opening.js', 'components/text-grid/text-grid.js']) {
    assert.equal(fs.readFileSync(path.join(root, 'pages/portfolios', relative), 'utf8'), fs.readFileSync(path.join(root, 'pages/team-portfolios', relative), 'utf8'))
  }
  const entries = ['portfolios/standard-preview/portfolio-standard-preview', 'portfolios/visitor-portfolio/visitor-portfolio',
    'team-portfolios/standard-preview/team-portfolio-standard-preview', 'team-portfolios/visitor-portfolio/team-visitor-portfolio', 'mock/portfolio-standard-preview/portfolio-standard-preview']
  for (const entry of entries) {
    const markup = fs.readFileSync(path.join(root, `pages/${entry}.wxml`), 'utf8')
    assert.match(markup, /template is="portfolio-opening"/)
    assert.match(markup, /active: loading \|\| fontOpeningVisible/)
    assert.match(markup, /scroll-y="\{\{!fontOpening(?: && !portfolio\.underMaintenance)?\}\}"/)
    assert.match(markup, /portfolio-font-grid/)
    const template = fs.readFileSync(path.join(root, `pages/${entry.split('/')[0]}/templates/portfolio-opening.wxml`), 'utf8')
    for (const logo of ['folio-logo-stack-bold-dark-50kb.png', 'folio-logo-stack-bold-small-50kb.png']) {
      assert.ok(template.includes(logo)); assert.ok(fs.existsSync(path.join(root, 'assets/system', logo)))
    }
    assert.match(template, /catchtouchmove="blockPortfolioOpeningTouch"/)
    assert.doesNotMatch(template, /wx:if="\{\{active/)
  }
})
for (const pkg of ['portfolios', 'team-portfolios']) {
  test(`${pkg}: 访客只在正文就绪后恢复停留计时与首次背景音频`, async () => {
    const f = fixture(pkg)
    Object.assign(f.page, f.load('portfolio-opening').portfolioOpeningPageMethods)
    let audio = 0, shown = 0, pauses = 0
    f.page.syncBackgroundAudio = () => { audio++ }
    f.page.pauseBackgroundAudio = () => { pauses++ }
    f.page.singleWorkPageVisible = true
    f.page.browserContext.show = () => { shown++ }
    const helper = f.load('portfolio-remote-font-page').installRemoteFontPage(f.page)
    f.page.setData({ portfolio: view(['ALLURA']), loading: false })
    assert.equal(audio, 0)
    assert.equal(pauses, 0, '首次 loading 不调用 pause 消耗音频 autoplay 机会')
    assert.deepEqual(f.activity, [false])
    f.calls[0].success(); await flush()
    assert.equal(audio, 1)
    assert.equal(shown, 1)
    assert.deepEqual(f.activity, [false, true])
    helper.dispose()
  })
  for (const missing of [false, true]) {
    test(`${pkg}: 实际网格${missing ? '两轮测量缺失转自然布局' : '最终样式与高度落地'}后才通知完成`, async () => {
      const filename = path.join(root, `pages/${pkg}/components/text-grid/text-grid.js`)
      let definition
      const queries = []
      vm.runInNewContext(fs.readFileSync(filename, 'utf8'), { Component(value) { definition = value }, setTimeout, clearTimeout,
        wx: { nextTick: callback => callback(), getWindowInfo: () => ({ windowWidth: 375 }) },
        require: id => require(path.resolve(path.dirname(filename), id)) }, { filename })
      const grid = require(`../pages/${pkg}/utils/portfolio-text-grid`).createTextGrid(1, 1)
      grid.cells[0].blocks[0].runs[0].text = '字高测量'
      const instance = { ...definition.methods, _alive: true, properties: { config: grid, themeMode: 'light', fontContext: { revision: 3 } },
        data: {}, triggerEvent() {}, setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb() },
        createSelectorQuery() { return { select() { return this }, selectAll() { return this }, boundingClientRect(cb) { queries.push(cb); return this }, exec() {} } } }
      let completed = false
      const waiting = instance.waitForFontLayout().then(() => { completed = true })
      assert.equal(queries.length, 1)
      queries.shift()({ width: 320 }); await flush()
      assert.equal(completed, false)
      queries.shift()(missing ? [] : [{ height: 80 }]); await flush()
      if (missing) {
        assert.equal(completed, false)
        queries.shift()([])
      }
      await waiting
      assert.equal(completed, true)
      assert.equal(instance.data.natural, missing)
      assert.equal(instance.data.measuringCells.length, 0)
      assert.ok(instance.data.cells.length > 0)
    })
  }
}
for (const pkg of ['portfolios', 'team-portfolios']) {
  test(`${pkg}: 维护状态立即撤销字体等待，迟到回调不恢复正文或播放`, async () => {
    const f = fixture(pkg)
    const audio = []
    f.page.backgroundAudioPlayer = {}
    f.page.syncBackgroundAudio = (resource, autoplay) => audio.push([resource, autoplay])
    const helper = f.load('portfolio-remote-font-page').installRemoteFontPage(f.page)
    f.page.setData({ portfolio: view(), loading: true })
    f.calls[0].success(); await flush()
    assert.equal(f.calls.length, 2)
    // 保持字体需求完全相同，证明维护状态本身能够中断既有等待。
    f.page.setData({ portfolio: { ...view(), underMaintenance: true } })
    assert.equal(f.page.data.fontOpening, false)
    assert.equal(f.page.data.fontOpeningVisible, false)
    assert.deepEqual(audio, [[null, false]])
    assert.equal(f.activity.at(-1), false)
    const updateCount = f.updates.length
    f.calls[1].success(); await f.tick(10000)
    assert.equal(f.calls.length, 2, '维护期间不启动尚未注册的第三款字体')
    assert.equal(f.updates.length, updateCount)
    assert.equal(f.ready.length, 0)
    assert.equal(f.timers.size, 0)
    helper.show()
    assert.equal(f.ready.length, 0, '从后台返回也不能恢复已取消的播放')
    f.page.setData({ portfolio: view(), loading: false }); await flush()
    assert.equal(f.calls.length, 3, '恢复访问后仍能加载所需字体')
    f.calls[2].success(); await flush()
    assert.equal(f.page.data.fontOpening, false)
    assert.equal(f.ready.length, 1)
    helper.dispose()
  })
  test(`${pkg}: 首次收到维护响应时不注册字体、不启动等待计时`, async () => {
    const f = fixture(pkg)
    const helper = f.load('portfolio-remote-font-page').installRemoteFontPage(f.page)
    f.page.setData({ portfolio: { ...view(), underMaintenance: true }, loading: false })
    await flush()
    assert.equal(f.calls.length, 0)
    assert.equal(f.timers.size, 0)
    assert.equal(f.ready.length, 0)
    assert.equal(f.page.data.fontOpeningVisible, false)
    helper.dispose()
  })
}
test('积分不足维护遮罩置于最终 root-portal，层级高于 loading 与其余页面遮罩', () => {
  const dir = path.join(root, 'pages/portfolios/visitor-portfolio/visitor-portfolio')
  const wxml = fs.readFileSync(`${dir}.wxml`, 'utf8')
  const styles = fs.readFileSync(`${dir}.wxss`, 'utf8')
  const loadingStyles = fs.readFileSync(path.join(root, 'styles/portfolio-opening.wxss'), 'utf8')
  const maintenance = wxml.match(/<root-portal wx:if="\{\{portfolio\.underMaintenance\}\}">[\s\S]*?<\/root-portal>/)[0]
  assert.match(maintenance, /catchtap="handleMaintenanceMaskTouch" catchtouchmove="handleMaintenanceMaskTouch"/)
  assert.match(maintenance, /UNDER MAINTENANCE/)
  assert.match(maintenance, /维护中/)
  assert.ok(wxml.indexOf(maintenance) > wxml.indexOf('<template is="portfolio-opening"'))
  assert.match(wxml, /<template is="portfolio-opening"[^>]*wx:if="\{\{!portfolio\.underMaintenance\}\}"/)
  assert.match(wxml, /scroll-y="\{\{!fontOpening && !portfolio\.underMaintenance\}\}"/)
  const mask = styles.match(/\.maintenance-mask\s*\{[^}]+\}/)[0]
  const priority = Number(mask.match(/z-index:\s*(\d+)/)[1])
  const others = styles.replace(mask, '') + loadingStyles
  for (const match of others.matchAll(/z-index:\s*(\d+)/g)) assert.ok(priority > Number(match[1]))
  assert.match(fs.readFileSync(`${dir}.js`, 'utf8'), /handleMaintenanceMaskTouch\(\)\s*\{\s*\}/)
})
