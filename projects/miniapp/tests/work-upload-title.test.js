const assert = require('node:assert/strict')
const test = require('node:test')
const {
  normalizeChosenMediaFiles,
  buildUploadCompletePayload,
  buildUploadTicketPayload
} = require('../pages/works/utils/work-upload')

const selectionDate = new Date(2026, 8, 16, 0, 5, 7)

test('临时路径生成类型、本地日期时间和两位序号组成的默认标题', () => {
  const files = normalizeChosenMediaFiles([
    { tempFilePath: 'wxfile://tmp_b2ae2172342688990011223344556677.mp4', fileType: 'video' },
    { tempFilePath: 'wxfile://24196d18e79649d9a288112233445566.jpg', fileType: 'image' },
    { path: 'wxfile://tmp_audio.m4a', name: '  ', fileType: 'audio' },
    { tempFilePath: 'wxfile://tmp/photo.jpg', fileType: 'image' }
  ], { now: selectionDate })

  assert.deepEqual(files.map(file => file.title), [
    '视频 2026-09-16 00:05:07 01', '图片 2026-09-16 00:05:07 02', '音频 2026-09-16 00:05:07 03', '图片 2026-09-16 00:05:07 04'
  ])
  assert.ok(files.every(file => file.title.length <= 30))
  assert.equal(files[0].fileName, 'tmp_b2ae2172342688990011223344556677.mp4')
  assert.equal(files[2].mimeType, 'audio/mp4')
  assert.equal(buildUploadTicketPayload(files).files[0].fileName, files[0].fileName)
})

test('明确提供的原始文件名去扩展名并保留三十字上限，不按名字形状误判临时文件', () => {
  const longName = '婚礼现场'.repeat(10)
  const names = [' 婚礼.精选.mp4 ', '歌曲.M4A', `${longName}.jpg`, 'tmp_我的婚礼.mp4', '24196d18e79649d9a288112233445566.mp4']
  const files = normalizeChosenMediaFiles(names.map(name => ({
    name, tempFilePath: 'wxfile://tmp_random', fileType: 'image'
  })), { now: selectionDate })

  assert.deepEqual(files.map(file => file.title), [
    '婚礼.精选', '歌曲', longName.slice(0, 30), 'tmp_我的婚礼', '24196d18e79649d9a288112233445566'.slice(0, 30)
  ])
})

test('追加选择可延续序号，日期和时间各部分补零', () => {
  const files = normalizeChosenMediaFiles([
    { tempFilePath: 'wxfile://tmp_one.mp4', fileType: 'video' },
    { tempFilePath: 'wxfile://tmp_two.mp4', fileType: 'video' }
  ], { now: new Date(2026, 0, 2, 0, 5), startIndex: 2 })

  assert.deepEqual(files.map(file => file.title), ['视频 2026-01-02 00:05:00 03', '视频 2026-01-02 00:05:00 04'])
})

test('同一天不同时间重新进入页面上传，序号从一开始也能区分两批作品', () => {
  const rawFiles = [{ tempFilePath: 'wxfile://tmp_video.mp4', fileType: 'video' }]
  const [first] = normalizeChosenMediaFiles(rawFiles, { now: new Date(2026, 8, 16, 17, 6, 32) })
  const [second] = normalizeChosenMediaFiles(rawFiles, { now: new Date(2026, 8, 16, 17, 6, 33) })

  assert.equal(first.title, '视频 2026-09-16 17:06:32 01')
  assert.equal(second.title, '视频 2026-09-16 17:06:33 01')
  assert.notEqual(first.title, second.title)
})

test('上传确认保存生成的标题，并尊重用户后续编辑的标题', () => {
  const files = normalizeChosenMediaFiles([
    { tempFilePath: 'wxfile://tmp_one.mp4', fileType: 'video' },
    { tempFilePath: 'wxfile://tmp_two.jpg', fileType: 'image' }
  ], { now: selectionDate }).map((file, index) => ({ ...file, taskId: index + 1 }))
  files[1].title = '  草坪婚礼  '

  assert.deepEqual(buildUploadCompletePayload(files).items.map(file => file.title), [
    '视频 2026-09-16 00:05:07 01', '草坪婚礼'
  ])
})
