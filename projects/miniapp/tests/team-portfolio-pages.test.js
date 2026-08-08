const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../pages/team-portfolios')
const PAGE_NAMES = [
  'portfolios', 'team-select/team-select', 'standard-edit/team-portfolio-standard-edit',
  'standard-preview/team-portfolio-standard-preview', 'contact-leads/team-contact-leads',
  'visitor-portfolio/team-visitor-portfolio'
]
const ELEVEN_COMPONENTS = ['team-profile', 'team-carousel', 'team-video-carousel', 'team-single-work', 'team-divider', 'team-member-portfolio-grid', 'team-member-portfolio-list', 'team-text-section', 'team-schedule-query', 'team-contact-form', 'team-qr-contact']
const STANDARD_EDITOR_RENDERED_COMPONENTS = ELEVEN_COMPONENTS.filter((component) => !['team-video-carousel', 'team-divider', 'team-text-section', 'team-schedule-query', 'team-contact-form'].includes(component))
const EDITOR_COMPONENT_PATHS = [
  'team-profile/team-profile',
  'carousel/carousel',
  'single-work/single-work',
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

function flushPromises() {
  return new Promise((resolve) => {
    setImmediate(resolve)
  })
}

function loadPage(relativePath, requestFn, wxOverrides = {}, fontLoaderOverrides = null) {
  const pagePath = path.join(ROOT, relativePath)
  const requestPath = path.resolve(ROOT, '../../utils/request.js')
  const fontLoaderPath = path.resolve(ROOT, '../../utils/portfolio-font-loader.js')
  const requestCacheKey = require.resolve(requestPath)
  const fontLoaderCacheKey = require.resolve(fontLoaderPath)
  const oldRequest = require.cache[requestCacheKey]
  const oldFontLoader = require.cache[fontLoaderCacheKey]
  const oldPage = global.Page
  const oldWx = global.wx
  let definition
  require.cache[requestCacheKey] = { id: requestPath, filename: requestPath, loaded: true, exports: { request: requestFn } }
  if (fontLoaderOverrides) {
    require.cache[fontLoaderCacheKey] = {
      id: fontLoaderPath,
      filename: fontLoaderPath,
      loaded: true,
      exports: fontLoaderOverrides
    }
  }
  global.Page = (value) => { definition = value }
  global.wx = Object.assign({ navigateTo() {}, redirectTo() {}, showToast() {}, stopPullDownRefresh() {}, getCurrentPages() { return [] } }, wxOverrides)
  delete require.cache[require.resolve(pagePath)]
  try { require(pagePath) } finally {
    global.Page = oldPage
    if (oldRequest) require.cache[requestCacheKey] = oldRequest
    else delete require.cache[requestCacheKey]
    if (oldFontLoader) require.cache[fontLoaderCacheKey] = oldFontLoader
    else delete require.cache[fontLoaderCacheKey]
  }
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)), setData(patch) { Object.assign(this.data, patch) } })
  page.cleanup = () => { global.wx = oldWx }
  return page
}

test('team portfolio pages provide all page artifacts, custom navigation, and explicit eleven component composition', () => {
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
      : ELEVEN_COMPONENTS
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

test('team preview and visitor reuse the personal-style shared bottom navigation', () => {
  for (const name of [
    'standard-preview/team-portfolio-standard-preview',
    'visitor-portfolio/team-visitor-portfolio'
  ]) {
    const json = JSON.parse(read(`${name}.json`))
    const wxml = read(`${name}.wxml`)
    const wxss = read(`${name}.wxss`)

    assert.equal(
      json.usingComponents['portfolio-bottom-nav'],
      '/components/portfolio-bottom-nav/portfolio-bottom-nav'
    )
    assert.match(wxml, /<portfolio-bottom-nav/)
    assert.match(wxml, /navigation="\{\{portfolio\.bottomNav\}\}"/)
    assert.match(wxml, /active-menu-key="\{\{portfolio\.activeMenuKey\}\}"/)
    assert.match(wxml, /theme-mode="\{\{themeMode\}\}"/)
    assert.match(wxml, /bindchange="handleMenuTap"/)
    assert.doesNotMatch(wxml, /class="team-bottom-nav/)
    assert.doesNotMatch(wxss, /\.team-bottom-nav/)
  }
})

test('team visitor page binds navigation and guide back buttons to page-stack state', () => {
  const wxml = read('visitor-portfolio/team-visitor-portfolio.wxml')
  const json = JSON.parse(read('visitor-portfolio/team-visitor-portfolio.json'))

  assert.match(wxml, /<navigation-bar title="团队作品集" back="\{\{showNavigationBack && !timelineGuideVisible\}\}" color="\{\{navigationColor\}\}" background="\{\{backgroundColor\}\}" \/>/)
  assert.equal(json.usingComponents['timeline-share-guide'], '/components/timeline-share-guide/timeline-share-guide')
  assert.match(wxml, /<timeline-share-guide[^>]*back="\{\{showNavigationBack\}\}"[^>]*bindback="handleTimelineGuideBack"[^>]*bindclose="handleCloseTimelineGuide"/)
})

test('team preview and visitor pages render the personal-style brand footer', () => {
  const logoUrl = '/assets/system/folio-logo-stack-bold-small-50kb.png'
  const darkLogoUrl = '/assets/system/folio-logo-stack-bold-dark-50kb.png'
  const logoPath = path.resolve(__dirname, `..${logoUrl}`)
  const darkLogoPath = path.resolve(__dirname, `..${darkLogoUrl}`)

  for (const name of ['standard-preview/team-portfolio-standard-preview', 'visitor-portfolio/team-visitor-portfolio']) {
    const wxml = read(`${name}.wxml`)
    const wxss = read(`${name}.wxss`)
    assert.match(wxml, /class="folio-brand-footer"/)
    assert.match(wxml, new RegExp(`class="folio-brand-logo" src="${logoUrl.replace(/\./g, '\\.')}" mode="aspectFit"`))
    assert.match(wxml, new RegExp(`class="folio-brand-logo" src="${darkLogoUrl.replace(/\./g, '\\.')}" mode="aspectFit"`))
    assert.match(wxml, /class="folio-brand-name">映期Folio<\/view>/)
    assert.match(readCssRule(wxss, '.folio-brand-footer'), /padding:56rpx0calc\(160rpx\+env\(safe-area-inset-bottom\)\);/)
  }
  assert.equal(fs.existsSync(logoPath), true)
  assert.equal(fs.existsSync(darkLogoPath), true)
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

test('team portfolio selection and editor use the approved neutral surfaces', () => {
  const teamSelectCss = read('team-select/team-select.wxss')
  const editorCss = read('standard-edit/team-portfolio-standard-edit.wxss')
  const foundationCss = fs.readFileSync(path.resolve(ROOT, '../../styles/portfolio-editor-foundation.wxss'), 'utf8')
  const teamSelectCardRule = readCssRule(teamSelectCss, '.team')
  const editorPanelRule = readCssRule(foundationCss, '.pe-page-card')
  const sheetRule = readCssRule(foundationCss, '.pe-sheet-panel')

  assert.match(teamSelectCardRule, /border-radius:48rpx/)
  assert.doesNotMatch(editorCss, /\.component-panel\s*\{/)
  assert.match(editorPanelRule, /padding:28rpx/)
  assert.match(editorPanelRule, /border-radius:var\(--pe-radius-card,48rpx\)/)
  assert.match(sheetRule, /border-radius:var\(--pe-radius-sheet,56rpx\)var\(--pe-radius-sheet,56rpx\)00/)
})

test('standard team editor independently matches the personal editor interaction shell', () => {
  const wxml = read('standard-edit/team-portfolio-standard-edit.wxml')
  const wxss = read('standard-edit/team-portfolio-standard-edit.wxss')
  const foundationWxss = fs.readFileSync(path.resolve(ROOT, '../../styles/portfolio-editor-foundation.wxss'), 'utf8')
  const sheetCancelRule = readCssRule(foundationWxss, '.pe-sheet-action-cancel')
  const js = read('standard-edit/team-portfolio-standard-edit.js')
  const personalRoot = path.resolve(ROOT, '../portfolios/standard-edit')
  const personalCss = fs.readFileSync(
    path.join(personalRoot, 'portfolio-standard-edit.wxss'),
    'utf8'
  )

  assert.match(wxml, /class="page-shell portfolio-edit-page team-portfolio-edit-page"/)
  assert.match(wxml, /class="panel share-panel pe-page-card"/)
  assert.match(wxml, /class="panel page-setting-panel pe-page-card"/)
  assert.match(wxml, /<view class="field-label">背景色<\/view>/)
  assert.match(wxml, /class="background-color-current"/)
  assert.match(wxml, /class="background-color-swatch \{\{config\.style\.backgroundColor === item \? 'active' : ''\}\}"/)
  assert.match(wxml, /item === 1 \? '不开启' : item \+ ' 个'/)
  assert.match(wxml, /class="editor-menu-rename"/)
  assert.match(wxml, /value="\{\{activeMenuTitle\}\}"/)
  assert.doesNotMatch(wxml, /bindchange="handleBottomNavigationToggle"/)
  assert.doesNotMatch(wxml, /class="background-color-option/)
  assert.doesNotMatch(wxml, /class="navigation-title-list"/)
  assert.match(wxml, /class="background-color-panel pe-sheet-panel pe-sheet-size-compact/)
  assert.match(wxml, /bindtouchstart="handleBackgroundColorPadTouch"/)
  assert.match(wxml, /bindtap="handleMenuTabTap"/)
  assert.match(wxml, /class="component-move-sheet-panel pe-sheet-panel pe-sheet-size-compact/)
  assert.match(wxml, /catchtap="handleMoveTargetTap"/)
  assert.match(wxml, /class="status-pill \{\{statusTone\}\}"/)
  assert.match(wxml, /class="component-list"/)
  assert.match(wxml, /bindlongpress="handleComponentDragStart"/)
  assert.match(wxml, /bindtouchmove="handleComponentTouchMove"/)
  assert.match(wxml, /class="component-remove-pane"[\s\S]*handleRemoveComponent/)
  assert.match(wxml, /pe-sheet-mask \{\{componentSheetVisible \? 'pe-sheet-mask-visible' : ''\}\}/)
  assert.match(wxml, /component-editor-mask pe-sheet-mask \{\{componentEditorVisible \? 'pe-sheet-mask-visible' : ''\}\}/)
  assert.equal(Array.from(wxml.matchAll(/bindcancel="handleCloseComponentEditor"/g)).length, 0)
  assert.match(wxml, /class="pe-sheet-action-cancel" catchtap="handleCloseComponentEditor">取消<\/button>/)
  assert.match(wxml, /share-cover-crop-mask pe-sheet-mask \{\{shareCoverCropVisible \? 'pe-sheet-mask-visible' : ''\}\}/)
  assert.match(wxml, /wx:if="\{\{shareCoverCropPath\}\}" class="share-cover-crop-image" src="\{\{shareCoverCropPath\}\}"/)
  assert.match(wxml, /share-cover-crop-panel pe-sheet-panel pe-sheet-size-standard/)
  assert.match(wxss, /\.share-cover-crop-image\s*\{[^}]*width:\s*100%;[^}]*height:\s*100%;/)
  assert.doesNotMatch(wxss, /\.sheet-actions button\s*\{/)
  assert.match(sheetCancelRule, /display:flex/)
  assert.match(sheetCancelRule, /align-items:center/)
  assert.match(sheetCancelRule, /justify-content:center/)
  assert.match(sheetCancelRule, /padding:024rpx/)
  assert.match(wxml, /class="preview-action-note">请保存后预览/)
  assert.match(wxml, /wx:if="\{\{showPublishAction\}\}"/)
  assert.match(wxss, /\.component-swipe-row\.revealed \.component-row/)
  assert.match(wxml, /pe-sheet-mask-visible/)
  assert.match(wxml, /bottom-actions pe-page-actions/)
  assert.match(js, /handleOpenComponentSheet/)
  assert.match(js, /handleComponentDragStart/)
  assert.match(js, /handleComponentTouchMove/)
  assert.match(js, /handleRemoveComponent/)
  assert.match(js, /validateTeamPortfolioForPublish/)
  assert.match(js, /visitTeamPortfolioComponents/)
  assert.match(js, /moveTeamComponent/)
  assert.match(js, /TEAM_BACKGROUND_COLORS\s*=\s*Object\.freeze\(\['#151515', '#FFFFFF', '#F5F6F8'\]\)/)
  assert.match(js, /TEAM_BOTTOM_NAV_COUNTS\s*=\s*Object\.freeze\(\[1, 2, 3, 4\]\)/)
  assert.doesNotMatch(js, /portfolio-standard-edit/)
  for (const selector of [
    '.background-color-row',
    '.background-color-swatch',
    '.background-color-current',
    '.background-custom-button',
    '.bottom-nav-count-row',
    '.bottom-nav-count-option',
    '.bottom-nav-count-option.active',
    '.editor-menu-bar',
    '.editor-menu-item',
    '.editor-menu-item.active',
    '.editor-menu-rename',
    '.editor-menu-title-input',
    '.editor-menu-title-count',
    '.editor-menu-remove'
  ]) {
    assert.equal(readCssRule(wxss, selector), readCssRule(personalCss, selector), selector)
  }
})

test('team preview and visitor own theme styling without importing personal portfolio code', () => {
  const theme = read('styles/team-portfolio-theme.wxss')
  const preview = read('standard-preview/team-portfolio-standard-preview.wxml')
  const visitor = read('visitor-portfolio/team-visitor-portfolio.wxml')
  const previewJs = read('standard-preview/team-portfolio-standard-preview.js')
  const visitorJs = read('visitor-portfolio/team-visitor-portfolio.js')

  assert.match(theme, /\.theme-dark/)
  assert.match(theme, /--team-portfolio-text-primary/)
  assert.match(theme, /\.contact-form-panel/)
  assert.match(theme, /\.schedule-query-modal-panel/)
  assert.match(preview, /theme-mode="\{\{themeMode\}\}"/)
  assert.match(visitor, /theme-mode="\{\{themeMode\}\}"/)
  assert.match(previewJs, /clearTeamPortfolioMenuComponentState/)
  assert.match(visitorJs, /clearTeamPortfolioMenuComponentState/)
  assert.doesNotMatch(theme, /portfolios\//)
})

test('team page level component editor owns the shared cancel and confirm actions for team-specific selectors', () => {
  const wxml = read('standard-edit/team-portfolio-standard-edit.wxml')
  const wxss = read('standard-edit/team-portfolio-standard-edit.wxss')
  const foundationWxss = fs.readFileSync(path.resolve(ROOT, '../../styles/portfolio-editor-foundation.wxss'), 'utf8')
  const js = read('standard-edit/team-portfolio-standard-edit.js')

  assert.equal(Array.from(wxml.matchAll(/id="active-component-editor"/g)).length, 6)
  assert.match(wxml, /class="component-editor-actions pe-sheet-actions"/)
  assert.match(wxml, /class="pe-sheet-action-cancel" catchtap="handleCloseComponentEditor">取消<\/button>/)
  assert.match(wxml, /class="pe-sheet-action-confirm" catchtap="handleConfirmComponentEditor" disabled="\{\{activeComponentType === 'VIDEO_CAROUSEL' && teamVideoSelectedItems.length < 3\}\}">完成<\/button>/)
  assert.doesNotMatch(wxml, /class="editor-close"/)
  assert.match(js, /handleConfirmComponentEditor\(\)/)
  assert.doesNotMatch(wxss, /\.component-editor-actions\s*\{/)
  assert.match(readCssRule(foundationWxss, '.pe-sheet-actions'), /display:flex/)
})

test('horizontal member portfolio pickers use a shorter component editor sheet', () => {
  const wxml = read('standard-edit/team-portfolio-standard-edit.wxml')
  const wxss = read('standard-edit/team-portfolio-standard-edit.wxss')
  const js = read('standard-edit/team-portfolio-standard-edit.js')

  assert.match(wxml, /componentEditorLayoutType === 'MEMBER_PORTFOLIO_GRID' \|\| componentEditorLayoutType === 'MEMBER_PORTFOLIO_LIST'/)
  assert.match(js, /componentEditorLayoutType: componentType/)
  assert.match(readCssRule(wxss, '.member-portfolio-editor-compact'), /height:auto/)
  assert.match(readCssRule(wxss, '.member-portfolio-editor-compact'), /max-height:76vh/)
  const compactScrollRule = readCssRule(wxss, '.member-portfolio-editor-compact .component-editor-scroll')
  assert.match(compactScrollRule, /height:680rpx/)
  assert.match(compactScrollRule, /min-height:0/)
  assert.match(compactScrollRule, /max-height:58vh/)
  assert.match(compactScrollRule, /flex:01auto/)
})

test('team carousel editor sheet follows a bounded animated content height without blank space', () => {
  const wxml = read('standard-edit/team-portfolio-standard-edit.wxml')
  const wxss = read('standard-edit/team-portfolio-standard-edit.wxss')

  assert.match(wxml, /\{\{componentEditorLayoutType === 'CAROUSEL' \? 'carousel-editor-content-sized' : ''\}\}/)
  assert.match(wxml, /style="\{\{componentEditorLayoutType === 'CAROUSEL' \? carouselEditorPanelStyle : ''\}\}"/)
  assert.match(wxml, /class="component-editor-scroll pe-sheet-scroll"[^>]*style="\{\{componentEditorLayoutType === 'CAROUSEL' \? carouselEditorScrollStyle : ''\}\}"/)
  assert.match(wxml, /<team-carousel[^>]*bindlayoutchange="handleCarouselLayoutChange"/)

  const panelRule = readCssRule(wxss, '.carousel-editor-content-sized')
  assert.match(panelRule, /min-height:710rpx/)
  assert.match(panelRule, /max-height:calc\(100vh-176rpx-env\(safe-area-inset-top\)\)/)
  assert.match(panelRule, /transition:transform220msease-out,height220msease-out/)

  const scrollRule = readCssRule(wxss, '.carousel-editor-content-sized .component-editor-scroll')
  assert.match(scrollRule, /min-height:460rpx/)
  assert.match(scrollRule, /max-height:58vh/)
  assert.match(scrollRule, /flex:01auto/)
  assert.match(scrollRule, /transition:height220msease-out/)
})

test('team carousel keeps member source loading inside its fixed editor content area', () => {
  const wxml = read('standard-edit/team-portfolio-standard-edit.wxml')

  assert.match(
    wxml,
    /wx:if="\{\{activeComponentSource\.loadingFingerprint && activeComponentType !== 'CAROUSEL'\}\}" class="source-state"/
  )
  assert.match(
    wxml,
    /<team-carousel[^>]*loading="\{\{activeComponentSource\.loadingFingerprint \? true : false\}\}"/
  )
})

test('team text section opens the dedicated personal-style editing sheet', () => {
  const wxml = read('standard-edit/team-portfolio-standard-edit.wxml')
  const wxss = read('standard-edit/team-portfolio-standard-edit.wxss')
  const js = read('standard-edit/team-portfolio-standard-edit.js')

  assert.match(wxml, /text-section-sheet-mask pe-sheet-mask \{\{textSectionSheetVisible \? 'pe-sheet-mask-visible' : ''\}\}/)
  assert.match(wxml, /class="pe-sheet-title">编辑文字说明<\/view>/)
  assert.match(wxml, /value="\{\{textSectionForm\.content\}\}"/)
  assert.match(wxml, /wx:for="\{\{textSectionFontOptions\}\}"/)
  assert.match(wxml, /映期 Folio 字体预览 123/)
  assert.match(wxml, /当前设备不可用/)
  assert.match(wxml, /当前设备以系统字体预览/)
  assert.match(wxml, /catchtap="handleTextSectionFontTap"/)
  assert.match(wxml, /wx:for="\{\{textSectionSizeOptions\}\}"/)
  assert.match(wxml, /catchtap="handleTextSectionFontSizeTap"/)
  assert.match(wxml, /catchtap="handleTextSectionAlignmentTap"/)
  assert.ok(
    wxml.indexOf('text-section-sheet-textarea') < wxml.indexOf('textSectionFontOptions')
  )
  assert.ok(
    wxml.indexOf('textSectionFontOptions') < wxml.indexOf('textSectionSizeOptions')
  )
  assert.ok(
    wxml.indexOf('textSectionSizeOptions') < wxml.indexOf('textSectionAlignmentOptions')
  )
  assert.match(wxml, /catchtap="handleCloseTextSectionSheet">取消<\/button>/)
  assert.match(wxml, /catchtap="handleConfirmTextSectionConfig">完成<\/button>/)
  assert.doesNotMatch(wxml, /<team-text-section[\s\S]*edit-mode="\{\{true\}\}"/)
  assert.match(wxss, /^@import "\.\.\/\.\.\/\.\.\/styles\/portfolio-text-typography\.wxss";/)
  assert.match(wxml, /text-section-sheet-panel pe-sheet-panel pe-sheet-size-long/)
  assert.match(wxss, /\.text-section-sheet-textarea\s*\{[^}]*height:\s*220rpx;/)
  assert.match(js, /openTextSectionSheet/)
  assert.match(js, /loadPortfolioFontCapability/)
  assert.match(js, /handleConfirmTextSectionConfig/)
})

test('team contact form uses an isolated display-mode sheet', () => {
  const wxml = read('standard-edit/team-portfolio-standard-edit.wxml')
  const wxss = read('standard-edit/team-portfolio-standard-edit.wxss')
  const js = read('standard-edit/team-portfolio-standard-edit.js')

  assert.match(wxml, /contact-form-sheet-mask pe-sheet-mask \{\{contactFormSheetVisible \? 'pe-sheet-mask-visible' : ''\}\}/)
  assert.match(wxml, /class="pe-sheet-title">编辑预留联系信息<\/view>/)
  assert.match(wxml, /catchtap="handleContactFormDisplayModeTap"/)
  assert.match(wxml, /catchtap="handleCloseContactFormSheet">取消<\/button>/)
  assert.match(wxml, /catchtap="handleConfirmContactFormConfig">完成<\/button>/)
  assert.doesNotMatch(wxml, /<team-contact-form[\s\S]*edit-mode="\{\{true\}\}"/)
  assert.match(wxml, /contact-form-sheet-panel pe-sheet-panel pe-sheet-size-standard/)
  assert.match(js, /openContactFormSheet/)
  assert.match(js, /handleConfirmContactFormConfig/)
  assert.doesNotMatch(js, /portfolio-standard-edit/)
})

test('team schedule and divider use isolated configuration sheets', () => {
  const wxml = read('standard-edit/team-portfolio-standard-edit.wxml')
  const js = read('standard-edit/team-portfolio-standard-edit.js')

  assert.match(wxml, /schedule-query-sheet-mask pe-sheet-mask \{\{scheduleQuerySheetVisible \? 'pe-sheet-mask-visible' : ''\}\}/)
  assert.match(wxml, /divider-sheet-mask pe-sheet-mask \{\{dividerSheetVisible \? 'pe-sheet-mask-visible' : ''\}\}/)
  assert.doesNotMatch(wxml, /<team-schedule-query[\s\S]*edit-mode="\{\{true\}\}"/)
  assert.doesNotMatch(wxml, /<team-divider[\s\S]*edit-mode="\{\{true\}\}"/)
  assert.match(js, /openScheduleQuerySheet/)
  assert.match(js, /openDividerSheet/)
})

test('team text section sheet keeps legacy typography and saves all fields only after confirmation', () => {
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}))
  page.data.config = {
    schemaVersion: 'standard-team-v1',
    share: {},
    components: [{ componentKey: 'text-1', componentType: 'TEXT_SECTION', sortOrder: 0, enabled: true, config: { content: '原说明', alignment: 'LEFT', futureField: 'keep' } }]
  }

  page.openTextSectionSheet('text-1')
  assert.equal(page.data.textSectionSheetVisible, true)
  assert.deepEqual(page.data.textSectionForm, {
    content: '原说明',
    alignment: 'LEFT',
    fontFamily: 'SYSTEM',
    fontSizeRpx: 32
  })

  page.handleTextSectionInput({ detail: { value: '团队说明' } })
  page.handleTextSectionAlignmentTap({ currentTarget: { dataset: { value: 'CENTER' } } })
  page.handleTextSectionFontSizeTap({ currentTarget: { dataset: { value: 28 } } })
  page.handleCloseTextSectionSheet()

  assert.deepEqual(page.data.config.components[0].config, {
    content: '原说明',
    alignment: 'LEFT',
    futureField: 'keep'
  })

  page.openTextSectionSheet('text-1')
  page.handleTextSectionInput({ detail: { value: '团队说明' } })
  page.handleTextSectionAlignmentTap({ currentTarget: { dataset: { value: 'CENTER' } } })
  page.handleTextSectionFontSizeTap({ currentTarget: { dataset: { value: 28 } } })
  page.handleConfirmTextSectionConfig()

  assert.equal(page.data.textSectionSheetVisible, false)
  assert.deepEqual(page.data.config.components[0].config, {
    content: '团队说明',
    alignment: 'CENTER',
    fontFamily: 'SYSTEM',
    fontSizeRpx: 28,
    futureField: 'keep'
  })
  page.cleanup()
})

test('team text section preserves unavailable WeChat font and a custom integer size', () => {
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}))
  page.data.config = {
    schemaVersion: 'standard-team-v1',
    share: {},
    components: [{
      componentKey: 'text-1',
      componentType: 'TEXT_SECTION',
      sortOrder: 0,
      enabled: true,
      config: {
        content: '原说明',
        alignment: 'RIGHT',
        fontFamily: 'WECHAT_SANS_SS',
        fontSizeRpx: 30
      }
    }]
  }

  page.openTextSectionSheet('text-1')

  assert.equal(page.data.textSectionForm.fontFamily, 'WECHAT_SANS_SS')
  assert.equal(page.data.textSectionForm.fontSizeRpx, 30)
  assert.deepEqual(
    page.data.textSectionFontOptions.map((item) => item.value),
    ['SYSTEM', 'WECHAT_SANS_SS']
  )
  assert.equal(
    page.data.textSectionFontOptions.find(
      (item) => item.value === 'WECHAT_SANS_SS'
    ).available,
    false
  )
  assert.deepEqual(
    page.data.textSectionSizeOptions.find((item) => item.value === 30),
    { value: 30, label: '自定义 30rpx', custom: true }
  )

  page.handleConfirmTextSectionConfig()

  assert.equal(page.data.config.components[0].config.fontFamily, 'WECHAT_SANS_SS')
  assert.equal(page.data.config.components[0].config.fontSizeRpx, 30)
  page.cleanup()
})

test('team font capability refresh keeps the unsupported saved font selected', async () => {
  let resolveFontCapability
  const pendingCapability = new Promise((resolve) => {
    resolveFontCapability = resolve
  })
  const initialCapability = {
    apiAvailable: false,
    loadedFamilies: {
      WECHAT_SANS_SS: false
    }
  }
  const page = loadPage(
    'standard-edit/team-portfolio-standard-edit.js',
    async () => ({}),
    {},
    {
      getPortfolioFontCapability() {
        return initialCapability
      },
      isPortfolioFontAvailable(fontFamily, capability) {
        return fontFamily === 'SYSTEM'
          || capability.apiAvailable === true
            && capability.loadedFamilies[fontFamily] === true
      },
      loadPortfolioFonts() {
        return pendingCapability
      }
    }
  )
  page.data.config = {
    schemaVersion: 'standard-team-v1',
    share: {},
    components: [{
      componentKey: 'text-1',
      componentType: 'TEXT_SECTION',
      sortOrder: 0,
      enabled: true,
      config: {
        content: '团队说明',
        alignment: 'LEFT',
        fontFamily: 'WECHAT_SANS_SS',
        fontSizeRpx: 32
      }
    }]
  }

  page.openTextSectionSheet('text-1')
  page.loadPortfolioFontCapability()
  resolveFontCapability({
    apiAvailable: true,
    loadedFamilies: {
      WECHAT_SANS_SS: false
    }
  })
  await flushPromises()

  assert.equal(page.data.textSectionForm.fontFamily, 'WECHAT_SANS_SS')
  assert.equal(
    page.data.textSectionFontOptions.find(
      (item) => item.value === 'WECHAT_SANS_SS'
    ).available,
    false
  )
  page.cleanup()
})

test('new team text section uses the explicit new-component typography defaults', () => {
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async () => ({}))

  page.addComponent('TEXT_SECTION')

  const component = page.data.config.components.find(
    (item) => item.componentType === 'TEXT_SECTION'
  )
  assert.equal(component.config.fontFamily, 'SYSTEM')
  assert.equal(component.config.fontSizeRpx, 28)
  page.cleanup()
})

test('team video carousel keeps global order while members act as filters', async () => {
  const requests = []
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    requests.push(JSON.parse(JSON.stringify(options)))
    if (options.url.endsWith('/components/carousel/members')) {
      return [
        { memberUserId: 8, displayName: '甲' },
        { memberUserId: 9, displayName: '乙' }
      ]
    }
    if (options.url.includes('/components/video-carousel/members/8/works')) {
      return { page: 1, pageSize: 20, total: 1, hasMore: false, works: [{ memberUserId: 8, memberDisplayName: '甲', workId: 81, title: '甲的视频', durationMs: 65000 }] }
    }
    if (options.url.includes('/components/video-carousel/members/9/works')) {
      return { page: 1, pageSize: 20, total: 2, hasMore: false, works: [{ memberUserId: 9, workId: 91, title: '乙的视频一' }, { memberUserId: 9, workId: 92, title: '乙的视频二' }] }
    }
    return {}
  })
  page.data.portfolioId = 55
  page.updateConfig({
    schemaVersion: 'standard-team-v1',
    share: {},
    components: [{
      componentKey: 'video-1',
      componentType: 'VIDEO_CAROUSEL',
      sortOrder: 1000,
      enabled: true,
      config: { title: '团队视频', items: [], showTitle: true, showSwipeHint: true }
    }]
  })

  await page.openTeamVideoCarouselSheet('video-1')
  assert.deepEqual(requests[0], { url: '/api/mine/team-portfolios/55/components/carousel/members' })
  assert.deepEqual(requests[1], {
    url: '/api/mine/team-portfolios/55/components/video-carousel/members/8/works',
    data: { keyword: '', page: 1, pageSize: 20 }
  })
  assert.equal(page.data.teamVideoWorkOptions[0].memberDisplayName, '甲')
  assert.equal(page.data.teamVideoWorkOptions[0].durationText, '01:05')
  page.handleTeamVideoWorkTap({ currentTarget: { dataset: { workId: 81 } } })
  await page.handleTeamVideoMemberTap({ currentTarget: { dataset: { memberUserId: 9 } } })
  page.handleTeamVideoWorkTap({ currentTarget: { dataset: { workId: 91 } } })
  page.handleTeamVideoWorkTap({ currentTarget: { dataset: { workId: 92 } } })
  assert.deepEqual(page.data.teamVideoSelectedItems.map((item) => [item.memberUserId, item.workId]), [[8, 81], [9, 91], [9, 92]])
  assert.deepEqual(page.data.teamVideoMembers.map((item) => item.selectedCount), [1, 2])
  assert.deepEqual(page.data.teamVideoWorkOptions.map((item) => item.selectionOrder), [2, 3])

  page.handleTeamVideoTitleInput({ detail: { value: '一二三四五六七八九十😀' } })
  page.handleTeamVideoShowTitleChange({ detail: { value: false } })
  page.handleConfirmComponentEditor()
  assert.deepEqual(page.data.config.components[0].config, {
    title: '一二三四五六七八九十',
    items: [
      { memberUserId: 8, workId: 81 },
      { memberUserId: 9, workId: 91 },
      { memberUserId: 9, workId: 92 }
    ],
    showTitle: false,
    showSwipeHint: true
  })
  page.cleanup()
})

test('team video carousel blocks ninth selection and keeps selections after source failure', async () => {
  const toasts = []
  let rejectWorks = false
  const page = loadPage('standard-edit/team-portfolio-standard-edit.js', async (options) => {
    if (options.url.endsWith('/components/carousel/members')) return [{ memberUserId: 8, displayName: '甲' }]
    if (rejectWorks) throw new Error('视频加载失败')
    return { page: 1, pageSize: 20, total: 1, hasMore: false, works: [{ memberUserId: 8, workId: 9, title: '第九个' }] }
  }, { showToast(options) { toasts.push(options) } })
  page.data.portfolioId = 55
  page.updateConfig({
    schemaVersion: 'standard-team-v1',
    share: {},
    components: [{
      componentKey: 'video-1', componentType: 'VIDEO_CAROUSEL', sortOrder: 1000, enabled: true,
      config: { items: [1, 2, 3, 4, 5, 6, 7, 8].map((workId) => ({ memberUserId: 8, workId })) }
    }]
  })
  await page.openTeamVideoCarouselSheet('video-1')
  page.handleTeamVideoWorkTap({ currentTarget: { dataset: { workId: 9 } } })
  assert.equal(toasts.at(-1).title, '视频轮播最多选择8个视频')
  rejectWorks = true
  await page.handleTeamVideoSearchConfirm()
  assert.equal(page.data.teamVideoErrorText, '视频加载失败')
  assert.equal(page.data.teamVideoSelectedItems.length, 8)
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
  const teamWxml = read('standard-edit/team-portfolio-standard-edit.wxml')
  const personalCss = fs.readFileSync(path.resolve(ROOT, '../portfolios/standard-edit/portfolio-standard-edit.wxss'), 'utf8')
  const componentDragHandleMarkup = /<view class="component-drag-handle">\s*(?:<view class="component-drag-line"><\/view>\s*){3}<\/view>/
  const sharedSelectors = [
    '.edit-content', '.panel', '.section-title', '.section-desc', '.status-pill',
    '.field-label', '.field-limit', '.input', '.cover-preview', '.cover-empty',
    '.link-button', '.component-list', '.component-swipe-row', '.component-row',
    '.component-remove-pane', '.component-remove-button', '.component-order',
    '.component-drag-handle', '.component-drag-line', '.component-title', '.component-row-arrow',
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
  assert.match(teamWxml, componentDragHandleMarkup)
  assert.match(readCssRule(teamCss, '.component-drag-line'), /width:22rpx/)
  assert.match(readCssRule(teamCss, '.component-drag-line'), /height:4rpx/)
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

test('team menu title editor shows a real-time Unicode-aware character counter', () => {
  const editor = read('standard-edit/team-portfolio-standard-edit.wxml')

  assert.match(editor, /\{\{activeMenuTitleCount\}\}\s*\/\s*5/)
})

test('team profile avatar stays horizontally centered in preview and visitor display mode', () => {
  const profileStyles = read('components/team-profile/team-profile.wxss')

  assert.match(profileStyles, /\.avatar\s*\{[^}]*display:\s*block;[^}]*margin:\s*0 auto;/)
})

test('member portfolio components expose a default-on member name switch through editor, preview, and visitor pages', () => {
  const editor = read('standard-edit/team-portfolio-standard-edit.wxml')
  const preview = read('standard-preview/team-portfolio-standard-preview.wxml')
  const visitor = read('visitor-portfolio/team-visitor-portfolio.wxml')

  assert.equal(Array.from(editor.matchAll(/show-member-name="\{\{activeComponent\.config\.showMemberName\}\}"/g)).length, 2)
  assert.equal(Array.from(preview.matchAll(/show-member-name="\{\{item\.data\.showMemberName\}\}"/g)).length, 2)
  assert.equal(Array.from(visitor.matchAll(/show-member-name="\{\{item\.data\.showMemberName\}\}"/g)).length, 2)

  for (const componentPath of ['member-portfolio-grid/member-portfolio-grid', 'member-portfolio-list/member-portfolio-list']) {
    const wxml = read(`components/${componentPath}.wxml`)
    assert.match(wxml, /显示成员姓名/)
    assert.match(wxml, /checked="\{\{draftShowMemberName\}\}"[^>]*bindchange="handleShowMemberNameChange"/)
    assert.match(wxml, /wx:if="\{\{showMemberName && item\.memberDisplayName\}\}"/)
    assert.doesNotMatch(wxml, /display-tag|标签筛选/)
  }
})

test('team member portfolio display aligns with personal list and grid without tag filtering', () => {
  const gridWxml = read('components/member-portfolio-grid/member-portfolio-grid.wxml')
  const gridWxss = read('components/member-portfolio-grid/member-portfolio-grid.wxss')
  const listWxml = read('components/member-portfolio-list/member-portfolio-list.wxml')
  const listWxss = read('components/member-portfolio-list/member-portfolio-list.wxss')

  assert.match(gridWxml, /class="work-section-title">作品集<\/view>/)
  assert.match(listWxml, /class="work-section-title">作品集<\/view>/)
  assert.doesNotMatch(gridWxml, /作品集列表/)
  assert.doesNotMatch(listWxml, /作品集列表/)
  assert.match(listWxml, /wx:if="\{\{item\.description\}\}" class="portfolio-description"/)
  assert.match(gridWxml, /class="portfolio-cover"[^>]*mode="widthFix"/)
  assert.match(listWxml, /class="portfolio-cover"[^>]*mode="widthFix"/)

  const gridHostRule = readCssRule(gridWxss, ':host')
  const gridRootRule = readCssRule(gridWxss, '.grid-component')
  const gridDisplayRule = readCssRule(gridWxss, '.portfolio-display')
  const gridRule = readCssRule(gridWxss, '.portfolio-grid')
  const gridCardRule = readCssRule(gridWxss, '.portfolio-card')
  const gridCoverRule = readCssRule(gridWxss, '.portfolio-cover')
  const listHostRule = readCssRule(listWxss, ':host')
  const listRootRule = readCssRule(listWxss, '.list-component')
  const listDisplayRule = readCssRule(listWxss, '.portfolio-display')
  const listRule = readCssRule(listWxss, '.portfolio-list')
  const listCardRule = readCssRule(listWxss, '.portfolio-card')
  const listCoverRule = readCssRule(listWxss, '.portfolio-cover')
  assert.match(gridHostRule, /width:100%/)
  assert.match(gridRootRule, /width:100%/)
  assert.match(gridDisplayRule, /width:100%/)
  assert.match(gridRule, /width:100%/)
  assert.match(gridRule, /display:flex/)
  assert.match(gridRule, /flex-wrap:wrap/)
  assert.doesNotMatch(gridRule, /grid-template-columns/)
  assert.match(gridCardRule, /width:50%/)
  assert.match(gridCardRule, /flex:0050%/)
  assert.match(gridCoverRule, /width:100%/)
  assert.doesNotMatch(gridCoverRule, /height:/)
  assert.match(listHostRule, /width:100%/)
  assert.match(listRootRule, /width:100%/)
  assert.match(listDisplayRule, /width:100%/)
  assert.match(listRule, /width:100%/)
  assert.match(listRule, /display:flex/)
  assert.match(listRule, /flex-direction:column/)
  assert.match(listCardRule, /width:100%/)
  assert.match(listCardRule, /flex:00100%/)
  assert.match(listCoverRule, /width:100%/)
  assert.doesNotMatch(listCoverRule, /height:/)
})

test('team preview and visitor use the personal portfolio content baseline without native card buttons', () => {
  const previewWxml = read('standard-preview/team-portfolio-standard-preview.wxml')
  const previewWxss = read('standard-preview/team-portfolio-standard-preview.wxss')
  const visitorWxml = read('visitor-portfolio/team-visitor-portfolio.wxml')
  const visitorWxss = read('visitor-portfolio/team-visitor-portfolio.wxss')
  const gridWxml = read('components/member-portfolio-grid/member-portfolio-grid.wxml')
  const gridWxss = read('components/member-portfolio-grid/member-portfolio-grid.wxss')
  const listWxml = read('components/member-portfolio-list/member-portfolio-list.wxml')
  const listWxss = read('components/member-portfolio-list/member-portfolio-list.wxss')

  for (const page of [previewWxml, visitorWxml]) {
    assert.equal(Array.from(page.matchAll(/class="folio-component"/g)).length, 10)
  }

  for (const pageStyles of [previewWxss, visitorWxss]) {
    const contentRule = readCssRule(pageStyles, '.content')
    const folioRule = readCssRule(pageStyles, '.folio-component')
    assert.doesNotMatch(contentRule, /padding:/)
    assert.doesNotMatch(contentRule, /gap:/)
    assert.match(folioRule, /width:100%/)
    assert.match(folioRule, /margin-top:34rpx/)
  }

  assert.match(gridWxml, /<view[^>]*class="portfolio-card"[^>]*aria-role="button"/)
  assert.match(listWxml, /<view[^>]*class="portfolio-card"[^>]*aria-role="button"/)
  assert.doesNotMatch(gridWxml, /<button[^>]*class="portfolio-card"/)
  assert.doesNotMatch(listWxml, /<button[^>]*class="portfolio-card"/)
  assert.match(readCssRule(gridWxss, '.grid-component'), /padding:0/)
  assert.match(readCssRule(listWxss, '.list-component'), /padding:0/)
})

test('team preview shows the same side-effect-free notice as personal preview', () => {
  const teamWxml = read('standard-preview/team-portfolio-standard-preview.wxml')
  const teamWxss = read('standard-preview/team-portfolio-standard-preview.wxss')
  const personalWxml = fs.readFileSync(path.join(ROOT, '../portfolios/standard-preview/portfolio-standard-preview.wxml'), 'utf8')
  const notice = '预览模式：禁用真实提交，不统计访问，不扣积分。'

  assert.match(personalWxml, new RegExp(notice))
  assert.match(teamWxml, /class="preview-toolbar"/)
  assert.match(teamWxml, new RegExp(`<view class="preview-note">${notice}</view>`))
  assert.doesNotMatch(teamWxml, /草稿预览|正式预览/)
  assert.match(teamWxss, /\.preview-toolbar\s*\{[^}]*padding:\s*20rpx 32rpx;[^}]*border-bottom:\s*1rpx solid #e9ecef;/)
  assert.match(teamWxss, /\.preview-note\s*\{[^}]*color:\s*#868e96;[^}]*font-size:\s*22rpx;[^}]*line-height:\s*1\.4;/)
})
