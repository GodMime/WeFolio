const assert = require('node:assert/strict')
const test = require('node:test')

const {
  COMPONENT_TYPES,
  addDisplayGroup,
  addComponent,
  buildDraftPayload,
  buildPublishPayload,
  copyWorkTagsToDisplayGroups,
  createComponent,
  importWorksIntoDisplayGroup,
  normalizePortfolioConfig,
  normalizeSingleWorkConfig,
  reorderComponent,
  reorderDisplayGroup,
  removeComponent,
  removeDisplayGroup,
  updateComponentProfileConfig,
  updateSingleWorkConfig,
  updateDisplayGroupName,
  updateDisplayGroupWorkIds,
  updateComponentWorkIds,
  validateDisplayGroupName,
  validateCarouselComponent,
  validateSingleWorkComponent,
  validateWorkGridComponent
} = require('../utils/portfolios')

test('normalizes portfolio config with stable component order', () => {
  const result = normalizePortfolioConfig({
    share: {
      title: ' 林安婚礼司仪 ',
      intro: ' 温暖沉稳 '
    },
    components: [
      createComponent(COMPONENT_TYPES.WORK_GRID, { componentKey: 'c_grid', sortOrder: 3000, config: { workIds: [12, '13'], columns: 2 } }),
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_profile', sortOrder: 1000 })
    ]
  })

  assert.equal(result.schemaVersion, 'standard-personal-v1')
  assert.equal(result.share.title, '林安婚礼司仪')
  assert.equal(Object.hasOwn(result.share, 'intro'), false)
  assert.deepEqual(result.components.map((item) => item.componentKey), ['c_profile', 'c_grid'])
  assert.deepEqual(result.components.map((item) => item.sortOrder), [1000, 2000])
  assert.deepEqual(result.components[1].config.workIds, [12, 13])
  assert.deepEqual(result.components[1].config.groups[0].workIds, [12, 13])
  assert.equal(result.components[1].config.groups[0].name, '全部作品')
})

test('supports single-column work list component type', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_profile', sortOrder: 1000 })
    ]
  })

  const result = addComponent(config, COMPONENT_TYPES.WORK_LIST)

  assert.equal(COMPONENT_TYPES.WORK_LIST, 'WORK_LIST')
  assert.equal(result.components[1].name, '单列作品列表')
  assert.equal(result.components[1].componentType, COMPONENT_TYPES.WORK_LIST)
})

test('single work component defaults title on and keeps only singular config', () => {
  const component = createComponent(COMPONENT_TYPES.SINGLE_WORK, {
    componentKey: 'c_single',
    config: {
      workId: '12',
      workIds: [13],
      showTitle: 'false',
      unsupported: true
    }
  })

  assert.equal(COMPONENT_TYPES.SINGLE_WORK, 'SINGLE_WORK')
  assert.equal(component.name, '单个作品')
  assert.deepEqual(component.config, { workId: 12, showTitle: true })
  assert.deepEqual(normalizeSingleWorkConfig({ workId: 13, showTitle: false }), {
    workId: 13,
    showTitle: false
  })
  assert.deepEqual(normalizeSingleWorkConfig({ workId: 13.9, showTitle: true }), {
    workId: 0,
    showTitle: true
  })
})

test('single work update replaces one work and preserves explicit title switch', () => {
  const config = normalizePortfolioConfig({
    components: [createComponent(COMPONENT_TYPES.SINGLE_WORK, {
      componentKey: 'c_single',
      config: { workId: 11 }
    })]
  })

  const updated = updateSingleWorkConfig(config, 'c_single', { workId: 12, showTitle: false })

  assert.deepEqual(updated.components[0].config, { workId: 12, showTitle: false })
})

test('single work validation requires one valid image or video work', () => {
  const works = [
    { id: 11, mediaType: 'IMAGE' },
    { id: 12, mediaType: 'VIDEO' }
  ]

  assert.equal(validateSingleWorkComponent({ config: {} }, works).message, '请选择一个作品')
  assert.equal(validateSingleWorkComponent({ config: { workId: 99 } }, works).message, '请选择有效作品')
  assert.equal(validateSingleWorkComponent({ config: { workId: 11 } }, works).valid, true)
  assert.equal(validateSingleWorkComponent({ config: { workId: 12 } }, works).valid, true)
})

test('contact form component defaults to visitor input fields', () => {
  const component = createComponent(COMPONENT_TYPES.CONTACT_FORM, {
    componentKey: 'c_form',
    sortOrder: 1000
  })

  assert.deepEqual(component.config.fields, ['contactName', 'phone', 'wechat', 'needs'])
})

test('validates carousel as image-only work selector', () => {
  const works = [
    { id: 11, mediaType: 'IMAGE' },
    { id: 12, mediaType: 'VIDEO' }
  ]

  assert.equal(
    validateCarouselComponent({ config: { workIds: [11, 12] } }, works).message,
    '轮播图只能选择图片作品'
  )
  assert.equal(
    validateCarouselComponent({ config: { workIds: [11] } }, works).valid,
    true
  )
})

test('validates work grid as image and video selector', () => {
  const works = [
    { id: 11, mediaType: 'IMAGE' },
    { id: 12, mediaType: 'VIDEO' }
  ]

  assert.equal(validateWorkGridComponent({ config: { workIds: [11, 12] } }, works).valid, true)
  assert.equal(
    validateWorkGridComponent({ config: { workIds: [99] } }, works).message,
    '请选择有效作品'
  )
})

test('removes components and keeps sort order compact', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_profile', sortOrder: 1000 }),
      createComponent(COMPONENT_TYPES.QR_CONTACT, { componentKey: 'c_qr', sortOrder: 2000 }),
      createComponent(COMPONENT_TYPES.CONTACT_FORM, { componentKey: 'c_form', sortOrder: 3000 })
    ]
  })

  const result = removeComponent(config, 'c_qr')

  assert.deepEqual(result.components.map((item) => item.componentKey), ['c_profile', 'c_form'])
  assert.deepEqual(result.components.map((item) => item.sortOrder), [1000, 2000])
})

test('reorders components after long press drag and keeps sort order compact', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_profile', sortOrder: 1000 }),
      createComponent(COMPONENT_TYPES.CAROUSEL, { componentKey: 'c_carousel', sortOrder: 2000 }),
      createComponent(COMPONENT_TYPES.WORK_GRID, { componentKey: 'c_grid', sortOrder: 3000 })
    ]
  })

  const result = reorderComponent(config, 0, 2)

  assert.deepEqual(result.components.map((item) => item.componentKey), ['c_carousel', 'c_grid', 'c_profile'])
  assert.deepEqual(result.components.map((item) => item.sortOrder), [1000, 2000, 3000])
})

test('adds selected component to the end of the arrangement list', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_profile', sortOrder: 1000 })
    ]
  })

  const result = addComponent(config, COMPONENT_TYPES.QR_CONTACT)

  assert.deepEqual(result.components.map((item) => item.componentType), [COMPONENT_TYPES.PROFILE, COMPONENT_TYPES.QR_CONTACT])
  assert.deepEqual(result.components.map((item) => item.sortOrder), [1000, 2000])
  assert.equal(result.components[1].name, '二维码联系')
})

test('updates selected works for a portfolio component', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_profile', sortOrder: 1000 }),
      createComponent(COMPONENT_TYPES.CAROUSEL, { componentKey: 'c_carousel', sortOrder: 2000, config: { workIds: [11] } })
    ]
  })

  const result = updateComponentWorkIds(config, 'c_carousel', [12, '13', 12, 0])

  assert.deepEqual(result.components[0].config.workIds || [], [])
  assert.deepEqual(result.components[1].config.workIds, [12, 13])
  assert.deepEqual(result.components.map((item) => item.sortOrder), [1000, 2000])
})

test('updates independent profile copy for portfolio profile component', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_profile', sortOrder: 1000 }),
      createComponent(COMPONENT_TYPES.CAROUSEL, { componentKey: 'c_carousel', sortOrder: 2000 })
    ]
  })

  const result = updateComponentProfileConfig(config, 'c_profile', {
    profile: {
      avatarUrl: ' https://example.com/avatar.jpg ',
      displayName: ' 林安 ',
      profession: ' 婚礼主持人 ',
      city: ' 上海 ',
      bio: ' 温暖沉稳 ',
      tags: [
        { name: ' 高端婚礼 ', color: '#0f766e' },
        { content: ' 双语主持 ', color: '#2d5f9a' },
        { name: '高端婚礼', color: '#0f766e' }
      ],
      wechatQrUrl: ' https://example.com/qr.jpg '
    },
    visibleFields: {
      avatar: true,
      displayName: true,
      profession: false,
      city: true,
      bio: true,
      tags: true,
      wechatQr: false
    }
  })

  assert.deepEqual(result.components[0].config.profile, {
    avatarUrl: 'https://example.com/avatar.jpg',
    displayName: '林安',
    profession: '婚礼主持人',
    city: '上海',
    bio: '温暖沉稳',
    tags: [
      { name: '高端婚礼', color: '#0f766e' },
      { name: '双语主持', color: '#2d5f9a' }
    ],
    wechatQrUrl: 'https://example.com/qr.jpg'
  })
  assert.deepEqual(result.components[0].config.visibleFields, {
    avatar: true,
    displayName: true,
    profession: false,
    city: true,
    bio: true,
    tags: true,
    wechatQr: false
  })
  assert.deepEqual(result.components[1].config, {})
})

test('maintains independent portfolio display tags for work list components', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.WORK_LIST, {
        componentKey: 'c_list',
        sortOrder: 1000,
        config: {
          groups: [
            { groupKey: 'g_a', name: '全部案例', sortOrder: 1000, workIds: [11] },
            { groupKey: 'g_b', name: '户外案例', sortOrder: 2000, workIds: [12] }
          ]
        }
      })
    ]
  })

  const renamed = updateDisplayGroupName(config, 'c_list', 'g_a', '精选案例')
  const imported = importWorksIntoDisplayGroup(renamed, 'c_list', 'g_a', [12, '13', 13])
  const updatedWorks = updateDisplayGroupWorkIds(imported, 'c_list', 'g_b', [15, 16, 15])
  const reordered = reorderDisplayGroup(updatedWorks, 'c_list', 1, 0)
  const removed = removeDisplayGroup(reordered, 'c_list', 'g_a')

  assert.equal(renamed.components[0].config.groups[0].name, '精选案例')
  assert.deepEqual(imported.components[0].config.groups[0].workIds, [11, 12, 13])
  assert.deepEqual(updatedWorks.components[0].config.groups[1].workIds, [15, 16])
  assert.deepEqual(reordered.components[0].config.groups.map((item) => item.groupKey), ['g_b', 'g_a'])
  assert.deepEqual(reordered.components[0].config.groups.map((item) => item.sortOrder), [1000, 2000])
  assert.deepEqual(removed.components[0].config.groups.map((item) => item.groupKey), ['g_b'])
})

test('adds portfolio display tags with validation and stable keys', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.WORK_GRID, {
        componentKey: 'c_grid',
        sortOrder: 1000,
        config: {
          groups: [
            { groupKey: 'g_1', name: '全部案例', sortOrder: 1000, workIds: [11] }
          ]
        }
      })
    ]
  })

  assert.deepEqual(validateDisplayGroupName(config.components[0].config.groups, '').message, '展示标签名称不能为空')
  assert.deepEqual(validateDisplayGroupName(config.components[0].config.groups, '全部案例').message, '展示标签名称不能重复')
  assert.equal(validateDisplayGroupName(config.components[0].config.groups, '户外案例').valid, true)

  const result = addDisplayGroup(config, 'c_grid', '户外案例')

  assert.deepEqual(result.components[0].config.groups, [
    { groupKey: 'g_1', name: '全部案例', sortOrder: 1000, workIds: [11] },
    { groupKey: 'g_2', name: '户外案例', sortOrder: 2000, workIds: [] }
  ])
})

test('copies work tags into independent portfolio display tags without source binding', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.WORK_GRID, { componentKey: 'c_grid', sortOrder: 1000 })
    ]
  })

  const result = copyWorkTagsToDisplayGroups(config, 'c_grid', [
    { id: 8, name: '户外案例' },
    { id: 9, name: '室内案例' }
  ])

  assert.deepEqual(result.components[0].config.groups, [
    { groupKey: 'g_1', name: '户外案例', sortOrder: 1000, workIds: [] },
    { groupKey: 'g_2', name: '室内案例', sortOrder: 2000, workIds: [] }
  ])
  assert.equal(JSON.stringify(result.components[0].config.groups).includes('tagId'), false)
})

test('builds draft and publish payloads', () => {
  const config = normalizePortfolioConfig({ components: [createComponent(COMPONENT_TYPES.PROFILE)] })

  assert.deepEqual(buildDraftPayload(config, 3, 'draft-1'), {
    config,
    clientRevision: 3,
    idempotencyKey: 'draft-1'
  })
  assert.deepEqual(buildPublishPayload(4, 'publish-1'), {
    draftRevision: 4,
    idempotencyKey: 'publish-1'
  })
})
