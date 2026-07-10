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

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function loadCarouselComponent() {
  const componentPath = path.join(__dirname, '..', 'components/portfolio-carousel/portfolio-carousel.js')
  const previousComponent = global.Component
  let componentDefinition
  delete require.cache[require.resolve(componentPath)]
  try {
    global.Component = (definition) => {
      componentDefinition = definition
    }
    require(componentPath)
  } finally {
    if (previousComponent === undefined) {
      delete global.Component
    } else {
      global.Component = previousComponent
    }
  }
  const component = {
    data: clone(componentDefinition.data || {}),
    setData(patch) {
      Object.assign(this.data, patch)
    }
  }
  Object.keys(componentDefinition.methods || {}).forEach((methodName) => {
    component[methodName] = componentDefinition.methods[methodName].bind(component)
  })
  return component
}

test('portfolio preview, visitor, and mock preview pages render carousel through shared progress component', () => {
  const previewJson = readJson('pages/portfolios/standard-preview/portfolio-standard-preview.json')
  const visitorJson = readJson('pages/portfolios/visitor-portfolio/visitor-portfolio.json')
  const mockPreviewJson = readJson('pages/mock/portfolio-standard-preview/portfolio-standard-preview.json')
  const previewWxml = read('pages/portfolios/standard-preview/portfolio-standard-preview.wxml')
  const visitorWxml = read('pages/portfolios/visitor-portfolio/visitor-portfolio.wxml')
  const mockPreviewWxml = read('pages/mock/portfolio-standard-preview/portfolio-standard-preview.wxml')

  assert.equal(previewJson.usingComponents['portfolio-carousel'], '/components/portfolio-carousel/portfolio-carousel')
  assert.equal(visitorJson.usingComponents['portfolio-carousel'], '/components/portfolio-carousel/portfolio-carousel')
  assert.equal(mockPreviewJson.usingComponents['portfolio-carousel'], '/components/portfolio-carousel/portfolio-carousel')
  assert.match(previewWxml, /<portfolio-carousel[\s\S]*works="\{\{item\.works\}\}"[\s\S]*interval="\{\{item\.carouselIntervalMs\}\}"/)
  assert.match(visitorWxml, /<portfolio-carousel[\s\S]*works="\{\{item\.works\}\}"[\s\S]*interval="\{\{item\.carouselIntervalMs\}\}"/)
  assert.match(mockPreviewWxml, /<portfolio-carousel[\s\S]*works="\{\{item\.works\}\}"[\s\S]*interval="\{\{item\.carouselIntervalMs\}\}"/)
  assert.doesNotMatch(previewWxml, /indicator-dots/)
  assert.doesNotMatch(visitorWxml, /indicator-dots/)
  assert.doesNotMatch(mockPreviewWxml, /indicator-dots/)
})

test('portfolio carousel component renders original work image before thumbnail cover', () => {
  const componentWxml = read('components/portfolio-carousel/portfolio-carousel.wxml')

  assert.match(componentWxml, /src="\{\{item\.previewUrl \|\| item\.mediaUrl \|\| item\.coverUrl\}\}"/)
  assert.doesNotMatch(componentWxml, /src="\{\{item\.coverUrl \|\| item\.mediaUrl/)
})

test('portfolio carousel chooses the most common normalized image ratio', () => {
  const component = loadCarouselComponent()
  const works = [
    { workId: 1, aspectRatio: '1920:1080', width: 800, height: 600 },
    { workId: 2, aspectRatio: '16:9' },
    { workId: 3, aspectRatio: '3:4' }
  ]

  component.data.works = works
  component.syncCarouselState(works)

  assert.equal(component.data.carouselFrameStyle, 'height: 422rpx;')
})

test('portfolio carousel computes ratio from width and height when ratio field is missing', () => {
  const component = loadCarouselComponent()
  const works = [
    { workId: 1, width: 1000, height: 1000 },
    { workId: 2, width: 640, height: 640 },
    { workId: 3, width: 1600, height: 900 }
  ]

  component.data.works = works
  component.syncCarouselState(works)

  assert.equal(component.data.carouselFrameStyle, 'height: 750rpx;')
})

test('portfolio carousel falls back to four by three when every ratio appears once', () => {
  const component = loadCarouselComponent()
  const works = [
    { workId: 1, aspectRatio: '16:9' },
    { workId: 2, aspectRatio: '1:1' },
    { workId: 3, width: 900, height: 1200 }
  ]

  component.data.works = works
  component.syncCarouselState(works)

  assert.equal(component.data.carouselFrameStyle, 'height: 563rpx;')
})

test('portfolio carousel component keeps native swipe, isolated styles, and progress timing', () => {
  const componentJson = readJson('components/portfolio-carousel/portfolio-carousel.json')
  const componentJs = read('components/portfolio-carousel/portfolio-carousel.js')
  const componentWxml = read('components/portfolio-carousel/portfolio-carousel.wxml')
  const componentWxss = read('components/portfolio-carousel/portfolio-carousel.wxss')

  assert.equal(componentJson.component, true)
  assert.equal(componentJson.styleIsolation, 'isolated')
  assert.match(componentJs, /DEFAULT_INTERVAL_MS\s*=\s*3000/)
  assert.match(componentJs, /interval:\s*\{[\s\S]*type:\s*Number,[\s\S]*value:\s*DEFAULT_INTERVAL_MS/)
  assert.match(componentWxml, /class="portfolio-carousel"[\s\S]*style="\{\{carouselFrameStyle\}\}"/)
  assert.match(componentWxml, /<swiper[\s\S]*autoplay="\{\{canAutoplay\}\}"[\s\S]*circular="\{\{canAutoplay\}\}"[\s\S]*interval="\{\{safeInterval\}\}"[\s\S]*bindchange="handleSwiperChange"/)
  assert.match(componentWxml, /<swiper-item[\s\S]*wx:for="\{\{works\}\}"/)
  assert.match(componentWxml, /class="carousel-progress-bar"[\s\S]*wx:for="\{\{progressSegments\}\}"/)
  assert.match(componentWxml, /style="\{\{item\.state === 'current' \? progressStyle : ''\}\}"/)
  assert.doesNotMatch(componentWxml, /disable-touch/)
  assert.doesNotMatch(componentWxml, /indicator-dots/)
  assert.match(componentWxss, /\.portfolio-carousel\s*\{[\s\S]*height:\s*563rpx;/)
  assert.doesNotMatch(componentWxss, /aspect-ratio/)
  assert.doesNotMatch(componentWxss, /\.folio-carousel/)
  assert.doesNotMatch(componentWxss, /\.carousel-image/)
  assert.match(componentWxss, /@keyframes portfolioCarouselProgress/)
  assert.match(componentWxss, /\.carousel-progress-bar\s*\{[\s\S]*left:\s*50%;[\s\S]*right:\s*auto;[\s\S]*transform:\s*translateX\(-50%\);/)
  assert.match(componentWxss, /\.carousel-progress-segment\s*\{[\s\S]*flex:\s*none;[\s\S]*width:\s*32rpx;/)
  assert.match(componentWxss, /\.carousel-progress-segment\.current\s*\{[\s\S]*width:\s*48rpx;/)
  assert.doesNotMatch(componentWxss, /\.carousel-progress-segment\s*\{[\s\S]*flex:\s*1 1 0;/)
  assert.match(componentWxss, /\.carousel-progress-segment\.current\s+\.carousel-progress-fill\s*\{[\s\S]*animation:\s*portfolioCarouselProgress/)
  assert.match(componentWxss, /\.carousel-progress-segment\.done\s+\.carousel-progress-fill\s*\{[\s\S]*width:\s*100%;/)
})
