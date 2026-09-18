const test = require('node:test')
const assert = require('node:assert/strict')
const { canvasRuntime } = require('./helpers/miniapp-code-canvas')
const fs = require('node:fs')
const path = require('node:path')
const rendererPath = '../pages/portfolios/utils/portfolio-miniapp-code-renderer'
const tick = () => new Promise(resolve => setImmediate(resolve))
const card = { avatarSize: 120, ownerType: 'USER', displayName: '主持人😀'.repeat(15), subtitle: '上海\n主持人', shareTitle: '婚礼😀'.repeat(40) }
const input = () => ({ useAvatar: true, resources: card, codePath: '/tmp/code.png', avatarPath: '/tmp/avatar.png', isActive: () => true })
function renderer(runtime) { const { createPortfolioMiniappCodeRenderer } = require(rendererPath); return createPortfolioMiniappCodeRenderer({ page: {}, wxApi: runtime.wxApi }) }

test('renderer exists as a package-local utility', () => {
  assert.ok(fs.existsSync(path.join(__dirname, `${rendererPath}.js`)), 'client Canvas renderer must exist')
})

test('canvas remains unqueried before onReady and exports one fixed resolution PNG', async () => {
  const runtime = canvasRuntime(); const r = renderer(runtime)
  const pending = r.render(input()); await tick()
  assert.deepEqual(runtime.calls, [])
  r.ready(); assert.equal(await pending, '/tmp/rendered.png')
  assert.equal(runtime.canvas.width, 1080); assert.equal(runtime.canvas.height, 1440)
  const options = runtime.calls.find(c => c[0] === 'export')[1]
  assert.equal(options.fileType, 'png'); assert.equal(options.destWidth, 1080); assert.equal(options.destHeight, 1440)
})

test('code remains 800px and a 360px avatar covers the app logo despite legacy 120px server hint', async () => {
  const runtime = canvasRuntime(); const r = renderer(runtime); r.ready(); await r.render(input())
  const draws = runtime.calls.filter(c => c[0] === 'drawImage')
  assert.deepEqual(draws[0].slice(2), [140, 390])
  assert.deepEqual(draws[1].slice(2), [0, 0, 512, 512, 360, 610, 360, 360])
  assert.deepEqual(runtime.calls.find(c => c[0] === 'arc').slice(1), [540, 790, 180, 0, Math.PI * 2])
  assert.ok(runtime.calls.findIndex(c => c[0] === 'fillRect' && c[1] === 360) < runtime.calls.indexOf(draws[1]))
  assert.ok(runtime.calls.findIndex(c => c[0] === 'restore') < runtime.calls.findIndex(c => c[0] === 'fillText' && c[1].includes('微信扫码')))
})

test('actual measured widths cap two title lines and ellipsis never splits emoji', async () => {
  const runtime = canvasRuntime(); const r = renderer(runtime); r.ready(); await r.render(input())
  const texts = runtime.calls.filter(c => c[0] === 'fillText')
  const title = texts.filter(c => c[3] === 292 || c[3] === 342)
  assert.equal(title.length, 2); assert.ok(title[1][1].endsWith('…'))
  for (const c of texts) assert.equal(c[1].isWellFormed(), true)
  for (const c of title) assert.ok(Array.from(c[1]).length * 38 <= 870)
  assert.equal(texts.find(c => c[3] === 211)[1], '上海 主持人')
})

test('wrong original code dimensions and oversized avatar never enter Canvas decode', async () => {
  for (const [bad, width] of [['code', 799], ['avatar', 4096]]) {
    const runtime = canvasRuntime({ getImageInfo(o) { o.success({ width: o.src.includes(bad) ? width : 800, height: o.src.includes(bad) ? width : 800 }) } })
    const r = renderer(runtime); r.ready(); await assert.rejects(r.render(input()), /尺寸/)
    assert.equal(runtime.calls.some(c => c[0] === 'createImage'), false)
  }
})

test('unload during node lookup or image decode stops all later Canvas work', async () => {
  let complete
  const runtime = canvasRuntime(); runtime.canvas.createImage = () => ({ set src(value) { complete = () => this.onload() }, width: 800, height: 800 })
  const r = renderer(runtime); r.ready(); let active = true
  const pending = r.render(Object.assign(input(), { isActive: () => active })); await tick()
  active = false; r.dispose(); complete(); await assert.rejects(pending)
  assert.equal(runtime.calls.some(c => c[0] === 'drawImage' || c[0] === 'export'), false)
})

test('dispose before readiness releases pending work without querying canvas', async () => {
  const runtime = canvasRuntime(); const r = renderer(runtime)
  const pending = r.render(input()); r.dispose(); await assert.rejects(pending)
  assert.deepEqual(runtime.calls, [])
})

test('pending node query after unload cannot start image metadata lookup', async () => {
  let queried
  const runtime = canvasRuntime({ createSelectorQuery() { return { in() { return this }, select() { return this }, fields() { return this }, exec(callback) { queried = callback } } } })
  const r = renderer(runtime); r.ready()
  const pending = r.render(input()); await tick(); r.dispose(); queried([{ node: runtime.canvas }])
  await assert.rejects(pending)
  assert.equal(runtime.calls.some(c => c[0] === 'info'), false)
})

test('export completion after unload rejects its temporary result', async () => {
  let exporting
  const runtime = canvasRuntime({ canvasToTempFilePath(o) { exporting = o } })
  const r = renderer(runtime); r.ready()
  const pending = r.render(input()); await tick(); r.dispose(); exporting.success({ tempFilePath: '/tmp/late.png' })
  await assert.rejects(pending)
})

test('export failure is recoverable on the same renderer', async () => {
  let attempts = 0
  const runtime = canvasRuntime({ canvasToTempFilePath(o) { if (++attempts === 1) o.fail({}); else o.success({ tempFilePath: '/tmp/retry.png' }) } })
  const r = renderer(runtime); r.ready()
  await assert.rejects(r.render(input()), /绘制失败/)
  assert.equal(await r.render(input()), '/tmp/retry.png')
})


test('default or non-boolean avatar option never reads avatar and preserves the entire original code', async () => {
  for (const useAvatar of [undefined, false, 'true']) {
    const runtime = canvasRuntime({ getImageInfo(o) {
      runtime.calls.push(['info', o.src])
      if (o.src !== '/tmp/code.png') { o.fail({ errMsg: 'avatar must not load' }); return }
      o.success({ width: 800, height: 800 })
    } })
    const r = renderer(runtime); r.ready()
    await r.render(Object.assign(input(), { useAvatar, avatarPath: undefined }))
    assert.deepEqual(runtime.calls.filter(c => c[0] === 'info'), [['info', '/tmp/code.png']])
    assert.equal(runtime.calls.filter(c => c[0] === 'createImage').length, 1)
    const draws = runtime.calls.filter(c => c[0] === 'drawImage')
    assert.equal(draws.length, 1)
    assert.deepEqual(draws[0].slice(2), [140, 390])
    assert.equal(runtime.calls.some(c => c[0] === 'clip' || c[0] === 'arc'), false)
    assert.deepEqual(runtime.calls.filter(c => c[0] === 'fillRect').map(c => c.slice(1)), [[0, 0, 1080, 1440], [504, 66, 72, 5]])
  }
})

test('direct card drawing defaults to no avatar and does not access an absent avatar image', () => {
  const { drawPortfolioMiniappCode } = require(rendererPath)
  const runtime = canvasRuntime()
  drawPortfolioMiniappCode(runtime.canvas, runtime.context, card, { width: 800, height: 800 }, undefined)
  assert.equal(runtime.calls.filter(c => c[0] === 'drawImage').length, 1)
  assert.equal(runtime.calls.some(c => c[0] === 'clip'), false)
})

test('result page starts with avatar disabled and delegates only boolean switch changes', () => {
  const pageFile = path.join(__dirname, '../pages/portfolios/share-code/portfolio-share-code.js')
  const previous = global.Page
  let page
  global.Page = definition => { page = definition }
  try {
    delete require.cache[require.resolve(pageFile)]; require(pageFile)
    assert.equal(page.data.useAvatar, false)
    assert.equal(page.data.canToggleAvatar, false)
    const choices = []
    page.codeController = { setUseAvatar(value) { choices.push(value) } }
    page.handleUseAvatarChange({ detail: { value: true } })
    page.handleUseAvatarChange({ detail: { value: false } })
    page.handleUseAvatarChange({ detail: { value: 'true' } })
    assert.deepEqual(choices, [true, false, false])
  } finally { global.Page = previous }
})
