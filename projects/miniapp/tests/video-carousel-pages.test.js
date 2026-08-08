const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '..')

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

test('personal preview and visitor register and render video carousel before other content branches', () => {
  for (const page of [
    'pages/portfolios/standard-preview/portfolio-standard-preview',
    'pages/portfolios/visitor-portfolio/visitor-portfolio'
  ]) {
    const json = JSON.parse(read(`${page}.json`))
    const wxml = read(`${page}.wxml`)
    const js = read(`${page}.js`)
    assert.equal(json.usingComponents['video-carousel'], '/pages/portfolios/components/video-carousel/video-carousel')
    assert.match(wxml, /item\.componentType === 'VIDEO_CAROUSEL'/)
    assert.match(wxml, /<video-carousel[\s\S]*bindplay="handleVideoCarouselPlay"/)
    assert.match(wxml, /<video-carousel[\s\S]*theme-mode="\{\{portfolio\.themeMode\}\}"/)
    assert.match(wxml, /binderror="handleVideoPreviewError"/)
    assert.match(js, /videoPreviewUrl/)
    assert.match(js, /handleVideoCarouselPlay/)
  }
})

test('team preview and visitor bucket and render video carousel with a root portal player', () => {
  for (const page of [
    'pages/team-portfolios/standard-preview/team-portfolio-standard-preview',
    'pages/team-portfolios/visitor-portfolio/team-visitor-portfolio'
  ]) {
    const json = JSON.parse(read(`${page}.json`))
    const wxml = read(`${page}.wxml`)
    const js = read(`${page}.js`)
    assert.equal(json.usingComponents['team-video-carousel'], '/pages/team-portfolios/components/video-carousel/video-carousel')
    assert.match(js, /VIDEO_CAROUSEL:\s*'videoCarousel'/)
    assert.match(wxml, /componentBuckets\.videoCarousel/)
    assert.match(wxml, /<team-video-carousel/)
    assert.match(wxml, /<team-video-carousel[^>]*theme-mode="\{\{themeMode\}\}"/)
    assert.match(wxml, /<root-portal wx:if="\{\{videoPreviewVisible\}\}">/)
    assert.match(wxml, /binderror="handleVideoPreviewError"/)
  }
})

test('all video carousel players keep a responsive sixteen-by-nine viewport', () => {
  for (const page of [
    'pages/portfolios/standard-preview/portfolio-standard-preview',
    'pages/portfolios/visitor-portfolio/visitor-portfolio',
    'pages/team-portfolios/standard-preview/team-portfolio-standard-preview',
    'pages/team-portfolios/visitor-portfolio/team-visitor-portfolio'
  ]) {
    const wxss = read(`${page}.wxss`)
    const rule = wxss.match(/\.work-video-player\s*\{([^}]+)\}/)
    assert.ok(rule, `${page} should style the video player`)
    assert.match(rule[1], /aspect-ratio:\s*16\s*\/\s*9/)
    assert.doesNotMatch(rule[1], /height:\s*62vh|max-height:\s*720rpx/)
  }
})

test('video carousel visitor event contracts include component identity and zero duration', () => {
  const personal = read('pages/portfolios/visitor-portfolio/visitor-portfolio.js')
  const team = read('pages/team-portfolios/visitor-portfolio/team-visitor-portfolio.js')
  for (const source of [personal, team]) {
    assert.match(source, /eventType:\s*VIDEO_PLAYED_EVENT_TYPE|eventType:\s*'VIDEO_PLAYED'/)
    assert.match(source, /componentKey/)
    assert.match(source, /durationSeconds:\s*0/)
  }
})
