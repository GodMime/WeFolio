const test = require('node:test')
const assert = require('node:assert/strict')
for (const pkg of ['portfolios', 'team-portfolios']) {
const { createRemoteFontSession } = require(`../pages/${pkg}/utils/portfolio-remote-font-session`)

test(`${pkg}: 模拟原生桥仅保留末次在途回调时，三款字体仍逐一注册且不重试`, async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let latest
  const calls = []
  const session = createRemoteFontSession({ loadFontFace(options) {
    latest = options
    calls.push(options)
    setTimeout(() => { if (latest === options) options.success() }, 1)
  } })
  const ids = ['ALLURA', 'CORMORANT_GARAMOND', 'ZCOOL_XIAOWEI']
  const manifest = { versions: Object.fromEntries(ids.map(id => [id, { fontVersion: 'test' }])),
    assets: ids.map(id => ({ fontId: id, fontVersion: 'test', status: 'READY', url: `https://fonts.example/${id}.woff` })) }
  try {
    const loading = session.load(manifest, ids.map(fontId => ({ fontId })))
    for (let i = 0; i < 12; i++) { t.mock.timers.tick(1000); await Promise.resolve() }
    await loading
    assert.equal(Object.keys(session.context().families).length, 3)
    assert.deepEqual(session.context().failed, [])
    await session.load(manifest, ids.map(fontId => ({ fontId })))
    assert.equal(calls.length, 3)
  } finally { session.dispose() }
})

test(`${pkg}: 前一款超时后才启动下一款，迟到回调不能结束下一款注册`, async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const calls = []
  const session = createRemoteFontSession({ loadFontFace(options) { calls.push(options) } })
  const ids = ['ALLURA', 'CORMORANT_GARAMOND', 'ZCOOL_XIAOWEI']
  try {
    const loading = session.load({ versions: Object.fromEntries(ids.map(id => [id, { fontVersion: 'test' }])),
      assets: ids.map(id => ({ fontId: id, fontVersion: 'test', status: 'READY', url: `https://fonts.example/${id}.woff` }))
    }, ids.map(fontId => ({ fontId })))
    assert.equal(calls.length, 1)
    t.mock.timers.tick(10001)
    assert.equal(calls.length, 2)
    calls[0].success()
    assert.equal(calls.length, 2)
    calls[1].fail({ errMsg: 'loadFontFace:fail test' })
    assert.equal(calls.length, 3)
    calls[2].success()
    await loading
    assert.deepEqual(session.context().failed.sort(), ['ALLURA', 'CORMORANT_GARAMOND'])
    assert.equal(Object.keys(session.context().families).length, 1)
  } finally { session.dispose() }
})

test(`${pkg}: 超时后迟到成功不改变系统字体回退，也不触发重试`, async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let callback
  let calls = 0
  const session = createRemoteFontSession({ loadFontFace(options) { calls++; callback = options } })
  try {
    const loading = session.load({ versions: { ALLURA: { fontVersion: 'v1' } }, assets: [
      { status: 'READY', fontId: 'ALLURA', fontVersion: 'v1', url: 'https://fonts.example/a.woff' }
    ] }, [{ fontId: 'ALLURA' }])
    t.mock.timers.tick(10001)
    await loading
    callback.success()
    assert.deepEqual(session.context().failed, ['ALLURA'])
    assert.deepEqual(session.context().families, {})
    assert.equal(calls, 1)
  } finally { session.dispose() }
})

test(`${pkg}: 基础库 3.7.9 起使用默认全部作用域，旧版和未知版本仍显式注册`, async () => {
  for (const [version, modern] of [['3.7.8', false], ['3.6.99', false], ['3.7.9', true], ['3.17.3', true], ['4.0.0', true], ['', false]]) {
    const calls = []
    const session = createRemoteFontSession({ getAppBaseInfo: () => ({ SDKVersion: version }),
      loadFontFace(options) { calls.push(options); options.success() } })
    try {
      await session.load({ versions: { ALLURA: { fontVersion: 'v1' } }, assets: [
        { status: 'READY', fontId: 'ALLURA', fontVersion: 'v1', fontWeight: 400, url: 'https://fonts.example/a.woff' }
      ] }, [{ fontId: 'ALLURA' }])
      assert.equal(calls.length, 1)
      assert.equal(Object.hasOwn(calls[0], 'scopes'), !modern, version)
      if (!modern) assert.deepEqual(calls[0].scopes, ['webview', 'skyline'])
      assert.equal(calls[0].global, false)
      assert.equal(calls[0].desc.weight, '400')
      assert.ok(session.context().families['ALLURA:v1:400'])
    } finally { session.dispose() }
  }
})

test(pkg + ': ' + '页面实例加载同一资源仅一次，失败回退系统字体且不重试', async () => {
  let calls = 0
  const session = createRemoteFontSession({ loadFontFace(options) { calls++; options.fail({ errMsg: 'offline' }) } })
  const manifest = { planVersion: 1, planHash: 'a', versions: { ALLURA: { fontVersion: 'v1' } },
    assets: [{ assetId: '1', status: 'READY', fontId: 'ALLURA', fontVersion: 'v1', fontWeight: 400, fontStyle: 'normal', subsetHash: 'hash', url: 'https://fonts.example/a.woff' }] }
  await session.load(manifest, [{ fontId: 'ALLURA' }])
  await session.load(manifest, [{ fontId: 'ALLURA' }])
  assert.equal(calls, 1)
  assert.deepEqual(session.context().families, {})
  session.dispose()
})

test(pkg + ': ' + '独立页面 family 不同，卸载后丢弃迟到成功回调', async () => {
  const callbacks = []
  const api = { loadFontFace(options) { callbacks.push(options) } }
  const first = createRemoteFontSession(api)
  const second = createRemoteFontSession(api)
  const manifest = { versions: { ALLURA: { fontVersion: 'v1' } }, assets: [
    { status: 'READY', assetId: '1', fontId: 'ALLURA', fontVersion: 'v1', fontWeight: 400, subsetHash: 'x', url: 'https://fonts.example/a.woff' }] }
  const a = first.load(manifest, [{ fontId: 'ALLURA' }])
  const b = second.load(manifest, [{ fontId: 'ALLURA' }])
  assert.notEqual(callbacks[0].family, callbacks[1].family)
  assert.deepEqual(callbacks[0].scopes, ['webview', 'skyline'])
  first.dispose()
  callbacks.forEach(options => options.success())
  await Promise.all([a, b])
  assert.deepEqual(first.context().families, {})
  assert.equal(Object.keys(second.context().families).length, 1)
  second.dispose()
})

test(pkg + ': ' + '展示数据的空 config 不遮蔽正文，结构化留白不收集', () => {
  const { collectFontNodes } = require(`../pages/${pkg}/utils/portfolio-remote-font-session`)
  assert.deepEqual(collectFontNodes([
    { componentType: 'TEXT_SECTION', config: {}, textSection: { fontId: 'ALLURA' } },
    { componentType: 'STRUCTURED_TEXT_SECTION', config: {}, structuredTextSection: { blocks: [{ type: 'TITLE', fontId: 'MANROPE' }, { type: 'SPACER', fontId: 'ALLURA' }] } },
    { componentType: 'TEXT_GRID', config: {}, textGrid: { cells: [{ blocks: [{ runs: [{ fontId: 'LXGW_WENKAI' }] }] }] } }
  ]).map(node => node.fontId), ['ALLURA', 'MANROPE', 'LXGW_WENKAI'])
})

test(pkg + ': ' + '当前菜单仅下载使用的字重，合成粗体复用常规资源', async () => {
  const calls = []
  const session = createRemoteFontSession({ loadFontFace(options) { calls.push(options); options.success() } })
  const asset = (fontId, fontWeight) => ({ fontId, fontWeight, fontVersion: 'v1', status: 'READY', assetId: `${fontId}-${fontWeight}`, url: `https://fonts.example/${fontId}-${fontWeight}.woff` })
  const manifest = { versions: { ALLURA: { fontVersion: 'gf-809e4d8b8d7e-r1' }, MANROPE: { fontVersion: 'gf-809e4d8b8d7e-r1' } }, assets: [asset('MANROPE', 400), asset('MANROPE', 700), asset('ALLURA', 400)].map(item => ({ ...item, fontVersion: 'gf-809e4d8b8d7e-r1' })) }
  await session.load(manifest, [{ fontId: 'MANROPE', fontWeight: 'BOLD' }, { fontId: 'ALLURA', fontWeight: 'BOLD' }])
  assert.equal(calls.length, 2)
  assert.deepEqual(calls.map(call => call.desc.weight), ['700', '400'])
  session.dispose()
})

}
for (const pkg of ['portfolios', 'team-portfolios']) {
  test(`${pkg}: 取消候选移除尚未开始的加载需求，迟到回调不发布旧 family`, async () => {
    const { createRemoteFontSession } = require(`../pages/${pkg}/utils/portfolio-remote-font-session`)
    const callbacks = []
    const session = createRemoteFontSession({ loadFontFace(options) { callbacks.push(options) } })
    const ids = ['ALLURA', 'MANROPE', 'LXGW_WENKAI', 'ZCOOL_XIAOWEI']
    const manifest = { versions: Object.fromEntries(ids.map(id => [id, { fontVersion: 'test' }])), assets: ids.map(id => ({
      fontId: id, fontVersion: 'test', fontWeight: 400, status: 'READY', assetId: id, url: `https://font.example/${id}.woff`
    })) }
    const loading = session.load(manifest, ids.map(fontId => ({ fontId })))
    assert.equal(callbacks.length, 1)
    await session.load(manifest, [])
    callbacks.slice().forEach(options => options.success())
    await loading
    assert.equal(callbacks.length, 1)
    assert.deepEqual(session.context().families, {})
    session.dispose()
  })
}
