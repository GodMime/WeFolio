const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')
const { chooseAudioFiles } = require('../pages/works/utils/work-upload')

const pagePath = path.resolve(__dirname, '../pages/works/work-add/work-add.js')

// 执行真实页面，只有微信和网络边界使用替身。
function loadPage(wxApi, logger = { warn() {} }) {
  let page
  const localRequire = createRequire(pagePath)
  vm.runInNewContext(fs.readFileSync(pagePath, 'utf8'), {
    Page(value) { page = value }, wx: wxApi, console: logger,
    require(name) {
      if (name.endsWith('/request')) return { request() { assert.fail('选择音频期间不得申请票据') } }
      return localRequire(name)
    }
  })
  page.setData = (patch) => Object.assign(page.data, patch)
  return page
}

test('聊天音频选择复用添加页，读取时长、默认封面且不申请票据', async () => {
  let destroyed = 0
  const page = loadPage({
    chooseMessageFile(options) {
      assert.equal(options.count, 9)
      assert.equal(options.type, 'file')
      assert.deepEqual(Array.from(options.extension), ['mp3', 'm4a', 'aac', 'wav'])
      options.success({ tempFiles: [{ path: 'wxfile://song', name: 'song.mp3', size: 1024 }] })
    },
    createInnerAudioContext() {
      return {
        duration: 12,
        onCanplay(fn) { this.ready = fn }, onError() {},
        set src(value) { this.ready() },
        destroy() { destroyed++ }
      }
    },
    showToast(options) { assert.fail(options.title) }
  })
  await page.handleChooseMedia({ currentTarget: { dataset: { mediaType: 'AUDIO' } } })
  assert.equal(page.data.files.length, 1)
  assert.equal(page.data.files[0].durationMs, 12000)
  assert.equal(page.data.files[0].mediaType, 'AUDIO')
  assert.match(page.data.files[0].audioCoverUrl, /default-audio-cover-v1-200kb.png$/)
  assert.equal(page.data.files[0].coverPath, '')
  assert.equal(destroyed, 1)
  assert.equal(page.data.choosing, false)
  page.handleOpenFileEditor({ currentTarget: { dataset: { index: 0 } } })
  assert.equal(page.data.editForm.isAudio, true)
  assert.equal(page.data.editForm.audioCoverUrl, page.data.files[0].audioCoverUrl)
})

test('无效音频和页面退出均不能把文件加入上传列表', async () => {
  for (const invalid of [true, false]) {
    let destroyed = 0
    const page = loadPage({
      chooseMessageFile(options) {
        options.success({ tempFiles: [{ path: 'wxfile://song', name: invalid ? 'bad.exe' : 'a.mp3', size: 1024 }] })
      },
      createInnerAudioContext() {
        assert.equal(invalid, false)
        return { duration: 0, onCanplay() {}, onError() {}, destroy() { destroyed++ } }
      },
      showToast() {}
    })
    const pending = page.handleChooseMedia({ currentTarget: { dataset: { mediaType: 'AUDIO' } } })
    if (!invalid) { await new Promise(setImmediate); page.onUnload() }
    await pending
    assert.equal(page.data.files.length, 0)
    assert.equal(destroyed, invalid ? 0 : 1)
  }
})

test('聊天文件选择不可用时提示升级微信，并释放选择状态', async () => {
  const toasts = []
  const page = loadPage({ showToast(options) { toasts.push(options.title) } })
  await page.handleChooseMedia({ currentTarget: { dataset: { mediaType: 'AUDIO' } } })
  assert.deepEqual(toasts, ['当前微信版本不支持选择文件，请升级微信'])
  assert.equal(page.data.choosing, false)
  assert.equal(page.data.files.length, 0)
})

test('聊天文件选择失败保留微信原始错误及错误码，隐私配置和授权错误分别提示', async () => {
  const cases = [
    [{ errMsg: 'chooseMessageFile:fail api scope is not declared in the privacy agreement', errno: 112 }, '暂时无法选择音频，请联系管理员'],
    [{ errMsg: 'chooseMessageFile:fail appid privacy api banned' }, '暂时无法选择音频，请联系管理员'],
    [{ errMsg: 'chooseMessageFile:fail privacy permission is not authorized', errno: 104 }, '请同意隐私保护指引后选择音频'],
    [{ errMsg: 'chooseMessageFile:fail auth deny', errno: 103 }, '请同意隐私保护指引后选择音频'],
    [{ errMsg: 'chooseMessageFile:fail native picker unavailable', errno: 1001 }, '选择音频失败，请重试']
  ]
  for (const [nativeError, message] of cases) {
    await assert.rejects(chooseAudioFiles(3, {
      wxApi: { chooseMessageFile(options) { options.fail(nativeError) } }
    }), (error) => {
      assert.equal(error.message, message)
      assert.equal(error.errMsg, nativeError.errMsg)
      assert.equal(error.errno, nativeError.errno)
      return true
    })
  }
})

test('音频选择失败记录原始原因、保留已有作品并允许再次选择', async () => {
  const toasts = []
  const diagnostics = []
  const nativeError = { errMsg: 'chooseMessageFile:fail api scope is not declared in the privacy agreement', errno: 112 }
  let attempts = 0
  const page = loadPage({
    chooseMessageFile(options) {
      attempts++
      assert.equal(options.count, 8)
      if (attempts === 1) options.fail(nativeError)
      else options.success({ tempFiles: [{ path: 'wxfile://song', name: 'song.mp3', size: 1024 }] })
    },
    createInnerAudioContext() {
      return { duration: 2, onCanplay(fn) { this.ready = fn }, onError() {}, set src(value) { this.ready() }, destroy() {} }
    },
    showToast(options) { toasts.push(options.title) }
  }, { warn(...args) { diagnostics.push(args) } })
  const existingFile = { id: 'existing', mediaType: 'IMAGE', title: '已有作品', size: 1024 }
  page.data.files = [existingFile]
  const event = { currentTarget: { dataset: { mediaType: 'AUDIO' } } }
  await page.handleChooseMedia(event)
  assert.deepEqual(toasts, ['暂时无法选择音频，请联系管理员'])
  assert.equal(page.data.files.length, 1)
  assert.equal(page.data.files[0], existingFile)
  assert.equal(page.data.choosing, false)
  assert.equal(diagnostics.length, 1)
  assert.equal(diagnostics[0][1].errMsg, nativeError.errMsg)
  assert.equal(diagnostics[0][1].errno, nativeError.errno)
  await page.handleChooseMedia(event)
  assert.equal(page.data.files.length, 2)
  assert.equal(page.data.files[1].mediaType, 'AUDIO')
  assert.equal(page.data.choosing, false)
})

test('取消聊天文件选择保持安静且不修改上传列表', async () => {
  const page = loadPage({
    chooseMessageFile(options) { options.fail({ errMsg: 'chooseMessageFile:fail cancel' }) },
    showToast(options) { assert.fail(options.title) }
  }, { warn() { assert.fail('用户取消不应记录为故障') } })
  await page.handleChooseMedia({ currentTarget: { dataset: { mediaType: 'AUDIO' } } })
  assert.equal(page.data.files.length, 0)
  assert.equal(page.data.choosing, false)
})
