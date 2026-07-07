const assert = require('node:assert/strict')
const test = require('node:test')

const {
  buildWorkTagPayload,
  buildWorkFieldCounters,
  applyUnifiedWorkTags,
  buildUnifiedWorkTagItems,
  buildUnifiedWorkTagNames,
  buildWorkTagOptionListHeight,
  buildWorkUpdatePayload,
  buildWorkTagDeleteBlockedMessage,
  buildWorkTagPickerOptions,
  createWorkTagForm,
  normalizeWorkDetail,
  normalizeWorkList,
  normalizeWorkTags,
  validateWorkTagForm,
  validateWorkForm
} = require('../utils/works')

test('normalizes work list for page rendering', () => {
  const result = normalizeWorkList({
    page: 1,
    pageSize: 20,
    total: 1,
    hasMore: false,
    summary: {
      totalCount: 36,
      imageCount: 30,
      videoCount: 6
    },
    tags: [
      { id: null, name: '全部', count: 36, active: true },
      { id: 12, name: '高端婚礼', color: '#2d5f9a', count: 8 }
    ],
    works: [
      {
        id: 9,
        mediaType: 'VIDEO',
        title: '草坪婚礼快剪',
        coverUrl: '',
        mediaUrl: 'https://cos.example.com/video.mp4',
        durationMs: 125000,
        fileSize: 10485760,
        referenceCount: 3,
        tags: [{ id: 12, name: '高端婚礼', color: '#2d5f9a' }]
      }
    ]
  })

  assert.equal(result.summary.totalText, '全部 36')
  assert.equal(result.summary.videoText, '视频 6')
  assert.equal(result.tags[1].labelText, '高端婚礼 8')
  assert.equal(result.tags[1].style, 'color: #2d5f9a; background: #e5effb; border-color: #bfd7f4;')
  assert.equal(result.tags[1].activeStyle, 'color: #ffffff; background: #2d5f9a; border-color: #2d5f9a;')
  assert.equal(result.tags[1].deleteStyle, 'color: #ffffff; background: #2d5f9a;')
  assert.deepEqual(result.filterTags.map((item) => item.labelText), ['全部 36', '高端婚礼 8'])
  assert.equal(result.filterTags[0].activeStyle, 'color: #40546a; background: #eef4f7; border-color: #cbd8e5;')
  assert.equal(result.works[0].typeText, '视频')
  assert.equal(result.works[0].durationText, '02:05')
  assert.equal(result.works[0].fileSizeText, '10.0MB')
  assert.equal(result.works[0].referenceText, '引用 3 次')
  assert.equal(result.works[0].coverUrl, '')
  assert.equal(result.works[0].hasCover, false)
})

test('builds and validates work tag form payload', () => {
  const form = createWorkTagForm({
    id: 31,
    name: ' 高端婚礼 ',
    color: '#2D5F9A'
  })

  assert.deepEqual(form, {
    id: 31,
    name: '高端婚礼',
    color: '#2d5f9a'
  })
  assert.deepEqual(buildWorkTagPayload(form), {
    name: '高端婚礼',
    color: '#2d5f9a'
  })
  assert.equal(validateWorkTagForm(form, [{ id: 12, name: '户外仪式' }], 31).valid, true)
})

test('rejects invalid work tag forms', () => {
  const fullTags = Array.from({ length: 10 }, (_, index) => ({
    id: index + 1,
    name: `标签${index + 1}`
  }))

  assert.equal(validateWorkTagForm({ name: '', color: '#0f766e' }).message, '标签名称不能为空')
  assert.equal(validateWorkTagForm({ name: '一'.repeat(11), color: '#0f766e' }).message, '单个标签不能超过 10 个字')
  assert.equal(validateWorkTagForm({ name: '高端婚礼', color: '#123456' }).message, '请选择有效的标签颜色')
  assert.equal(validateWorkTagForm({ name: '高端婚礼', color: '#0f766e' }, [{ id: 12, name: '高端婚礼' }]).message, '标签不能重复')
  assert.equal(validateWorkTagForm({ name: '新标签', color: '#0f766e' }, fullTags).message, '标签最多保留 10 个')
})

test('builds occupied tag delete message', () => {
  assert.equal(
    buildWorkTagDeleteBlockedMessage({ name: '高端婚礼', count: 8 }),
    '标签「高端婚礼」下还有 8 个作品，先移除这些作品的标签后再删除。'
  )
})

test('normalizes work tag response for add-page picker', () => {
  const tags = normalizeWorkTags({
    tags: [
      { id: null, name: '全部', count: 36 },
      { id: 12, name: ' 高端婚礼 ', color: '#2D5F9A', count: 8 },
      { id: 13, name: '户外仪式', color: '#123456', count: 0 }
    ]
  })

  assert.deepEqual(tags.map((item) => item.name), ['高端婚礼', '户外仪式'])
  assert.equal(tags[0].color, '#2d5f9a')
  assert.equal(tags[0].style, 'color: #2d5f9a; background: #e5effb; border-color: #bfd7f4;')
  assert.equal(tags[1].color, '')
})

test('builds picker selection and applies unified tags to chosen files', () => {
  const options = buildWorkTagPickerOptions([
    { id: 12, name: '高端婚礼', color: '#2d5f9a' },
    { id: 13, name: '户外仪式', color: '#0f766e' }
  ], [' 户外仪式 '])

  assert.equal(options[0].selected, false)
  assert.equal(options[1].selected, true)

  const selectedNames = buildUnifiedWorkTagNames([
    Object.assign({}, options[0], { selected: true }),
    Object.assign({}, options[1], { selected: true }),
    { id: 14, name: '高端婚礼', selected: true }
  ])
  const selectedItems = buildUnifiedWorkTagItems([
    Object.assign({}, options[0], { selected: true }),
    Object.assign({}, options[1], { selected: true }),
    { id: 14, name: '高端婚礼', selected: true }
  ])

  assert.deepEqual(selectedNames, ['高端婚礼', '户外仪式'])
  assert.deepEqual(selectedItems.map((item) => ({ name: item.name, style: item.style })), [
    {
      name: '高端婚礼',
      style: 'color: #2d5f9a; background: #e5effb; border-color: #bfd7f4;'
    },
    {
      name: '户外仪式',
      style: 'color: #0f766e; background: #dcf7f1; border-color: #a7eadc;'
    }
  ])
  assert.deepEqual(
    applyUnifiedWorkTags([{ id: 'a', tags: ['旧标签'] }, { id: 'b' }], selectedItems),
    [
      { id: 'a', tags: ['高端婚礼', '户外仪式'] },
      { id: 'b', tags: ['高端婚礼', '户外仪式'] }
    ]
  )
})

test('builds adaptive work tag picker list height from tag count', () => {
  assert.equal(buildWorkTagOptionListHeight([]), 0)
  assert.equal(buildWorkTagOptionListHeight([{ id: 1, name: '户外仪式' }]), 80)
  assert.equal(buildWorkTagOptionListHeight([
    { id: 1, name: '户外仪式' },
    { id: 2, name: '室内迎宾' },
    { id: 3, name: '晚宴快剪' }
  ]), 224)
  assert.equal(buildWorkTagOptionListHeight(Array.from({ length: 10 }, (_, index) => ({
    id: index + 1,
    name: `标签${index + 1}`
  }))), 520)
})

test('normalizes empty work list with safe defaults', () => {
  const result = normalizeWorkList({})

  assert.equal(result.page, 1)
  assert.equal(result.pageSize, 20)
  assert.equal(result.total, 0)
  assert.deepEqual(result.works, [])
  assert.equal(result.empty, true)
  assert.equal(result.summary.totalText, '全部 0')
  assert.equal(result.filterTags[0].labelText, '全部 0')
})

test('limits work filter tag labels to ten display characters', () => {
  const result = normalizeWorkList({
    summary: {
      totalCount: 0
    },
    tags: [
      { id: 12, name: '超长作品标签名称测试', count: 123 }
    ]
  })

  assert.equal(result.filterTags[1].labelText, '超长作品标签名称测试')
  assert.equal(Array.from(result.filterTags[1].labelText).length, 10)
})

test('normalizes unreferenced work copy for compact list pills', () => {
  const result = normalizeWorkList({
    works: [
      {
        id: 10,
        title: '中式婚礼仪式',
        referenceCount: 0
      }
    ]
  })

  assert.equal(result.works[0].referenceText, '未引用')
})

test('normalizes work detail references and counters', () => {
  const result = normalizeWorkDetail({
    work: {
      id: 9,
      mediaType: 'IMAGE',
      title: '海边仪式',
      description: ' 夕阳时段 ',
      coverUrl: 'https://cos.example.com/a.jpg',
      tags: [{ name: '户外仪式' }]
    },
    references: [
      { id: 1, portfolioId: 88, componentPath: 'components[0]', valid: true }
    ]
  })

  assert.equal(result.work.typeText, '图片')
  assert.equal(result.work.description, '夕阳时段')
  assert.equal(result.work.tagText, '户外仪式')
  assert.equal(result.references[0].titleText, '作品集 #88')
  assert.equal(result.referenceSummaryText, '已被 1 个作品集引用')
})

test('builds and validates work update payload', () => {
  const payload = buildWorkUpdatePayload({
    title: ' 海边仪式 ',
    description: ' 夕阳时段 ',
    tagInput: ' 户外仪式 ',
    tags: ['高端婚礼']
  })

  assert.deepEqual(payload, {
    title: '海边仪式',
    description: '夕阳时段'
  })
  assert.equal(validateWorkForm(payload).valid, true)
  assert.equal(buildWorkFieldCounters(payload).title, '4/30')
})

test('builds work update payload with selected cover frame time', () => {
  const payload = buildWorkUpdatePayload({
    title: ' 片头快剪 ',
    description: ' 晚宴开场 ',
    coverFrameTimeMs: 5200,
    width: 1080,
    height: 1920
  })

  assert.deepEqual(payload, {
    title: '片头快剪',
    description: '晚宴开场',
    coverFrameTimeMs: 5200,
    width: 1080,
    height: 1920
  })
})

test('builds work update payload without cover frame when cover is unchanged', () => {
  const payload = buildWorkUpdatePayload({
    title: ' 片头快剪 ',
    description: ' 晚宴开场 '
  })

  assert.deepEqual(payload, {
    title: '片头快剪',
    description: '晚宴开场'
  })
})

test('rejects invalid work form fields', () => {
  assert.equal(validateWorkForm({ title: '' }).message, '作品标题不能为空')
  assert.equal(validateWorkForm({ title: '一'.repeat(31) }).message, '作品标题不能超过 30 字')
  assert.equal(validateWorkForm({ title: '草坪婚礼', description: '一'.repeat(1001) }).message, '作品说明不能超过 1000 字')
})
