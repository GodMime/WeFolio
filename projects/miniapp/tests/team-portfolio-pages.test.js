const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../pages/team-portfolios')
const PAGE_NAMES = [
  'portfolios', 'team-select/team-select', 'standard-edit/team-portfolio-standard-edit',
  'component-library/team-portfolio-component-library', 'standard-preview/team-portfolio-standard-preview',
  'contact-leads/team-contact-leads', 'visitor-portfolio/team-visitor-portfolio'
]
const NINE_COMPONENTS = ['team-profile', 'team-carousel', 'team-divider', 'team-member-portfolio-grid', 'team-member-portfolio-list', 'team-text-section', 'team-schedule-query', 'team-contact-form', 'team-qr-contact']

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

function loadPage(relativePath, requestFn, wxOverrides = {}) {
  const pagePath = path.join(ROOT, relativePath)
  const requestPath = path.resolve(ROOT, '../../utils/request.js')
  const requestCacheKey = require.resolve(requestPath)
  const oldRequest = require.cache[requestCacheKey]
  const oldPage = global.Page
  const oldWx = global.wx
  let definition
  require.cache[requestCacheKey] = { id: requestPath, filename: requestPath, loaded: true, exports: { request: requestFn } }
  global.Page = (value) => { definition = value }
  global.wx = Object.assign({ navigateTo() {}, redirectTo() {}, showToast() {}, stopPullDownRefresh() {}, getCurrentPages() { return [] } }, wxOverrides)
  delete require.cache[require.resolve(pagePath)]
  try { require(pagePath) } finally {
    global.Page = oldPage
    if (oldRequest) require.cache[requestCacheKey] = oldRequest
    else delete require.cache[requestCacheKey]
  }
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)), setData(patch) { Object.assign(this.data, patch) } })
  page.cleanup = () => { global.wx = oldWx }
  return page
}

test('team portfolio pages provide all page artifacts, custom navigation, and explicit nine component composition', () => {
  for (const name of PAGE_NAMES) {
    for (const extension of ['.js', '.json', '.wxml', '.wxss']) assert.equal(fs.existsSync(path.join(ROOT, `${name}${extension}`)), true, `${name}${extension}`)
    const json = JSON.parse(read(`${name}.json`))
    assert.equal(json.usingComponents['navigation-bar'], '/components/navigation-bar/navigation-bar')
  }
  for (const name of ['standard-edit/team-portfolio-standard-edit', 'standard-preview/team-portfolio-standard-preview', 'visitor-portfolio/team-visitor-portfolio']) {
    const json = JSON.parse(read(`${name}.json`))
    const wxml = read(`${name}.wxml`)
    for (const component of NINE_COMPONENTS) {
      assert.ok(json.usingComponents[component], `${name} registers ${component}`)
      assert.match(wxml, new RegExp(`<${component}[\\s>]`), `${name} renders ${component}`)
    }
    assert.doesNotMatch(wxml, /componentType\s*===|wx:if="\{\{.*componentType/)
  }
})

test('legacy team list is a request-free compatibility redirect', () => {
  const source = read('portfolios.js')
  assert.match(source, /UNIFIED_TEAM_PORTFOLIO_LIST_URL\s*=\s*'\/pages\/portfolios\/portfolios\?ownerType=TEAM'/)
  assert.match(source, /wx\.redirectTo\(\{\s*url:\s*UNIFIED_TEAM_PORTFOLIO_LIST_URL\s*\}\)/)
  assert.doesNotMatch(source, /require\(|\/api\//)
})

test('team page source keeps draft, preview, leads and visitor traffic on team contracts', () => {
  const edit = read('standard-edit/team-portfolio-standard-edit.js')
  const preview = read('standard-preview/team-portfolio-standard-preview.js')
  const leads = read('contact-leads/team-contact-leads.js')
  const visitor = read('visitor-portfolio/team-visitor-portfolio.js')
  assert.match(edit, /clientRevision/)
  assert.match(edit, /saveTeamPortfolioDraft/)
  assert.match(edit, /publishTeamPortfolio/)
  assert.match(preview, /previewTeamPortfolio/)
  assert.match(leads, /updateTeamContactLeadFollowStatus/)
  assert.doesNotMatch(leads, /console\.|setStorage|mask|desensiti/)
  assert.match(visitor, /resolveTeamShareCode/)
  assert.match(visitor, /openTeamVisitorSession/)
  assert.match(visitor, /QR_CODE_INTERACTED/)
  assert.match(visitor, /sourceType.*QR_CODE|QR_CODE.*sourceType/)
})

test('legacy team list keeps only a lightweight loading surface', () => {
  const wxml = read('portfolios.wxml')
  const wxss = read('portfolios.wxss')
  const js = read('portfolios.js')
  assert.match(wxml, /team-portfolio-compat-page/)
  assert.match(wxml, /团队作品集加载中/)
  assert.match(wxss, /\.compat-loading/)
  assert.doesNotMatch(wxml, /portfolio-item-card|create-actions|tabbar/)
  assert.doesNotMatch(js, /fetchTeamPortfolioList|handleCreateTap|onShareAppMessage/)
})
