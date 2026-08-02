const assert = require('node:assert/strict')
const test = require('node:test')

const {
  buildWorkTagPayload,
  buildWorkFieldCounters,
  buildWorkEditSelectedTagIds,
  buildWorkEditTagOptions,
  applyUnifiedWorkTags,
  buildUnifiedWorkTagItems,
  buildUnifiedWorkTagNames,
  buildWorkUpdatePayload,
  buildWorkTagDeleteBlockedMessage,
  buildWorkTagOptionListHeight,
  buildWorkTagPickerOptions,
  createWorkTagForm,
  normalizeWorkDetail,
  normalizeWorkList,
  normalizeWorkTags,
  validateWorkTagForm,
  validateWorkForm
} = require('../pages/works/utils/works')

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
        aspectRatio: '16:9',
        auditStatus: 'PASSED',
        referenceCount: 3,
        tags: [{ id: 12, name: '高端婚礼', color: '#2d5f9a' }]
      }
    ]
  })

  assert.deepEqual(result.summary, {
    totalCount: 36
  })
  assert.equal(result.tags[1].labelText, '高端婚礼 8')
  assert.equal(result.tags[1].style, 'color: #2d5f9a; background: #e5effb; border-color: #bfd7f4;')
  assert.equal(result.tags[1].filterStyle, 'color: #2d5f9a; background: #ffffff; border-color: #2d5f9a;')
  assert.equal(result.tags[1].activeStyle, 'color: #ffffff; background: #2d5f9a; border-color: #2d5f9a;')
  assert.equal(result.tags[1].deleteStyle, 'color: #ffffff; background: #2d5f9a;')
  assert.deepEqual(result.filterTags.map((item) => item.labelText), ['全部 36', '高端婚礼 8'])
  assert.equal(result.filterTags[0].activeStyle, 'color: #ffffff; background: #212529; border-color: #212529;')
  assert.equal(result.works[0].typeText, '视频')
  assert.equal(result.works[0].durationText, '02:05')
  assert.equal(result.works[0].fileSizeText, '10.0MB')
  assert.equal(result.works[0].aspectRatio, '16:9')
  assert.equal(result.works[0].aspectRatioText, '16:9')
  assert.equal(result.works[0].auditStatus, 'PASSED')
  assert.equal(result.works[0].auditStatusText, '审核通过')
  assert.equal(result.works[0].auditStatusTone, 'passed')
  assert.equal(result.works[0].referenceText, '引用 3 次')
  assert.equal(result.works[0].coverUrl, '')
  assert.equal(result.works[0].hasCover, false)
})

test('normalizes work audit status for list badges', () => {
  const result = normalizeWorkList({
    works: [
      { id: 1, title: '待审作品', auditStatus: 'PENDING' },
      { id: 2, title: '复核作品', auditStatus: 'REVIEW_REQUIRED', auditRejectReason: ' 疑似图片风险 ' },
      { id: 3, title: '自定义文案', auditStatus: 'PASSED', auditStatusText: '已通过平台审核' },
      { id: 4, title: '违规作品', auditStatus: 'REJECTED', auditRejectReason: '确认违规内容' },
      { id: 5, title: '失败作品', auditStatus: 'FAILED', auditRejectReason: '审核服务异常' }
    ]
  })

  assert.deepEqual(
    result.works.map((work) => ({
      status: work.auditStatus,
      text: work.auditStatusText,
      tone: work.auditStatusTone,
      rejectReason: work.auditRejectReason,
      showRejectReason: work.showAuditRejectReason
    })),
    [
      { status: 'PENDING', text: '未审核', tone: 'pending', rejectReason: '', showRejectReason: false },
      { status: 'REVIEW_REQUIRED', text: '疑似违规', tone: 'review', rejectReason: '疑似图片风险', showRejectReason: true },
      { status: 'PASSED', text: '已通过平台审核', tone: 'passed', rejectReason: '', showRejectReason: false },
      { status: 'REJECTED', text: '确认违规', tone: 'rejected', rejectReason: '确认违规内容', showRejectReason: true },
      { status: 'FAILED', text: '审核失败', tone: 'failed', rejectReason: '审核服务异常', showRejectReason: true }
    ]
  )
})

test('normalizes all audit reasons with legacy fallback and a maximum of twenty', () => {
  const extraReasons = Array.from({ length: 22 }, (_, index) => ({
    code: `RISK_${index}`,
    message: ` 风险原因 ${index} `
  }))
  const result = normalizeWorkList({
    works: [
      {
        id: 21,
        auditStatus: 'REJECTED',
        auditRejectReason: '旧版主原因',
        auditReasons: [
          { code: 'PORN_CONTENT', message: ' 色情或低俗内容 ' },
          { code: 'ADVERTISING_CONTENT', message: '广告或引流信息' },
          { code: 'PORN_CONTENT', message: '重复原因应忽略' },
          { code: 'EMPTY', message: '   ' },
          null,
          ...extraReasons
        ]
      },
      {
        id: 22,
        auditStatus: 'FAILED',
        auditRejectReason: ' 旧客户端审核失败原因 '
      },
      {
        id: 23,
        auditStatus: 'PASSED',
        auditRejectReason: '不应展示',
        auditReasons: [{ code: 'PORN_CONTENT', message: '不应展示' }]
      }
    ]
  })

  assert.equal(result.works[0].auditReasons.length, 20)
  assert.deepEqual(result.works[0].auditReasons.slice(0, 2), [
    { code: 'PORN_CONTENT', message: '色情或低俗内容' },
    { code: 'ADVERTISING_CONTENT', message: '广告或引流信息' }
  ])
  assert.deepEqual(result.works[1].auditReasons, [
    { code: 'LEGACY_PRIMARY', message: '旧客户端审核失败原因' }
  ])
  assert.equal(result.works[1].showAuditRejectReason, true)
  assert.deepEqual(result.works[2].auditReasons, [])
  assert.equal(result.works[2].showAuditRejectReason, false)
})

test('normalizes backend audit rounds and resubmit eligibility without client-side inference', () => {
  const result = normalizeWorkList({
    works: [{
      id: 6,
      title: '可重新审核作品',
      auditStatus: 'REJECTED',
      auditRejectReason: '作品内容需要调整',
      auditRound: 1,
      maxAuditRounds: 3,
      remainingAuditResubmitCount: 2,
      canResubmitAudit: true
    }]
  })

  assert.deepEqual({
    auditRound: result.works[0].auditRound,
    maxAuditRounds: result.works[0].maxAuditRounds,
    remainingAuditResubmitCount: result.works[0].remainingAuditResubmitCount,
    canResubmitAudit: result.works[0].canResubmitAudit,
    auditRoundText: result.works[0].auditRoundText
  }, {
    auditRound: 1,
    maxAuditRounds: 3,
    remainingAuditResubmitCount: 2,
    canResubmitAudit: true,
    auditRoundText: '第 1/3 轮 · 还可重审 2 次'
  })
})

test('normalizes missing work aspect ratio as empty display text', () => {
  const result = normalizeWorkList({
    works: [
      {
        id: 10,
        mediaType: 'IMAGE',
        title: '宴会照片',
        aspectRatio: '   '
      }
    ]
  })

  assert.equal(result.works[0].aspectRatio, '')
  assert.equal(result.works[0].aspectRatioText, '--')
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
  assert.equal(tags[0].filterStyle, 'color: #2d5f9a; background: #ffffff; border-color: #2d5f9a;')
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

test('builds a non-zero bounded tag picker height for Skyline scroll view', () => {
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

test('builds editable work tag options from all tags and current bindings', () => {
  const options = buildWorkEditTagOptions(
    [
      { id: 1, name: '婚礼', color: '#ff6b81' },
      { id: 2, name: '晚宴', color: '#4dabf7' },
      { id: null, name: '全部', color: '#999999' }
    ],
    [{ id: 2, name: '晚宴', color: '#4dabf7' }],
    0
  )

  assert.deepEqual(options.map((item) => ({
    id: item.id,
    selected: item.selected,
    initiallySelected: item.initiallySelected,
    locked: item.locked
  })), [
    { id: 1, selected: false, initiallySelected: false, locked: false },
    { id: 2, selected: true, initiallySelected: true, locked: false }
  ])
  assert.deepEqual(buildWorkEditSelectedTagIds(options), [2])
})

test('locks existing tag bindings when work is referenced', () => {
  const options = buildWorkEditTagOptions(
    [
      { id: 1, name: '婚礼', color: '#ff6b81' },
      { id: 2, name: '晚宴', color: '#4dabf7' }
    ],
    [{ id: 1, name: '婚礼', color: '#ff6b81' }],
    3
  )

  assert.equal(options[0].selected, true)
  assert.equal(options[0].locked, true)
  assert.equal(options[1].selected, false)
  assert.equal(options[1].locked, false)
})

test('normalizes empty work list with safe defaults', () => {
  const result = normalizeWorkList({})

  assert.equal(result.page, 1)
  assert.equal(result.pageSize, 20)
  assert.equal(result.total, 0)
  assert.deepEqual(result.works, [])
  assert.equal(result.empty, true)
  assert.deepEqual(result.summary, { totalCount: 0 })
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

test('normalizes animation metadata without media type statistics', () => {
  const result = normalizeWorkList({
    summary: {
      totalCount: 36,
      imageCount: 30,
      videoCount: 5,
      animationCount: 1
    },
    works: [{
      id: 10,
      mediaType: 'ANIMATION',
      title: '循环片段',
      mediaUrl: 'https://cos.example.com/a.webp',
      coverUrl: 'https://cos.example.com/a-cover-v2.jpg',
      frameCount: 24,
      coverFrameNumber: 8
    }]
  })

  assert.equal(result.works[0].typeText, '动图')
  assert.equal(result.works[0].frameCount, 24)
  assert.equal(result.works[0].coverFrameNumber, 8)
  assert.deepEqual(result.summary, {
    totalCount: 36
  })
  assert.equal(Object.prototype.hasOwnProperty.call(result, 'mediaFilters'), false)
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

test('builds work update payload with explicit tag ids', () => {
  const payload = buildWorkUpdatePayload({
    title: ' 主舞台 ',
    description: ' 现场图 ',
    tagIds: [2, 1, 2, null, 0]
  })

  assert.deepEqual(payload, {
    title: '主舞台',
    description: '现场图',
    tagIds: [2, 1]
  })
})

test('builds work update payload with empty tag ids to clear unreferenced bindings', () => {
  const payload = buildWorkUpdatePayload({
    title: ' 主舞台 ',
    description: '',
    tagIds: []
  })

  assert.deepEqual(payload, {
    title: '主舞台',
    description: '',
    tagIds: []
  })
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

test('builds work update payload with selected animation frame and edit-session key', () => {
  const payload = buildWorkUpdatePayload({
    title: ' 循环片段 ',
    description: ' 动图封面 ',
    coverFrameNumber: 18,
    coverFrameIdempotencyKey: ' animation-cover-10-session '
  })

  assert.deepEqual(payload, {
    title: '循环片段',
    description: '动图封面',
    coverFrameNumber: 18,
    coverFrameIdempotencyKey: 'animation-cover-10-session'
  })
})

test('omits incomplete animation cover fields', () => {
  const payload = buildWorkUpdatePayload({
    title: ' 循环片段 ',
    description: '',
    coverFrameNumber: 18
  })

  assert.deepEqual(payload, {
    title: '循环片段',
    description: ''
  })
})

test('builds work update payload with edited image thumbnail task id', () => {
  const payload = buildWorkUpdatePayload({
    title: ' 海边仪式 ',
    description: ' 新缩略图 ',
    thumbnailTaskId: 88
  })

  assert.deepEqual(payload, {
    title: '海边仪式',
    description: '新缩略图',
    thumbnailTaskId: 88
  })
})

test('builds work update payload without empty thumbnail task id', () => {
  const payload = buildWorkUpdatePayload({
    title: ' 海边仪式 ',
    description: ' 不改缩略图 ',
    thumbnailTaskId: ''
  })

  assert.deepEqual(payload, {
    title: '海边仪式',
    description: '不改缩略图'
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
