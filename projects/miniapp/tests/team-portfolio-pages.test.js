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

function readCssRule(content, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = content.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))
  return match ? match[1].replace(/\s+/g, '') : ''
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

test('standard team editor independently matches the personal editor interaction shell', () => {
  const wxml = read('standard-edit/team-portfolio-standard-edit.wxml')
  const wxss = read('standard-edit/team-portfolio-standard-edit.wxss')
  const js = read('standard-edit/team-portfolio-standard-edit.js')

  assert.match(wxml, /class="page-shell portfolio-edit-page team-portfolio-edit-page"/)
  assert.match(wxml, /class="panel share-panel"/)
  assert.match(wxml, /class="status-pill \{\{statusTone\}\}"/)
  assert.match(wxml, /class="component-list"/)
  assert.match(wxml, /bindlongpress="handleComponentDragStart"/)
  assert.match(wxml, /bindtouchmove="handleComponentTouchMove"/)
  assert.match(wxml, /class="component-remove-pane"[\s\S]*handleRemoveComponent/)
  assert.match(wxml, /component-picker-mask \{\{componentSheetVisible \? 'visible' : ''\}\}/)
  assert.match(wxml, /component-editor-mask \{\{componentEditorVisible \? 'visible' : ''\}\}/)
  assert.equal(Array.from(wxml.matchAll(/bindcancel="handleCloseComponentEditor"/g)).length, 9)
  assert.match(wxml, /share-cover-crop-mask \{\{shareCoverCropVisible \? 'visible' : ''\}\}/)
  assert.match(wxml, /wx:if="\{\{shareCoverCropPath\}\}" class="share-cover-crop-image" src="\{\{shareCoverCropPath\}\}"/)
  assert.match(wxss, /\.share-cover-crop-panel\s*\{[^}]*overflow-y:\s*auto;/)
  assert.match(wxss, /\.share-cover-crop-image\s*\{[^}]*width:\s*100%;[^}]*height:\s*100%;/)
  assert.match(wxss, /\.sheet-actions button\s*\{[^}]*display:\s*flex;[^}]*align-items:\s*center;[^}]*justify-content:\s*center;[^}]*padding:\s*0;[^}]*line-height:\s*1;/)
  assert.match(wxml, /class="preview-action-note">请保存后预览/)
  assert.match(wxml, /wx:if="\{\{showPublishAction\}\}"/)
  assert.match(wxss, /\.component-swipe-row\.revealed \.component-row/)
  assert.match(wxss, /\.component-picker-mask\.visible/)
  assert.match(wxss, /\.bottom-actions/)
  assert.match(js, /handleOpenComponentSheet/)
  assert.match(js, /handleComponentDragStart/)
  assert.match(js, /handleComponentTouchMove/)
  assert.match(js, /handleRemoveComponent/)
  assert.doesNotMatch(js, /portfolio-standard-edit/)
})

test('standard team editor uses the personal editor core visual measurements', () => {
  const teamCss = read('standard-edit/team-portfolio-standard-edit.wxss')
  const personalCss = fs.readFileSync(path.resolve(ROOT, '../portfolios/standard-edit/portfolio-standard-edit.wxss'), 'utf8')
  const sharedSelectors = [
    '.edit-content', '.panel', '.section-title', '.section-desc', '.status-pill',
    '.field-label', '.field-limit', '.input', '.cover-preview', '.cover-empty',
    '.link-button', '.component-list', '.component-swipe-row', '.component-row',
    '.component-remove-pane', '.component-remove-button', '.component-order',
    '.component-drag-handle', '.component-title', '.component-row-arrow',
    '.component-row-arrow-icon', '.component-picker-mask', '.component-picker-panel',
    '.component-picker-grabber', '.component-picker-title', '.component-picker-count',
    '.component-option-scroll', '.component-option', '.component-option-name',
    '.component-option-desc', '.component-option-plus', '.bottom-actions',
    '.action-button', '.preview-action-stack', '.preview-action-title',
    '.preview-action-note', '.primary-button', '.secondary-button', '.publish-button'
  ]
  for (const selector of sharedSelectors) {
    assert.equal(readCssRule(teamCss, selector), readCssRule(personalCss, selector), selector)
  }
  assert.doesNotMatch(teamCss, /\binset\s*:/)
})
