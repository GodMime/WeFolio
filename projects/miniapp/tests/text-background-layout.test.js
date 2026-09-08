const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')

const RENDERERS = [
  ['个人文字', 'portfolios', 'text-section', 'textSection', 'updatePresentation'],
  ['个人结构化文字', 'portfolios', 'structured-text-section', 'structuredTextSection', 'refreshPresentation'],
  ['团队文字', 'team-portfolios', 'text-section', 'config', 'updatePresentation'],
  ['团队结构化文字', 'team-portfolios', 'structured-text-section', 'config', 'updatePresentation']
]
const IMAGE_URL = 'https://example.test/background.gif'

function config(dimensions = {}) {
  return {
    content: '简短说明', blocks: [{ blockKey: 'title', type: 'TITLE', content: '简短说明' }],
    backgroundEnabled: true, backgroundWorkId: 7,
    backgroundWork: { workId: 7, mediaType: 'ANIMATION', url: IMAGE_URL, ...dimensions }
  }
}

function renderer(context, source) {
  const [, root, name, property, refresh] = context
  const path = require.resolve(`../pages/${root}/components/${name}/${name}`)
  const previous = global.Component
  let definition
  global.Component = value => { definition = value }
  try {
    delete require.cache[path]
    require(path)
  } finally {
    if (previous === undefined) delete global.Component
    else global.Component = previous
  }
  const measurements = []
  const instance = {
    properties: { [property]: source, themeMode: 'light', repairMode: false },
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch) },
    createSelectorQuery() {
      return {
        select(selector) { assert.equal(selector, '.text-background-image'); return this },
        boundingClientRect(callback) { measurements.push(callback); return this },
        exec() {}
      }
    }
  }
  for (const [name, method] of Object.entries(definition.methods)) instance[name] = method.bind(instance)
  const template = fs.readFileSync(path.replace(/\.js$/, '.wxml'), 'utf8')
  const image = template.match(/<image\b[^>]*class="text-background-image"[^>]*>/)[0]
  const handler = (image.match(/bindload="([^"]+)"/) || [])[1]
  const hasImageSource = /data-src="\{\{background.imageUrl\}\}"/.test(image)
  return {
    instance, template,
    refresh(next = source) { instance.properties[property] = next; instance[refresh]() },
    load(width, height, url = IMAGE_URL) {
      // 通过模板声明的图片加载入口触发真实组件方法，捕获漏绑事件的问题。
      if (handler) instance[handler]({ detail: { width, height }, currentTarget: { dataset: hasImageSource ? { src: url } : {} } })
    },
    measure(width) { measurements.shift()?.({ width }) }
  }
}

for (const context of RENDERERS) {
  test(`${context[0]}：缺少作品尺寸时，加载原图后按容器宽度保留完整背景高度`, () => {
    const source = config()
    const before = JSON.stringify(source)
    const view = renderer(context, source)
    view.refresh()
    view.load(1000, 1600)
    view.measure(300)
    assert.equal(view.instance.data.frameStyle, 'min-height:480px;')
    assert.equal(view.instance.data.background.imageUrl, IMAGE_URL)
    assert.equal(JSON.stringify(source), before)
    assert.match(view.template, /style="\{\{frameStyle\}\}"/)
  })

  test(`${context[0]}：横图和已有尺寸同样按真实展示宽度计算，长文字仍可撑高`, () => {
    const source = config({ width: 1600, height: 900 })
    source.content = '多行说明\n'.repeat(20)
    source.blocks[0].content = source.content
    const view = renderer(context, source)
    view.refresh()
    view.load(1600, 900)
    view.measure(320)
    assert.equal(view.instance.data.frameStyle, 'min-height:180px;')
    assert.doesNotMatch(view.instance.data.frameStyle, /(?:^|;)height:/)
    assert.equal(view.instance.properties[context[3]].content, source.content)
  })

  test(`${context[0]}：文字和主题刷新保留已加载高度，关闭或换图清除旧高度`, () => {
    const source = config()
    const view = renderer(context, source)
    view.refresh()
    view.load(1000, 1600)
    view.measure(300)
    view.instance.properties.themeMode = 'dark'
    view.refresh({ ...source, content: '修改说明' })
    assert.equal(view.instance.data.frameStyle, 'min-height:480px;')
    view.refresh({ ...source, backgroundEnabled: false })
    assert.equal(view.instance.data.frameStyle, '')
    view.refresh({ ...source, backgroundWorkId: 8,
      backgroundWork: { workId: 8, mediaType: 'IMAGE', url: 'https://example.test/next.jpg' } })
    assert.equal(view.instance.data.frameStyle, '')
    view.load(1000, 1600)
    view.measure(300)
    assert.equal(view.instance.data.frameStyle, '')
  })

  test(`${context[0]}：换图后的旧测量回调不会恢复旧背景高度`, () => {
    const source = config()
    const view = renderer(context, source)
    view.refresh()
    view.load(1000, 1600)
    view.refresh({ ...source, backgroundEnabled: false })
    view.measure(300)
    assert.equal(view.instance.data.frameStyle, '')
  })

  test(`${context[0]}：隐藏容器和无效加载尺寸不产生非法高度`, () => {
    const view = renderer(context, config())
    view.refresh()
    view.load(1000, 1600)
    view.measure(0)
    assert.equal(view.instance.data.frameStyle, 'min-height:1200rpx;')
    for (const [width, height] of [[0, 100], [100, NaN], [Infinity, 100], [100, -1]]) {
      view.load(width, height)
      assert.equal(view.instance.data.frameStyle, 'min-height:1200rpx;')
    }
  })
}
