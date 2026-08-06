const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const { getRegisteredPageRoutes } = require('./helpers/app-pages')

const appJson = JSON.parse(fs.readFileSync(path.join(__dirname, '../app.json'), 'utf8'))
const registeredPageRoutes = getRegisteredPageRoutes(appJson)

function read(relativePath) {
  return fs.readFileSync(path.join(__dirname, '..', relativePath), 'utf8')
}

function readRule(content, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = content.match(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`))
  return match ? match[1] : ''
}

function readRules(content, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return Array.from(content.matchAll(new RegExp(`${escapedSelector}\\s*\\{([^}]*)\\}`, 'g')))
    .map((match) => match[1])
    .join('\n')
}

function assertPageRegistered(pagePath) {
  assert.ok(registeredPageRoutes.includes(pagePath), `${pagePath} should be registered`)
}

function assertUsesNavigation(pagePath) {
  const json = JSON.parse(read(`${pagePath}.json`))
  assert.equal(json.usingComponents['navigation-bar'], '/components/navigation-bar/navigation-bar')
}

function readExisting(relativePath) {
  const absolutePath = path.join(__dirname, '..', relativePath)
  assert.equal(fs.existsSync(absolutePath), true, `${relativePath} should exist`)
  return fs.readFileSync(absolutePath, 'utf8')
}

test('app registers portfolio pages', () => {
  [
    'pages/portfolios/portfolios',
    'pages/portfolios/standard-edit/portfolio-standard-edit',
    'pages/portfolios/standard-preview/portfolio-standard-preview',
    'pages/portfolios/visitor-portfolio/visitor-portfolio',
    'pages/portfolios/visitor-schedule/visitor-schedule',
    'pages/portfolios/unavailable/portfolio-unavailable'
  ].forEach(assertPageRegistered)
})

test('portfolio pages use custom navigation bar', () => {
  [
    'pages/portfolios/portfolios',
    'pages/portfolios/standard-edit/portfolio-standard-edit',
    'pages/portfolios/standard-preview/portfolio-standard-preview',
    'pages/portfolios/visitor-portfolio/visitor-portfolio',
    'pages/portfolios/visitor-schedule/visitor-schedule',
    'pages/portfolios/unavailable/portfolio-unavailable'
  ].forEach(assertUsesNavigation)
})

test('personal and team text section editors share typography control styles', () => {
  const personalEditorWxss = read(
    'pages/portfolios/standard-edit/portfolio-standard-edit.wxss'
  )
  const teamEditorWxss = read(
    'pages/team-portfolios/standard-edit/team-portfolio-standard-edit.wxss'
  )

  assert.match(
    personalEditorWxss,
    /@import "\.\.\/\.\.\/\.\.\/styles\/portfolio-text-section-editor\.wxss";/
  )
  assert.match(
    teamEditorWxss,
    /@import "\.\.\/\.\.\/\.\.\/styles\/portfolio-text-section-editor\.wxss";/
  )

  const sharedEditorWxss = readExisting(
    'styles/portfolio-text-section-editor.wxss'
  )
  for (const selector of [
    '.text-section-font-list',
    '.text-section-font-option',
    '.text-section-font-option[aria-checked="true"] .schedule-query-mode-radio',
    '.text-section-font-option[aria-checked="true"] .text-section-alignment-radio',
    '.text-section-font-option.disabled[aria-checked="false"]',
    '.text-section-size-list',
    '.text-section-size-option'
  ]) {
    assert.notEqual(readRule(sharedEditorWxss, selector).trim(), '', selector)
  }
  assert.doesNotMatch(personalEditorWxss, /\.text-section-font-list\s*\{/)
  assert.doesNotMatch(personalEditorWxss, /\.text-section-size-list\s*\{/)
  assert.doesNotMatch(teamEditorWxss, /\.text-section-font-list\s*\{/)
  assert.doesNotMatch(teamEditorWxss, /\.text-section-size-list\s*\{/)
})

test('personal and team text section sheets keep actions reachable when controls overflow', () => {
  const editorWxmlPaths = [
    'pages/portfolios/standard-edit/portfolio-standard-edit.wxml',
    'pages/team-portfolios/standard-edit/team-portfolio-standard-edit.wxml'
  ]

  for (const editorWxmlPath of editorWxmlPaths) {
    const editorWxml = read(editorWxmlPath)
    const sheetStart = editorWxml.indexOf('text-section-sheet-mask')
    const scrollStart = editorWxml.indexOf(
      '<scroll-view class="text-section-form',
      sheetStart
    )
    const scrollEnd = editorWxml.indexOf('</scroll-view>', scrollStart)
    const confirmButton = editorWxml.indexOf(
      'catchtap="handleConfirmTextSectionConfig"',
      sheetStart
    )

    assert.ok(sheetStart >= 0, `${editorWxmlPath} should contain the text section sheet`)
    assert.ok(scrollStart > sheetStart, `${editorWxmlPath} should make the form scrollable`)
    assert.ok(scrollEnd > scrollStart, `${editorWxmlPath} should close the form scroll view`)
    assert.ok(
      confirmButton > scrollEnd,
      `${editorWxmlPath} should keep the confirm button outside the scrollable form`
    )
    assert.match(
      editorWxml.slice(scrollStart, scrollEnd),
      /<view class="text-section-form-content">/
    )
  }

  const sharedEditorWxss = readExisting('styles/portfolio-text-section-editor.wxss')
  const foundationWxss = readExisting('styles/portfolio-editor-foundation.wxss')
  const sheetPanelRule = readRule(foundationWxss, '.pe-sheet-size-long')
  const formRule = readRule(sharedEditorWxss, '.text-section-form')

  assert.match(
    sheetPanelRule,
    /height:\s*86vh;/
  )
  assert.match(formRule, /flex:\s*1 1 auto;/)
  assert.match(formRule, /height:\s*0;/)
  assert.match(formRule, /min-height:\s*0;/)
  assert.match(
    foundationWxss,
    /\.pe-sheet-actions\s*\{[^}]*flex:\s*none;/
  )
})

test('bottom portfolio tabs navigate to portfolio list page', () => {
  [
    'pages/index/index.js',
    'pages/schedule/schedule.js',
    'pages/works/works.js'
  ].forEach((pagePath) => {
    const pageJs = read(pagePath)

    assert.match(pageJs, /PORTFOLIOS_PAGE_URL\s*=\s*'\/pages\/portfolios\/portfolios'/)
    assert.match(pageJs, /label === '作品集'[\s\S]*wx\.redirectTo\(\{[\s\S]*url:\s*PORTFOLIOS_PAGE_URL/)
    assert.doesNotMatch(pageJs, /title:\s*`\$\{label\}页面接入中`[\s\S]*label === '作品集'/)
  })
})

test('maintainer portfolio pages expose expected controls', () => {
  const listWxml = read('pages/portfolios/portfolios.wxml')
  const listJson = JSON.parse(read('pages/portfolios/portfolios.json'))
  const listWxss = read('pages/portfolios/portfolios.wxss')
  const listJs = read('pages/portfolios/portfolios.js')
  const shareChannelSheetWxml = read('components/share-channel-sheet/share-channel-sheet.wxml')
  const mockListWxml = read('pages/mock/portfolios/portfolios.wxml')
  const portfolioActionButtonRule = readRule(listWxss, '.portfolio-action-button')
  const portfolioActionButtonPublishRule = readRule(listWxss, '.portfolio-action-button.publish')
  const portfolioContentRule = readRule(listWxss, '.portfolio-content')
  const portfolioListPanelRule = readRules(listWxss, '.portfolio-list-panel')
  const portfolioItemCardRule = readRule(listWxss, '.portfolio-item-card')
  const portfolioListScrollRule = readRule(listWxss, '.portfolio-list-scroll')
  const createActionsRule = readRule(listWxss, '.create-actions')
  const teamActionHintSlotRule = readRule(listWxss, '.team-action-hint-slot')
  const portfolioTitleRule = readRule(listWxss, '.portfolio-title')
  const portfolioTitleTrackRule = readRule(listWxss, '.portfolio-title-track')
  const portfolioTitleScrollingTrackRule = readRule(listWxss, '.portfolio-title.scrolling .portfolio-title-track')
  const teamPortfolioAffiliationRule = readRule(listWxss, '.team-portfolio-affiliation')
  const teamPortfolioSideRule = readRule(listWxss, '.team-portfolio-card .portfolio-side')
  const editWxml = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  const editWxss = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')
  const foundationWxss = read('styles/portfolio-editor-foundation.wxss')
  const componentEditorPanelRule = readRule(foundationWxss, '.pe-sheet-panel')
  const primaryActionRule = readRule(foundationWxss, '.pe-page-action-primary')
  const qrSourceTabsRule = readRule(editWxss, '.qr-source-tabs')
  const qrSourceTabRule = readRule(editWxss, '.qr-source-tab')
  const sheetChoiceRule = readRule(foundationWxss, '.pe-sheet-choice')
  const sheetChoiceSelectedRule = readRule(foundationWxss, '.pe-sheet-choice-selected')
  const sheetChoiceSelectedFilledRule = readRule(foundationWxss, '.pe-sheet-choice-selected-filled')
  const qrContactImageEditorRule = readRule(editWxss, '.qr-contact-image-editor')
  const qrContactImagePreviewRule = readRule(editWxss, '.qr-contact-image-preview')
  const qrContactSheetMarkup = editWxml.slice(
    editWxml.indexOf('<view class="qr-contact-sheet-mask'),
    editWxml.indexOf('<view class="schedule-query-sheet-mask')
  )
  const primaryActionHandler = listJs.slice(
    listJs.indexOf('handlePrimaryActionTap(event)'),
    listJs.indexOf('resolveShareTarget(')
  )
  const previewWxml = read('pages/portfolios/standard-preview/portfolio-standard-preview.wxml')
  const unavailableWxml = read('pages/portfolios/unavailable/portfolio-unavailable.wxml')

  assert.match(listWxml, /个人/)
  assert.match(listWxml, /团队/)
  assert.match(listWxml, /class="portfolio-list-panel/)
  assert.match(listWxml, /个人作品集/)
  assert.match(listWxml, /class="summary-pill published/)
  assert.match(listWxml, /class="summary-pill draft/)
  assert.match(listWxml, /class="portfolio-item-card/)
  assert.match(listWxml, /class="portfolio-cover/)
  assert.match(listWxml, /class="portfolio-title \{\{item\.titleScrollable \? 'scrolling' : ''\}\}"[\s\S]*class="portfolio-title-track"[\s\S]*class="portfolio-title-text"[\s\S]*\{\{item\.title\}\}[\s\S]*wx:if="\{\{item\.titleScrollable\}\}"[\s\S]*class="portfolio-title-text duplicate"/)
  assert.match(mockListWxml, /class="portfolio-title \{\{item\.titleScrollable \? 'scrolling' : ''\}\}"[\s\S]*class="portfolio-title-track"[\s\S]*class="portfolio-title-text"[\s\S]*\{\{item\.title\}\}[\s\S]*wx:if="\{\{item\.titleScrollable\}\}"[\s\S]*class="portfolio-title-text duplicate"/)
  assert.match(listWxml, /class="portfolio-side/)
  assert.match(listWxml, /class="portfolio-action-row/)
  assert.match(listWxml, /wx:if="\{\{item\.showPublishedPreview\}\}"[\s\S]*data-action="PREVIEW_PUBLISHED"[\s\S]*预览/)
  assert.match(listWxml, /wx:if="\{\{item\.showDraftPreview\}\}"[\s\S]*data-action="PREVIEW_DRAFT"[\s\S]*catchtap="handleDraftPreviewTap"[\s\S]*预览/)
  assert.match(listWxml, /class="portfolio-action-button/)
  assert.match(listWxml, /wx:if="\{\{item\.actionType === 'SHARE'\}\}"[^>]*class="portfolio-action-button share"[^>]*data-id="\{\{item\.portfolioId\}\}"[^>]*data-owner-type="USER"[^>]*catchtap="handleShareTap"[^>]*>分享<\/button>/)
  assert.doesNotMatch(listWxml, /class="portfolio-icon-button share"/)
  assert.doesNotMatch(listWxml, /class="portfolio-share-hit"/)
  assert.doesNotMatch(listWxml, /class="portfolio-share-icon"/)
  assert.match(listWxml, /class="portfolio-arrow/)
  assert.match(listWxml, /class="portfolio-swipe-row \{\{revealedPortfolioId === item\.portfolioId \? 'revealed' : ''\}\}"/)
  assert.match(listWxml, /bindtouchstart="handlePortfolioTouchStart"[\s\S]*bindtouchmove="handlePortfolioTouchMove"[\s\S]*bindtouchend="handlePortfolioTouchEnd"[\s\S]*bindtouchcancel="handlePortfolioTouchCancel"/)
  assert.match(listWxml, /class="portfolio-delete-pane"[\s\S]*class="portfolio-delete-button"[\s\S]*catchtap="handleDeletePortfolioTap"[\s\S]*删除/)
  assert.match(listWxml, /class="portfolio-swipe-row \{\{revealedTeamPortfolioId === item\.portfolioId \? 'revealed' : ''\}\}"/)
  assert.match(listWxml, /bindtouchstart="handleTeamPortfolioTouchStart"[\s\S]*bindtouchmove="handleTeamPortfolioTouchMove"[\s\S]*bindtouchend="handleTeamPortfolioTouchEnd"[\s\S]*bindtouchcancel="handleTeamPortfolioTouchCancel"/)
  assert.match(listWxml, /class="portfolio-delete-pane"[\s\S]*catchtap="handleTeamDeleteTap"[\s\S]*删除/)
  assert.match(listWxml, /class="portfolio-item-card team-portfolio-card"[^>]*bindtap="handleTeamPortfolioCardTap"/)
  assert.match(listWxml, /class="portfolio-item-card team-portfolio-card"[\s\S]*?<view class="team-portfolio-affiliation">\{\{item\.teamName\}\} · \{\{item\.currentRole\}\}<\/view>[\s\S]*?<view class="portfolio-cover">/)
  assert.doesNotMatch(listWxml, /<view class="portfolio-meta">\{\{item\.teamName\}\} · \{\{item\.currentRole\}\}<\/view>/)
  assert.match(listWxml, /catchtap="handleTeamPreviewTap"[\s\S]*>预览<\/button>/)
  assert.match(listWxml, /catchtap="handleTeamPublishTap"[\s\S]*>发布<\/button>/)
  assert.match(listWxml, /wx:elif="\{\{item\.canShare\}\}"[^>]*class="portfolio-action-button share"[^>]*data-id="\{\{item\.portfolioId\}\}"[^>]*data-owner-type="TEAM"[^>]*catchtap="handleShareTap"[^>]*>分享<\/button>/)
  assert.equal(listJson.usingComponents['share-channel-sheet'], '/components/share-channel-sheet/share-channel-sheet')
  assert.match(listWxml, /<share-channel-sheet[\s\S]*bindclose="handleCloseShareSheet"[\s\S]*bindtimeline="handleTimelineShare"/)
  assert.match(shareChannelSheetWxml, /open-type="share"/)
  assert.doesNotMatch(primaryActionHandler, /ACTION_TYPE_SHARE/)
  assert.doesNotMatch(listWxml, />编辑<\/button>/)
  assert.doesNotMatch(listWxml, />线索<\/button>/)
  assert.doesNotMatch(listWxml, /class="team-card-actions"/)
  assert.match(listWxml, /class="create-actions/)
  assert.match(listWxml, /新建标准个人作品集/)
  assert.match(listWxml, /新建高级个人作品集/)
  assert.match(listWxml, /<view class="portfolio-switch-panel">\s*<view class="portfolio-content personal-portfolio-content">[\s\S]*?<scroll-view\s+class="portfolio-list-scroll personal-portfolio-list-scroll"[\s\S]*?bindrefresherrefresh="handlePullDownRefresh"/)
  assert.match(listWxml, /<view class="portfolio-switch-panel">\s*<view class="portfolio-content team-portfolio-content">[\s\S]*?<scroll-view\s+class="portfolio-list-scroll team-portfolio-list-scroll"[\s\S]*?bindrefresherrefresh="handleTeamPullDownRefresh"/)
  assert.doesNotMatch(listWxml, /<scroll-view\s+class="portfolio-switch-panel portfolio-scroll"/)
  assert.match(listWxml, /<view wx:if="\{\{noMaintainableTeam\}\}" class="team-action-hint-slot">/)
  assert.match(listWxml, /class="tabbar"/)
  assert.match(listWxml, /item\.active/)
  assert.match(listWxss, /\.portfolios-page\s*\{[\s\S]*height:\s*100vh;[\s\S]*display:\s*flex;[\s\S]*overflow:\s*hidden;/)
  assert.match(portfolioContentRule, /height:\s*100%/)
  assert.match(portfolioContentRule, /padding:\s*20rpx 32rpx calc\(140rpx \+ env\(safe-area-inset-bottom\)\)/)
  assert.match(portfolioContentRule, /display:\s*flex/)
  assert.match(portfolioContentRule, /flex-direction:\s*column/)
  assert.match(portfolioListPanelRule, /flex:\s*1/)
  assert.match(portfolioListPanelRule, /min-height:\s*0/)
  assert.match(portfolioListPanelRule, /display:\s*flex/)
  assert.match(portfolioListPanelRule, /flex-direction:\s*column/)
  assert.match(portfolioListScrollRule, /height:\s*0/)
  assert.match(portfolioListScrollRule, /flex:\s*1/)
  assert.match(portfolioListScrollRule, /min-height:\s*0/)
  assert.match(createActionsRule, /flex:\s*none/)
  assert.match(teamActionHintSlotRule, /flex:\s*none/)
  assert.doesNotMatch(teamActionHintSlotRule, /min-height:/)
  assert.match(portfolioListPanelRule, /border-radius:\s*48rpx/)
  assert.match(portfolioItemCardRule, /border-radius:\s*48rpx/)
  assert.match(portfolioItemCardRule, /border:\s*1rpx solid #e9ecef/)
  assert.match(listWxss, /\.portfolio-swipe-row\.revealed \.portfolio-item-card\s*\{[\s\S]*transform:\s*translateX\(-140rpx\);/)
  assert.match(listWxss, /\.portfolio-delete-pane\s*\{[\s\S]*position:\s*absolute;[\s\S]*right:\s*0;[\s\S]*width:\s*128rpx;/)
  assert.match(listWxss, /\.portfolio-delete-button\s*\{[\s\S]*width:\s*128rpx;[\s\S]*height:\s*100%;[\s\S]*border-radius:\s*0;/)
  assert.match(listWxss, /\.portfolio-item-card\s*\{[\s\S]*min-height:\s*184rpx;[\s\S]*display:\s*flex;[\s\S]*align-items:\s*center;/)
  assert.match(listWxss, /\.portfolio-item-card\s*\{[\s\S]*gap:\s*20rpx;/)
  assert.match(listWxss, /\.portfolio-cover\s*\{[\s\S]*width:\s*152rpx;[\s\S]*height:\s*133rpx;/)
  assert.match(teamPortfolioAffiliationRule, /position:\s*absolute/)
  assert.match(teamPortfolioAffiliationRule, /top:\s*26rpx/)
  assert.match(teamPortfolioAffiliationRule, /right:\s*24rpx/)
  assert.match(teamPortfolioAffiliationRule, /width:\s*256rpx/)
  assert.match(teamPortfolioAffiliationRule, /overflow:\s*hidden/)
  assert.match(teamPortfolioAffiliationRule, /text-overflow:\s*ellipsis/)
  assert.match(teamPortfolioAffiliationRule, /white-space:\s*nowrap/)
  assert.match(teamPortfolioSideRule, /flex:\s*0 0 256rpx/)
  assert.match(teamPortfolioSideRule, /justify-content:\s*flex-end/)
  assert.match(portfolioTitleRule, /overflow:\s*hidden/)
  assert.match(portfolioTitleRule, /white-space:\s*nowrap/)
  assert.doesNotMatch(portfolioTitleRule, /text-overflow:\s*ellipsis/)
  assert.match(portfolioTitleTrackRule, /display:\s*inline-flex/)
  assert.match(portfolioTitleScrollingTrackRule, /animation:\s*portfolio-title-marquee/)
  assert.match(listWxss, /@keyframes portfolio-title-marquee/)
  assert.match(portfolioActionButtonRule, /height:\s*60rpx/)
  assert.match(portfolioActionButtonRule, /border-radius:\s*999rpx/)
  assert.match(portfolioActionButtonRule, /color:\s*#212529/)
  assert.match(portfolioActionButtonRule, /border:\s*0/)
  assert.match(portfolioActionButtonRule, /background:\s*#f5f6f7/)
  assert.doesNotMatch(portfolioActionButtonRule, /box-shadow:/)
  assert.match(portfolioActionButtonPublishRule, /color:\s*#ffffff/)
  assert.match(portfolioActionButtonPublishRule, /border-color:\s*#212529/)
  assert.match(portfolioActionButtonPublishRule, /background:\s*#212529/)
  assert.match(componentEditorPanelRule, /border-radius:\s*var\(--pe-radius-sheet,\s*56rpx\)\s+var\(--pe-radius-sheet,\s*56rpx\)\s+0\s+0/)
  assert.match(primaryActionRule, /background:\s*var\(--pe-color-text-primary,\s*#212529\)/i)
  assert.doesNotMatch(listWxss, /#d9a84a|#b88a44/i)
  assert.match(listWxss, /\.portfolio-action-button\.share\s*\{[\s\S]*color:\s*#212529;[\s\S]*background:\s*#f5f6f7;/)
  assert.match(listWxss, /\.portfolio-action-button\.share:active\s*\{[\s\S]*background:\s*#e9eff5;/)
  assert.doesNotMatch(listWxss, /\.portfolio-icon-button\s*\{/)
  assert.doesNotMatch(listWxss, /\.portfolio-share-icon\s*\{/)
  assert.doesNotMatch(listWxss, /\.portfolio-share-hit\s*\{/)
  assert.match(listWxss, /\.create-actions\s*\{[\s\S]*display:\s*flex;[\s\S]*gap:\s*16rpx;[\s\S]*margin:\s*20rpx 0 0;[\s\S]*padding-bottom:\s*28rpx;/)
  assert.doesNotMatch(listWxss, /\.create-actions\s*\{[\s\S]*grid-template-columns:/)
  assert.match(listWxss, /\.tabbar\s*\{[\s\S]*position:\s*fixed;[\s\S]*bottom:\s*calc\(28rpx \+ env\(safe-area-inset-bottom\)\);/)
  assert.match(listJs, /const \{ normalizeId \} = require\('\.\.\/\.\.\/utils\/id'\)/)
  assert.match(listJs, /revealedPortfolioId:\s*null/)
  assert.match(listJs, /handlePortfolioTouchStart/)
  assert.match(listJs, /handleDeletePortfolioTap/)
  assert.match(listJs, /handleDraftPreviewTap/)
  assert.match(editWxml, /分享信息/)
  assert.match(editWxml, /分享封面/)
  assert.match(editWxml, /class="cover-preview"[\s\S]*bindtap="handleChooseShareCover"/)
  assert.match(editWxml, /class="cover-preview"[\s\S]*aria-label="选择分享封面"/)
  assert.doesNotMatch(editWxml, /class="cover-preview"[\s\S]*更换分享封面/)
  assert.doesNotMatch(editWxml, /class="cover-actions"/)
  assert.doesNotMatch(editWxml, /class="cover-action-button"/)
  assert.doesNotMatch(editWxml, /bindtap="handleRemoveShareCover"/)
  assert.match(editWxml, /组件编排/)
  assert.match(editWxml, /class="component-list"/)
  assert.match(editWxml, /保存草稿/)
  assert.match(editWxml, /预览草稿/)
  assert.match(editWxml, /请保存后预览/)
  assert.match(editWxml, /<button[^>]*bindtap="handlePreview"[^>]*>[\s\S]*预览草稿[\s\S]*请保存后预览[\s\S]*<\/button>/)
  assert.match(editWxml, /<button[^>]*wx:if="\{\{showPublishAction\}\}"[^>]*bindtap="handlePublish"[^>]*>发布<\/button>/)
  assert.match(editWxml, /class="status-pill \{\{statusTone\}\}">\{\{statusText\}\}<\/view>/)
  assert.match(editWxml, /二维码联系/)
  assert.match(editWxml, /class="qr-source-tabs"[^>]*aria-role="tablist"/)
  assert.match(editWxml, /class="qr-source-tab pe-sheet-choice \{\{qrContactForm\.qrUrlSource !== 'CUSTOM' \? 'pe-sheet-choice-selected-filled' : ''\}\}"[^>]*aria-role="tab"[^>]*aria-selected="\{\{qrContactForm\.qrUrlSource !== 'CUSTOM'\}\}"/)
  assert.match(editWxml, /class="qr-source-tab pe-sheet-choice \{\{qrContactForm\.qrUrlSource === 'CUSTOM' \? 'pe-sheet-choice-selected-filled' : ''\}\}"[^>]*aria-role="tab"[^>]*aria-selected="\{\{qrContactForm\.qrUrlSource === 'CUSTOM'\}\}"/)
  assert.match(editWxml, /class="qr-source-tab-panel"[^>]*aria-role="tabpanel"/)
  assert.doesNotMatch(qrContactSheetMarkup, /qrContactForm\.title/)
  assert.doesNotMatch(qrContactSheetMarkup, /qrContactForm\.description/)
  assert.doesNotMatch(qrContactSheetMarkup, /data-field="title"/)
  assert.doesNotMatch(qrContactSheetMarkup, /data-field="description"/)
  assert.match(qrContactSheetMarkup, /class="qr-contact-image-preview custom"[^>]*catchtap="handleChooseQrContactImage"[^>]*aria-role="button"[^>]*aria-label="选择自定义二维码图片"/)
  assert.doesNotMatch(qrContactSheetMarkup, /class="profile-avatar-button"/)
  assert.match(qrSourceTabsRule, /min-height:\s*76rpx/)
  assert.match(qrSourceTabsRule, /margin:\s*8rpx 0 12rpx/)
  assert.match(qrSourceTabsRule, /padding:\s*8rpx/)
  assert.match(qrSourceTabsRule, /border:\s*1rpx solid var\(--pe-color-border,\s*#E9ECEF\)/)
  assert.match(qrSourceTabsRule, /border-radius:\s*24rpx/)
  assert.match(qrSourceTabsRule, /background:\s*var\(--pe-color-page,\s*#F5F6F7\)/)
  assert.match(qrSourceTabRule, /height:\s*60rpx/)
  assert.match(qrSourceTabRule, /font-size:\s*24rpx/)
  assert.match(sheetChoiceRule, /border-radius:\s*var\(--pe-radius-control,\s*28rpx\)/)
  assert.match(sheetChoiceSelectedFilledRule, /color:\s*var\(--pe-color-surface,\s*#FFFFFF\)/)
  assert.match(sheetChoiceSelectedFilledRule, /background:\s*var\(--pe-color-text-primary,\s*#212529\)/)
  assert.match(qrContactImageEditorRule, /justify-content:\s*center/)
  assert.match(qrContactImagePreviewRule, /width:\s*220rpx/)
  assert.match(qrContactImagePreviewRule, /height:\s*220rpx/)
  assert.match(editWxml, /componentRows\.isEditable\(item\.componentType\)[\s\S]*editable/)
  assert.match(editWxml, /schedule-query-sheet-mask/)
  assert.match(editWxml, /编辑档期查询/)
  assert.match(editWxml, /scheduleQueryDisplayModeOptions/)
  assert.match(editWxml, /aria-role="radio"/)
  assert.match(editWxml, /handleScheduleQueryDisplayModeTap/)
  assert.match(editWxml, /componentRows\.isEditable\(item\.componentType\)[\s\S]*editable/)
  assert.match(editWxml, /text-section-sheet-mask/)
  assert.match(editWxml, /编辑文字说明/)
  assert.match(editWxml, /文字说明/)
  assert.match(editWxml, /maxlength="\{\{textSectionMaxLength\}\}"/)
  assert.match(editWxml, /映期 Folio 字体预览 123/)
  assert.match(editWxml, /catchtap="handleTextSectionFontTap"/)
  assert.match(editWxml, /catchtap="handleTextSectionFontSizeTap"/)
  assert.match(editWxml, /当前设备以系统字体预览/)
  assert.match(editWxml, /textSectionAlignmentOptions/)
  assert.match(editWxml, /handleTextSectionAlignmentTap/)
  const textSectionMarkup = editWxml.slice(
    editWxml.indexOf('<view class="text-section-sheet-mask'),
    editWxml.indexOf('<view class="divider-sheet-mask')
  )
  const textAreaIndex = textSectionMarkup.indexOf('text-section-sheet-textarea')
  const fontIndex = textSectionMarkup.indexOf('text-section-font-list')
  const sizeIndex = textSectionMarkup.indexOf('text-section-size-list')
  const alignmentIndex = textSectionMarkup.indexOf('textSectionAlignmentOptions')
  assert.ok(textAreaIndex >= 0)
  assert.ok(fontIndex > textAreaIndex)
  assert.ok(sizeIndex > fontIndex)
  assert.ok(alignmentIndex > sizeIndex)
  assert.match(editWxml, /componentRows\.isEditable\(item\.componentType\)[\s\S]*editable/)
  assert.match(editWxml, /divider-sheet-mask/)
  assert.match(editWxml, /编辑分割线/)
  assert.match(editWxml, /dividerColorOptions/)
  assert.match(editWxml, /handleDividerColorTap/)
  assert.match(editWxml, /handleDividerHeightInput/)
  assert.match(editWxss, /\.schedule-query-mode-option\s*\{/)
  assert.match(editWxss, /\.schedule-query-mode-option\[aria-checked="true"\] \.schedule-query-mode-radio\s*\{/)
  assert.match(editWxss, /\.schedule-query-mode-radio\s*\{/)
  assert.match(editWxss, /\.text-section-sheet-textarea\s*\{/)
  assert.match(editWxss, /\.divider-color-option\s*\{/)
  assert.doesNotMatch(editWxml, /class="component-hints"/)
  assert.doesNotMatch(editWxml, /class="hint-chip"/)
  assert.match(previewWxml, /预览/)
  assert.match(previewWxml, /禁用真实提交/)
  assert.match(previewWxml, /<navigation-bar[^>]*back="\{\{true\}\}"/)
  assert.match(previewWxml, /color="\{\{portfolio\.themeMode === 'dark' \? '#f8f9fa' : '#212529'\}\}"/)
  assert.match(previewWxml, /background="\{\{portfolio\.style\.backgroundColor\}\}"/)
  assert.doesNotMatch(previewWxml, /返回编辑/)
  assert.doesNotMatch(previewWxml, /bindtap="handleBackToEditor"/)
  assert.doesNotMatch(previewWxml, /maintenance-mask/)
  ;[
    'CAROUSEL',
    'PROFILE',
    'WORK_GRID',
    'WORK_LIST',
    'SCHEDULE_QUERY',
    'QR_CONTACT',
    'CONTACT_FORM',
    'TEXT_SECTION',
    'DIVIDER'
  ].forEach((componentType) => {
    assert.match(previewWxml, new RegExp(`item\\.componentType === '${componentType}'`))
  })
  assert.match(unavailableWxml, /暂未开放/)
})

test('team creation picker uses the unified bottom sheet layout', () => {
  const listWxml = read('pages/portfolios/portfolios.wxml')
  const listWxss = read('pages/portfolios/portfolios.wxss')
  const teamSelectMaskRule = readRule(listWxss, '.team-select-sheet-mask')
  const teamSelectPanelRule = readRule(listWxss, '.team-select-sheet-panel')
  const teamSelectListRule = readRule(listWxss, '.team-select-sheet-list')

  assert.match(listWxml, /<root-portal wx:if="\{\{teamSelectSheetVisible\}\}">/)
  assert.match(listWxml, /class="team-select-sheet-mask"[^>]*catchtap="handleCloseTeamSelectSheet"/)
  assert.match(listWxml, /class="team-select-sheet-panel"[^>]*catchtap="noop"/)
  assert.match(listWxml, /class="team-select-sheet-grabber"/)
  assert.match(listWxml, /class="team-select-sheet-title">选择团队<\/view>/)
  assert.match(listWxml, /<scroll-view[^>]*class="team-select-sheet-list"[^>]*style="height: \{\{teamSelectSheetListHeight\}\}rpx;"[^>]*scroll-y/)
  assert.match(listWxml, /aria-role="radio"[^>]*aria-checked="\{\{selectedCreateTeamId === item\.teamId\}\}"/)
  assert.match(listWxml, /catchtap="handleCloseTeamSelectSheet"[^>]*>取消<\/button>/)
  assert.match(listWxml, /disabled="\{\{!selectedCreateTeamId \|\| creatingTeamPortfolio\}\}"[^>]*catchtap="handleConfirmTeamSelect"/)
  assert.match(teamSelectMaskRule, /position:\s*fixed/)
  assert.match(teamSelectMaskRule, /left:\s*0/)
  assert.match(teamSelectMaskRule, /right:\s*0/)
  assert.match(teamSelectMaskRule, /top:\s*0/)
  assert.match(teamSelectMaskRule, /bottom:\s*0/)
  assert.doesNotMatch(teamSelectMaskRule, /inset:/)
  assert.match(teamSelectMaskRule, /z-index:\s*90/)
  assert.match(teamSelectMaskRule, /align-items:\s*flex-end/)
  assert.match(teamSelectPanelRule, /border-radius:\s*56rpx 56rpx 0 0/)
  assert.match(teamSelectPanelRule, /env\(safe-area-inset-bottom\)/)
  assert.match(teamSelectListRule, /max-height:\s*520rpx/)
  assert.match(teamSelectListRule, /flex:\s*none/)
})

test('visitor portfolio pages expose maintenance, QR, contact and schedule surfaces', () => {
  const visitorWxml = read('pages/portfolios/visitor-portfolio/visitor-portfolio.wxml')
  const visitorJson = JSON.parse(read('pages/portfolios/visitor-portfolio/visitor-portfolio.json'))
  const previewWxml = read('pages/portfolios/standard-preview/portfolio-standard-preview.wxml')
  const previewJson = JSON.parse(read('pages/portfolios/standard-preview/portfolio-standard-preview.json'))
  const contactFormWxml = readExisting('components/portfolio-contact-form/portfolio-contact-form.wxml')
  const contactFormJs = readExisting('components/portfolio-contact-form/portfolio-contact-form.js')
  const qrContactWxml = readExisting('pages/portfolios/components/qr-contact/qr-contact.wxml')
  const textSectionWxml = readExisting('pages/portfolios/components/text-section/text-section.wxml')
  const dividerWxml = readExisting('pages/portfolios/components/divider/divider.wxml')
  const scheduleWxml = read('pages/portfolios/visitor-schedule/visitor-schedule.wxml')
  const visitorQrMarkup = visitorWxml.slice(
    visitorWxml.indexOf(`<block wx:elif="{{item.componentType === 'QR_CONTACT'}}">`),
    visitorWxml.indexOf(`<block wx:elif="{{item.componentType === 'CONTACT_FORM'}}">`)
  )
  const previewQrMarkup = previewWxml.slice(
    previewWxml.indexOf(`<block wx:elif="{{item.componentType === 'QR_CONTACT'}}">`),
    previewWxml.indexOf(`<block wx:elif="{{item.componentType === 'CONTACT_FORM'}}">`)
  )

  assert.match(visitorWxml, /UNDER MAINTENANCE/)
  assert.match(visitorWxml, /<navigation-bar[^>]*back="\{\{showNavigationBack && !timelineGuideVisible\}\}"/)
  assert.match(visitorWxml, /color="\{\{portfolio\.themeMode === 'dark' \? '#f8f9fa' : '#212529'\}\}"/)
  assert.match(visitorWxml, /background="\{\{portfolio\.style\.backgroundColor\}\}"/)
  assert.doesNotMatch(contactFormWxml, /maxlength=/)
  assert.doesNotMatch(contactFormWxml, /field-count|\/20|\/11|\/200/)
  assert.match(contactFormJs, /DEFAULT_CONTACT_FORM_TITLE\s*=\s*'预留联系信息'/)
  assert.match(contactFormWxml, /contactComponent\.contactForm\.title \|\| defaultTitle/)
  assert.match(contactFormWxml, /\{\{submitText\}\}/)
  assert.equal((contactFormWxml.match(/<picker mode="date"/g) || []).length, 2)
  assert.match(contactFormWxml, /value="\{\{contactForm\.desiredSchedule\}\}"[^>]*data-field="desiredSchedule"[^>]*bindchange="handleDateChange"/)
  assert.match(contactFormWxml, /请选择档期（选填）/)
  assert.match(contactFormJs, /handleDateChange\(event\)[\s\S]*contactinput/)
  ;[
    'CAROUSEL',
    'PROFILE',
    'WORK_GRID',
    'WORK_LIST',
    'SCHEDULE_QUERY',
    'QR_CONTACT',
    'CONTACT_FORM',
    'TEXT_SECTION',
    'DIVIDER'
  ].forEach((componentType) => {
    assert.match(visitorWxml, new RegExp(`item\\.componentType === '${componentType}'`))
  })
  assert.match(visitorWxml, /bindpreviewqr="handlePreviewQr"/)
  assert.match(visitorQrMarkup, /<portfolio-qr-contact/)
  assert.match(previewQrMarkup, /<portfolio-qr-contact/)
  assert.match(qrContactWxml, /class="qr-image"[\s\S]*bindtap="handlePreviewQr"/)
  assert.doesNotMatch(visitorQrMarkup, /qrContact\.title|qrContact\.description|component-title|section-desc/)
  assert.doesNotMatch(previewQrMarkup, /qrContact\.title|qrContact\.description|component-title|section-desc/)
  assert.equal(visitorJson.usingComponents['portfolio-schedule-query'], '/components/portfolio-schedule-query/portfolio-schedule-query')
  assert.equal(previewJson.usingComponents['portfolio-schedule-query'], '/components/portfolio-schedule-query/portfolio-schedule-query')
  assert.equal(visitorJson.usingComponents['portfolio-contact-form'], '/components/portfolio-contact-form/portfolio-contact-form')
  assert.equal(previewJson.usingComponents['portfolio-contact-form'], '/components/portfolio-contact-form/portfolio-contact-form')
  assert.match(visitorWxml, /<portfolio-schedule-query[\s\S]*share-code="\{\{shareCode\}\}"[\s\S]*visitor-key="\{\{visitorKey\}\}"[\s\S]*component-key="\{\{item\.componentKey\}\}"[\s\S]*schedule-query="\{\{item\.scheduleQuery\}\}"/)
  assert.match(previewWxml, /<portfolio-schedule-query[\s\S]*portfolio-id="\{\{portfolioId\}\}"[\s\S]*preview="\{\{true\}\}"[\s\S]*preview-scope="\{\{previewScope\}\}"[\s\S]*component-key="\{\{item\.componentKey\}\}"[\s\S]*schedule-query="\{\{item\.scheduleQuery\}\}"/)
  assert.match(visitorWxml, /<portfolio-contact-form[\s\S]*contact-component="\{\{item\}\}"[\s\S]*bindcontactinput="handleContactInput"[\s\S]*bindopenmodal="handleOpenContactFormModal"/)
  assert.match(previewWxml, /<portfolio-contact-form[\s\S]*contact-component="\{\{item\}\}"[\s\S]*submit-text="预览提交"[\s\S]*bindcontactinput="handleContactInput"[\s\S]*bindopenmodal="handleOpenContactFormModal"/)
  assert.match(visitorWxml, /<portfolio-contact-form[\s\S]*view-mode="modal"[\s\S]*modal-visible="\{\{contactFormModalVisible\}\}"[\s\S]*contact-component="\{\{activeContactFormComponent\}\}"[\s\S]*bindclosemodal="handleCloseContactFormModal"/)
  assert.match(previewWxml, /<portfolio-contact-form[\s\S]*view-mode="modal"[\s\S]*modal-visible="\{\{contactFormModalVisible\}\}"[\s\S]*contact-component="\{\{activeContactFormComponent\}\}"[\s\S]*bindclosemodal="handleCloseContactFormModal"/)
  assert.doesNotMatch(visitorWxml, /class="contact-form-mask/)
  assert.doesNotMatch(previewWxml, /class="contact-form-mask/)
  assert.match(visitorWxml, /<portfolio-text-section text-section="\{\{item\.textSection\}\}"/)
  assert.match(previewWxml, /<portfolio-text-section text-section="\{\{item\.textSection\}\}"/)
  assert.match(textSectionWxml, /class="text-section portfolio-theme-\{\{themeMode\}\} \{\{textSection\.alignmentClass\}\}"/)
  assert.match(textSectionWxml, /class="text-content \{\{textSection\.fontClass\}\}"[\s\S]*style="\{\{textSection\.fontSizeStyle\}\}"[\s\S]*space="nbsp"[\s\S]*\{\{textSection\.content\}\}<\/text>/)
  assert.match(visitorWxml, /<portfolio-divider divider="\{\{item\.divider\}\}"/)
  assert.match(previewWxml, /<portfolio-divider divider="\{\{item\.divider\}\}"/)
  assert.match(dividerWxml, /class="divider-section portfolio-theme-\{\{themeMode\}\}"[\s\S]*style="\{\{divider\.style\}\}"/)
  assert.doesNotMatch(visitorWxml, /<button class="secondary-action">档期查询<\/button>/)
  assert.doesNotMatch(previewWxml, /<button class="secondary-action">档期查询<\/button>/)
  assert.match(scheduleWxml, /档期查询/)
  assert.match(scheduleWxml, /查询/)
  assert.doesNotMatch(scheduleWxml, /联系人电话/)
  assert.doesNotMatch(scheduleWxml, /内部备注/)
})

test('visitor work list components keep tags visible and grid cards in two columns', () => {
  const components = [
    ['grid', readExisting('pages/portfolios/components/work-grid/work-grid.wxss')],
    ['list', readExisting('pages/portfolios/components/work-list/work-list.wxss')]
  ]

  components.forEach(([pageName, wxss]) => {
    const displayTagScrollRule = readRule(wxss, '.display-tag-scroll')
    const displayTagsRule = readRules(wxss, '.display-tags')

    assert.match(displayTagScrollRule, /height:\s*56rpx/, `${pageName} display tag scroll should reserve row height`)
    assert.match(displayTagScrollRule, /overflow:\s*hidden/, `${pageName} display tag scroll should clip within its row`)
    assert.match(displayTagsRule, /display:\s*flex/, `${pageName} display tags should lay out filters in one row`)
    assert.match(displayTagsRule, /align-items:\s*center/, `${pageName} display tags should center filters vertically`)
    assert.match(displayTagsRule, /height:\s*56rpx/, `${pageName} display tags should fill the reserved row`)
    assert.match(displayTagsRule, /box-sizing:\s*border-box/, `${pageName} display tags should keep padding inside the row`)
    assert.match(displayTagsRule, /gap:\s*28rpx/, `${pageName} display tags should keep stable spacing`)
    assert.match(displayTagsRule, /white-space:\s*nowrap/, `${pageName} display tags should stay on a single row`)
    const displayTagRule = readRule(wxss, '.display-tag')
    assert.match(displayTagRule, /flex:\s*0\s+0\s+auto/, `${pageName} display tag should not shrink`)
    assert.match(displayTagRule, /transition:\s*color\s+160ms\s+ease,\s*opacity\s+160ms\s+ease/, `${pageName} display tag state should animate subtly`)
    assert.match(readRule(wxss, `.${pageName === 'grid' ? 'work-grid' : 'work-list'}.display-switching`), /animation:\s*work-list-switch-in\s+180ms\s+ease-out\s+both/, `${pageName} work content should animate after tag switches`)
    assert.match(wxss, /@keyframes\s+work-list-switch-in[\s\S]*opacity:\s*0\.2;[\s\S]*transform:\s*translateY\(8rpx\);[\s\S]*opacity:\s*1;[\s\S]*transform:\s*translateY\(0\);/, `${pageName} work switch animation should fade and lift content`)
  })

  const gridWxss = components[0][1]
  const listWxss = components[1][1]
  const workGridRule = readRule(gridWxss, '.work-grid')
  const gridCardRule = readRule(gridWxss, '.grid-card')
  const workListRule = readRule(listWxss, '.work-list')

  assert.match(workGridRule, /display:\s*flex/, 'work grid should use Skyline-safe flex layout')
  assert.match(workGridRule, /flex-wrap:\s*wrap/, 'work grid should wrap into rows')
  assert.doesNotMatch(workGridRule, /display:\s*grid/, 'work grid should avoid CSS grid')
  assert.doesNotMatch(workGridRule, /grid-template-columns/, 'work grid should avoid grid columns')
  assert.match(gridCardRule, /width:\s*50%/, 'grid card should occupy half the row')
  assert.match(gridCardRule, /padding:\s*0\s+6rpx\s+18rpx/, 'grid card should create stable gutters')
  assert.match(gridCardRule, /box-sizing:\s*border-box/, 'grid card should keep gutters inside half width')
  assert.match(workListRule, /display:\s*flex/, 'work list should remain a vertical flex list')
  assert.match(workListRule, /flex-direction:\s*column/, 'work list should remain single column')
  assert.doesNotMatch(workListRule, /display:\s*grid/, 'work list should avoid CSS grid')
})

test('portfolio media keeps images transparent and placeholders on the theme surface', () => {
  const carouselRule = readRule(
    read('components/portfolio-carousel/portfolio-carousel.wxss'),
    '.portfolio-carousel'
  )
  const singleWorkWxss = read('pages/portfolios/components/single-work/single-work.wxss')
  const singleWorkImageRule = readRule(singleWorkWxss, '.single-work-image')
  const singleWorkVideoRules = Array.from(singleWorkWxss.matchAll(
    /^\.single-work-video,\s*\.single-work-video-poster\s*\{([^}]*)\}/gm
  ))
  const singleWorkVideoRule = singleWorkVideoRules.at(-1)
  const singleWorkPlaceholderRule = singleWorkWxss.match(
    /^\.single-work-media-placeholder,\s*\.single-work-repair\s*\{([^}]*)\}/m
  )
  const workGridRule = readRule(
    read('pages/portfolios/components/work-grid/work-grid.wxss'),
    '.work-cover-wrap'
  )
  const workListRule = readRule(
    read('pages/portfolios/components/work-list/work-list.wxss'),
    '.work-cover-wrap'
  )

  assert.match(singleWorkImageRule, /background:\s*transparent/, 'single work image should preserve transparent media')
  assert.ok(singleWorkVideoRule, 'single work video placeholder rule should exist')
  assert.ok(singleWorkPlaceholderRule, 'single work missing-media placeholder rule should exist')
  ;[
    ['carousel', carouselRule],
    ['single work video', singleWorkVideoRule[1]],
    ['single work missing media', singleWorkPlaceholderRule[1]],
    ['work grid', workGridRule],
    ['work list', workListRule]
  ].forEach(([name, rule]) => {
    assert.match(
      rule,
      /background:\s*var\(--portfolio-surface-muted\)/,
      `${name} placeholder should follow the active portfolio theme`
    )
  })
})

test('contact form modal uses full-screen fixed bottom sheet layout', () => {
  const componentWxss = readExisting('components/portfolio-contact-form/portfolio-contact-form.wxss')

  ;[
    ['component', componentWxss]
  ].forEach(([pageName, wxss]) => {
    const maskRule = readRule(wxss, '.contact-form-mask')
    const visibleRule = readRule(wxss, '.contact-form-mask.visible')
    const panelRule = readRule(wxss, '.contact-form-panel')
    const fieldRule = readRule(wxss, '.field')
    const textareaRule = readRule(wxss, '.textarea')
    const primaryButtonRule = readRule(wxss, '.primary-button')

    assert.match(maskRule, /position:\s*fixed/, `${pageName} contact mask should escape page flow`)
    assert.match(maskRule, /left:\s*0/, `${pageName} contact mask should pin left edge`)
    assert.match(maskRule, /right:\s*0/, `${pageName} contact mask should pin right edge`)
    assert.match(maskRule, /top:\s*0/, `${pageName} contact mask should pin top edge`)
    assert.match(maskRule, /bottom:\s*0/, `${pageName} contact mask should pin bottom edge`)
    assert.doesNotMatch(maskRule, /inset:/, `${pageName} contact mask should avoid Skyline-unstable inset shorthand`)
    assert.match(maskRule, /align-items:\s*flex-end/, `${pageName} contact mask should open as bottom sheet`)
    assert.match(maskRule, /justify-content:\s*center/, `${pageName} contact mask should center the sheet horizontally`)
    assert.match(maskRule, /padding:\s*0/, `${pageName} contact mask should attach to the viewport edge`)
    assert.match(visibleRule, /opacity:\s*1/, `${pageName} contact mask should become visible`)
    assert.match(visibleRule, /pointer-events:\s*auto/, `${pageName} contact mask should accept taps when visible`)
    assert.match(panelRule, /width:\s*100%/, `${pageName} contact panel should fill the mask content width`)
    assert.match(panelRule, /max-height:\s*82vh/, `${pageName} contact panel should stay inside the viewport`)
    assert.match(panelRule, /overflow-y:\s*auto/, `${pageName} contact panel should scroll if content grows`)
    assert.match(panelRule, /border-radius:\s*56rpx 56rpx 0 0/, `${pageName} contact panel should use the shared sheet radius`)
    assert.match(fieldRule, /min-height:\s*84rpx/, `${pageName} contact fields should meet the shared touch target`)
    assert.match(textareaRule, /border-radius:\s*40rpx/, `${pageName} contact textarea should use the shared large radius`)
    assert.match(primaryButtonRule, /border-radius:\s*999rpx/, `${pageName} contact primary action should be pill shaped`)
    assert.match(primaryButtonRule, /background:\s*#212529/, `${pageName} contact primary action should use ink`)
  })
})

test('visitor qr contact images are centered in portfolio pages', () => {
  const wxss = readExisting('pages/portfolios/components/qr-contact/qr-contact.wxss')
  const qrImageRule = readRule(wxss, '.qr-image')

  assert.match(qrImageRule, /display:\s*block/, 'QR image should not rely on inline text alignment')
  assert.match(qrImageRule, /width:\s*280rpx/, 'QR image should keep fixed scan size')
  assert.match(qrImageRule, /height:\s*280rpx/, 'QR image should keep fixed scan size')
  assert.match(qrImageRule, /margin:\s*24rpx auto 0/, 'QR image should center horizontally')
})

test('schedule query modal entry matches contact form button style', () => {
  const scheduleQueryWxml = read('components/portfolio-schedule-query/portfolio-schedule-query.wxml')
  const scheduleQueryWxss = read('components/portfolio-schedule-query/portfolio-schedule-query.wxss')
  const contactFormWxss = read('components/portfolio-contact-form/portfolio-contact-form.wxss')
  const entryStart = scheduleQueryWxml.indexOf('<view class="schedule-query-entry">')
  const entryEnd = scheduleQueryWxml.indexOf('<view class="schedule-query-modal-mask', entryStart)
  const entryMarkup = scheduleQueryWxml.slice(entryStart, entryEnd)
  const spacerRule = readRule(scheduleQueryWxss, '.schedule-query-entry-spacer')
  const openButtonRule = readRule(scheduleQueryWxss, '.schedule-query-open-button')
  const contactEntryButtonRule = readRule(contactFormWxss, '.contact-form-entry-button')
  const modalMaskRule = readRule(scheduleQueryWxss, '.schedule-query-modal-mask')
  const modalPanelRule = readRule(scheduleQueryWxss, '.schedule-query-modal-panel')
  const calendarRule = readRules(scheduleQueryWxss, '.schedule-query-calendar')
  const submitRule = readRule(scheduleQueryWxss, '.schedule-query-submit')

  assert.notEqual(entryStart, -1)
  assert.notEqual(entryEnd, -1)
  assert.doesNotMatch(entryMarkup, /component-title/)
  assert.doesNotMatch(entryMarkup, /\{\{scheduleQuery\.title \|\| '档期查询'\}\}/)
  assert.match(entryMarkup, /class="schedule-query-entry-spacer"[^>]*aria-hidden="true"/)
  assert.match(spacerRule, /height:\s*44rpx/)
  assert.match(openButtonRule, /width:\s*320rpx/)
  assert.match(contactEntryButtonRule, /width:\s*320rpx/)
  assert.match(openButtonRule, /height:\s*72rpx/)
  assert.match(contactEntryButtonRule, /height:\s*72rpx/)
  assert.match(openButtonRule, /margin:\s*24rpx auto 0/)
  assert.match(openButtonRule, /padding:\s*0/)
  assert.match(openButtonRule, /color:\s*#ffffff/)
  assert.match(openButtonRule, /font-size:\s*26rpx/)
  assert.match(openButtonRule, /line-height:\s*72rpx/)
  assert.match(contactEntryButtonRule, /line-height:\s*72rpx/)
  assert.match(openButtonRule, /border-radius:\s*999rpx/)
  assert.match(openButtonRule, /background:\s*#212529/)
  assert.doesNotMatch(openButtonRule, /linear-gradient/)
  assert.doesNotMatch(openButtonRule, /box-shadow/)
  assert.match(modalMaskRule, /padding:\s*0/)
  assert.match(modalPanelRule, /border-radius:\s*56rpx 56rpx 0 0/)
  assert.match(calendarRule, /border-radius:\s*48rpx/)
  assert.match(submitRule, /border-radius:\s*999rpx/)
  assert.match(submitRule, /background:\s*#212529/)
})

test('schedule query calendar exposes lunar meta and quick month picker', () => {
  const scheduleQueryWxml = read('components/portfolio-schedule-query/portfolio-schedule-query.wxml')
  const scheduleQueryWxss = read('components/portfolio-schedule-query/portfolio-schedule-query.wxss')
  const monthPickerRule = readRule(scheduleQueryWxss, '.schedule-query-month-picker-button')
  const weekdaysRule = readRule(scheduleQueryWxss, '.schedule-query-weekdays')
  const weekdayCellRule = readRule(scheduleQueryWxss, '.schedule-query-weekdays > view')
  const daysRule = readRule(scheduleQueryWxss, '.schedule-query-days')
  const calendarDayRule = readRule(scheduleQueryWxss, '.schedule-calendar-day')
  const selectedCalendarDayRule = readRule(scheduleQueryWxss, '.schedule-calendar-day.selected')
  const selectedDayStackRule = readRule(scheduleQueryWxss, '.schedule-calendar-day.selected .schedule-query-day-stack')
  const dayNumberRule = readRule(scheduleQueryWxss, '.schedule-query-day-number')
  const dayStackRule = readRule(scheduleQueryWxss, '.schedule-query-day-stack')
  const dayMetaRule = readRule(scheduleQueryWxss, '.schedule-query-day-meta')
  const dayDotsRule = readRule(scheduleQueryWxss, '.schedule-query-day-dots')
  const dayDotRule = readRule(scheduleQueryWxss, '.schedule-query-day-dot')
  const monthTextIndex = scheduleQueryWxml.indexOf('class="schedule-query-month-text"')
  const monthPickerIndex = scheduleQueryWxml.indexOf('class="schedule-query-month-picker"')

  assert.match(scheduleQueryWxml, /<picker[^>]*class="schedule-query-month-picker"[^>]*mode="date"[^>]*fields="month"[^>]*value="\{\{selectedMonth\}\}"[^>]*bindchange="handleMonthPickerChange"/)
  assert.match(scheduleQueryWxml, /class="schedule-query-month-picker-button"[\s\S]*切换年月/)
  assert.notEqual(monthTextIndex, -1)
  assert.notEqual(monthPickerIndex, -1)
  assert.ok(monthTextIndex < monthPickerIndex)
  assert.match(scheduleQueryWxml, /class="schedule-query-day-stack"/)
  assert.match(scheduleQueryWxml, /class="schedule-query-day-meta" wx:if="\{\{item\.metaText\}\}">\{\{item\.metaText\}\}<\/view>/)
  assert.match(monthPickerRule, /border-radius:\s*999rpx/)
  assert.match(monthPickerRule, /white-space:\s*nowrap/)
  assert.match(weekdaysRule, /display:\s*flex/)
  assert.doesNotMatch(weekdaysRule, /gap:/)
  assert.doesNotMatch(weekdaysRule, /display:\s*grid/)
  assert.doesNotMatch(weekdaysRule, /grid-template-columns/)
  assert.match(weekdayCellRule, /width:\s*calc\(14\.285714% - 8rpx\)/)
  assert.match(weekdayCellRule, /flex:\s*0 0 calc\(14\.285714% - 8rpx\)/)
  assert.match(weekdayCellRule, /margin:\s*0 4rpx/)
  assert.match(weekdayCellRule, /box-sizing:\s*border-box/)
  assert.match(daysRule, /display:\s*flex/)
  assert.match(daysRule, /flex-wrap:\s*wrap/)
  assert.doesNotMatch(daysRule, /gap:/)
  assert.doesNotMatch(daysRule, /display:\s*grid/)
  assert.doesNotMatch(daysRule, /grid-template-columns/)
  assert.match(calendarDayRule, /width:\s*calc\(14\.285714% - 8rpx\)/)
  assert.match(calendarDayRule, /flex:\s*0 0 calc\(14\.285714% - 8rpx\)/)
  assert.match(calendarDayRule, /margin:\s*4rpx/)
  assert.match(calendarDayRule, /min-height:\s*88rpx/)
  assert.match(calendarDayRule, /padding:\s*8rpx 4rpx/)
  assert.match(calendarDayRule, /border:\s*0/)
  assert.match(calendarDayRule, /border-radius:\s*28rpx/)
  assert.match(calendarDayRule, /background:\s*transparent/)
  assert.match(selectedCalendarDayRule, /background:\s*transparent/)
  assert.doesNotMatch(selectedCalendarDayRule, /border-color:/)
  assert.match(dayStackRule, /width:\s*54rpx/)
  assert.match(dayStackRule, /height:\s*54rpx/)
  assert.match(dayStackRule, /flex:\s*none/)
  assert.doesNotMatch(selectedDayStackRule, /\bwidth\s*:/)
  assert.doesNotMatch(selectedDayStackRule, /\bheight\s*:/)
  assert.match(selectedDayStackRule, /border-radius:\s*50%/)
  assert.match(selectedDayStackRule, /background:\s*#212529/)
  assert.match(selectedDayStackRule, /box-shadow:\s*0\s+0\s+0\s+9rpx\s+#212529/)
  assert.match(dayNumberRule, /font-size:\s*30rpx/)
  assert.match(dayNumberRule, /font-weight:\s*600/)
  assert.match(dayStackRule, /gap:\s*2rpx/)
  assert.match(dayMetaRule, /color:\s*#adb5bd/)
  assert.match(dayMetaRule, /font-size:\s*20rpx/)
  assert.match(dayMetaRule, /font-weight:\s*500/)
  assert.match(dayMetaRule, /text-overflow:\s*ellipsis/)
  assert.match(dayDotsRule, /height:\s*10rpx/)
  assert.match(dayDotsRule, /margin-top:\s*6rpx/)
  assert.match(dayDotRule, /width:\s*10rpx/)
  assert.match(dayDotRule, /height:\s*10rpx/)
})

test('portfolio create action buttons align to the list panel width', () => {
  const listWxss = read('pages/portfolios/portfolios.wxss')

  assert.match(listWxss, /\.create-actions\s*\{[\s\S]*width:\s*100%;[\s\S]*max-width:\s*100%;[\s\S]*box-sizing:\s*border-box;/)
  assert.match(listWxss, /\.create-action\s*\{[\s\S]*flex:\s*1 1 0;[\s\S]*width:\s*auto;[\s\S]*min-width:\s*0;[\s\S]*margin:\s*0;[\s\S]*box-sizing:\s*border-box;/)
})

test('portfolio list keeps both owner panels mounted in one animated viewport', () => {
  const listWxml = read('pages/portfolios/portfolios.wxml')
  const listWxss = read('pages/portfolios/portfolios.wxss')

  assert.match(listWxml, /class="segment-indicator \{\{ownerType === 'TEAM' \? 'team-active' : ''\}\}"/)
  assert.match(listWxml, /class="portfolio-switch-track \{\{ownerType === 'TEAM' \? 'team-active' : ''\}\}"/)
  assert.equal((listWxml.match(/class="portfolio-switch-panel/g) || []).length, 2)
  assert.doesNotMatch(listWxml, /portfolio-switch-panel[^>]*wx:if/)
  assert.match(listWxss, /transition:\s*transform 220ms cubic-bezier\(0\.22, 1, 0\.36, 1\)/)
  assert.match(listWxss, /\.portfolio-switch-track\.team-active[\s\S]*translate3d\(-50%, 0, 0\)/)
  assert.match(listWxss, /\.segment-indicator[\s\S]*transition:\s*transform 180ms/)
})

test('portfolio list cards keep action buttons from squeezing title copy', () => {
  const listWxss = read('pages/portfolios/portfolios.wxss')
  const actionButtonRule = readRule(listWxss, '.portfolio-action-button')

  assert.match(listWxss, /\.portfolio-item-card\s*\{[\s\S]*gap:\s*20rpx;/)
  assert.match(listWxss, /\.portfolio-cover\s*\{[\s\S]*width:\s*152rpx;[\s\S]*height:\s*133rpx;/)
  assert.match(listWxss, /\.portfolio-copy\s*\{[\s\S]*min-width:\s*0;[\s\S]*flex:\s*1 1 0;/)
  assert.match(listWxss, /\.portfolio-side\s*\{[\s\S]*flex:\s*0 0 auto;[\s\S]*min-width:\s*0;[\s\S]*gap:\s*12rpx;/)
  assert.match(listWxss, /\.portfolio-action-row\s*\{[\s\S]*flex:\s*0 0 auto;[\s\S]*max-width:\s*226rpx;[\s\S]*display:\s*flex;[\s\S]*align-items:\s*center;[\s\S]*gap:\s*10rpx;/)
  assert.match(actionButtonRule, /(?:^|\n)\s*width:\s*108rpx/)
  assert.match(actionButtonRule, /(?:^|\n)\s*min-width:\s*108rpx/)
  assert.match(actionButtonRule, /(?:^|\n)\s*max-width:\s*108rpx/)
  assert.match(actionButtonRule, /height:\s*60rpx/)
  assert.match(actionButtonRule, /padding:\s*0 26rpx/)
  assert.match(actionButtonRule, /flex:\s*none/)
  assert.match(actionButtonRule, /box-sizing:\s*border-box/)
  assert.doesNotMatch(listWxss, /\.portfolio-card-actions\s*\{/)
})

test('portfolio list cards show cover title update time and status as item information', () => {
  const listWxml = read('pages/portfolios/portfolios.wxml')
  const listWxss = read('pages/portfolios/portfolios.wxss')
  const listJs = read('pages/portfolios/portfolios.js')

  assert.match(listWxml, /class="portfolio-cover"/)
  assert.match(listWxml, /item\.coverUrl/)
  assert.match(listWxml, /class="portfolio-title[^"]*"[\s\S]*item\.title/)
  assert.match(listWxml, /class="portfolio-meta"[\s\S]*item\.updatedText/)
  assert.match(listWxml, /class="portfolio-badges"[\s\S]*class="status-badge \{\{item\.statusTone\}\}"[\s\S]*item\.statusText/)
  assert.match(listWxml, /class="status-badge \{\{item\.statusTone\}\}"/)
  assert.match(listWxml, /item\.statusText/)
  assert.doesNotMatch(listWxml, /item\.templateText/)
  assert.doesNotMatch(listWxml, /item\.revisionText/)
  assert.doesNotMatch(listWxml, /item\.metricText/)
  assert.doesNotMatch(listWxml, /class="metric-badge/)
  assert.match(listWxss, /\.portfolio-badges\s*\{[\s\S]*margin-top:\s*12rpx;[\s\S]*gap:\s*12rpx;[\s\S]*flex-wrap:\s*wrap;/)
  assert.match(listWxss, /\.status-badge\s*\{[\s\S]*padding:\s*0;[\s\S]*font-size:\s*21rpx;[\s\S]*font-weight:\s*500;/)
  assert.match(listWxss, /\.status-badge::before\s*\{[\s\S]*width:\s*12rpx;[\s\S]*height:\s*12rpx;[\s\S]*background:\s*currentColor;/)
  assert.match(listWxss, /\.status-badge\.published\s*\{[\s\S]*color:\s*#5c9e6e;/)
  assert.match(listWxss, /\.status-badge\.draft\s*\{[\s\S]*color:\s*#868e96;/)
  assert.match(listWxss, /\.status-badge\.muted\s*\{[\s\S]*color:\s*#b55656;/)
  assert.doesNotMatch(listJs, /templateText:/)
  assert.doesNotMatch(listJs, /revisionText:/)
  assert.doesNotMatch(listJs, /metricText:/)
  assert.doesNotMatch(listJs, /resolveTemplateText/)
  assert.doesNotMatch(listJs, /resolveMetricText/)
  assert.doesNotMatch(listJs, /草稿\s*\$\{[^}]*Revision/)
  assert.doesNotMatch(listJs, /正式\s*\$\{[^}]*Revision/)
  assert.match(listJs, /onShareAppMessage/)
  assert.match(listJs, /pages\/portfolios\/visitor-portfolio\/visitor-portfolio\?shareCode=/)
  assert.match(listJs, /imageUrl:\s*portfolio\.coverUrl/)
})

test('portfolio list delete action stays hidden at rest and centers its label when revealed', () => {
  const listWxss = read('pages/portfolios/portfolios.wxss')

  assert.match(listWxss, /\.portfolio-delete-pane\s*\{[\s\S]*transform:\s*translateX\(100%\);[\s\S]*opacity:\s*0;[\s\S]*pointer-events:\s*none;/)
  assert.match(listWxss, /\.portfolio-swipe-row\.revealed \.portfolio-delete-pane\s*\{[\s\S]*transform:\s*translateX\(0\);[\s\S]*opacity:\s*1;[\s\S]*pointer-events:\s*auto;/)
  assert.match(listWxss, /\.portfolio-delete-button\s*\{[\s\S]*display:\s*flex;[\s\S]*align-items:\s*center;[\s\S]*justify-content:\s*center;/)
})

test('portfolio editor component delete action stays hidden at rest and while dragging', () => {
  const editWxss = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')

  assert.match(editWxss, /\.component-remove-pane\s*\{[\s\S]*transform:\s*translateX\(100%\);[\s\S]*opacity:\s*0;[\s\S]*pointer-events:\s*none;/)
  assert.match(editWxss, /\.component-swipe-row\.revealed \.component-remove-pane\s*\{[\s\S]*transform:\s*translateX\(0\);[\s\S]*opacity:\s*1;[\s\S]*pointer-events:\s*auto;/)
  assert.match(editWxss, /\.component-swipe-row\.dragging \.component-remove-pane\s*\{[\s\S]*transform:\s*translateX\(100%\);[\s\S]*opacity:\s*0;[\s\S]*pointer-events:\s*none;/)
})

test('portfolio editor component rows keep order title drag handle and edit cue vertically centered', () => {
  const editWxml = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  const editWxss = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')
  const foundationWxss = read('styles/portfolio-editor-foundation.wxss')
  const componentRowRule = readRule(foundationWxss, '.pe-component-row')
  const componentTitleRule = readRule(foundationWxss, '.pe-component-title')
  const componentOrderRule = readRule(foundationWxss, '.pe-component-index')
  const componentDragHandleRule = readRule(editWxss, '.component-drag-handle')
  const componentArrowRule = readRule(editWxss, '.component-row-arrow')
  const componentArrowIconRule = readRule(editWxss, '.component-row-arrow-icon')

  assert.match(editWxml, /class="component-row pe-component-row/)
  assert.match(componentRowRule, /display:\s*flex;/)
  assert.match(componentRowRule, /align-items:\s*center;/)
  assert.match(editWxss, /\.component-copy\s*\{\s*display:\s*flex;\s*align-items:\s*center;\s*\}/)
  assert.match(componentTitleRule, /line-height:\s*1\.35/)
  assert.doesNotMatch(componentDragHandleRule, /repeating-linear-gradient/)
  assert.match(componentDragHandleRule, /transparent\s+10rpx/)
  assert.match(componentDragHandleRule, /#6f7f90\s+22rpx/)
  assert.match(componentDragHandleRule, /transparent\s+38rpx/)
  assert.match(editWxml, /class="component-row-arrow[\s\S]*class="component-row-arrow-icon"/)
  assert.doesNotMatch(editWxml, /\? '›' : ''/)
  assert.match(componentArrowRule, /font-size:\s*0/)
  assert.match(componentArrowRule, /line-height:\s*0/)
  assert.match(componentArrowIconRule, /border-right:\s*5rpx\s+solid\s+var\(--pe-color-text-secondary,\s*#868E96\)/)
  assert.match(componentArrowIconRule, /border-bottom:\s*5rpx\s+solid\s+var\(--pe-color-text-secondary,\s*#868E96\)/)
  assert.match(componentArrowIconRule, /transform:\s*rotate\(-45deg\)/)
  assert.match(componentOrderRule, /height:\s*44rpx/)
  ;[componentDragHandleRule, componentArrowRule].forEach((rule) => {
    assert.match(rule, /height:\s*48rpx/)
    assert.match(rule, /align-self:\s*center/)
  })
})

test('portfolio editor component picker keeps option list visible in Skyline', () => {
  const editWxss = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')
  const optionScrollRule = readRule(editWxss, '.component-option-scroll')

  assert.match(optionScrollRule, /height:\s*46vh/)
  assert.match(optionScrollRule, /min-height:\s*320rpx/)
  assert.match(optionScrollRule, /flex:\s*1\s+1\s+auto/)
})

test('portfolio component work picker keeps work list visible in Skyline', () => {
  const editWxml = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  const editWxss = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')
  const foundationWxss = read('styles/portfolio-editor-foundation.wxss')
  const basePanelRule = readRule(foundationWxss, '.pe-sheet-panel')
  const listPanelRule = readRule(foundationWxss, '.pe-sheet-size-long')
  const scrollRule = readRule(editWxss, '.component-work-scroll')

  assert.match(editWxml, /class="component-work-list-panel pe-sheet-panel pe-sheet-size-long/)
  assert.match(editWxml, /class="qr-contact-sheet-panel pe-sheet-panel pe-sheet-size-standard/)
  assert.match(editWxml, /class="schedule-query-sheet-panel pe-sheet-panel pe-sheet-size-compact/)
  assert.match(editWxml, /class="contact-form-sheet-panel pe-sheet-panel pe-sheet-size-standard/)
  assert.match(editWxml, /class="text-section-sheet-panel pe-sheet-panel pe-sheet-size-long/)
  assert.match(editWxml, /class="divider-sheet-panel pe-sheet-panel pe-sheet-size-compact/)
  assert.match(editWxml, /<scroll-view wx:else class="component-work-scroll"[^>]*scroll-y[^>]*type="list"/)
  assert.doesNotMatch(basePanelRule, /height:\s*calc\(76vh - env\(safe-area-inset-bottom\)\);/)
  assert.match(basePanelRule, /padding:\s*24rpx 32rpx calc\(32rpx \+ env\(safe-area-inset-bottom\)\);/)
  assert.match(listPanelRule, /height:\s*86vh;/)
  assert.match(listPanelRule, /max-height:\s*calc\(100vh - 176rpx - env\(safe-area-inset-top\)\);/)
  assert.match(scrollRule, /flex:\s*1 1 auto;/)
  assert.match(scrollRule, /height:\s*0;/)
  assert.match(scrollRule, /min-height:\s*0;/)
  assert.match(scrollRule, /max-height:\s*none;/)
})

test('single work pickers expose the next horizontal card and keep tail states scrollable', () => {
  const personalWxml = read('pages/portfolios/components/single-work-picker/single-work-picker.wxml')
  const personalWxss = read('pages/portfolios/components/single-work-picker/single-work-picker.wxss')
  const teamWxss = read('pages/team-portfolios/components/single-work/single-work.wxss')
  const personalHostRule = readRule(personalWxss, ':host')
  const personalVerticalScrollRule = readRule(personalWxss, '.single-work-picker-scroll')
  const personalScrollRule = readRule(personalWxss, '.single-work-picker-candidate-scroll')
  const personalRowRule = readRule(personalWxss, '.single-work-picker-candidate-row')
  const personalCardRule = readRule(personalWxss, '.single-work-picker-candidate')
  const personalTailRule = readRule(personalWxss, '.single-work-picker-tail')
  const teamScrollRule = readRule(teamWxss, '.editor-work-scroll')
  const teamRowRule = readRule(teamWxss, '.editor-option-row')
  const teamCardRule = readRule(teamWxss, '.editor-option')
  const teamTailRule = readRule(teamWxss, '.editor-work-tail')

  assert.match(personalWxml, /^<scroll-view class="single-work-picker-scroll" scroll-y>/)
  assert.doesNotMatch(personalWxml, /^<scroll-view class="single-work-picker-scroll"[^>]*type="list"/)
  assert.match(personalHostRule, /flex:\s*1\s+1\s+auto;/)
  assert.match(personalHostRule, /height:\s*0;/)
  assert.match(personalVerticalScrollRule, /height:\s*100%;/)
  assert.match(personalScrollRule, /height:\s*286rpx;/)
  assert.match(personalRowRule, /display:\s*flex;/)
  assert.match(personalRowRule, /width:\s*max-content;/)
  assert.match(personalRowRule, /padding-right:\s*48rpx;/)
  assert.match(personalCardRule, /width:\s*260rpx;/)
  assert.match(personalCardRule, /flex:\s*0\s+0\s+260rpx;/)
  assert.match(personalTailRule, /flex:\s*0\s+0\s+152rpx;/)

  assert.match(teamScrollRule, /height:\s*286rpx;/)
  assert.match(teamRowRule, /display:\s*flex;/)
  assert.match(teamRowRule, /width:\s*max-content;/)
  assert.match(teamRowRule, /padding-right:\s*48rpx;/)
  assert.match(teamCardRule, /width:\s*248rpx;/)
  assert.match(teamCardRule, /flex:\s*0\s+0\s+248rpx;/)
  assert.match(teamTailRule, /flex:\s*0\s+0\s+152rpx;/)
})

test('portfolio editor work filters use neutral shared choices and retain semantic dots', () => {
  const editWxml = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  const editWxss = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')
  const workFilterDotRule = readRule(editWxss, '.component-work-filter-dot')
  const tagDotRule = readRule(editWxss, '.display-group-dot')
  const foundationWxss = read('styles/portfolio-editor-foundation.wxss')
  const displayPillRule = readRule(foundationWxss, '.pe-sheet-choice')
  const displayNameRule = readRule(editWxss, '.display-group-name')
  const displayCountRule = readRule(editWxss, '.display-group-count')

  assert.match(
    editWxml,
    /class="component-work-filter-pill pe-sheet-choice \{\{[^\n]+pe-sheet-choice-selected/
  )
  assert.doesNotMatch(editWxml, /component-work-filter-pill[^>]+activeStyle/)
  assert.match(
    editWxml,
    /class="component-work-filter-dot"[\s\S]*background: \{\{[^}]*\? '#ffffff' : \(item\.color \|\| '#212529'\)\}\}/
  )
  assert.match(
    editWxml,
    /class="display-group-pill pe-sheet-choice \{\{item\.active \? 'pe-sheet-choice-selected-filled' : ''\}\}/
  )
  assert.doesNotMatch(editWxml, /display-group-pill[^>]+activeStyle/)
  assert.match(
    editWxml,
    /class="display-group-dot"[\s\S]*background: \{\{item\.active \? '#ffffff' : item\.color\}\}/
  )
  assert.match(editWxss, /\.component-work-filter-dot,\s*\.display-group-dot\s*\{/)
  assert.match(workFilterDotRule, /margin-right:\s*10rpx/)
  assert.match(tagDotRule, /width:\s*12rpx/)
  assert.match(tagDotRule, /height:\s*12rpx/)
  assert.match(tagDotRule, /border-radius:\s*50%/)
  assert.match(displayPillRule, /background:\s*var\(--pe-color-page,\s*#F5F6F7\)/)
  assert.match(displayNameRule, /color:\s*inherit/)
  assert.match(displayCountRule, /color:\s*inherit/)
})

test('portfolio profile editor sheet keeps form content visible in Skyline', () => {
  const editWxml = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  const editWxss = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')
  const panelRule = readRule(read('styles/portfolio-editor-foundation.wxss'), '.pe-sheet-size-long')
  const scrollRule = readRule(editWxss, '.profile-sheet-scroll')

  assert.match(editWxml, /class="profile-form-scroll profile-sheet-scroll pe-sheet-scroll"/)
  assert.match(panelRule, /height:\s*86vh;/)
  assert.match(panelRule, /max-height:\s*calc\(100vh - 176rpx - env\(safe-area-inset-top\)\);/)
  assert.match(scrollRule, /flex:\s*1 1 auto;/)
  assert.match(scrollRule, /height:\s*0;/)
  assert.match(scrollRule, /min-height:\s*0;/)
  assert.match(scrollRule, /max-height:\s*none;/)
})

test('portfolio profile editor reuses structured basic profile tag interaction', () => {
  const editWxml = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  const editWxss = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')

  assert.doesNotMatch(editWxml, /profileForm\.tagsText/)
  assert.doesNotMatch(editWxml, /data-field="tagsText"/)
  assert.match(editWxml, /wx:for="\{\{profileForm\.tags\}\}"/)
  assert.match(editWxml, /class="profile-tag-pill"[\s\S]*style="\{\{item\.style\}\}"/)
  assert.match(editWxml, /class="profile-tag-dot"[\s\S]*style="\{\{item\.dotStyle\}\}"/)
  assert.match(editWxml, /catchtap="handleRemoveProfileTag"/)
  assert.match(editWxml, /catchtap="handleOpenProfileTagDialog"/)
  assert.match(editWxml, /wx:for="\{\{profileTagColorOptions\}\}"/)
  assert.match(editWxml, /catchtap="handleAddProfileTag"/)
  assert.match(editWxss, /\.profile-tag-pill-row\s*\{[\s\S]*flex-wrap:\s*wrap;/)
  assert.match(editWxml, /class="profile-tag-dialog pe-sheet-mask/)
  assert.match(read('styles/portfolio-editor-foundation.wxss'), /\.pe-sheet-mask\s*\{[\s\S]*position:\s*fixed;/)
})

test('standard personal portfolio editor renders status as a dot label', () => {
  const editWxml = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  const editWxss = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')
  const statusRule = readRule(editWxss, '.status-pill')
  const statusDotRule = readRule(editWxss, '.status-pill::before')

  assert.match(editWxml, /class="status-pill \{\{statusTone\}\}"/)
  assert.match(statusRule, /gap:\s*8rpx;/)
  assert.match(statusRule, /color:\s*var\(--pe-color-text-secondary,\s*#868E96\);/)
  assert.match(statusRule, /font-size:\s*22rpx;/)
  assert.match(statusRule, /font-weight:\s*500;/)
  assert.doesNotMatch(statusRule, /^\s*(?:min-width|height|border|border-radius|background)\s*:/m)
  assert.match(statusDotRule, /width:\s*12rpx;/)
  assert.match(statusDotRule, /height:\s*12rpx;/)
  assert.match(statusDotRule, /border-radius:\s*50%;/)
  assert.match(statusDotRule, /background:\s*currentColor;/)
})

test('standard personal portfolio editor follows shared maintainer layout', () => {
  const editWxml = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  const editWxss = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')
  const foundationWxss = read('styles/portfolio-editor-foundation.wxss')
  const editJs = read('pages/portfolios/standard-edit/portfolio-standard-edit.js')
  const pageContentRule = readRule(foundationWxss, '.pe-page-content')
  const pageFieldRule = readRule(foundationWxss, '.pe-page-field')
  const componentRowRule = readRule(foundationWxss, '.pe-component-row')
  const sheetChoiceSelectedRule = readRule(foundationWxss, '.pe-sheet-choice-selected')

  assert.match(editWxml, /class="edit-content pe-page-content"/)
  assert.match(editWxml, /class="panel share-panel pe-page-card"/)
  assert.match(editWxml, /class="panel component-panel pe-page-card"/)
  assert.match(editWxml, /class="section-desc pe-page-card-description"/)
  assert.match(editWxml, /class="field-heading"/)
  assert.match(editWxml, /class="field-heading"[\s\S]*作品集标题[\s\S]*\{\{shareFieldCounters\.title\}\}/)
  assert.doesNotMatch(editWxml, /分享简介/)
  assert.doesNotMatch(editWxml, /shareFieldCounters\.intro/)
  assert.match(editWxml, /data-path="share\.title"[\s\S]*maxlength="50"/)
  assert.match(editWxml, /class="component-work-search"/)
  assert.match(editWxml, /placeholder="搜索作品名称"/)
  assert.match(editWxml, /bindinput="handleComponentWorkKeywordInput"/)
  assert.match(editWxml, /bindconfirm="handleComponentWorkSearchConfirm"/)
  assert.match(editWxml, /class="component-work-search-clear"[\s\S]*catchtap="handleClearComponentWorkSearch"/)
  assert.match(editWxml, /wx:for="\{\{componentWorkFilterTags\}\}"/)
  assert.match(editWxml, /catchtap="handleComponentWorkTagTap"/)
  assert.match(editWxml, /bindscrolltolower="handleComponentWorkScrollToLower"/)
  assert.match(editWxml, /componentWorkLoadingMore/)
  assert.match(editJs, /componentWorkKeyword/)
  assert.match(editJs, /componentWorkFilterTags/)
  assert.match(editJs, /handleComponentWorkScrollToLower/)
  assert.match(editWxss, /\.component-work-search\s*\{/)
  assert.match(editWxss, /\.component-work-filter-scroll\s*\{/)
  assert.doesNotMatch(editWxss, /\.component-work-filter-pill\.active\s*\{/)
  assert.match(editWxss, /\.component-work-footer\s*\{/)
  assert.doesNotMatch(editWxml, /data-path="share\.intro"/)
  assert.doesNotMatch(editWxml, /class="input textarea"/)
  assert.match(editWxml, /class="component-list"/)
  assert.match(editWxml, /class="component-swipe-row \{\{revealedComponentKey === item\.componentKey \? 'revealed' : ''\}\} \{\{draggingIndex === index \? 'dragging' : ''\}\}"/)
  assert.match(editWxml, /class="component-order pe-component-index"/)
  assert.doesNotMatch(editWxml, /class="component-meta"/)
  assert.doesNotMatch(editWxml, /\{\{item\.componentType\}\} · \{\{item\.componentKey\}\}/)
  assert.match(editWxml, /class="component-drag-handle"/)
  assert.match(editWxml, /<wxs module="componentRows" src="\.\/component-rows\.wxs"><\/wxs>/)
  assert.match(editWxml, /componentRows\.isEditable\(item\.componentType\)/)
  assert.match(editWxml, /componentRows\.resolveAriaLabel\(item\.componentType,\s*item\.name\)/)
  assert.doesNotMatch(editWxml, /item\.componentType === 'CAROUSEL' \|\| item\.componentType === 'PROFILE' \|\| item\.componentType === 'QR_CONTACT'/)
  assert.match(editWxml, /class="component-row-arrow \{\{componentRows\.isEditable\(item\.componentType\) \? '' : 'placeholder'\}\}"/)
  assert.doesNotMatch(editWxml, /wx:if="\{\{item\.componentType === 'CAROUSEL'\}\}" class="component-row-arrow"/)
  assert.match(editWxml, /class="profile-sheet-mask pe-sheet-mask \{\{profileSheetVisible \? 'pe-sheet-mask-visible' : ''\}\}"/)
  assert.match(editWxml, /catchtap="handleConfirmProfileSheet"/)
  assert.match(editWxml, /bindchange="handleProfileVisibleFieldChange"/)
  assert.match(editWxml, />从基础资料刷新</)
  assert.doesNotMatch(editWxml, /例如/)
  assert.doesNotMatch(editWxml, /头像地址/)
  assert.doesNotMatch(editWxml, /data-field="avatarUrl"/)
  assert.doesNotMatch(editWxml, /头像图片 URL/)
  assert.doesNotMatch(editWxml, /微信二维码地址/)
  assert.doesNotMatch(editWxml, /data-field="wechatQrUrl"/)
  assert.doesNotMatch(editWxml, /二维码图片 URL/)
  assert.doesNotMatch(editWxml, /profileFieldCounters\.wechatQrUrl/)
  assert.doesNotMatch(editWxml, /handleRemoveProfileAvatar/)
  assert.doesNotMatch(editWxml, /profile-avatar-remove/)
  assert.doesNotMatch(editWxml, />移除</)
  assert.doesNotMatch(editWxml, /class="profile-form-section-title">资料副本/)
  assert.match(editWxml, /class="profile-sheet-fields-panel"/)
  assert.match(editWxml, /class="profile-avatar-editor"/)
  assert.match(editWxml, /catchtap="handleChooseProfileAvatar"/)
  assert.match(editWxml, /class="profile-qr-editor"/)
  assert.match(editWxml, /src="{{profileForm\.wechatQrUrl}}"/)
  assert.match(editWxml, /catchtap="handleChooseProfileWechatQr"/)
  assert.match(editWxml, /class="profile-field-limit"/)
  assert.match(editWxml, /maxlength="50"/)
  assert.match(editWxml, /maxlength="500"/)
  assert.match(editWxml, /class="component-remove-pane"/)
  assert.match(editWxml, /class="component-remove-button pe-component-delete"/)
  assert.match(editWxml, /class="action-button secondary-button(?: [^"]*)?"/)
  assert.match(editWxml, /class="action-button primary-button pe-page-action-primary"/)
  assert.match(editWxml, /style="\{\{draggingIndex === index \? componentDragStyle : ''\}\}"/)
  assert.match(editWxml, /data-type="\{\{item\.componentType\}\}"/)
  assert.match(editWxml, /catchtap="handleComponentTap"/)
  assert.match(editWxml, /class="pe-sheet-mask \{\{componentSheetVisible \? 'pe-sheet-mask-visible' : ''\}\}"/)
  assert.match(editWxml, /class="pe-sheet-panel pe-sheet-size-compact/)
  assert.match(editWxml, /class="pe-sheet-grabber"/)
  assert.match(editWxml, /class="pe-sheet-mask \{\{componentWorkSheetVisible \? 'pe-sheet-mask-visible' : ''\}\}"/)
  assert.match(editWxml, /wx:for="{{componentWorkOptions}}"/)
  assert.match(editWxml, /class="component-work-ratio">\{\{item\.aspectRatioText\}\}<\/view>/)
  assert.match(editWxml, /catchtap="handleToggleComponentWork"/)
  assert.match(editWxml, /catchtap="handleConfirmComponentWorks"/)
  assert.match(editWxml, /class="display-group-order"[\s\S]*catchtap="handleToggleDisplayGroupTag"[\s\S]*aria-checked="\{\{item\.selected\}\}"/)
  assert.match(editWxml, /wx:for="\{\{displayGroupWorkOptions\}\}"/)
  assert.match(editWxml, /class="display-group-work-order">/)
  assert.match(editWxml, /catchtap="handleToggleDisplayGroupWork"/)
  assert.doesNotMatch(editWxml, /display-group-add-button/)
  assert.doesNotMatch(editWxml, /displayGroupFormVisible/)
  assert.doesNotMatch(editWxml, /handleDisplayGroupNameInput/)
  assert.doesNotMatch(editWxml, /handleConfirmDisplayGroupForm/)
  assert.doesNotMatch(editWxml, /handleDisplayGroupLongPress/)
  assert.doesNotMatch(editWxml, /handleDeleteDisplayGroup/)
  assert.doesNotMatch(editWxml, /handleCopyWorkTagsToDisplayGroups/)
  assert.doesNotMatch(editWxml, /handleImportWorksByTag/)
  assert.doesNotMatch(editWxml, /handleOpenDisplayGroupWorkPicker/)
  assert.doesNotMatch(editWxml, /class="display-group-row-actions"/)
  assert.doesNotMatch(editWxml, /class="display-group-row-button/)
  assert.match(editWxml, /wx:for="{{componentOptions}}"/)
  assert.match(editWxml, /data-type="{{item\.componentType}}"/)
  assert.match(editWxml, /catchtap="handleSelectComponent"/)
  assert.match(editWxml, /catchtap="handleCloseComponentSheet"/)
  assert.match(editWxml, /bindlongpress="handleComponentDragStart"/)
  assert.match(editWxml, /bindtouchstart="handleComponentTouchStart"/)
  assert.match(editWxml, /bindtouchmove="handleComponentTouchMove"/)
  assert.match(editWxml, /bindtouchend="handleComponentTouchEnd"/)
  assert.match(editWxml, /bindtouchcancel="handleComponentTouchCancel"/)
  assert.doesNotMatch(editWxml, />上移</)
  assert.doesNotMatch(editWxml, />下移</)
  assert.match(editJs, /componentSheetVisible/)
  assert.match(editJs, /revealedComponentKey/)
  assert.match(editJs, /componentDragStyle/)
  assert.match(editJs, /componentWorkSheetVisible/)
  assert.match(editJs, /profileSheetVisible/)
  assert.match(editJs, /updateComponentProfileConfig/)
  assert.match(editJs, /isEditableComponentType/)
  assert.match(editJs, /uploadPortfolioImageAsset/)
  assert.match(editJs, /PORTFOLIO_ASSET_TYPES/)
  assert.match(editJs, /handleChooseProfileAvatar/)
  assert.match(editJs, /handleChooseProfileWechatQr/)
  assert.match(editJs, /handleChooseShareCover/)
  assert.doesNotMatch(editJs, /handleRemoveShareCover/)
  assert.doesNotMatch(editJs, /handleRemoveProfileAvatar/)
  assert.match(editJs, /addComponent/)
  assert.match(editJs, /handleSelectComponent/)
  assert.match(editJs, /handleComponentTap/)
  assert.match(editJs, /updateComponentWorkIds/)
  assert.doesNotMatch(editJs, /handleOpenComponentLibrary\(\)\s*\{[\s\S]*wx\.navigateTo/)

  assert.match(editWxss, /\.portfolio-edit-page\s*\{[\s\S]*height:\s*100vh;[\s\S]*display:\s*flex;[\s\S]*overflow:\s*hidden;/)
  assert.match(editWxss, /\.edit-scroll\s*\{[\s\S]*flex:\s*1;[\s\S]*min-height:\s*0;/)
  assert.match(pageContentRule, /padding:\s*20rpx 32rpx calc\(140rpx \+ env\(safe-area-inset-bottom\)\)/)
  assert.match(pageContentRule, /gap:\s*20rpx/)
  assert.match(pageContentRule, /box-sizing:\s*border-box/)
  assert.match(foundationWxss, /\.pe-page-card\s*\{[\s\S]*background:\s*var\(--pe-color-surface,\s*#FFFFFF\);[\s\S]*border-radius:\s*var\(--pe-radius-card,\s*48rpx\);[\s\S]*box-shadow:\s*none;/)
  assert.match(editWxss, /\.field-limit\s*\{[\s\S]*color:\s*var\(--pe-color-text-muted,\s*#ADB5BD\);[\s\S]*font-size:\s*22rpx;/)
  assert.match(pageFieldRule, /width:\s*100%/)
  assert.match(pageFieldRule, /min-height:\s*84rpx/)
  assert.match(pageFieldRule, /border-radius:\s*var\(--pe-radius-pill,\s*999rpx\)/)
  assert.match(pageFieldRule, /box-sizing:\s*border-box/)
  assert.match(editWxss, /\.cover-row\s*\{[\s\S]*display:\s*flex;[\s\S]*gap:/)
  assert.match(editWxss, /\.cover-preview\s*\{[\s\S]*width:\s*360rpx;[\s\S]*height:\s*288rpx;/)
  assert.doesNotMatch(editWxss, /\.cover-actions\s*\{/)
  assert.doesNotMatch(editWxss, /\.cover-action-button\s*\{/)
  assert.doesNotMatch(editWxss, /\.cover-action-button\.remove\s*\{/)
  assert.doesNotMatch(editWxss, /\.profile-avatar-remove\s*\{/)
  assert.match(editWxss, /\.component-swipe-row\s*\{[\s\S]*position:\s*relative;[\s\S]*overflow:\s*hidden;/)
  assert.match(componentRowRule, /position:\s*relative/)
  assert.match(componentRowRule, /display:\s*flex/)
  assert.match(componentRowRule, /transition:\s*transform 180ms ease/)
  assert.match(editWxss, /\.component-swipe-row\.revealed \.component-row\s*\{[\s\S]*transform:\s*translateX\(-140rpx\);/)
  assert.match(editWxss, /\.component-remove-pane\s*\{[\s\S]*position:\s*absolute;[\s\S]*right:\s*0;[\s\S]*width:\s*128rpx;/)
  assert.match(editWxss, /\.component-row\.dragging\s*\{[\s\S]*border-color:/)
  assert.match(editWxss, /\.component-drag-handle\s*\{[\s\S]*background:\s*linear-gradient/)
  assert.match(editWxss, /\.component-drag-handle\s*\{[\s\S]*background-size:\s*22rpx 48rpx;/)
  assert.match(editWxss, /\.component-row-arrow\s*\{[\s\S]*width:\s*28rpx;[\s\S]*flex:\s*none;/)
  assert.match(editWxss, /\.component-row-arrow\.placeholder\s*\{[\s\S]*visibility:\s*hidden;/)
  assert.match(foundationWxss, /\.pe-sheet-mask\s*\{[\s\S]*position:\s*fixed;[\s\S]*align-items:\s*flex-end;[\s\S]*background:\s*var\(--pe-color-mask,\s*rgba\(17,\s*24,\s*39,\s*0\.42\)\);/)
  assert.match(foundationWxss, /\.pe-sheet-mask-visible\s*\{[\s\S]*opacity:\s*1;[\s\S]*pointer-events:\s*auto;/)
  assert.match(foundationWxss, /\.pe-sheet-size-compact\s*\{[\s\S]*max-height:\s*72vh;/)
  assert.match(foundationWxss, /\.pe-sheet-panel-visible\s*\{[\s\S]*transform:\s*translateY\(0\);/)
  assert.match(foundationWxss, /\.pe-sheet-grabber\s*\{[\s\S]*width:\s*72rpx;[\s\S]*height:\s*8rpx;/)
  assert.match(editWxss, /\.component-option\s*\{[\s\S]*width:\s*100%;[\s\S]*box-sizing:\s*border-box;/)
  assert.match(foundationWxss, /\.pe-sheet-mask\s*\{[\s\S]*position:\s*fixed;[\s\S]*align-items:\s*flex-end;/)
  assert.match(sheetChoiceSelectedRule, /border-color:\s*var\(--pe-color-text-primary,\s*#212529\)/)
  assert.match(editWxss, /\.display-group-order\s*\{[\s\S]*border-radius:\s*50%;/)
  assert.match(editWxss, /\.display-group-order\[aria-checked="true"\]\s*\{[\s\S]*background:\s*var\(--pe-color-text-primary,\s*#212529\);/)
  assert.match(editWxss, /\.display-group-work-order\s*\{[\s\S]*border-radius:\s*50%;/)
  assert.match(editWxss, /\.display-group-work-item\[aria-checked="true"\] \.display-group-work-order\s*\{[\s\S]*background:\s*var\(--pe-color-text-primary,\s*#212529\);/)
  assert.doesNotMatch(editWxss, /\.display-group-add-button\s*\{/)
  assert.doesNotMatch(editWxss, /\.display-group-form\s*\{/)
  assert.doesNotMatch(editWxss, /\.display-group-pill\.manage\s*\{/)
  assert.doesNotMatch(editWxss, /\.display-group-delete\s*\{/)
  assert.doesNotMatch(editWxss, /\.work-tag-import-button\s*\{/)
  assert.doesNotMatch(editWxss, /\.display-group-row-actions\s*\{/)
  assert.doesNotMatch(editWxss, /\.display-group-row-button\s*\{/)
  assert.match(foundationWxss, /\.pe-page-actions\s*\{[\s\S]*position:\s*fixed;[\s\S]*display:\s*flex;[\s\S]*gap:\s*16rpx;/)
  assert.match(foundationWxss, /\.pe-page-action-primary\s*\{[\s\S]*height:\s*80rpx;[\s\S]*flex:\s*1;[\s\S]*border-radius:\s*var\(--pe-radius-pill,\s*999rpx\);/)
})

test('standard portfolio display tag sheet shows ordered tag and work selections', () => {
  const editWxml = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  const editWxss = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')
  const foundationWxss = read('styles/portfolio-editor-foundation.wxss')
  const tagScrollRule = readRule(editWxss, '.display-group-scroll')
  const tagListRule = readRule(editWxss, '.display-group-list-content')
  const activePillRule = readRule(foundationWxss, '.pe-sheet-choice-selected-filled')
  const tagPillRule = readRule(editWxss, '.display-group-pill')
  const tagMainRule = readRule(editWxss, '.display-group-main')
  const tagNameRule = readRule(editWxss, '.display-group-name')
  const tagBadgesRule = readRule(editWxss, '.display-group-badges')
  const displayGroupPanelRule = readRule(editWxss, '.display-group-picker-panel')
  const workScrollIndex = editWxml.indexOf('class="display-group-work-scroll"')
  const workListRule = readRule(editWxss, '.display-group-work-list')
  const workItemRule = readRule(editWxss, '.display-group-work-item')
  const workThumbRule = readRule(editWxss, '.display-group-work-thumb')
  const workCopyRule = readRule(editWxss, '.display-group-work-item-copy')
  const workSectionRule = readRule(editWxss, '.display-group-work-section')
  const workScrollRule = readRule(editWxss, '.display-group-work-scroll')
  const tagOrderRule = readRule(editWxss, '.display-group-order')
  const workOrderRule = readRule(editWxss, '.display-group-work-order')

  assert.match(editWxml, /<scroll-view wx:else class="display-group-scroll" scroll-x>/)
  assert.doesNotMatch(editWxml, /class="display-group-scroll[^"]*"[^>]*(?:scroll-y|type="list")/)
  assert.match(
    editWxml,
    /class="display-group-badges"[\s\S]*class="display-group-count"[\s\S]*class="display-group-order"/
  )
  assert.match(tagScrollRule, /padding-top:\s*12rpx/)
  assert.match(tagScrollRule, /height:\s*84rpx/)
  assert.match(tagScrollRule, /min-height:\s*84rpx/)
  assert.match(tagScrollRule, /max-height:\s*84rpx/)
  assert.match(tagScrollRule, /flex:\s*none/)
  assert.match(tagListRule, /display:\s*inline-flex/)
  assert.match(tagListRule, /flex-wrap:\s*nowrap/)
  assert.match(tagListRule, /width:\s*max-content/)
  assert.match(tagPillRule, /width:\s*auto/)
  assert.match(tagPillRule, /min-width:\s*0/)
  assert.match(tagPillRule, /flex:\s*0\s+0\s+auto/)
  assert.match(tagPillRule, /justify-content:\s*space-between/)
  assert.match(tagMainRule, /flex:\s*none/)
  assert.match(tagMainRule, /justify-content:\s*flex-start/)
  assert.match(tagNameRule, /flex:\s*none/)
  assert.match(tagNameRule, /min-width:\s*0/)
  assert.match(tagBadgesRule, /flex:\s*none/)
  assert.match(tagBadgesRule, /gap:\s*8rpx/)
  assert.doesNotMatch(activePillRule, /translateY/)
  assert.match(editWxml, /wx:for="\{\{displayGroupOptions\}\}"[\s\S]*class="display-group-pill/)
  assert.match(editWxml, /class="display-group-count">\{\{item\.countText\}\}<\/view>/)
  assert.match(editWxml, /data-group-key="\{\{item\.groupKey\}\}"[\s\S]*catchtap="handleSelectDisplayGroup"/)
  assert.match(editWxml, /class="display-group-order"[\s\S]*data-tag-id="\{\{item\.id\}\}"[\s\S]*catchtap="handleToggleDisplayGroupTag"[\s\S]*aria-checked="\{\{item\.selected\}\}"/)
  assert.match(editWxml, /\{\{item\.selectionOrder \? item\.selectionOrder : ''\}\}/)
  assert.notEqual(workScrollIndex, -1)
  assert.match(editWxml, /<scroll-view[^>]*class="display-group-work-scroll"[^>]*scroll-y[^>]*type="list"/)
  assert.match(editWxml, /class="display-group-work-scroll"[^>]*style="height: \{\{displayGroupWorkScrollHeight\}\}rpx;"/)
  assert.match(editWxml, /class="display-group-work-list"[\s\S]*wx:for="\{\{displayGroupWorkOptions\}\}"/)
  assert.match(editWxml, /class="display-group-work-item pe-sheet-choice \{\{item\.selected \? 'pe-sheet-choice-selected' : ''\}\}"[\s\S]*catchtap="handleToggleDisplayGroupWork"/)
  assert.match(editWxml, /class="display-group-work-thumb"[\s\S]*src="\{\{item\.thumbUrl\}\}"/)
  assert.match(editWxml, /class="display-group-work-item-title">/)
  assert.match(editWxml, /class="display-group-work-ratio">\{\{item\.aspectRatioText\}\}<\/view>/)
  assert.match(editWxml, /class="display-group-work-order">/)
  assert.match(editWxml, /class="component-work-actions pe-sheet-actions"[\s\S]*class="pe-sheet-action-cancel"[\s\S]*handleCancelDisplayGroupSheet[\s\S]*>取消<[\s\S]*class="pe-sheet-action-confirm"[\s\S]*handleConfirmDisplayGroupSheet[\s\S]*>完成</)
  assert.doesNotMatch(editWxml, />关闭</)
  assert.doesNotMatch(editWxml, /displayGroupWorkPreviewOptions/)
  assert.doesNotMatch(editWxml, /work-tag-import-section/)
  assert.match(displayGroupPanelRule, /height:\s*auto/)
  assert.match(displayGroupPanelRule, /max-height:\s*calc\(100vh - 176rpx - env\(safe-area-inset-top\)\)/)
  assert.match(workScrollRule, /max-height:\s*360rpx/)
  assert.doesNotMatch(workScrollRule, /(?:^|\n)\s*height:\s*360rpx/)
  assert.match(workScrollRule, /transition:\s*height\s+200ms\s+ease-out/)
  assert.match(workSectionRule, /display:\s*flex/)
  assert.match(workSectionRule, /flex-direction:\s*column/)
  assert.match(workListRule, /flex-direction:\s*column/)
  assert.match(workItemRule, /display:\s*flex/)
  assert.match(workItemRule, /flex-direction:\s*row/)
  assert.match(workItemRule, /min-height:\s*96rpx/)
  assert.match(workThumbRule, /flex:\s*none/)
  assert.match(workCopyRule, /display:\s*flex/)
  assert.match(workCopyRule, /flex:\s*1 1 0/)
  assert.match(workCopyRule, /flex-direction:\s*column/)
  assert.match(editWxss, /\.component-work-ratio\s*\{[\s\S]*font-size:\s*20rpx;/)
  assert.match(editWxss, /\.display-group-work-ratio\s*\{[\s\S]*font-size:\s*20rpx;/)
  assert.match(tagOrderRule, /border-radius:\s*50%/)
  assert.match(workOrderRule, /border-radius:\s*50%/)
})

test('standard portfolio display tag choices keep compact geometry outside shared choice defaults', () => {
  const editWxss = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')
  const tagPillRule = readRule(editWxss, '.display-group-pill')
  const workItemRule = readRule(editWxss, '.display-group-work-item')

  assert.match(tagPillRule, /padding:\s*0\s+18rpx;/)
  assert.match(tagPillRule, /border-radius:\s*var\(--pe-radius-pill,\s*999rpx\);/)
  assert.match(workItemRule, /padding:\s*12rpx;/)
  assert.match(workItemRule, /border-radius:\s*14rpx;/)
})

test('standard personal portfolio editor keeps add button compact and delete hidden behind swipe', () => {
  const editWxss = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')
  const foundationWxss = read('styles/portfolio-editor-foundation.wxss')
  const deleteRule = readRule(foundationWxss, '.pe-component-delete')

  assert.match(editWxss, /\.link-button\s*\{[\s\S]*width:\s*136rpx;[\s\S]*min-width:\s*136rpx;[\s\S]*max-width:\s*136rpx;[\s\S]*flex:\s*0 0 136rpx;[\s\S]*padding:\s*0;/)
  assert.match(deleteRule, /width:\s*108rpx;/)
  assert.match(deleteRule, /min-width:\s*108rpx;/)
  assert.match(deleteRule, /height:\s*100%/)
  assert.doesNotMatch(editWxss, /\.component-actions\s*\{/)
})

test('standard personal portfolio picker shows disabled profile as an auto-width added pill', () => {
  const editWxml = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxml')
  const editWxss = read('pages/portfolios/standard-edit/portfolio-standard-edit.wxss')
  const plusRule = readRule(editWxss, '.component-option-plus')
  const addedRule = readRule(editWxss, '.component-option-added')

  assert.match(editWxml, /class="component-option pe-sheet-choice \{\{item\.disabled \? 'disabled' : ''\}\}"/)
  assert.match(editWxml, /data-disabled="\{\{item\.disabled\}\}"/)
  assert.match(editWxml, /class="\{\{item\.disabled \? 'component-option-added' : 'component-option-plus'\}\}">\{\{item\.disabled \? '已添加' : '\+'\}\}<\/view>/)
  assert.match(plusRule, /width:\s*44rpx/)
  assert.match(plusRule, /border-radius:\s*50%/)
  assert.match(addedRule, /width:\s*auto/)
  assert.match(addedRule, /min-width:\s*104rpx/)
  assert.match(addedRule, /padding:\s*0 18rpx/)
  assert.match(addedRule, /border-radius:\s*999rpx/)
  assert.match(addedRule, /white-space:\s*nowrap/)
})
