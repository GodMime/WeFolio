const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const requestPath = require.resolve('../utils/request')
function fixture(pkg, requestFn) {
  const original = require.cache[requestPath]
  require.cache[requestPath] = { exports: { request: requestFn } }
  const file = require.resolve(`../pages/${pkg}/utils/portfolio-remote-font-page`)
  delete require.cache[file]
  const { installRemoteFontPage } = require(file)
  if (original) require.cache[requestPath] = original
  else delete require.cache[requestPath]
  const page = { data: { portfolioId: 1, activeMenuKey: 'second', config: { components: [{ componentKey: 'root', componentType: 'TEXT_SECTION', config: { content: 'root' } }], bottomNav: { items: [{ key: 'first' }, { key: 'second', components: [{ componentKey: 'text', componentType: 'TEXT_SECTION', config: { content: 'saved' } }] }] } } }, setData(patch) { for (const [key, value] of Object.entries(patch)) { const parts = key.split('.'); const last = parts.pop(); parts.reduce((o, k) => o[k] ||= {}, this.data)[last] = value } } }
  return { page, helper: installRemoteFontPage(page, { editor: true, team: pkg === 'team-portfolios' }) }
}
for (const pkg of ['portfolios', 'team-portfolios']) {
  test(`${pkg}: 目录仅显式展开且失败同页不重发`, async () => {
    let calls = 0
    const { page, helper } = fixture(pkg, async () => { calls++; throw Error('offline') })
    page.setData({ textSectionSheetVisible: true, textSectionEditingComponentKey: 'text', textSectionForm: { content: 'candidate' } })
    assert.equal(calls, 0)
    await helper.expand()
    await helper.expand()
    assert.equal(calls, 1)
    assert.match(page.data.fontMessage, /系统字体/)
    helper.dispose()
  })
  test(`${pkg}: prepare 合并子菜单候选，取消不污染根表且拒绝迟到响应`, async () => {
    const requests = []
    let respond
    const { page, helper } = fixture(pkg, options => {
      requests.push(options)
      if (options.method === 'POST') return new Promise(resolve => { respond = resolve })
      return Promise.resolve({ available: true, fonts: [{ fontId: 'ALLURA', fontVersion: 'catalog-v2' }] })
    })
    page.setData({ textSectionSheetVisible: true, textSectionEditingComponentKey: 'text', textSectionForm: { content: 'candidate' } })
    await helper.expand()
    helper.select('ALLURA')
    page.setData({ 'textSectionForm.fontId': 'ALLURA' })
    assert.equal(page.data.config.fonts, undefined)
    assert.equal(page.data.hasRemoteFonts, true)
    const pending = helper.prepare()
    const sent = requests.at(-1).data.config
    assert.equal(sent.bottomNav.items[1].components[0].config.content, 'candidate')
    assert.equal(sent.bottomNav.items[1].components[0].config.fontId, 'ALLURA')
    assert.equal(sent.fonts.ALLURA.fontVersion, 'catalog-v2')
    assert.equal(sent.components[0].config.content, 'root')
    page.setData({ textSectionSheetVisible: false })
    respond({ fontAssets: { planHash: 'stale' } })
    await pending
    assert.equal(page.data.fontAssets, undefined)
    assert.equal(page.data.config.fonts, undefined)
    helper.dispose()
  })
  test(`${pkg}: 确认根版本与组件共同提交，pending payload 保持原字节`, async () => {
    const { page, helper } = fixture(pkg, async () => ({ available: true, fonts: [{ fontId: 'ALLURA', fontVersion: 'v2' }] }))
    const api = require(`../pages/${pkg}/utils/${pkg === 'portfolios' ? 'portfolios' : 'team-portfolios'}`)
    let payload
    if (pkg === 'portfolios') payload = api.buildDraftPayload(page.data.config, 7, 'frozen-font-save')
    else await api.saveTeamPortfolioDraft(options => { payload = options.data; return Promise.resolve({}) }, 1, page.data.config, 7, 'frozen-font-save')
    page.pendingDraftSave = { payload: JSON.parse(JSON.stringify(payload)) }
    assert.deepEqual(page.pendingDraftSave.payload.clientCapabilities, { portfolioRemoteFont: 1 })
    assert.equal(page.pendingDraftSave.payload.clientRevision, 7)
    const before = JSON.stringify(page.pendingDraftSave.payload)
    page.setData({ textSectionSheetVisible: true, textSectionEditingComponentKey: 'text', textSectionForm: { content: 'new', fontId: 'ALLURA' } })
    await helper.expand(); helper.select('ALLURA')
    const next = helper.commit(helper.candidateConfig())
    assert.equal(next.fonts.ALLURA.fontVersion, 'v2')
    assert.equal(JSON.stringify(page.pendingDraftSave.payload), before)
    helper.dispose()
  })
}
test('正式 session/page/sections 工具副本逐字节一致', () => {
  for (const name of ['portfolio-remote-font-session', 'portfolio-remote-font-page', 'portfolio-text-sections']) {
    const read = pkg => fs.readFileSync(path.join(__dirname, `../pages/${pkg}/utils/${name}.js`), 'utf8')
    assert.equal(read('portfolios'), read('team-portfolios'), name)
  }
})
for (const pkg of ['portfolios', 'team-portfolios']) {
  for (const type of ['TEXT_SECTION', 'STRUCTURED_TEXT_SECTION', 'TEXT_GRID']) {
    test(`${pkg}: ${type} 未确认内容、版本撤销与会话隔离`, async () => {
      const { page, helper } = fixture(pkg, async () => ({ available: true, fonts: [{ fontId: 'ALLURA', fontVersion: 'new-v' }] }))
      const draft = type === 'TEXT_SECTION' ? { content: 'typed', fontId: 'ALLURA' }
        : type === 'STRUCTURED_TEXT_SECTION' ? { blocks: [{ blockKey: 'title', type: 'TITLE', content: 'typed', fontId: 'ALLURA' }] }
          : { cells: [{ blocks: [{ runs: [{ text: 'typed', fontId: 'ALLURA' }] }] }] }
      const open = type === 'TEXT_SECTION' ? { textSectionSheetVisible: true, textSectionEditingComponentKey: 'text', textSectionForm: draft }
        : type === 'STRUCTURED_TEXT_SECTION' ? { structuredTextSheetVisible: true, structuredTextEditingComponentKey: '', structuredTextConfig: { blocks: [] } }
          : { newComponentSheetVisible: true, newComponentType: type, newComponentKey: '', newComponentConfig: { cells: [] } }
      page.setData(open)
      const firstSession = page.data.fontEditSessionId
      await helper.expand(); helper.select('ALLURA', firstSession)
      if (type !== 'TEXT_SECTION') helper.editDraft({ componentType: type, config: draft, sessionId: firstSession })
      const candidate = helper.candidateConfig()
      assert.equal(candidate.fonts.ALLURA.fontVersion, 'new-v')
      assert.deepEqual(candidate.bottomNav.items[1].components.at(-1).config, draft)
      assert.equal(candidate.components.length, 1)
      if (type === 'TEXT_GRID') {
        const removed = { cells: [] }
        helper.editDraft({ componentType: type, config: removed, sessionId: firstSession })
        helper.editDraft({ componentType: type, config: draft, sessionId: firstSession, operation: 'undo' })
        assert.equal(helper.candidateConfig().fonts.ALLURA.fontVersion, 'new-v')
      }
      page.setData({ textSectionSheetVisible: false, structuredTextSheetVisible: false, newComponentSheetVisible: false })
      page.setData(open)
      helper.editDraft({ componentType: type, config: { stale: true }, sessionId: firstSession })
      helper.select('ALLURA', firstSession)
      assert.equal(helper.candidateConfig().fonts.ALLURA, undefined)
      assert.equal(helper.candidateConfig().bottomNav.items[1].components.at(-1).config.stale, undefined)
      helper.dispose()
    })
  }
  test(`${pkg}: 候选已加载后取消恢复原清单；改字与菜单切换拒绝旧 prepare`, async () => {
    let resolve
    const { page, helper } = fixture(pkg, () => new Promise(done => { resolve = done }))
    page.setData({ fontAssets: { planHash: 'saved' }, textSectionSheetVisible: true, textSectionEditingComponentKey: 'text', textSectionForm: { content: 'a' } })
    const first = helper.prepare()
    resolve({ fontAssets: { planHash: 'candidate' } }); await first
    assert.equal(page.data.fontAssets.planHash, 'candidate')
    page.setData({ textSectionSheetVisible: false })
    assert.equal(page.data.fontAssets.planHash, 'saved')
    page.setData({ textSectionSheetVisible: true, textSectionEditingComponentKey: 'text', textSectionForm: { content: 'b' } })
    const second = helper.prepare()
    page.setData({ 'textSectionForm.content': 'c' })
    resolve({ fontAssets: { planHash: 'stale-text' } }); await second
    assert.equal(page.data.fontAssets.planHash, 'saved')
    const third = helper.prepare()
    page.setData({ activeMenuKey: 'first' })
    resolve({ fontAssets: { planHash: 'stale-menu' } }); await third
    assert.equal(page.data.fontAssets.planHash, 'saved')
    const fourth = helper.prepare(); helper.dispose()
    resolve({ fontAssets: { planHash: 'disposed' } }); await fourth
    assert.equal(page.data.fontAssets.planHash, 'saved')
  })
}
for (const pkg of ['portfolios', 'team-portfolios']) {
  test(`${pkg}: 版本修复仅修复缺失与对应未知版本，不暗改有效旧版本且不自动 prepare`, async () => {
    const calls = []
    const priorWx = global.wx
    global.wx = { loadFontFace() {}, showModal(options) { options.success({ confirm: true }) } }
    try {
      const { page, helper } = fixture(pkg, async options => { calls.push(options); return { available: true, fonts: [
        { fontId: 'ALLURA', fontVersion: 'latest' }, { fontId: 'MANROPE', fontVersion: 'latest' }, { fontId: 'LXGW_WENKAI', fontVersion: 'latest' }
      ] } })
      page.setData({ config: { fonts: { ALLURA: { fontVersion: 'fixed-old' }, MANROPE: { fontVersion: 'unknown' } }, components: ['ALLURA', 'MANROPE', 'LXGW_WENKAI', 'UNRECOGNIZED'].map(fontId => ({ componentType: 'TEXT_SECTION', componentKey: fontId, config: { content: 'text', fontId } })) }, fontAssets: { unavailable: [
        { fontId: 'ALLURA', fontVersion: 'another-stale-version', reason: 'UNKNOWN_VERSION' },
        { fontId: 'MANROPE', fontVersion: 'unknown', reason: 'UNKNOWN_VERSION' }
      ] } })
      await helper.repairVersions()
      assert.equal(page.data.config.fonts.ALLURA.fontVersion, 'fixed-old')
      assert.equal(page.data.config.fonts.MANROPE.fontVersion, 'latest')
      assert.equal(page.data.config.fonts.LXGW_WENKAI.fontVersion, 'latest')
      assert.equal(page.data.config.fonts.UNRECOGNIZED, undefined)
      assert.equal(calls.length, 1)
      assert.equal(calls[0].method, undefined)
      helper.dispose()
    } finally { global.wx = priorWx }
  })
}
for (const pkg of ['portfolios', 'team-portfolios']) {
  test(`${pkg}: 网格按历史身份恢复根表，相同字形快照不混淆不同的版本`, async () => {
    const { page, helper } = fixture(pkg, async () => ({ available: true, fonts: [{ fontId: 'ALLURA', fontVersion: 'new' }] }))
    const empty = { cells: [] }
    page.setData({ newComponentSheetVisible: true, newComponentType: 'TEXT_GRID', newComponentKey: '', newComponentConfig: empty })
    const sessionId = page.data.fontEditSessionId
    helper.editDraft({ componentType: 'TEXT_GRID', config: empty, sessionId, historyKey: 0 })
    await helper.expand(); helper.select('ALLURA', sessionId)
    helper.editDraft({ componentType: 'TEXT_GRID', config: empty, sessionId, historyKey: 1 })
    assert.equal(helper.candidateConfig().fonts.ALLURA.fontVersion, 'new')
    helper.editDraft({ componentType: 'TEXT_GRID', config: empty, sessionId, historyKey: 0, operation: 'undo' })
    assert.equal(helper.candidateConfig().fonts.ALLURA, undefined)
    helper.dispose()
  })
}
for (const pkg of ['portfolios', 'team-portfolios']) {
  test(`${pkg}: 弹层内版本修复随候选确认或取消，迟到确认不串入新会话`, async () => {
    let modal
    const priorWx = global.wx
    global.wx = { loadFontFace() {}, showModal(options) { modal = options } }
    try {
      const { page, helper } = fixture(pkg, async () => ({ available: true, fonts: [{ fontId: 'ALLURA', fontVersion: 'new-v' }] }))
      page.setData({ config: { fonts: { ALLURA: { fontVersion: 'unknown-v' } }, components: [
        { componentKey: 'text', componentType: 'TEXT_SECTION', config: { content: 'old', fontId: 'ALLURA' } }
      ] }, activeMenuKey: '', fontAssets: { unavailable: [{ fontId: 'ALLURA', fontVersion: 'unknown-v', reason: 'UNKNOWN_VERSION' }] } })
      const open = () => page.setData({ textSectionSheetVisible: true, textSectionEditingComponentKey: 'text', textSectionForm: { content: 'candidate', fontId: 'ALLURA' } })
      open(); await helper.repairVersions(); modal.success({ confirm: true })
      assert.equal(helper.candidateConfig().fonts.ALLURA.fontVersion, 'new-v')
      assert.equal(page.data.config.fonts.ALLURA.fontVersion, 'unknown-v')
      page.setData({ textSectionSheetVisible: false })
      assert.equal(page.data.config.fonts.ALLURA.fontVersion, 'unknown-v')
      open(); await helper.repairVersions(); const stale = modal
      page.setData({ textSectionSheetVisible: false }); open(); stale.success({ confirm: true })
      assert.equal(helper.candidateConfig().fonts.ALLURA.fontVersion, 'unknown-v')
      await helper.repairVersions(); modal.success({ confirm: true })
      assert.equal(helper.commit(helper.candidateConfig()).fonts.ALLURA.fontVersion, 'new-v')
      helper.dispose()
    } finally { global.wx = priorWx }
  })
}
