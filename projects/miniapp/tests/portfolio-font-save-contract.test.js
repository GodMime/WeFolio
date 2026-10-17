const test = require('node:test')
const assert = require('node:assert/strict')
const legacy = require('./fixtures/portfolio-font-legacy-requests.json')
const { collectFontNodes } = require('../pages/portfolios/utils/portfolio-remote-font-session')
for (const pkg of ['portfolios', 'team-portfolios']) {
  const api = require(`../pages/${pkg}/utils/${pkg === 'portfolios' ? 'portfolios' : 'team-portfolios'}`)
  const gridApi = require(`../pages/${pkg}/utils/portfolio-text-grid`)
  for (const state of ['omitted', null, 'ALLURA']) {
    test(`${pkg}: 真实保存请求保留 fontId=${state} 三类文字与固定根版本`, async () => {
      const font = state === 'omitted' ? {} : { fontId: state }
      const grid = gridApi.createTextGrid(1, 1)
      Object.assign(grid.cells[0].blocks[0].runs[0], { text: 'grid' }, font)
      const config = (api.normalizePortfolioConfig || api.normalizeTeamPortfolioConfig)({ fonts: { ALLURA: { fontVersion: 'fixed-old-version' } }, components: [
        { componentKey: 'plain', componentType: 'TEXT_SECTION', config: { content: 'plain', ...font } },
        { componentKey: 'structured', componentType: 'STRUCTURED_TEXT_SECTION', config: { blocks: [{ blockKey: 'block', type: 'TITLE', content: 'title', ...font }] } },
        { componentKey: 'grid', componentType: 'TEXT_GRID', config: grid }
      ] })
      let request
      if (pkg === 'portfolios') request = api.buildDraftPayload(config, 7, 'font-contract')
      else await api.saveTeamPortfolioDraft(options => { request = options.data; return Promise.resolve({}) }, 1, config, 7, 'font-contract')
      const body = JSON.parse(JSON.stringify(request))
      assert.deepEqual(body.clientCapabilities, { portfolioRemoteFont: 1 })
      assert.deepEqual(body.config.fonts, { ALLURA: { fontVersion: 'fixed-old-version' } })
      const nodes = collectFontNodes(body.config.components)
      assert.equal(nodes.length, 3)
      nodes.forEach(node => state === 'omitted' ? assert.equal(Object.hasOwn(node, 'fontId'), false) : assert.equal(node.fontId, state))
    })
  }
  test(`${pkg}: 旧提交真实保存保留透传节点、编辑白名单省略字体且均无能力声明`, () => {
    const request = legacy.requests[pkg].untouched
    assert.equal(request.clientCapabilities, undefined)
    assert.equal(request.config.fonts, undefined)
    assert.equal(request.clientRevision, 7)
    collectFontNodes(request.config.components).forEach(node => assert.equal(node.fontId, 'ALLURA'))
    const edited = collectFontNodes(legacy.requests[pkg].edited.config.components)
    assert.equal(Object.hasOwn(edited[0], 'fontId'), false)
    assert.equal(Object.hasOwn(edited[1], 'fontId'), false)
    assert.equal(edited[2].fontId, 'ALLURA')
    assert.ok(Object.keys(legacy.sourceSha256).length > 2)
  })
}

test('旧请求黄金夹具的提交及 Java/小程序来源摘要持续可追溯', () => {
  const { execFileSync } = require('node:child_process')
  const { createHash } = require('node:crypto')
  const path = require('node:path')
  const fs = require('node:fs')
  const root = path.resolve(__dirname, '../../..')
  const baseline = '971336c90e1421453047df57dd6db330047f26df'
  const java = JSON.parse(fs.readFileSync(path.join(root, 'projects/java/wefolio-java-runtime/src/test/resources/portfolio-font-legacy-goldens.json')))
  for (const [fixture, hashes] of [[legacy, legacy.sourceSha256], [java, java.dtoSha256]]) {
    assert.equal(fixture.sourceCommit, baseline)
    assert.ok(Object.keys(hashes).length >= 4)
    for (const [file, expected] of Object.entries(hashes)) {
      const source = execFileSync('git', ['show', `${baseline}:${file}`], { cwd: root })
      assert.equal(createHash('sha256').update(source).digest('hex'), expected, file)
    }
  }
})
