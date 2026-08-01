const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const {
  normalizeSingleWorkTapDataset,
  normalizeWorkTapDataset,
  readPortfolioRenderEventData
} = require('../pages/portfolios/utils/portfolio-render-events')

const COMPONENT_ROOT = path.resolve(__dirname, '../pages/portfolios/components')
const COMPONENT_CASES = [
  {
    name: 'profile',
    properties: { profile: Object },
    wxml: [/class="profile-section portfolio-theme-\{\{themeMode\}\}"/, /bindtap="handlePreviewQr"/],
    wxss: [/\.profile-bio\s*\{[\s\S]*white-space:\s*pre-wrap;/]
  },
  {
    name: 'work-grid',
    properties: { componentKey: String, displayTags: Array, activeGroup: Object, switching: Boolean, showTitle: Boolean, showDescription: Boolean },
    wxml: [/class="work-grid/, /bindtap="handleDisplayTagTap"/, /bindtap="handleWorkTap"/, /wx:if="\{\{showTitle && work\.title\}\}"/, /wx:if="\{\{showDescription && work\.description\}\}"/],
    wxss: [/\.work-grid\.display-switching/, /@keyframes work-list-switch-in/, /\.work-copy\s*\{[\s\S]*min-height:\s*10rpx;[\s\S]*padding-top:\s*10rpx;/, /\.work-desc\s*\{[\s\S]*color:\s*var\(--portfolio-text-secondary\);/]
  },
  {
    name: 'work-list',
    properties: { componentKey: String, displayTags: Array, activeGroup: Object, switching: Boolean, showTitle: Boolean, showDescription: Boolean },
    wxml: [/class="work-list/, /bindtap="handleDisplayTagTap"/, /bindtap="handleWorkTap"/, /wx:if="\{\{showTitle && work\.title\}\}"/, /wx:if="\{\{showDescription && work\.description\}\}"/],
    wxss: [/\.work-list\.display-switching/, /\.work-copy\s*\{[\s\S]*min-height:\s*10rpx;[\s\S]*padding-top:\s*10rpx;/, /\.work-desc\s*\{[\s\S]*color:\s*var\(--portfolio-text-secondary\);[\s\S]*white-space:\s*pre-wrap;/]
  },
  {
    name: 'single-work',
    properties: { componentKey: String, work: Object, showTitle: Boolean, showDescription: Boolean, activeVideoKey: String, repairMode: Boolean },
    wxml: [
      /wx:if="\{\{repairMode && !work\}\}"/,
      /wx:elif="\{\{work\}\}"/,
      /作品不可用，请重新选择/,
      /图片地址缺失/,
      /图片不可用/,
      /aria-role="button"/,
      /aria-label="查看原图\{\{work\.title\}\}"/,
      /binderror="handleVideoError"/,
      /work\.isAnimation && !animationLoadFailed/,
      /src="\{\{work\.previewUrl\}\}"[\s\S]*webp="\{\{true\}\}"[\s\S]*binderror="handleAnimationLoadError"/,
      /wx:elif="\{\{work\.isAnimation\}\}"[\s\S]*src="\{\{work\.thumbnailUrl\}\}"/,
      /wx:if="\{\{showTitle && work\.title\}\}"/,
      /wx:if="\{\{showDescription && work\.description\}\}"/
    ],
    wxss: [
      /\.single-work-image\s*\{[^}]*background:\s*transparent;/,
      /\.single-work-video,\s*\.single-work-video-poster\s*\{[^}]*background:\s*var\(--portfolio-surface-muted\);/,
      /\.single-work-repair/,
      /\.single-work-play-badge/,
      /\.single-work-copy\s*\{[\s\S]*min-height:\s*16rpx;[\s\S]*padding-top:\s*16rpx;/,
      /\.single-work-description\s*\{[\s\S]*color:\s*var\(--portfolio-text-secondary\);/
    ]
  },
  {
    name: 'qr-contact',
    properties: { qrContact: Object },
    wxml: [/class="qr-section portfolio-theme-\{\{themeMode\}\}"/, /bindtap="handlePreviewQr"/],
    wxss: [/\.qr-image/]
  },
  {
    name: 'text-section',
    properties: { textSection: Object },
    wxml: [
      /class="text-section/,
      /class="text-content \{\{textSection\.fontClass\}\}"[^>]*style="\{\{textSection\.fontSizeStyle\}\}"/
    ],
    wxss: [
      /^@import "\.\.\/\.\.\/\.\.\/\.\.\/styles\/portfolio-text-typography\.wxss";/m,
      /\.text-content\s*\{[\s\S]*white-space:\s*pre-wrap;/,
      /\.text-section\.align-center/
    ]
  },
  {
    name: 'divider',
    properties: { divider: Object },
    wxml: [/class="divider-section portfolio-theme-\{\{themeMode\}\}"/, /style="\{\{divider\.style\}\}"/],
    wxss: [/\.divider-section/]
  }
]

test('text section typography applies only to the body copy', () => {
  const wxml = fs.readFileSync(
    path.join(COMPONENT_ROOT, 'text-section/text-section.wxml'),
    'utf8'
  )
  const wxss = fs.readFileSync(
    path.join(COMPONENT_ROOT, 'text-section/text-section.wxss'),
    'utf8'
  )

  assert.match(
    wxml,
    /class="text-content \{\{textSection\.fontClass\}\}"[^>]*style="\{\{textSection\.fontSizeStyle\}\}"/
  )
  assert.match(wxml, /class="component-title"/)
  assert.doesNotMatch(
    wxml,
    /class="component-title[^"]*\{\{textSection\.fontClass\}\}"/
  )
  assert.doesNotMatch(wxss, /\.text-content\s*\{[^}]*font-size:/)
})

function loadComponent(name, wxApi = {}) {
  const modulePath = path.join(COMPONENT_ROOT, name, `${name}.js`)
  const previousComponent = global.Component
  const previousWx = global.wx
  let definition
  try {
    global.Component = (value) => { definition = value }
    global.wx = wxApi
    delete require.cache[require.resolve(modulePath)]
    require(modulePath)
    return definition
  } finally {
    if (previousComponent === undefined) delete global.Component
    else global.Component = previousComponent
    if (previousWx === undefined) delete global.wx
    else global.wx = previousWx
  }
}

function createComponentHarness(definition, data = {}) {
  const events = []
  const instance = {
    data: Object.assign({}, data),
    triggerEvent(name, detail) {
      events.push({ name, detail })
    }
  }
  for (const [name, method] of Object.entries(definition.methods || {})) {
    instance[name] = method.bind(instance)
  }
  return { instance, events }
}

test('portfolio render event data prefers component detail and falls back to page dataset', () => {
  assert.deepEqual(readPortfolioRenderEventData({
    currentTarget: {
      dataset: {
        componentKey: 'old-component',
        groupKey: 'fallback-group'
      }
    },
    detail: {
      componentKey: 'new-component'
    }
  }), {
    componentKey: 'new-component',
    groupKey: 'fallback-group'
  })

  assert.deepEqual(readPortfolioRenderEventData(), {})
  assert.deepEqual(readPortfolioRenderEventData({
    currentTarget: { dataset: null },
    detail: null
  }), {})
})

test('portfolio render event normalizes list and single work media with existing priorities', () => {
  assert.deepEqual(normalizeWorkTapDataset({
    workId: '12',
    mediaType: 'VIDEO',
    mediaUrl: 'video.mp4',
    previewUrl: 'preview.mp4',
    coverUrl: 'cover.jpg',
    title: '幕后花絮'
  }), {
    workId: 12,
    mediaType: 'VIDEO',
    previewUrl: 'video.mp4',
    coverUrl: 'cover.jpg',
    title: '幕后花絮'
  })

  assert.deepEqual(normalizeWorkTapDataset({
    workId: '8',
    previewUrl: 'original.jpg',
    coverUrl: 'thumb.jpg'
  }), {
    workId: 8,
    mediaType: '',
    previewUrl: 'original.jpg',
    coverUrl: 'thumb.jpg',
    title: ''
  })

  assert.deepEqual(normalizeSingleWorkTapDataset({
    workId: '9',
    mediaType: 'IMAGE',
    mediaUrl: '',
    previewUrl: 'must-not-fallback.jpg',
    coverUrl: 'must-not-fallback-thumb.jpg',
    title: '单图'
  }), {
    workId: 9,
    mediaType: 'IMAGE',
    previewUrl: '',
    coverUrl: 'must-not-fallback-thumb.jpg',
    title: '单图'
  })
})

test('personal portfolio renderers are seven isolated four-file components with explicit properties', () => {
  for (const componentCase of COMPONENT_CASES) {
    const directory = path.join(COMPONENT_ROOT, componentCase.name)
    for (const extension of ['js', 'json', 'wxml', 'wxss']) {
      assert.equal(fs.existsSync(path.join(directory, `${componentCase.name}.${extension}`)), true)
    }

    const json = JSON.parse(fs.readFileSync(path.join(directory, `${componentCase.name}.json`), 'utf8'))
    assert.deepEqual(json, {
      component: true,
      styleIsolation: 'isolated',
      usingComponents: {}
    })

    const definition = loadComponent(componentCase.name)
    assert.equal(definition.properties.themeMode.type, String)
    for (const [propertyName, propertyType] of Object.entries(componentCase.properties)) {
      assert.equal(definition.properties[propertyName].type, propertyType)
    }

    const wxml = fs.readFileSync(path.join(directory, `${componentCase.name}.wxml`), 'utf8')
    for (const pattern of componentCase.wxml) assert.match(wxml, pattern)
    const wxss = fs.readFileSync(path.join(directory, `${componentCase.name}.wxss`), 'utf8')
    for (const pattern of componentCase.wxss) assert.match(wxss, pattern)
  }
})

test('personal portfolio interactive components emit page-compatible event details', () => {
  const profile = createComponentHarness(loadComponent('profile'), {
    profile: { wechatQrUrl: 'profile-qr.png' }
  })
  profile.instance.handlePreviewQr()
  assert.deepEqual(profile.events, [{ name: 'previewqr', detail: { qrUrl: 'profile-qr.png' } }])

  for (const name of ['work-grid', 'work-list']) {
    const harness = createComponentHarness(loadComponent(name), { componentKey: 'works-1' })
    harness.instance.handleDisplayTagTap({ currentTarget: { dataset: { groupKey: 'tag-2' } } })
    harness.instance.handleWorkTap({
      currentTarget: {
        dataset: {
          workId: 12,
          mediaType: 'VIDEO',
          mediaUrl: 'video.mp4',
          coverUrl: 'cover.jpg',
          title: '作品'
        }
      }
    })
    assert.deepEqual(harness.events, [
      {
        name: 'displaytagtap',
        detail: { componentKey: 'works-1', groupKey: 'tag-2' }
      },
      {
        name: 'worktap',
        detail: {
          workId: 12,
          mediaType: 'VIDEO',
          mediaUrl: 'video.mp4',
          coverUrl: 'cover.jpg',
          title: '作品'
        }
      }
    ])
  }

  const single = createComponentHarness(loadComponent('single-work'), { componentKey: 'single-1' })
  single.instance.handleSingleWorkTap({
    currentTarget: {
      dataset: {
        workId: 9,
        mediaType: 'IMAGE',
        mediaUrl: 'image.jpg',
        coverUrl: 'thumb.jpg',
        title: '单图'
      }
    }
  })
  const error = { errMsg: 'video decode failed' }
  single.instance.handleVideoError({ detail: error })
  assert.deepEqual(single.events, [
    {
      name: 'singleworktap',
      detail: {
        componentKey: 'single-1',
        workId: 9,
        mediaType: 'IMAGE',
        mediaUrl: 'image.jpg',
        coverUrl: 'thumb.jpg',
        title: '单图'
      }
    },
    {
      name: 'videoerror',
      detail: {
        componentKey: 'single-1',
        error
      }
    }
  ])

  const qr = createComponentHarness(loadComponent('qr-contact'), {
    qrContact: { qrUrl: 'contact-qr.png' }
  })
  qr.instance.handlePreviewQr()
  assert.deepEqual(qr.events, [{ name: 'previewqr', detail: { qrUrl: 'contact-qr.png' } }])
})

test('single work pauses native video only in the matching component scope', () => {
  const createCalls = []
  let pauseCount = 0
  const wxApi = {
    createVideoContext(id, scope) {
      createCalls.push({ id, scope })
      return {
        pause() {
          pauseCount += 1
        }
      }
    }
  }
  const definition = loadComponent('single-work', wxApi)

  const active = createComponentHarness(definition, {
    componentKey: 'single:video-1',
    activeVideoKey: 'single:video-1'
  })
  const previousWx = global.wx
  try {
    global.wx = wxApi
    active.instance.pauseVideo()
    assert.equal(createCalls.length, 1)
    assert.equal(createCalls[0].id, 'singleWorkVideo-single:video-1')
    assert.equal(createCalls[0].scope, active.instance)
    assert.equal(pauseCount, 1)

    const inactive = createComponentHarness(definition, {
      componentKey: 'single:video-2',
      activeVideoKey: 'single:video-1'
    })
    inactive.instance.pauseVideo()
    assert.equal(createCalls.length, 1)
    assert.equal(pauseCount, 1)
  } finally {
    if (previousWx === undefined) delete global.wx
    else global.wx = previousWx
  }
})

test('preview and visitor pages register and compose the same seven personal render components', () => {
  const componentNames = [
    'profile',
    'work-grid',
    'work-list',
    'single-work',
    'qr-contact',
    'text-section',
    'divider'
  ]
  const pages = [
    {
      directory: 'standard-preview',
      name: 'portfolio-standard-preview',
      repairMode: 'true'
    },
    {
      directory: 'visitor-portfolio',
      name: 'visitor-portfolio',
      repairMode: 'false'
    }
  ]

  for (const page of pages) {
    const pageRoot = path.resolve(__dirname, `../pages/portfolios/${page.directory}`)
    const json = JSON.parse(fs.readFileSync(path.join(pageRoot, `${page.name}.json`), 'utf8'))
    const wxml = fs.readFileSync(path.join(pageRoot, `${page.name}.wxml`), 'utf8')
    const wxss = fs.readFileSync(path.join(pageRoot, `${page.name}.wxss`), 'utf8')

    for (const componentName of componentNames) {
      assert.equal(
        json.usingComponents[`portfolio-${componentName}`],
        `/pages/portfolios/components/${componentName}/${componentName}`
      )
      assert.match(wxml, new RegExp(`<portfolio-${componentName}`))
    }
    assert.match(
      wxml,
      new RegExp(`<portfolio-single-work[\\s\\S]*repair-mode="\\{\\{${page.repairMode}\\}\\}"[\\s\\S]*class="portfolio-single-work-instance"`)
    )
    assert.match(wxml, /show-title="\{\{item\.showTitle\}\}"/)
    assert.match(wxml, /show-description="\{\{item\.showDescription\}\}"/)
    assert.doesNotMatch(wxss, /@import\s+"[^"]*portfolio-render-shared\.wxss"/)
    assert.doesNotMatch(wxml, /class="profile-section"/)
    assert.doesNotMatch(wxml, /class="work-grid/)
    assert.doesNotMatch(wxml, /class="work-list/)
    assert.doesNotMatch(wxml, /class="single-work/)
    assert.doesNotMatch(wxml, /class="qr-section"/)
    assert.doesNotMatch(wxml, /class="text-section/)
    assert.doesNotMatch(wxml, /class="divider-section"/)
  }
})
