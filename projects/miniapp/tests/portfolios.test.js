const assert = require('node:assert/strict')
const test = require('node:test')

const {
  COMPONENT_TYPES,
  addComponent,
  buildDraftPayload,
  buildPublishPayload,
  createComponent,
  normalizePortfolioConfig,
  reorderComponent,
  removeComponent,
  updateComponentWorkIds,
  validateCarouselComponent,
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
  assert.deepEqual(result.components.map((item) => item.componentKey), ['c_profile', 'c_grid'])
  assert.deepEqual(result.components.map((item) => item.sortOrder), [1000, 2000])
  assert.deepEqual(result.components[1].config.workIds, [12, 13])
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
