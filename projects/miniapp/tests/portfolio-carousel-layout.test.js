const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

function read(relativePath) {
  return fs.readFileSync(path.join(__dirname, '..', relativePath), 'utf8')
}

function readJson(relativePath) {
  return JSON.parse(read(relativePath))
}

test('portfolio preview and visitor pages render carousel through shared progress component', () => {
  const previewJson = readJson('pages/portfolio-standard-preview/portfolio-standard-preview.json')
  const visitorJson = readJson('pages/visitor-portfolio/visitor-portfolio.json')
  const previewWxml = read('pages/portfolio-standard-preview/portfolio-standard-preview.wxml')
  const visitorWxml = read('pages/visitor-portfolio/visitor-portfolio.wxml')

  assert.equal(previewJson.usingComponents['portfolio-carousel'], '/components/portfolio-carousel/portfolio-carousel')
  assert.equal(visitorJson.usingComponents['portfolio-carousel'], '/components/portfolio-carousel/portfolio-carousel')
  assert.match(previewWxml, /<portfolio-carousel[\s\S]*works="\{\{item\.works\}\}"[\s\S]*interval="\{\{item\.carouselIntervalMs\}\}"/)
  assert.match(visitorWxml, /<portfolio-carousel[\s\S]*works="\{\{item\.works\}\}"[\s\S]*interval="\{\{item\.carouselIntervalMs\}\}"/)
  assert.doesNotMatch(previewWxml, /indicator-dots/)
  assert.doesNotMatch(visitorWxml, /indicator-dots/)
})

test('portfolio carousel component keeps native swipe and exposes progress timing', () => {
  const componentJson = readJson('components/portfolio-carousel/portfolio-carousel.json')
  const componentJs = read('components/portfolio-carousel/portfolio-carousel.js')
  const componentWxml = read('components/portfolio-carousel/portfolio-carousel.wxml')
  const componentWxss = read('components/portfolio-carousel/portfolio-carousel.wxss')

  assert.equal(componentJson.component, true)
  assert.match(componentJs, /DEFAULT_INTERVAL_MS\s*=\s*3000/)
  assert.match(componentJs, /interval:\s*\{[\s\S]*type:\s*Number,[\s\S]*value:\s*DEFAULT_INTERVAL_MS/)
  assert.match(componentWxml, /<swiper[\s\S]*autoplay="\{\{canAutoplay\}\}"[\s\S]*circular="\{\{canAutoplay\}\}"[\s\S]*interval="\{\{safeInterval\}\}"[\s\S]*bindchange="handleSwiperChange"/)
  assert.match(componentWxml, /<swiper-item[\s\S]*wx:for="\{\{works\}\}"/)
  assert.match(componentWxml, /class="carousel-progress-bar"[\s\S]*wx:for="\{\{progressSegments\}\}"/)
  assert.match(componentWxml, /style="\{\{item\.state === 'current' \? progressStyle : ''\}\}"/)
  assert.doesNotMatch(componentWxml, /disable-touch/)
  assert.doesNotMatch(componentWxml, /indicator-dots/)
  assert.match(componentWxss, /@keyframes portfolioCarouselProgress/)
  assert.match(componentWxss, /\.carousel-progress-bar\s*\{[\s\S]*left:\s*50%;[\s\S]*right:\s*auto;[\s\S]*transform:\s*translateX\(-50%\);/)
  assert.match(componentWxss, /\.carousel-progress-segment\s*\{[\s\S]*flex:\s*none;[\s\S]*width:\s*32rpx;/)
  assert.match(componentWxss, /\.carousel-progress-segment\.current\s*\{[\s\S]*width:\s*48rpx;/)
  assert.doesNotMatch(componentWxss, /\.carousel-progress-segment\s*\{[\s\S]*flex:\s*1 1 0;/)
  assert.match(componentWxss, /\.carousel-progress-segment\.current\s+\.carousel-progress-fill\s*\{[\s\S]*animation:\s*portfolioCarouselProgress/)
  assert.match(componentWxss, /\.carousel-progress-segment\.done\s+\.carousel-progress-fill\s*\{[\s\S]*width:\s*100%;/)
})
