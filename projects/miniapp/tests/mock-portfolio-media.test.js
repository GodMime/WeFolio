const test = require('node:test')
const assert = require('node:assert/strict')
const mock = require('../pages/mock/utils/mock-experience')
test('静态媒体追加四项，标签计数和未知元数据正确', () => {
  const lib = mock.MOCK_WORK_LIBRARY
  assert.equal(lib.works.length, 11)
  assert.equal(lib.total, 11)
  assert.equal(lib.tags[0].count, 9)
  assert.equal(new Set(lib.works.map(w => w.id)).size, 11)
  assert.equal(lib.works.find(w => w.id === 108).mediaUrl, 'https://cdn2.we-folio.dingchenyong.top/system/IF_YOU-BIGBANG.mp3')
  for (const id of [110, 111]) {
    const work = lib.works.find(w => w.id === id)
    assert.equal(work.thumbnailUrl, '')
    assert.equal(work.durationMs, 0)
  }
})
test('媒体选择矩阵封闭且保留原数组顺序', () => {
  const { selectMockWorksFor } = require('../pages/mock/utils/mock-work-media')
  const works = mock.MOCK_WORK_LIBRARY.works
  const ids = context => selectMockWorksFor(context, works).map(w => w.id)
  assert.deepEqual(ids('VIDEO_CAROUSEL'), [107, 110, 111])
  assert.deepEqual(ids('BACKGROUND_AUDIO'), [108])
  assert.deepEqual(ids('HYPERLINK'), [101,102,103,104,105,106,109])
  assert.equal(ids('WORK_GRID').length, 9)
  assert.equal(ids('SINGLE_WORK').length, 10)
  assert.equal(ids('CAROUSEL').length, 6)
  assert.deepEqual(ids('UNKNOWN'), [])
})
