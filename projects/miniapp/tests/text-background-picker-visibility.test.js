const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function createEditor(directory, config, structured) {
  const target = require.resolve(`../pages/${directory}/components/text-background-editor/text-background-editor`)
  let definition
  const previous = global.Component
  global.Component = value => { definition = value }
  delete require.cache[target]
  try { require(target) } finally {
    if (previous) global.Component = previous
    else delete global.Component
  }
  return Object.assign({}, definition.methods, {
    definition,
    data: JSON.parse(JSON.stringify(definition.data)),
    properties: { config, structured },
    events: [],
    setData(patch) { Object.assign(this.data, patch) },
    triggerEvent(name, detail) { this.events.push({ name, detail }) }
  })
}

for (const directory of ['portfolios', 'team-portfolios']) {
  for (const structured of [false, true]) {
    test(`${directory} ${structured ? '结构化' : '普通'}背景入口再次点击收起列表，不重复请求或改写配置`, () => {
      const config = { backgroundEnabled: true, backgroundWorkId: 7, backgroundMemberUserId: 9,
        backgroundTreatment: 'GRADIENT', verticalAlignment: 'BOTTOM' }
      const before = JSON.stringify(config)
      const editor = createEditor(directory, config, structured)
      editor.handleOpenPicker()
      assert.equal(editor.data.pickerOpen, true)
      assert.equal(editor.events.filter(event => event.name === 'request').length, 1)
      editor.handleOpenPicker()
      assert.equal(editor.data.pickerOpen, false)
      assert.equal(editor.events.filter(event => event.name === 'request').length, 1)
      assert.equal(JSON.stringify(config), before)
      assert.equal(editor.events.some(event => event.name === 'change'), false)
    })
  }

  test(`${directory} 作品列表紧随选择入口，展开反馈不被后续背景配置推到屏幕外`, () => {
    const template = fs.readFileSync(path.join(__dirname, `../pages/${directory}/components/text-background-editor/text-background-editor.wxml`), 'utf8')
    const selection = template.indexOf('class="background-selection"')
    const picker = template.indexOf('class="background-picker"')
    const treatment = template.indexOf('>背景处理</view>')
    assert.ok(selection >= 0 && picker > selection && treatment > picker,
      '作品列表应直接展开在入口下方，不应位于背景处理和上下对齐之后')
    assert.match(template, /aria-expanded="\{\{pickerOpen\}\}"/)
    assert.match(template, /pickerOpen\s*\?\s*'收起作品列表'/)
    assert.match(template.slice(picker, treatment), /<block wx:if="\{\{!pickerOpen\}\}">/,
      '展开选择时暂时隐藏背景处理及对齐配置，收起后恢复')
  })
}

for (const directory of ['portfolios', 'team-portfolios']) {
  for (const structured of [false, true]) {
    test(`${directory} ${structured ? '结构化' : '普通'}背景可选视频且保留播放地址与封面`, () => {
      const editor = createEditor(directory, { backgroundEnabled: true, backgroundWorkId: 1,
        backgroundInvalid: true, backgroundWork: { workId: 1, mediaType: 'IMAGE', url: 'old.jpg' } }, structured)
      editor.properties.options = [
        { id: 7, memberUserId: 9, mediaType: 'VIDEO', url: 'video.mp4', posterUrl: 'poster.jpg', width: 1920, height: 1080 },
        { id: 8, memberUserId: 9, mediaType: 'VIDEO', url: 'no-poster.mp4' },
        { id: 9, memberUserId: 9, mediaType: 'AUDIO', url: 'audio.mp3' }
      ]
      editor.handleSelect({ currentTarget: { dataset: { id: 7 } } })
      const selected = editor.events.find(event => event.name === 'change').detail
      assert.equal(selected.backgroundWorkId, 7)
      assert.equal(selected.backgroundWork.url, 'video.mp4')
      assert.equal(selected.backgroundWork.posterUrl, 'poster.jpg')
      assert.equal(selected.backgroundInvalid, false)
      if (directory === 'team-portfolios') assert.equal(selected.backgroundMemberUserId, 9)
      editor.events = []
      editor.handleSelect({ currentTarget: { dataset: { id: 8 } } })
      assert.equal(editor.events.find(event => event.name === 'change').detail.backgroundWork.url, 'no-poster.mp4')
      editor.events = []
      editor.handleSelect({ currentTarget: { dataset: { id: 9 } } })
      assert.equal(editor.events.length, 0)
      assert.ok(editor.data.selectionError)
    })
  }

  test(`${directory} 恢复视频和翻页后同步更新封面，无封面时显示占位且保留候选源数据`, () => {
    const config = { backgroundWork: { workId: 7, mediaType: 'VIDEO', url: 'video.mp4', posterUrl: 'poster.jpg' } }
    const options = [{ id: 7, mediaType: 'VIDEO', url: 'video.mp4', posterUrl: 'poster.jpg' },
      { id: 8, mediaType: 'VIDEO', url: 'no-poster.mp4' }, { id: 9, mediaType: 'ANIMATION', url: 'animation.gif' }]
    const original = JSON.stringify({ config, options })
    const editor = createEditor(directory, config, false)
    const observer = editor.definition.observers['config, options']
    observer.call(editor, config, options)
    assert.equal(editor.data.selectedWorkPreview.thumbnailUrl, 'poster.jpg')
    assert.deepEqual(editor.data.displayOptions.map(work => work.thumbnailUrl), ['poster.jpg', '', 'animation.gif'])
    assert.equal(editor.data.displayOptions[1].isVideo, true)
    observer.call(editor, { backgroundWork: options[1] }, [])
    assert.equal(editor.data.selectedWorkPreview.thumbnailUrl, '')
    assert.equal(editor.data.selectedWorkPreview.isVideo, true)
    assert.deepEqual(editor.data.displayOptions, [])
    assert.equal(JSON.stringify({ config, options }), original)
  })

  test(`${directory} 背景选择器展示安全缩略图和视频播放说明`, () => {
    const template = fs.readFileSync(path.join(__dirname, `../pages/${directory}/components/text-background-editor/text-background-editor.wxml`), 'utf8')
    assert.match(template, /src="\{\{selectedWorkPreview.thumbnailUrl\}\}"/)
    assert.match(template, /src="\{\{item.thumbnailUrl\}\}"/)
    assert.doesNotMatch(template, /src="\{\{(?:config.backgroundWork|item).url\}\}"/)
    assert.match(template, /background-thumbnail-placeholder/)
    assert.match(template, /视频背景自动静音循环播放/)
    assert.match(template, /图片、动图或视频/)
  })
}
