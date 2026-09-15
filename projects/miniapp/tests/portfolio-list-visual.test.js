const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function read(relativePath) {
  return fs.readFileSync(path.join(__dirname, '..', relativePath), 'utf8')
}

function readRule(content, selector) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matches = Array.from(content.matchAll(
    new RegExp(`(?:^|\\n)\\s*${escapedSelector}\\s*\\{([^}]*)\\}`, 'g')
  ))
  const match = matches[matches.length - 1]
  return match ? match[1] : ''
}

const styleFiles = [
  'pages/portfolios/portfolios.wxss',
  'pages/mock/styles/portfolios.wxss'
]
const FORMAL_PORTFOLIO_STYLE_FILE = 'pages/portfolios/portfolios.wxss'

test('portfolio skeleton preserves card geometry and contains no interactive or remote content', () => {
  const wxml = read('pages/portfolios/portfolios.wxml')
  const wxss = read(FORMAL_PORTFOLIO_STYLE_FILE)
  const template = wxml.match(/<template name="portfolio-loading-skeleton">([\s\S]*?)<\/template>/)[1]

  assert.doesNotMatch(template, /<button|<image|bindtap|catchtap|https?:\/\//)
  assert.match(template, /aria-hidden="true"/)
  assert.match(readRule(wxss, '.portfolio-loading-skeleton'), /pointer-events:\s*none/)

  // 比较骨架与真实卡片的外尺寸，防止后续单独调样式导致载入时跳动。
  for (const [skeleton, content, properties] of [
    ['.portfolio-skeleton-row', '.portfolio-item-card', ['min-height', 'padding', 'gap', 'border-radius']],
    ['.portfolio-skeleton-cover', '.portfolio-cover', ['width', 'height', 'border-radius']]
  ]) {
    for (const property of properties) {
      const rule = new RegExp(`(?:^|;)\\s*${property}:\\s*([^;]+)`)
      assert.equal(readRule(wxss, skeleton).match(rule)[1], readRule(wxss, content).match(rule)[1])
    }
  }
})

test('portfolio loading placeholders and errors never replace an already loaded list', () => {
  const wxml = read('pages/portfolios/portfolios.wxml')

  for (const [loading, loaded, error, refreshing] of [
    ['loading', 'loaded', 'errorMessage', 'pullDownRefreshing'],
    ['teamLoading', 'teamLoaded', 'teamErrorMessage', 'teamPullDownRefreshing']
  ]) {
    assert.match(wxml, new RegExp(`<template wx:if="\\{\\{${loading} && !${loaded}\\}\\}" is="portfolio-loading-skeleton"`))
    assert.match(wxml, new RegExp(`wx:elif="\\{\\{${error} && !${loaded}\\}\\}" class="inline-state error"`))
    assert.match(wxml, new RegExp(`<view wx:if="\\{\\{${loaded} && ${error}\\}\\}" class="refresh-error"`))
    assert.match(wxml, new RegExp(`wx:if="\\{\\{!${loaded}\\}\\}"[\\s\\S]*?class="summary-skeleton"`))
    assert.match(wxml, new RegExp(`wx:if="\\{\\{${loading} && !${refreshing}\\}\\}" class="portfolio-loading-spinner summary-refresh-spinner"`))
  }
})

test('mock portfolio scroll viewport occupies the remaining page height on device', () => {
  const wxss = read('pages/mock/styles/portfolios.wxss')
  const scrollRule = readRule(wxss, '.portfolio-scroll')

  assert.match(scrollRule, /flex:\s*1(?:\s+1\s+0)?/)
  assert.match(scrollRule, /min-height:\s*0/)
})

test('portfolio list matches the Travel design hierarchy and measurements', () => {
  styleFiles.forEach((styleFile) => {
    const wxss = read(styleFile)
    const segmentRule = readRule(wxss, '.segment')
    const segmentItemRule = readRule(wxss, '.segment-item')
    const activeSegmentRule = readRule(wxss, '.segment-item.active')
    const segmentIndicatorRule = readRule(wxss, '.segment-indicator')
    const listPanelRule = readRule(wxss, '.portfolio-list-panel')
    const listTitleRule = readRule(wxss, '.list-title')
    const summaryPillRule = readRule(wxss, '.summary-pill')
    const summaryPillBeforeRule = readRule(wxss, '.summary-pill::before')
    const cardRule = readRule(wxss, '.portfolio-item-card')
    const coverRule = readRule(wxss, '.portfolio-cover')
    const metaRule = readRule(wxss, '.portfolio-meta')
    const statusBadgeRule = readRule(wxss, '.status-badge')
    const statusBadgeBeforeRule = readRule(wxss, '.status-badge::before')
    const createActionsRule = readRule(wxss, '.create-actions')
    const createActionRule = readRule(wxss, '.create-action')
    const advancedActionRule = readRule(wxss, '.create-action.advanced')
    const actionButtonRule = readRule(wxss, '.portfolio-action-button')

    assert.match(segmentRule, /min-height:\s*72rpx/)
    assert.match(segmentRule, /border-bottom:\s*1rpx solid #e9ecef/)
    assert.doesNotMatch(segmentRule, /border-radius|background/)
    assert.match(segmentItemRule, /height:\s*72rpx/)
    assert.match(segmentItemRule, /color:\s*#868e96/)
    assert.match(segmentItemRule, /font-size:\s*26rpx/)
    assert.match(segmentItemRule, /font-weight:\s*500/)
    assert.match(activeSegmentRule, /color:\s*#212529/)
    assert.match(activeSegmentRule, /font-weight:\s*600/)
    assert.match(segmentIndicatorRule, /width:\s*48rpx/)
    assert.match(segmentIndicatorRule, /height:\s*5rpx/)
    assert.match(segmentIndicatorRule, /background:\s*#212529/)

    assert.doesNotMatch(listPanelRule, /box-shadow/)
    assert.match(listTitleRule, /font-weight:\s*700/)
    assert.match(summaryPillRule, /height:\s*48rpx/)
    assert.match(summaryPillRule, /padding:\s*0 8rpx/)
    assert.doesNotMatch(summaryPillRule, /border|background/)
    assert.match(summaryPillBeforeRule, /width:\s*12rpx/)
    assert.match(summaryPillBeforeRule, /height:\s*12rpx/)
    assert.match(summaryPillBeforeRule, /border-radius:\s*50%/)

    assert.match(cardRule, /min-height:\s*184rpx/)
    assert.match(cardRule, /padding:\s*24rpx/)
    assert.match(cardRule, /gap:\s*20rpx/)
    assert.match(cardRule, /border-radius:\s*48rpx/)
    if (styleFile === FORMAL_PORTFOLIO_STYLE_FILE) {
      assert.match(coverRule, /width:\s*152rpx/)
      assert.match(coverRule, /height:\s*133rpx/)
    } else {
      assert.match(coverRule, /width:\s*128rpx/)
      assert.match(coverRule, /height:\s*112rpx/)
    }
    assert.match(coverRule, /border-radius:\s*40rpx/)
    assert.match(coverRule, /background:\s*#e9ecef/)
    assert.match(metaRule, /color:\s*#adb5bd/)

    assert.match(statusBadgeRule, /font-size:\s*21rpx/)
    assert.doesNotMatch(statusBadgeRule, /border|background/)
    assert.match(statusBadgeBeforeRule, /width:\s*12rpx/)
    assert.match(statusBadgeBeforeRule, /height:\s*12rpx/)
    assert.match(statusBadgeBeforeRule, /border-radius:\s*50%/)

    assert.match(createActionsRule, /gap:\s*16rpx/)
    assert.match(createActionsRule, /margin:\s*20rpx 0 0/)
    assert.match(createActionsRule, /padding-bottom:\s*28rpx/)
    assert.match(createActionRule, /height:\s*92rpx/)
    assert.match(createActionRule, /font-weight:\s*600/)
    assert.doesNotMatch(createActionRule, /box-shadow/)
    assert.match(advancedActionRule, /color:\s*#212529/)
    assert.match(advancedActionRule, /background:\s*#e9ecef/)
    assert.doesNotMatch(advancedActionRule, /border|box-shadow/)
    assert.match(actionButtonRule, /color:\s*#212529/)
    assert.match(actionButtonRule, /(?:^|\n)\s*width:\s*108rpx/)
    assert.match(actionButtonRule, /(?:^|\n)\s*min-width:\s*108rpx/)
    assert.match(actionButtonRule, /(?:^|\n)\s*max-width:\s*108rpx/)
  })
})

test('bottom navigation uses the Travel SVG icons and exact proportions', () => {
  const pageWxmlFiles = [
    'pages/index/index.wxml',
    'pages/schedule/schedule.wxml',
    'pages/works/works.wxml',
    'pages/portfolios/portfolios.wxml',
    'components/mock/tabbar/tabbar.wxml'
  ]
  const pageStyleFiles = [
    'pages/index/index.wxss',
    'pages/schedule/schedule.wxss',
    'pages/works/works.wxss',
    'pages/portfolios/portfolios.wxss',
    'components/mock/tabbar/tabbar.wxss'
  ]

  pageWxmlFiles.forEach((wxmlFile) => {
    const wxml = read(wxmlFile)
    assert.match(
      wxml,
      /class="tab-icon-image"[^>]*src="\/assets\/system\/tabbar\/\{\{item\.icon\}\}\{\{item\.active \? '-active' : ''\}\}\.svg"/
    )
    assert.doesNotMatch(wxml, /class="tab-icon \{\{item\.icon\}\}-tab-icon"/)
  })

  pageStyleFiles.forEach((styleFile) => {
    const wxss = read(styleFile)
    const tabbarRule = readRule(wxss, '.tabbar')
    const tabRule = readRule(wxss, '.tab')
    const iconRule = readRule(wxss, '.tab-icon-image')
    const labelRule = readRule(wxss, '.tab-label')

    assert.match(tabbarRule, /left:\s*28rpx/)
    assert.match(tabbarRule, /right:\s*28rpx/)
    assert.match(tabbarRule, /bottom:\s*calc\(28rpx \+ env\(safe-area-inset-bottom\)\)/)
    assert.match(tabbarRule, /height:\s*116rpx/)
    assert.match(tabbarRule, /padding:\s*0/)
    assert.match(tabbarRule, /background:\s*#ffffff/)
    assert.match(tabbarRule, /border:\s*2rpx solid #e9ecef/)
    assert.match(tabRule, /height:\s*116rpx/)
    assert.match(tabRule, /color:\s*#868e96/)
    assert.match(iconRule, /width:\s*38rpx/)
    assert.match(iconRule, /height:\s*38rpx/)
    assert.match(labelRule, /font-size:\s*18rpx/)
    assert.match(labelRule, /font-weight:\s*500/)
  })

  ;['schedule', 'work', 'portfolio', 'mine'].forEach((icon) => {
    const inactiveSvg = read(`assets/system/tabbar/${icon}.svg`)
    const activeSvg = read(`assets/system/tabbar/${icon}-active.svg`)
    assert.doesNotMatch(inactiveSvg, /#8B96A3|#B88A44|#212529/i)
    assert.match(inactiveSvg, /#868E96/i)
    assert.doesNotMatch(activeSvg, /#8B96A3|#B88A44|#868E96/i)
    assert.match(activeSvg, /#FFFFFF/i)
    if (icon === 'work') {
      // 播放标记使用反差色，避免与右下角方框同色而不可见。
      assert.match(inactiveSvg, /<path\b[^>]*fill="#FFFFFF"/i)
      assert.match(activeSvg, /<path\b[^>]*fill="#212529"/i)
    } else {
      assert.doesNotMatch(inactiveSvg, /#FFFFFF/i)
      assert.doesNotMatch(activeSvg, /#212529/i)
    }
  })
})
