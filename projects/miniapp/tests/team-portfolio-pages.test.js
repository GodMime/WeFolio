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
const STANDARD_EDITOR_RENDERED_COMPONENTS = NINE_COMPONENTS.filter((component) => !['team-divider', 'team-text-section', 'team-schedule-query', 'team-contact-form'].includes(component))
const EDITOR_COMPONENT_PATHS = [
  'team-profile/team-profile',
  'carousel/carousel',
  'divider/divider',
  'member-portfolio-grid/member-portfolio-grid',
  'member-portfolio-list/member-portfolio-list',
  'text-section/text-section',
  'schedule-query/schedule-query',
  'contact-form/contact-form',
  'qr-contact/qr-contact'
]

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
    const renderedComponents = name === 'standard-edit/team-portfolio-standard-edit'
      ? STANDARD_EDITOR_RENDERED_COMPONENTS
      : NINE_COMPONENTS
    for (const component of renderedComponents) {
      assert.ok(json.usingComponents[component], `${name} registers ${component}`)
      assert.match(wxml, new RegExp(`<${component}[\\s>]`), `${name} renders ${component}`)
    }
    assert.doesNotMatch(wxml, /componentType\s*===|wx:if="\{\{.*componentType/)
  }
})

test('team preview and visitor pages share the team carousel component', () => {
  for (const name of ['standard-preview/team-portfolio-standard-preview', 'visitor-portfolio/team-visitor-portfolio']) {
    const json = JSON.parse(read(`${name}.json`))
    const wxml = read(`${name}.wxml`)
    assert.equal(json.usingComponents['team-carousel'], '../components/carousel/carousel')
    assert.match(wxml, /<team-carousel[\s\S]*items="\{\{item\.data\.items\}\}"/)
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
  assert.equal(Array.from(wxml.matchAll(/bindcancel="handleCloseComponentEditor"/g)).length, 0)
  assert.match(wxml, /class="component-editor-cancel" catchtap="handleCloseComponentEditor">取消<\/button>/)
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

test('team page level component editor owns the shared cancel and confirm actions for team-specific selectors', () => {
  const wxml = read('standard-edit/team-portfolio-standard-edit.wxml')
  const wxss = read('standard-edit/team-portfolio-standard-edit.wxss')
  const js = read('standard-edit/team-portfolio-standard-edit.js')

  assert.equal(Array.from(wxml.matchAll(/id="active-component-editor"/g)).length, 5)
  assert.match(wxml, /class="component-editor-actions"/)
  assert.match(wxml, /class="component-editor-cancel" catchtap="handleCloseComponentEditor">取消<\/button>/)
  assert.match(wxml, /class="component-editor-confirm" catchtap="handleConfirmComponentEditor">完成<\/button>/)
  assert.doesNotMatch(wxml, /class="editor-close"/)
  assert.match(js, /handleConfirmComponentEditor\(\)/)
  assert.match(wxss, /\.component-editor-actions\s*\{[^}]*display:\s*flex;/)
})

test('team text section opens the dedicated personal-style editing sheet', () => {
  const wxml = read('standard-edit/team-portfolio-standard-edit.wxml')
  const wxss = read('standard-edit/team-portfolio-standard-edit.wxss')
  const js = read('standard-edit/team-portfolio-standard-edit.js')

  assert.match(wxml, /text-section-sheet-mask component-picker-mask \{\{textSectionSheetVisible \? 'visible' : ''\}\}/)
  assert.match(wxml, /class="component-picker-title">编辑文字说明<\/view>/)
  assert.match(wxml, /value="\{\{textSectionForm\.content\}\}"/)
  assert.match(wxml, /catchtap="handleTextSectionAlignmentTap"/)
  assert.match(wxml, /catchtap="handleCloseTextSectionSheet">取消<\/button>/)
  assert.match(wxml, /catchtap="handleConfirmTextSectionConfig">完成<\/button>/)
  assert.doesNotMatch(wxml, /<team-text-section[\s\S]*edit-mode="\{\{true\}\}"/)
  assert.match(wxss, /\.text-section-sheet-panel\s*\{[^}]*max-height:\s*76vh;/)
  assert.match(wxss, /\.text-section-sheet-textarea\s*\{[^}]*height:\s*220rpx;/)
  assert.match(js, /openTextSectionSheet/)
  assert.match(js, /handleConfirmTextSectionConfig/)
})

test('team contact form uses an isolated display-mode sheet', () => {
  const wxml = read('standard-edit/team-portfolio-standard-edit.wxml')
  const wxss = read('standard-edit/team-portfolio-standard-edit.wxss')
  const js = read('standard-edit/team-portfolio-standard-edit.js')

  assert.match(wxml, /contact-form-sheet-mask component-picker-mask \{\{contactFormSheetVisible \? 'visible' : ''\}\}/)
  assert.match(wxml, /class="component-picker-title">编辑预留联系信息<\/view>/)
  assert.match(wxml, /catchtap="handleContactFormDisplayModeTap"/)
  assert.match(wxml, /catchtap="handleCloseContactFormSheet">取消<\/button>/)
  assert.match(wxml, /catchtap="handleConfirmContactFormConfig">完成<\/button>/)
  assert.doesNotMatch(wxml, /<team-contact-form[\s\S]*edit-mode="\{\{true\}\}"/)
  assert.match(wxss, /\.contact-form-sheet-panel\s*\{[^}]*max-height:\s*76vh;/)
  assert.match(js, /openContactFormSheet/)
  assert.match(js, /handleConfirmContactFormConfig/)
  assert.doesNotMatch(js, /portfolio-standard-edit/)
})

test('team schedule and divider use isolated configuration sheets', () => {
  const wxml = read('standard-edit/team-portfolio-standard-edit.wxml')
  const js = read('standard-edit/team-portfolio-standard-edit.js')

  assert.match(wxml, /schedule-query-sheet-mask component-picker-mask \{\{scheduleQuerySheetVisible \? 'visible' : ''\}\}/)
  assert.match(wxml, /divider-sheet-mask component-picker-mask \{\{dividerSheetVisible \? 'visible' : ''\}\}/)
  assert.doesNotMatch(wxml, /<team-schedule-query[\s\S]*edit-mode="\{\{true\}\}"/)
  assert.doesNotMatch(wxml, /<team-divider[\s\S]*edit-mode="\{\{true\}\}"/)
  assert.match(js, /openScheduleQuerySheet/)
  assert.match(js, /openDividerSheet/)
})

test('team text section sheet saves text and alignment only after confirmation', () => {
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}))
  page.data.config = {
    schemaVersion: 'standard-team-v1',
    share: {},
    components: [{ componentKey: 'text-1', componentType: 'TEXT_SECTION', sortOrder: 0, enabled: true, config: { content: '原说明', alignment: 'LEFT' } }]
  }

  page.openTextSectionSheet('text-1')
  assert.equal(page.data.textSectionSheetVisible, true)
  assert.deepEqual(page.data.textSectionForm, { content: '原说明', alignment: 'LEFT' })

  page.handleTextSectionInput({ detail: { value: '团队说明' } })
  page.handleTextSectionAlignmentTap({ currentTarget: { dataset: { value: 'CENTER' } } })
  page.handleConfirmTextSectionConfig()

  assert.equal(page.data.textSectionSheetVisible, false)
  assert.deepEqual(page.data.config.components[0].config, { content: '团队说明', alignment: 'CENTER' })
  page.cleanup()
})

test('standard team portfolio picker shows disabled team profile as an auto-width added pill', () => {
  const wxml = read('standard-edit/team-portfolio-standard-edit.wxml')
  const wxss = read('standard-edit/team-portfolio-standard-edit.wxss')
  const plusRule = readCssRule(wxss, '.component-option-plus')
  const addedRule = readCssRule(wxss, '.component-option-added')

  assert.match(wxml, /data-disabled="\{\{item\.disabled\}\}"/)
  assert.match(wxml, /class="\{\{item\.disabled \? 'component-option-added' : 'component-option-plus'\}\}">\{\{item\.disabled \? '已添加' : '\+'\}\}<\/view>/)
  assert.match(plusRule, /width:44rpx/)
  assert.match(plusRule, /border-radius:50%/)
  assert.match(addedRule, /width:auto/)
  assert.match(addedRule, /min-width:104rpx/)
  assert.match(addedRule, /padding:018rpx/)
  assert.match(addedRule, /border-radius:999rpx/)
  assert.match(addedRule, /white-space:nowrap/)
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
    '.component-option-desc', '.component-option-plus', '.component-option-added', '.bottom-actions',
    '.action-button', '.preview-action-stack', '.preview-action-title',
    '.preview-action-note', '.primary-button', '.secondary-button', '.publish-button'
  ]
  for (const selector of sharedSelectors) {
    assert.equal(readCssRule(teamCss, selector), readCssRule(personalCss, selector), selector)
  }
  assert.doesNotMatch(teamCss, /\binset\s*:/)
})

test('team component edit branches retain forms while page owns editor actions', () => {
  for (const componentPath of EDITOR_COMPONENT_PATHS.filter((componentPath) => componentPath !== 'text-section/text-section')) {
    const wxml = read(`components/${componentPath}.wxml`)
    const wxss = read(`components/${componentPath}.wxss`)
    assert.match(wxml, /class="editor-form"/, `${componentPath} provides an editor form surface`)
    assert.doesNotMatch(wxml, /class="editor-actions"/, `${componentPath} delegates editor actions to the page`)
    assert.match(wxss, /\.editor-form\s*\{/, `${componentPath} styles the editor form`)
  }
})

test('team component editors expose styled choices, selectable assets and previews', () => {
  for (const componentPath of ['divider/divider', 'schedule-query/schedule-query', 'contact-form/contact-form']) {
    const wxml = read(`components/${componentPath}.wxml`)
    const wxss = read(`components/${componentPath}.wxss`)
    assert.match(wxml, /editor-choice/, `${componentPath} renders choice cards`)
    assert.match(wxss, /\.editor-choice\s*\{/, `${componentPath} styles choice cards`)
    assert.match(wxss, /\.editor-choice\.active\s*\{/, `${componentPath} styles active choices`)
  }
  for (const componentPath of ['carousel/carousel', 'member-portfolio-grid/member-portfolio-grid', 'member-portfolio-list/member-portfolio-list']) {
    const wxml = read(`components/${componentPath}.wxml`)
    const wxss = read(`components/${componentPath}.wxss`)
    assert.match(wxml, /editor-option/, `${componentPath} renders selectable content cards`)
    assert.match(wxss, /\.editor-option\s*\{/, `${componentPath} styles selectable content cards`)
    assert.match(wxss, /\.editor-option\.selected\s*\{/, `${componentPath} styles selected content cards`)
  }
  assert.match(read('components/team-profile/team-profile.wxml'), /class="editor-profile-card"/)
  assert.match(read('components/qr-contact/qr-contact.wxml'), /class="editor-qr-preview"/)
})

test('team profile display switches flow through editing, preview and visitor rendering', () => {
  const editor = read('standard-edit/team-portfolio-standard-edit.wxml')
  const profile = read('components/team-profile/team-profile.wxml')
  const preview = read('standard-preview/team-portfolio-standard-preview.wxml')
  const visitor = read('visitor-portfolio/team-visitor-portfolio.wxml')

  assert.match(editor, /visible-fields="\{\{activeComponent\.config\.visibleFields\}\}"/)
  assert.match(profile, /bindchange="handleVisibleFieldChange"/)
  assert.match(profile, /draft\.visibleFields\.avatar/)
  assert.match(profile, /draft\.visibleFields\.teamName/)
  assert.match(profile, /draft\.visibleFields\.intro/)
  assert.match(preview, /team="\{\{item\.data\.team\}\}"/)
  assert.match(visitor, /team="\{\{item\.data\.team\}\}"/)
})

test('team profile avatar stays horizontally centered in preview and visitor display mode', () => {
  const profileStyles = read('components/team-profile/team-profile.wxss')

  assert.match(profileStyles, /\.avatar\s*\{[^}]*display:\s*block;[^}]*margin:\s*0 auto;/)
})
