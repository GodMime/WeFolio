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
    assert.match(wxml, /<video-carousel[^>]*show-component-title="\{\{item\.showComponentTitle\}\}"/)
    assert.match(wxml, /<video-carousel[\s\S]*theme-mode="\{\{portfolio\.themeMode\}\}"/)
    assert.doesNotMatch(wxml, /work-video-mask|work-video-player/)
    assert.match(js, /openVideoPlayer/)
    assert.doesNotMatch(js, /previewMedia|openNativeVideoPreview/)
    assert.match(js, /handleVideoCarouselPlay/)
  }
})

test('team preview and visitor bucket and render video carousel with a dedicated video page', () => {
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
    assert.match(wxml, /<team-video-carousel[^>]*show-component-title="\{\{item\.data\.showComponentTitle\}\}"/)
    assert.match(wxml, /<team-video-carousel[^>]*theme-mode="\{\{themeMode\}\}"/)
    assert.match(wxml, /<team-video-carousel[^>]*bindplay="handleVideoCarouselPlay"/)
    assert.match(js, /openVideoPlayer/)
    assert.doesNotMatch(js, /previewMedia|openNativeVideoPreview/)
    assert.doesNotMatch(wxml, /work-video-mask|work-video-player/)
  }
})

test('all video carousel pages delegate fullscreen playback to the dedicated video page without source page overlays', () => {
  for (const page of [
    'pages/portfolios/standard-preview/portfolio-standard-preview',
    'pages/portfolios/visitor-portfolio/visitor-portfolio',
    'pages/team-portfolios/standard-preview/team-portfolio-standard-preview',
    'pages/team-portfolios/visitor-portfolio/team-visitor-portfolio'
  ]) {
    const wxss = read(`${page}.wxss`)
    const wxml = read(`${page}.wxml`)
    assert.doesNotMatch(wxss, /work-video-|video-page-hidden/)
    assert.doesNotMatch(wxml, /work-video-|videoPreviewFullscreen|bindfullscreenchange/)
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
