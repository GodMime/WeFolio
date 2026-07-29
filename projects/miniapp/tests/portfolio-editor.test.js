const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const {
  CONTACT_FORM_DISPLAY_MODES,
  DEFAULT_DIVIDER_HEIGHT_PX,
  DIVIDER_COLOR_OPTIONS,
  DIVIDER_COLORS,
  COMPONENT_TYPES,
  SCHEDULE_QUERY_DISPLAY_MODES,
  TEXT_SECTION_ALIGNMENTS,
  TEXT_SECTION_MAX_LENGTH,
  createComponent,
  normalizePortfolioConfig,
  updateComponentContactFormConfig,
  updateComponentDividerConfig,
  updateComponentScheduleQueryConfig,
  updateComponentTextSectionConfig
} = require('../utils/portfolios')

function flushPromises() {
  return new Promise((resolve) => {
    setImmediate(resolve)
  })
}

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function applyData(target, patch) {
  Object.keys(patch).forEach((key) => {
    if (!key.includes('.')) {
      target[key] = patch[key]
      return
    }
    const parts = key.split('.')
    const lastKey = parts.pop()
    const parent = parts.reduce((result, part) => {
      if (!result[part] || typeof result[part] !== 'object') {
        result[part] = {}
      }
      return result[part]
    }, target)
    parent[lastKey] = patch[key]
  })
}

function createSelectorQuery(rects) {
  const query = {
    in() {
      return query
    },
    selectAll() {
      return query
    },
    boundingClientRect(callback) {
      callback(rects)
      return query
    },
    exec() {}
  }
  return query
}

function buildMockShareCoverCropState(imageInfo = {}) {
  return {
    imagePath: imageInfo.path || imageInfo.tempFilePath || '',
    imageWidth: imageInfo.width || 1200,
    imageHeight: imageInfo.height || 800,
    cropBoxWidth: 320,
    cropBoxHeight: 256,
    displayWidth: 384,
    displayHeight: 256,
    offsetX: -32,
    offsetY: 0,
    minOffsetX: -64,
    maxOffsetX: 0,
    minOffsetY: 0,
    maxOffsetY: 0,
    scale: 0.32,
    cropBoxStyle: 'width: 320px; height: 256px;',
    imageStyle: 'width: 384px; height: 256px; transform: translate3d(-32px, 0px, 0);'
  }
}

test('normalizes schedule query display mode in portfolio config', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.SCHEDULE_QUERY, {
        componentKey: 'c_default',
        sortOrder: 1000,
        config: {}
      }),
      createComponent(COMPONENT_TYPES.SCHEDULE_QUERY, {
        componentKey: 'c_inline',
        sortOrder: 2000,
        config: { displayMode: 'INLINE_CALENDAR' }
      }),
      createComponent(COMPONENT_TYPES.SCHEDULE_QUERY, {
        componentKey: 'c_invalid',
        sortOrder: 3000,
        config: { displayMode: 'SIDE_PANEL' }
      })
    ]
  })

  assert.equal(config.components[0].config.displayMode, SCHEDULE_QUERY_DISPLAY_MODES.MODAL_CALENDAR)
  assert.equal(config.components[1].config.displayMode, SCHEDULE_QUERY_DISPLAY_MODES.INLINE_CALENDAR)
  assert.equal(config.components[2].config.displayMode, SCHEDULE_QUERY_DISPLAY_MODES.MODAL_CALENDAR)
})

test('updates schedule query component display mode in draft config', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.SCHEDULE_QUERY, {
        componentKey: 'c_schedule',
        sortOrder: 1000,
        config: {}
      })
    ]
  })

  const updated = updateComponentScheduleQueryConfig(config, 'c_schedule', {
    displayMode: 'INLINE_CALENDAR'
  })

  assert.equal(updated.components[0].config.displayMode, SCHEDULE_QUERY_DISPLAY_MODES.INLINE_CALENDAR)
})

test('normalizes contact form display mode in portfolio config', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.CONTACT_FORM, {
        componentKey: 'c_default',
        sortOrder: 1000,
        config: {}
      }),
      createComponent(COMPONENT_TYPES.CONTACT_FORM, {
        componentKey: 'c_inline',
        sortOrder: 2000,
        config: { displayMode: 'INLINE_FORM' }
      }),
      createComponent(COMPONENT_TYPES.CONTACT_FORM, {
        componentKey: 'c_invalid',
        sortOrder: 3000,
        config: { displayMode: 'SIDE_PANEL' }
      })
    ]
  })

  assert.equal(config.components[0].config.displayMode, CONTACT_FORM_DISPLAY_MODES.MODAL_FORM)
  assert.equal(config.components[1].config.displayMode, CONTACT_FORM_DISPLAY_MODES.INLINE_FORM)
  assert.equal(config.components[2].config.displayMode, CONTACT_FORM_DISPLAY_MODES.MODAL_FORM)
})

test('updates contact form component display mode in draft config', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.CONTACT_FORM, {
        componentKey: 'c_contact',
        sortOrder: 1000,
        config: {}
      })
    ]
  })

  const updated = updateComponentContactFormConfig(config, 'c_contact', {
    displayMode: 'INLINE_FORM'
  })

  assert.equal(updated.components[0].config.displayMode, CONTACT_FORM_DISPLAY_MODES.INLINE_FORM)
})

test('normalizes and updates text section component config', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.TEXT_SECTION, {
        componentKey: 'c_text',
        sortOrder: 1000,
        config: {
          content: ' 第一行\n第二行 ',
          alignment: 'CENTER'
        }
      }),
      createComponent(COMPONENT_TYPES.TEXT_SECTION, {
        componentKey: 'c_invalid',
        sortOrder: 2000,
        config: {
          content: '说明',
          alignment: 'JUSTIFY'
        }
      })
    ]
  })

  assert.equal(config.components[0].config.content, '第一行\n第二行')
  assert.equal(config.components[0].config.alignment, TEXT_SECTION_ALIGNMENTS.CENTER)
  assert.equal(config.components[1].config.alignment, TEXT_SECTION_ALIGNMENTS.LEFT)

  const updated = updateComponentTextSectionConfig(config, 'c_text', {
    content: '更新说明',
    alignment: TEXT_SECTION_ALIGNMENTS.RIGHT
  })

  assert.equal(updated.components[0].config.content, '更新说明')
  assert.equal(updated.components[0].config.alignment, TEXT_SECTION_ALIGNMENTS.RIGHT)
  assert.equal(TEXT_SECTION_MAX_LENGTH, 200)
})

test('normalizes and updates divider component config', () => {
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.DIVIDER, {
        componentKey: 'c_divider',
        sortOrder: 1000,
        config: {
          color: 'BLACK',
          heightPx: '24'
        }
      }),
      createComponent(COMPONENT_TYPES.DIVIDER, {
        componentKey: 'c_invalid',
        sortOrder: 2000,
        config: {
          color: 'BLUE',
          heightPx: '0'
        }
      })
    ]
  })

  assert.equal(config.components[0].config.color, DIVIDER_COLORS.BLACK)
  assert.equal(config.components[0].config.heightPx, 24)
  assert.equal(config.components[1].config.color, DIVIDER_COLORS.GRAY)
  assert.equal(config.components[1].config.heightPx, DEFAULT_DIVIDER_HEIGHT_PX)
  assert.deepEqual(DIVIDER_COLOR_OPTIONS.map((item) => item.value), [
    DIVIDER_COLORS.BLACK,
    DIVIDER_COLORS.WHITE,
    DIVIDER_COLORS.GRAY,
    DIVIDER_COLORS.TRANSPARENT
  ])

  const updated = updateComponentDividerConfig(config, 'c_divider', {
    color: DIVIDER_COLORS.TRANSPARENT,
    heightPx: '32'
  })

  assert.equal(updated.components[0].config.color, DIVIDER_COLORS.TRANSPARENT)
  assert.equal(updated.components[0].config.heightPx, 32)
})

function loadPortfolioEditorPage(fakeRequest, wxOverrides = {}, assetOverrides = {}, harnessOptions = {}) {
  const pagePath = path.join(__dirname, '../pages/portfolios/standard-edit/portfolio-standard-edit.js')
  const requestPath = path.join(__dirname, '../utils/request.js')
  const sessionPath = path.join(__dirname, '../utils/session.js')
  const assetsPath = path.join(__dirname, '../pages/portfolios/utils/portfolio-assets.js')
  const requestCacheKey = require.resolve(requestPath)
  const sessionCacheKey = require.resolve(sessionPath)
  const assetsCacheKey = require.resolve(assetsPath)
  const originalRequestCache = require.cache[requestCacheKey]
  const originalSessionCache = require.cache[sessionCacheKey]
  const originalAssetsCache = require.cache[assetsCacheKey]

  delete require.cache[require.resolve(pagePath)]
  require.cache[requestCacheKey] = {
    id: requestPath,
    filename: requestPath,
    loaded: true,
    exports: {
      request: fakeRequest
    }
  }
  require.cache[sessionCacheKey] = {
    id: sessionPath,
    filename: sessionPath,
    loaded: true,
    exports: {
      handleMaintainerAuthRequired() {},
      hasLocalToken() {
        return true
      }
    }
  }
  require.cache[assetsCacheKey] = {
    id: assetsPath,
    filename: assetsPath,
    loaded: true,
    exports: Object.assign({
      PORTFOLIO_ASSET_TYPES: {
        COVER: 'COVER',
        PROFILE_AVATAR: 'PROFILE_AVATAR',
        QR_CONTACT: 'QR_CONTACT'
      },
      createChoosePortfolioImageOptions() {
        return { count: 1, mediaType: ['image'], sourceType: ['album'] }
      },
      getPortfolioCoverImageInfo(imageFile) {
        return Promise.resolve({
          path: imageFile.tempFilePath || imageFile.path || '',
          width: imageFile.width || 0,
          height: imageFile.height || 0
        })
      },
      shouldCropPortfolioCover(imageInfo) {
        return Number(imageInfo.width) * 4 !== Number(imageInfo.height) * 5
      },
      buildPortfolioCoverCropState: buildMockShareCoverCropState,
      movePortfolioCoverCropState(state) {
        return state
      },
      buildPortfolioCoverCropFrame() {
        return {
          sx: 0,
          sy: 0,
          sWidth: 1000,
          sHeight: 800,
          destWidth: 1000,
          destHeight: 800
        }
      },
      cropPortfolioCoverToTempFilePath() {
        return Promise.resolve('wxfile://tmp/share-cover-cropped.jpg')
      },
      uploadPortfolioImageAsset(portfolioId, filePath) {
        return Promise.resolve(filePath)
      }
    }, assetOverrides)
  }

  let pageDefinition
  global.Page = (definition) => {
    pageDefinition = definition
  }
  global.wx = Object.assign({
    createSelectorQuery() {
      return createSelectorQuery([
        { top: 0, height: 100 },
        { top: 100, height: 100 },
        { top: 200, height: 100 }
      ])
    },
    navigateTo() {},
    navigateBack() {},
    redirectTo() {},
    showToast() {}
  }, wxOverrides)

  require(pagePath)
  delete global.Page
  if (originalRequestCache) {
    require.cache[requestCacheKey] = originalRequestCache
  } else {
    delete require.cache[requestCacheKey]
  }
  if (originalSessionCache) {
    require.cache[sessionCacheKey] = originalSessionCache
  } else {
    delete require.cache[sessionCacheKey]
  }
  if (originalAssetsCache) {
    require.cache[assetsCacheKey] = originalAssetsCache
  } else {
    delete require.cache[assetsCacheKey]
  }

  let deferredConfigPatch = null
  return Object.assign({}, pageDefinition, {
    data: clone(pageDefinition.data),
    setData(patch, callback) {
      if (harnessOptions.deferFirstConfigSetData && deferredConfigPatch === null && Object.prototype.hasOwnProperty.call(patch, 'config')) {
        const visiblePatch = Object.assign({}, patch)
        deferredConfigPatch = { config: patch.config, callback }
        delete visiblePatch.config
        applyData(this.data, visiblePatch)
        return
      }
      applyData(this.data, patch)
      if (callback) {
        callback()
      }
    },
    flushDeferredSetData() {
      if (!deferredConfigPatch) {
        return
      }
      applyData(this.data, { config: deferredConfigPatch.config })
      if (deferredConfigPatch.callback) {
        deferredConfigPatch.callback()
      }
      deferredConfigPatch = null
    }
  })
}

function touchEvent(dataset, point) {
  return {
    currentTarget: { dataset },
    touches: [point],
    changedTouches: [point]
  }
}

test('standard portfolio disables profile option when profile component already exists', () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))

  page.handleOpenComponentSheet()

  const profileOption = page.data.componentOptions.find((item) => item.componentType === COMPONENT_TYPES.PROFILE)
  assert.equal(profileOption.disabled, true)
})

test('standard portfolio keeps profile option disabled after component library loads', async () => {
  const page = loadPortfolioEditorPage((options) => {
    if (options.url === '/api/mine/portfolios/component-library') {
      return Promise.resolve({
        components: [
          { componentType: COMPONENT_TYPES.PROFILE, name: '个人资料', description: '展示个人资料' },
          { componentType: COMPONENT_TYPES.CAROUSEL, name: '轮播图', description: '展示图片作品' }
        ]
      })
    }
    return Promise.resolve({})
  })

  page.handleOpenComponentSheet()
  await flushPromises()

  const profileOption = page.data.componentOptions.find((item) => item.componentType === COMPONENT_TYPES.PROFILE)
  assert.equal(profileOption.disabled, true)
})

test('standard portfolio ignores selection of disabled profile option', () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))
  const originalComponentCount = page.data.config.components.length

  page.handleSelectComponent({
    currentTarget: { dataset: { type: COMPONENT_TYPES.PROFILE, disabled: true } }
  })

  assert.equal(page.data.config.components.length, originalComponentCount)
  assert.equal(page.data.componentSheetVisible, false)
})

test('standard portfolio enables profile option again after profile component is removed', () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))
  const profileComponent = page.data.config.components.find((item) => item.componentType === COMPONENT_TYPES.PROFILE)

  page.handleRemoveComponent({ currentTarget: { dataset: { key: profileComponent.componentKey } } })
  page.handleOpenComponentSheet()

  const profileOption = page.data.componentOptions.find((item) => item.componentType === COMPONENT_TYPES.PROFILE)
  assert.equal(profileOption.disabled, false)
})

test('standard portfolio component row reveals delete only after left swipe', () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))

  page.handleComponentTouchStart(touchEvent({ key: 'c_carousel', index: 1 }, { clientX: 220, clientY: 40 }))
  page.handleComponentTouchEnd(touchEvent({ key: 'c_carousel', index: 1 }, { clientX: 150, clientY: 44 }))

  assert.equal(page.data.revealedComponentKey, 'c_carousel')

  page.handleComponentTap({ currentTarget: { dataset: { key: 'c_carousel', type: COMPONENT_TYPES.CAROUSEL } } })

  assert.equal(page.data.revealedComponentKey, '')
  assert.equal(page.data.componentWorkSheetVisible, false)
})

test('tapping schedule query component edits display mode', () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.SCHEDULE_QUERY, {
        componentKey: 'c_schedule',
        sortOrder: 1000,
        config: { displayMode: 'MODAL_CALENDAR' }
      })
    ]
  })

  page.handleComponentTap({ currentTarget: { dataset: { key: 'c_schedule', type: COMPONENT_TYPES.SCHEDULE_QUERY } } })

  assert.equal(page.data.scheduleQuerySheetVisible, true)
  assert.equal(page.data.scheduleQueryEditingComponentKey, 'c_schedule')
  assert.equal(page.data.scheduleQueryForm.displayMode, SCHEDULE_QUERY_DISPLAY_MODES.MODAL_CALENDAR)
  assert.equal(page.data.scheduleQueryDisplayModeOptions[1].label, '直接显示月历')

  page.handleScheduleQueryDisplayModeTap({
    currentTarget: { dataset: { value: SCHEDULE_QUERY_DISPLAY_MODES.INLINE_CALENDAR } }
  })
  page.handleConfirmScheduleQueryConfig()

  assert.equal(page.data.scheduleQuerySheetVisible, false)
  assert.equal(page.data.config.components[0].config.displayMode, SCHEDULE_QUERY_DISPLAY_MODES.INLINE_CALENDAR)
})

test('tapping contact form component edits display mode', () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.CONTACT_FORM, {
        componentKey: 'c_contact',
        sortOrder: 1000,
        config: { displayMode: 'MODAL_FORM' }
      })
    ]
  })

  page.handleComponentTap({ currentTarget: { dataset: { key: 'c_contact', type: COMPONENT_TYPES.CONTACT_FORM } } })

  assert.equal(page.data.contactFormSheetVisible, true)
  assert.equal(page.data.contactFormEditingComponentKey, 'c_contact')
  assert.equal(page.data.contactFormConfigForm.displayMode, CONTACT_FORM_DISPLAY_MODES.MODAL_FORM)
  assert.equal(page.data.contactFormDisplayModeOptions[1].label, '直接显示表单')

  page.handleContactFormDisplayModeTap({
    currentTarget: { dataset: { value: CONTACT_FORM_DISPLAY_MODES.INLINE_FORM } }
  })
  page.handleConfirmContactFormConfig()

  assert.equal(page.data.contactFormSheetVisible, false)
  assert.equal(page.data.config.components[0].config.displayMode, CONTACT_FORM_DISPLAY_MODES.INLINE_FORM)
})

test('tapping text section component edits required content and alignment', () => {
  const toasts = []
  const page = loadPortfolioEditorPage(() => Promise.resolve({}), {
    showToast(options) {
      toasts.push(options)
    }
  })
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.TEXT_SECTION, {
        componentKey: 'c_text',
        sortOrder: 1000,
        config: {
          content: '原始说明',
          alignment: TEXT_SECTION_ALIGNMENTS.LEFT
        }
      })
    ]
  })

  page.handleComponentTap({ currentTarget: { dataset: { key: 'c_text', type: COMPONENT_TYPES.TEXT_SECTION } } })

  assert.equal(page.data.textSectionSheetVisible, true)
  assert.equal(page.data.textSectionEditingComponentKey, 'c_text')
  assert.equal(page.data.textSectionForm.content, '原始说明')
  assert.equal(page.data.textSectionForm.alignment, TEXT_SECTION_ALIGNMENTS.LEFT)
  assert.equal(page.data.textSectionFieldCounters.content, '4 / 200')
  assert.deepEqual(page.data.textSectionAlignmentOptions.map((item) => item.value), [
    TEXT_SECTION_ALIGNMENTS.LEFT,
    TEXT_SECTION_ALIGNMENTS.CENTER,
    TEXT_SECTION_ALIGNMENTS.RIGHT
  ])

  page.handleTextSectionInput({ detail: { value: '第一行\n第二行' } })
  page.handleTextSectionAlignmentTap({
    currentTarget: { dataset: { value: TEXT_SECTION_ALIGNMENTS.RIGHT } }
  })
  page.handleConfirmTextSectionConfig()

  assert.equal(page.data.textSectionSheetVisible, false)
  assert.equal(page.data.config.components[0].config.content, '第一行\n第二行')
  assert.equal(page.data.config.components[0].config.alignment, TEXT_SECTION_ALIGNMENTS.RIGHT)

  page.handleComponentTap({ currentTarget: { dataset: { key: 'c_text', type: COMPONENT_TYPES.TEXT_SECTION } } })
  page.handleTextSectionInput({ detail: { value: '   ' } })
  page.handleConfirmTextSectionConfig()

  assert.equal(page.data.textSectionSheetVisible, true)
  assert.equal(toasts.at(-1).title, '请填写文字说明')
})

test('tapping divider component edits color and pixel height', () => {
  const toasts = []
  const page = loadPortfolioEditorPage(() => Promise.resolve({}), {
    showToast(options) {
      toasts.push(options)
    }
  })
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.DIVIDER, {
        componentKey: 'c_divider',
        sortOrder: 1000,
        config: {
          color: DIVIDER_COLORS.GRAY,
          heightPx: 12
        }
      })
    ]
  })

  page.handleComponentTap({ currentTarget: { dataset: { key: 'c_divider', type: COMPONENT_TYPES.DIVIDER } } })

  assert.equal(page.data.dividerSheetVisible, true)
  assert.equal(page.data.dividerEditingComponentKey, 'c_divider')
  assert.equal(page.data.dividerForm.color, DIVIDER_COLORS.GRAY)
  assert.equal(page.data.dividerForm.heightPx, 12)
  assert.deepEqual(page.data.dividerColorOptions.map((item) => item.label), ['黑', '白', '灰', '透明'])

  page.handleDividerColorTap({ currentTarget: { dataset: { value: DIVIDER_COLORS.BLACK } } })
  page.handleDividerHeightInput({ detail: { value: '28' } })
  page.handleConfirmDividerConfig()

  assert.equal(page.data.dividerSheetVisible, false)
  assert.equal(page.data.config.components[0].config.color, DIVIDER_COLORS.BLACK)
  assert.equal(page.data.config.components[0].config.heightPx, 28)

  page.handleComponentTap({ currentTarget: { dataset: { key: 'c_divider', type: COMPONENT_TYPES.DIVIDER } } })
  page.handleDividerHeightInput({ detail: { value: '0' } })
  page.handleConfirmDividerConfig()

  assert.equal(page.data.dividerSheetVisible, true)
  assert.equal(toasts.at(-1).title, '请输入大于 0 的高度')
})

test('standard portfolio component drag moves row with animated style before reordering', () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_profile', sortOrder: 1000 }),
      createComponent(COMPONENT_TYPES.CAROUSEL, { componentKey: 'c_carousel', sortOrder: 2000 }),
      createComponent(COMPONENT_TYPES.QR_CONTACT, { componentKey: 'c_qr', sortOrder: 3000 })
    ]
  })

  page.handleComponentDragStart(touchEvent({ key: 'c_profile', index: 0 }, { clientY: 100 }))
  page.handleComponentTouchMove(touchEvent({ key: 'c_profile', index: 0 }, { clientY: 130 }))

  assert.equal(page.data.dragTargetIndex, 1)
  assert.match(page.data.componentDragStyle, /translate3d\(0, 30px, 0\)/)
  assert.match(page.data.componentDragStyle, /transition: transform 80ms linear/)

  page.handleComponentTouchEnd(touchEvent({ key: 'c_profile', index: 0 }, { clientY: 130 }))

  assert.deepEqual(page.data.config.components.map((item) => item.componentKey), ['c_carousel', 'c_profile', 'c_qr'])
  assert.equal(page.data.componentDragStyle, '')
})

test('share fields only keep title counter while editing', () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))

  assert.equal(page.data.shareFieldCounters.title, '0 / 50')
  assert.equal(Object.hasOwn(page.data.shareFieldCounters, 'intro'), false)

  page.handleShareInput({
    currentTarget: { dataset: { path: 'share.title' } },
    detail: { value: '婚礼主持作品集' }
  })
  page.handleShareInput({
    currentTarget: { dataset: { path: 'share.intro' } },
    detail: { value: '温暖沉稳\n' }
  })

  assert.equal(page.data.config.share.title, '婚礼主持作品集')
  assert.equal(Object.hasOwn(page.data.config.share, 'intro'), false)
  assert.equal(page.data.shareFieldCounters.title, '7 / 50')
  assert.equal(Object.hasOwn(page.data.shareFieldCounters, 'intro'), false)
})

test('share cover chooser uses native 5:4 image without crop sheet', async () => {
  let chooseOptions = null
  const page = loadPortfolioEditorPage(() => Promise.resolve({}), {
    chooseMedia(options) {
      chooseOptions = options
      options.success({
        tempFiles: [
          { tempFilePath: 'wxfile://tmp/share-cover-5x4.jpg', width: 1000, height: 800 }
        ]
      })
    }
  })

  page.handleChooseShareCover()
  await flushPromises()

  assert.deepEqual(chooseOptions.mediaType, ['image'])
  assert.equal(page.data.config.share.coverUrl, 'wxfile://tmp/share-cover-5x4.jpg')
  assert.equal(page.data.shareCoverCropVisible, false)
})

test('share cover chooser opens manual crop sheet for non 5:4 image', async () => {
  const cropCalls = []
  const page = loadPortfolioEditorPage(() => Promise.resolve({}), {
    getWindowInfo() {
      return { windowWidth: 375 }
    },
    getSystemInfoSync() {
      throw new Error('不应调用已废弃的 wx.getSystemInfoSync')
    },
    chooseMedia(options) {
      options.success({
        tempFiles: [
          { tempFilePath: 'wxfile://tmp/share-cover-wide.jpg', width: 1200, height: 800 }
        ]
      })
    }
  }, {
    cropPortfolioCoverToTempFilePath(options) {
      cropCalls.push(options)
      return Promise.resolve('wxfile://tmp/share-cover-cropped.jpg')
    }
  })

  page.handleChooseShareCover()
  await flushPromises()

  assert.equal(page.data.config.share.coverUrl, '')
  assert.equal(page.data.shareCoverCropVisible, true)
  assert.equal(page.data.shareCoverCropState.imagePath, 'wxfile://tmp/share-cover-wide.jpg')
  assert.match(page.data.shareCoverCropState.imageStyle, /translate3d\(-32px, 0px, 0\)/)

  await page.handleConfirmShareCoverCrop()

  assert.equal(cropCalls[0].imagePath, 'wxfile://tmp/share-cover-wide.jpg')
  assert.equal(page.data.config.share.coverUrl, 'wxfile://tmp/share-cover-cropped.jpg')
  assert.equal(page.data.shareCoverCropVisible, false)
})

test('tapping carousel component edits selected image works', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    return Promise.resolve({
      works: [
        { id: 11, mediaType: 'IMAGE', title: '仪式合影', coverUrl: 'https://example.com/11.jpg' },
        { id: 12, mediaType: 'VIDEO', title: '婚礼快剪', coverUrl: 'https://example.com/12.jpg' },
        { id: 13, mediaType: 'IMAGE', title: '迎宾布置', coverUrl: 'https://example.com/13.jpg' }
      ]
    })
  }
  const page = loadPortfolioEditorPage(fakeRequest)
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.CAROUSEL, {
        componentKey: 'c_carousel',
        sortOrder: 1000,
        config: { workIds: [13] }
      })
    ]
  })

  await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_carousel', type: COMPONENT_TYPES.CAROUSEL } } })
  await flushPromises()

  assert.equal(requests[0].url, '/api/mine/works')
  assert.deepEqual(requests[0].data, {
    keyword: '',
    tagId: undefined,
    mediaType: 'IMAGE',
    auditStatus: 'PASSED',
    page: 1,
    pageSize: 20
  })
  assert.equal(page.data.componentWorkSheetVisible, true)
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.id), [11, 13])
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.selected), [false, true])
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.selectionOrder), [0, 1])

  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 11 } } })
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.selectionOrder), [2, 1])
  page.handleConfirmComponentWorks()

  assert.deepEqual(page.data.config.components[0].config.workIds, [13, 11])
  assert.equal(page.data.componentWorkSheetVisible, false)
})

test('personal carousel compacts selection order and restarts from one after clearing', () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({ works: [] }))
  page.data.editingComponentType = COMPONENT_TYPES.CAROUSEL
  page.data.componentWorkSelectedIds = []
  page.data.componentWorkOptions = [11, 12, 13].map((id) => ({
    id,
    mediaType: 'IMAGE'
  }))

  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 11 } } })
  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 12 } } })
  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 13 } } })

  assert.deepEqual(page.data.componentWorkSelectedIds, [11, 12, 13])
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.selectionOrder), [1, 2, 3])

  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 12 } } })

  assert.deepEqual(page.data.componentWorkSelectedIds, [11, 13])
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.selectionOrder), [1, 0, 2])

  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 11 } } })
  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 13 } } })
  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 12 } } })

  assert.deepEqual(page.data.componentWorkSelectedIds, [12])
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.selectionOrder), [0, 1, 0])
})

test('personal carousel limits new selections to nine and allows replacement after removal', () => {
  const toastCalls = []
  const page = loadPortfolioEditorPage(() => Promise.resolve({ works: [] }), {
    showToast(options) {
      toastCalls.push(options)
    }
  })
  page.data.editingComponentType = COMPONENT_TYPES.CAROUSEL
  page.data.componentWorkSelectedIds = [1, 2, 3, 4, 5, 6, 7, 8, 9]
  page.data.componentWorkOptions = Array.from({ length: 10 }, (_, index) => ({
    id: index + 1,
    mediaType: 'IMAGE'
  }))

  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 10 } } })

  assert.deepEqual(page.data.componentWorkSelectedIds, [1, 2, 3, 4, 5, 6, 7, 8, 9])
  assert.equal(toastCalls.at(-1).title, '轮播图最多选择9张图片')

  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 5 } } })
  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 10 } } })

  assert.deepEqual(page.data.componentWorkSelectedIds, [1, 2, 3, 4, 6, 7, 8, 9, 10])
})

test('personal carousel preserves historical selections above nine while blocking additions', async () => {
  const toastCalls = []
  const historicalIds = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
  const page = loadPortfolioEditorPage(() => Promise.resolve({
    works: Array.from({ length: 11 }, (_, index) => ({
      id: index + 1,
      mediaType: 'IMAGE',
      title: `作品${index + 1}`
    }))
  }), {
    showToast(options) {
      toastCalls.push(options)
    }
  })
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.CAROUSEL, {
        componentKey: 'c_historical_carousel',
        config: { workIds: historicalIds }
      })
    ]
  })

  await page.handleComponentTap({
    currentTarget: {
      dataset: { key: 'c_historical_carousel', type: COMPONENT_TYPES.CAROUSEL }
    }
  })

  assert.deepEqual(page.data.componentWorkSelectedIds, historicalIds)
  assert.equal(page.data.componentWorkOptions.find((item) => item.id === 10).selectionOrder, 10)

  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 11 } } })

  assert.deepEqual(page.data.componentWorkSelectedIds, historicalIds)
  assert.equal(toastCalls.at(-1).title, '轮播图最多选择9张图片')

  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 5 } } })

  assert.deepEqual(page.data.componentWorkSelectedIds, [1, 2, 3, 4, 6, 7, 8, 9, 10])
  assert.equal(page.data.componentWorkOptions.find((item) => item.id === 10).selectionOrder, 9)
})

test('single work editor replaces one selection and commits display switches atomically', async () => {
  const page = loadPortfolioEditorPage((options) => {
    if (options.url === '/api/mine/works') {
      return Promise.resolve({
        works: [
          { id: 11, mediaType: 'IMAGE', title: '仪式合影', coverUrl: 'https://example.com/11.jpg' },
          { id: 12, mediaType: 'VIDEO', title: '婚礼快剪', coverUrl: 'https://example.com/12.jpg' }
        ]
      })
    }
    return Promise.resolve({})
  })
  page.data.config = normalizePortfolioConfig({
    components: [createComponent(COMPONENT_TYPES.SINGLE_WORK, {
      componentKey: 'c_single',
      sortOrder: 1000,
      config: { workId: 11, showTitle: true, showDescription: false }
    })]
  })

  await page.handleComponentTap({
    currentTarget: { dataset: { key: 'c_single', type: COMPONENT_TYPES.SINGLE_WORK } }
  })
  await flushPromises()

  assert.equal(page.data.componentWorkSheetTitle, '编辑单个作品')
  assert.equal(page.data.componentWorkSelectionMode, 'single')
  assert.deepEqual(page.data.componentWorkSelectedIds, [11])
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.selectionOrder), [0, 0])

  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 12 } } })
  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 12 } } })
  page.handleSingleWorkShowTitleChange({ detail: { value: false } })
  page.handleWorkShowDescriptionChange({ detail: { value: true } })

  assert.deepEqual(page.data.componentWorkSelectedIds, [12])
  assert.deepEqual(page.data.config.components[0].config, {
    workId: 11,
    showTitle: true,
    showDescription: false
  })

  page.handleConfirmComponentWorks()

  assert.deepEqual(page.data.config.components[0].config, {
    workId: 12,
    showTitle: false,
    showDescription: true
  })
  assert.equal(page.data.singleWorkSummaries[0].id, 12)
  assert.equal(page.data.singleWorkSummaries[0].title, '婚礼快剪')
  assert.equal(page.data.componentWorkSheetVisible, false)
})

test('single work editor blocks completion without a work and cancel preserves config', async () => {
  const toastCalls = []
  const page = loadPortfolioEditorPage(() => Promise.resolve({ works: [] }), {
    showToast(options) {
      toastCalls.push(options)
    }
  })
  page.data.config = normalizePortfolioConfig({
    components: [createComponent(COMPONENT_TYPES.SINGLE_WORK, {
      componentKey: 'c_single',
      sortOrder: 1000
    })]
  })

  await page.handleComponentTap({
    currentTarget: { dataset: { key: 'c_single', type: COMPONENT_TYPES.SINGLE_WORK } }
  })
  page.handleSingleWorkShowTitleChange({ detail: { value: false } })
  page.handleConfirmComponentWorks()

  assert.equal(toastCalls.at(-1).title, '请选择一个作品')
  assert.equal(page.data.componentWorkSheetVisible, true)
  assert.deepEqual(page.data.config.components[0].config, {
    workId: 0,
    showTitle: true,
    showDescription: false
  })

  page.handleCloseComponentWorkSheet()
  assert.deepEqual(page.data.config.components[0].config, {
    workId: 0,
    showTitle: true,
    showDescription: false
  })
})

test('single work row summaries resolve selected work titles from formal work details', async () => {
  const requests = []
  let resolveDetail
  const page = loadPortfolioEditorPage((options) => {
    requests.push(options)
    return new Promise((resolve) => {
      resolveDetail = resolve
    })
  })
  const config = normalizePortfolioConfig({
    components: [createComponent(COMPONENT_TYPES.SINGLE_WORK, {
      componentKey: 'c_single',
      config: { workId: 11 }
    })]
  })

  const loading = page.loadSingleWorkSummaries(config)

  assert.equal(requests[0].url, '/api/mine/works/11')
  assert.equal(page.data.singleWorkSummaryMap[11].status, 'LOADING')
  assert.equal(page.data.singleWorkSummaries[0].status, 'LOADING')

  resolveDetail({
    work: { id: 11, mediaType: 'IMAGE', title: '草坪仪式', coverUrl: 'https://example.com/11.jpg' }
  })
  await loading

  assert.equal(page.data.singleWorkSummaryMap[11].title, '草坪仪式')
  assert.equal(page.data.singleWorkSummaries[0].title, '草坪仪式')
})

test('single work row summaries distinguish detail failures from unavailable works', async () => {
  const responses = [Promise.reject(new Error('network failed')), Promise.resolve({})]
  const page = loadPortfolioEditorPage(() => responses.shift())
  const config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.SINGLE_WORK, {
        componentKey: 'c_failed',
        config: { workId: 11 }
      }),
      createComponent(COMPONENT_TYPES.SINGLE_WORK, {
        componentKey: 'c_unavailable',
        config: { workId: 12 }
      })
    ]
  })

  await page.loadSingleWorkSummaries(config)

  assert.equal(page.data.singleWorkSummaryMap[11].status, 'FAILED')
  assert.equal(page.data.singleWorkSummaryMap[12].status, 'UNAVAILABLE')
  assert.deepEqual(page.data.singleWorkSummaries.map((item) => item.status), ['FAILED', 'UNAVAILABLE'])
})

test('single work component rows avoid unsupported Number calls in WXS', () => {
  const source = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-edit/component-rows.wxs'),
    'utf8'
  )
  const wxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-edit/portfolio-standard-edit.wxml'),
    'utf8'
  )

  assert.doesNotMatch(source, /\bNumber\s*\(/)
  assert.match(source, /作品信息加载中/)
  assert.match(source, /作品信息加载失败/)
  assert.match(source, /for \(var index = 0; index < summaries\.length; index \+= 1\)/)
  assert.match(source, /summaries\[index\]\.id == workId/)
  assert.match(wxml, /resolveSingleWorkSummary\(item, singleWorkSummaries\)/)
})

test('component work picker renders carousel order without changing selection accessibility', () => {
  const wxml = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-edit/portfolio-standard-edit.wxml'),
    'utf8'
  )
  const wxss = fs.readFileSync(
    path.join(__dirname, '../pages/portfolios/standard-edit/portfolio-standard-edit.wxss'),
    'utf8'
  )

  assert.match(wxml, /editingComponentType === 'CAROUSEL'/)
  assert.match(wxml, /item\.selectionOrder/)
  assert.match(wxml, /item\.selected \? '✓' : ''/)
  assert.match(wxml, /aria-role="\{\{componentWorkSelectionMode === 'single' \? 'radio' : 'checkbox'\}\}"/)
  assert.match(wxml, /aria-checked="\{\{item\.selected\}\}"/)
  assert.match(wxss, /\.component-work-check\.carousel-order\s*\{[^}]*font-size:\s*22rpx;/)
  assert.match(wxss, /\.component-work-option\.selected \.component-work-check\s*\{[^}]*background:\s*#c28b37;/)
})

test('component work picker searches filters by tag and appends next page', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url !== '/api/mine/works') {
      return Promise.resolve({})
    }
    const data = options.data || {}
    if (data.keyword === '迎宾') {
      return Promise.resolve({
        page: 1,
        pageSize: 20,
        hasMore: false,
        summary: { totalCount: 1 },
        tags: [{ id: 7, name: '中式婚礼', count: 2 }],
        works: [
          { id: 21, mediaType: 'IMAGE', title: '迎宾区', coverUrl: 'https://example.com/21.jpg' }
        ]
      })
    }
    if (data.tagId === 7 && data.page === 2) {
      return Promise.resolve({
        page: 2,
        pageSize: 20,
        hasMore: false,
        summary: { totalCount: 2 },
        tags: [{ id: 7, name: '中式婚礼', count: 2 }],
        works: [
          { id: 32, mediaType: 'IMAGE', title: '中式合影', coverUrl: 'https://example.com/32.jpg' }
        ]
      })
    }
    if (data.tagId === 7) {
      return Promise.resolve({
        page: 1,
        pageSize: 20,
        hasMore: true,
        summary: { totalCount: 2 },
        tags: [{ id: 7, name: '中式婚礼', count: 2 }],
        works: [
          { id: 31, mediaType: 'IMAGE', title: '中式仪式', coverUrl: 'https://example.com/31.jpg' }
        ]
      })
    }
    return Promise.resolve({
      page: 1,
      pageSize: 20,
      hasMore: true,
      summary: { totalCount: 3 },
      tags: [{ id: 7, name: '中式婚礼', count: 2 }],
      works: [
        { id: 11, mediaType: 'IMAGE', title: '仪式合影', coverUrl: 'https://example.com/11.jpg' },
        { id: 12, mediaType: 'VIDEO', title: '婚礼快剪', coverUrl: 'https://example.com/12.jpg' }
      ]
    })
  }
  const page = loadPortfolioEditorPage(fakeRequest)
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.CAROUSEL, {
        componentKey: 'c_carousel',
        sortOrder: 1000,
        config: { workIds: [11, 32] }
      })
    ]
  })

  await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_carousel', type: COMPONENT_TYPES.CAROUSEL } } })

  assert.deepEqual(requests[0].data, {
    keyword: '',
    tagId: undefined,
    mediaType: 'IMAGE',
    auditStatus: 'PASSED',
    page: 1,
    pageSize: 20
  })
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.id), [11])
  assert.deepEqual(page.data.componentWorkFilterTags.map((item) => item.name), ['全部', '中式婚礼'])
  assert.equal(page.data.componentWorkHasMore, true)

  page.handleComponentWorkKeywordInput({ detail: { value: '迎宾' } })
  await page.handleComponentWorkSearchConfirm()

  assert.deepEqual(requests[1].data, {
    keyword: '迎宾',
    tagId: undefined,
    mediaType: 'IMAGE',
    auditStatus: 'PASSED',
    page: 1,
    pageSize: 20
  })
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.id), [21])
  assert.equal(page.data.componentWorkHasMore, false)

  await page.handleComponentWorkTagTap({ currentTarget: { dataset: { tagId: 7 } } })

  assert.deepEqual(requests[2].data, {
    keyword: '迎宾',
    tagId: 7,
    mediaType: 'IMAGE',
    auditStatus: 'PASSED',
    page: 1,
    pageSize: 20
  })
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.id), [21])

  page.handleComponentWorkKeywordInput({ detail: { value: '' } })
  await page.handleComponentWorkSearchConfirm()

  assert.deepEqual(requests[3].data, {
    keyword: '',
    tagId: 7,
    mediaType: 'IMAGE',
    auditStatus: 'PASSED',
    page: 1,
    pageSize: 20
  })
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.id), [31])

  await page.handleComponentWorkScrollToLower()

  assert.deepEqual(requests[4].data, {
    keyword: '',
    tagId: 7,
    mediaType: 'IMAGE',
    auditStatus: 'PASSED',
    page: 2,
    pageSize: 20
  })
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.id), [31, 32])
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.selected), [false, true])
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.selectionOrder), [0, 2])
  assert.equal(page.data.componentWorkHasMore, false)
})

test('tapping profile component edits independent profile copy', async () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, {
        componentKey: 'c_profile',
        sortOrder: 1000,
        config: {
          profile: {
            displayName: '林安',
            bio: '温暖沉稳',
            tags: [{ name: '高端婚礼', color: '#0f766e' }]
          },
          visibleFields: {
            avatar: false,
            displayName: true,
            profession: true,
            city: true,
            bio: true,
            tags: true,
            wechatQr: false
          }
        }
      })
    ]
  })

  await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_profile', type: COMPONENT_TYPES.PROFILE } } })

  assert.equal(page.data.profileSheetVisible, true)
  assert.equal(page.data.editingProfileComponentKey, 'c_profile')
  assert.equal(page.data.profileForm.displayName, '林安')
  assert.equal(page.data.profileForm.bio, '温暖沉稳')
  assert.deepEqual(
    page.data.profileForm.tags.map((item) => ({ content: item.content, color: item.color })),
    [{ content: '高端婚礼', color: '#0f766e' }]
  )
  assert.deepEqual(
    page.data.profileVisibleOptions.map((item) => ({ field: item.field, checked: item.checked })),
    [
      { field: 'avatar', checked: false },
      { field: 'displayName', checked: true },
      { field: 'profession', checked: true },
      { field: 'city', checked: true },
      { field: 'bio', checked: true },
      { field: 'tags', checked: true },
      { field: 'wechatQr', checked: false }
    ]
  )

  page.handleRemoveProfileTag({ currentTarget: { dataset: { index: 0 } } })
  assert.deepEqual(page.data.profileForm.tags, [])

  page.handleOpenProfileTagDialog()
  page.handleProfileNewTagInput({ detail: { value: '主持' } })
  page.handleSelectProfileTagColor({ currentTarget: { dataset: { color: '#2d5f9a' } } })
  page.handleAddProfileTag()

  assert.deepEqual(
    page.data.profileForm.tags.map((item) => ({ content: item.content, color: item.color })),
    [{ content: '主持', color: '#2d5f9a' }]
  )

  page.handleProfileInput({ currentTarget: { dataset: { field: 'displayName' } }, detail: { value: '沈佳磊' } })
  page.handleProfileVisibleFieldChange({ currentTarget: { dataset: { field: 'profession' } }, detail: { value: false } })
  page.handleConfirmProfileSheet()

  assert.equal(page.data.profileSheetVisible, false)
  assert.equal(page.data.editingProfileComponentKey, '')
  assert.equal(page.data.config.components[0].config.profile.displayName, '沈佳磊')
  assert.deepEqual(page.data.config.components[0].config.profile.tags, [
    { name: '主持', color: '#2d5f9a' }
  ])
  assert.equal(page.data.config.components[0].config.visibleFields.profession, false)
})

test('profile tag changes stay local until the profile sheet is confirmed', async () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, {
        componentKey: 'c_profile',
        config: {
          profile: {
            tags: [{ name: '主持', color: '#2d5f9a' }]
          }
        }
      })
    ]
  })

  await page.handleComponentTap({
    currentTarget: { dataset: { key: 'c_profile', type: COMPONENT_TYPES.PROFILE } }
  })
  page.handleOpenProfileTagDialog()
  page.handleProfileNewTagInput({ detail: { value: '主持' } })
  page.handleAddProfileTag()

  assert.equal(page.data.profileTagErrorText, '标签不能重复')
  page.handleCloseProfileTagDialog()
  page.handleRemoveProfileTag({ currentTarget: { dataset: { index: 0 } } })
  page.handleCloseProfileSheet()

  assert.deepEqual(page.data.config.components[0].config.profile.tags, [
    { name: '主持', color: '#2d5f9a' }
  ])
})

test('qr contact source tab switch reloads basic profile image and clears stale image before saving', async () => {
  const requests = []
  let basicProfileQrUrl = 'https://cdn.example.com/basic-profile-qr.jpg'
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/profile') {
      return Promise.resolve({ wechatQrUrl: basicProfileQrUrl })
    }
    return Promise.resolve({})
  }
  const basicProfileRequests = () => requests.filter((options) => options.url === '/api/mine/profile')
  const page = loadPortfolioEditorPage(fakeRequest)
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, {
        componentKey: 'c_profile',
        sortOrder: 1000,
        config: {
          profile: {
            wechatQrUrl: 'https://cdn.example.com/stale-component-profile-qr.jpg'
          }
        }
      }),
      createComponent(COMPONENT_TYPES.QR_CONTACT, {
        componentKey: 'c_qr',
        sortOrder: 2000,
        config: {
          title: '微信咨询',
          description: '扫码沟通档期',
          qrSize: 240,
          showLabel: true,
          qrUrlSource: 'CUSTOM',
          qrUrl: 'wxfile://tmp/custom-qr.jpg'
        }
      })
    ]
  })

  await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_qr', type: COMPONENT_TYPES.QR_CONTACT } } })

  assert.equal(page.data.qrContactSheetVisible, true)
  assert.equal(page.data.qrContactForm.qrUrl, 'wxfile://tmp/custom-qr.jpg')
  assert.equal(page.data.qrContactProfileQrUrl, '')

  await page.handleUseProfileQrContact()
  assert.equal(basicProfileRequests().length, 1)
  assert.equal(page.data.qrContactForm.qrUrlSource, 'PROFILE')
  assert.equal(page.data.qrContactForm.qrUrl, '')
  assert.equal(page.data.qrContactProfileQrUrl, 'https://cdn.example.com/basic-profile-qr.jpg')
  page.handleConfirmQrContactSheet()

  assert.equal(page.data.config.components[1].config.qrUrlSource, 'PROFILE')
  assert.equal(page.data.config.components[1].config.qrUrl, '')
  assert.equal(page.data.config.components[1].config.qrSize, 240)
  assert.equal(page.data.config.components[1].config.showLabel, true)
  assert.equal(page.data.config.components[1].config.title, undefined)
  assert.equal(page.data.config.components[1].config.description, undefined)
  assert.equal(page.data.qrContactProfileQrUrl, '')

  await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_qr', type: COMPONENT_TYPES.QR_CONTACT } } })
  assert.equal(basicProfileRequests().length, 2)
  assert.equal(page.data.qrContactProfileQrUrl, 'https://cdn.example.com/basic-profile-qr.jpg')
  page.handleUseCustomQrContact()

  assert.equal(page.data.qrContactProfileQrUrl, '')

  page.setQrContactImageUrl('wxfile://tmp/custom-qr-next.jpg')
  page.handleUseCustomQrContact()

  assert.equal(page.data.qrContactForm.qrUrlSource, 'CUSTOM')
  assert.equal(page.data.qrContactForm.qrUrl, 'wxfile://tmp/custom-qr-next.jpg')

  page.data.config.components[0].config.profile.wechatQrUrl = 'https://cdn.example.com/ignored-component-profile-qr.jpg'
  basicProfileQrUrl = 'https://cdn.example.com/basic-profile-qr-updated.jpg'
  const requestCountBeforeProfileSwitch = basicProfileRequests().length
  await page.handleUseProfileQrContact()

  assert.equal(basicProfileRequests().length, requestCountBeforeProfileSwitch + 1)
  assert.equal(page.data.qrContactForm.qrUrlSource, 'PROFILE')
  assert.equal(page.data.qrContactForm.qrUrl, '')
  assert.equal(page.data.qrContactProfileQrUrl, 'https://cdn.example.com/basic-profile-qr-updated.jpg')

  page.handleUseCustomQrContact()

  assert.equal(page.data.qrContactForm.qrUrlSource, 'CUSTOM')
  assert.equal(page.data.qrContactForm.qrUrl, '')
  assert.equal(page.data.qrContactProfileQrUrl, '')
})

test('create mode defaults profile component from basic profile', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/profile') {
      return Promise.resolve({
        displayName: '丁Sir',
        avatarUrl: 'https://cos.example.com/avatar.jpg',
        profession: '全栈',
        city: '杭州、湖州',
        intro: 'OPC',
        tags: [
          { content: '主持', color: '#2d5f9a' },
          { content: '测试', color: '#8a4b09' }
        ]
      })
    }
    return Promise.resolve({})
  }
  const page = loadPortfolioEditorPage(fakeRequest)
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_profile', sortOrder: 1000 })
    ]
  })

  await page.onLoad({})

  const profile = page.data.config.components[0].config.profile
  assert.equal(requests[0].url, '/api/mine/profile')
  assert.equal(profile.avatarUrl, 'https://cos.example.com/avatar.jpg')
  assert.equal(profile.displayName, '丁Sir')
  assert.equal(profile.profession, '全栈')
  assert.equal(profile.city, '杭州、湖州')
  assert.equal(profile.bio, 'OPC')
  assert.deepEqual(profile.tags, [
    { name: '主持', color: '#2d5f9a' },
    { name: '测试', color: '#8a4b09' }
  ])
})

test('profile refresh copies nickname without profession suffix', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/profile') {
      return Promise.resolve({
        nickname: '丁Sir',
        avatarUrl: 'https://cos.example.com/avatar.jpg',
        profession: '全栈',
        city: '杭州、湖州',
        intro: 'OPC',
        tags: [{ content: '主持' }]
      })
    }
    return Promise.resolve({})
  }
  const page = loadPortfolioEditorPage(fakeRequest)

  await page.refreshProfileFromBase()

  assert.equal(requests[0].url, '/api/mine/profile')
  assert.equal(page.data.profileForm.displayName, '丁Sir')
  assert.equal(page.data.profileForm.profession, '全栈')
})

test('profile refresh keeps confirmation and overwrites independent tags only after confirm', async () => {
  const requests = []
  const modals = []
  let confirmRefresh = false
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/profile') {
      return Promise.resolve({
        tags: [{ content: '基础标签', color: '#2d5f9a' }]
      })
    }
    return Promise.resolve({})
  }
  const page = loadPortfolioEditorPage(fakeRequest, {
    showModal(options) {
      modals.push(options)
      options.success({ confirm: confirmRefresh })
    }
  })
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, {
        componentKey: 'c_profile',
        config: {
          profile: {
            tags: [{ name: '作品集标签', color: '#8a4b09' }]
          }
        }
      })
    ]
  })

  await page.handleComponentTap({
    currentTarget: { dataset: { key: 'c_profile', type: COMPONENT_TYPES.PROFILE } }
  })
  await page.handleRefreshProfileFromBase()

  assert.equal(modals[0].content, '将用当前基础信息覆盖作品集内个人资料副本，是否继续？')
  assert.equal(modals[0].confirmText, '刷新')
  assert.equal(requests.length, 0)
  assert.deepEqual(
    page.data.profileForm.tags.map((item) => ({ content: item.content, color: item.color })),
    [{ content: '作品集标签', color: '#8a4b09' }]
  )

  confirmRefresh = true
  await page.handleRefreshProfileFromBase()

  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/mine/profile')
  assert.deepEqual(
    page.data.profileForm.tags.map((item) => ({ content: item.content, color: item.color })),
    [{ content: '基础标签', color: '#2d5f9a' }]
  )
})

test('profile sheet chooses avatar from media picker without url input', async () => {
  let chooseOptions = null
  const page = loadPortfolioEditorPage(() => Promise.resolve({}), {
    chooseMedia(options) {
      chooseOptions = options
      options.success({
        tempFiles: [
          { tempFilePath: 'wxfile://tmp/profile-avatar.jpg' }
        ]
      })
    }
  })
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, {
        componentKey: 'c_profile',
        sortOrder: 1000,
        config: {
          profile: { displayName: '林安' }
        }
      })
    ]
  })

  await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_profile', type: COMPONENT_TYPES.PROFILE } } })
  page.handleChooseProfileAvatar()

  assert.deepEqual(chooseOptions.mediaType, ['image'])
  assert.equal(page.data.profileForm.avatarUrl, 'wxfile://tmp/profile-avatar.jpg')
})

test('profile sheet chooses wechat qr from media picker without url input', async () => {
  let chooseOptions = null
  const page = loadPortfolioEditorPage(() => Promise.resolve({}), {
    chooseMedia(options) {
      chooseOptions = options
      options.success({
        tempFiles: [
          { tempFilePath: 'wxfile://tmp/profile-wechat-qr.jpg' }
        ]
      })
    }
  })
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, {
        componentKey: 'c_profile',
        sortOrder: 1000,
        config: {
          profile: { displayName: '林安' }
        }
      })
    ]
  })

  await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_profile', type: COMPONENT_TYPES.PROFILE } } })
  page.handleChooseProfileWechatQr()

  assert.deepEqual(chooseOptions.mediaType, ['image'])
  assert.equal(page.data.profileForm.wechatQrUrl, 'wxfile://tmp/profile-wechat-qr.jpg')
})

test('tapping work grid component loads tag-driven display group selector', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/works/tags') {
      return Promise.resolve({
        tags: [
          { id: 8, name: '户外案例' },
          { id: 9, name: '室内案例' },
          { id: 10, name: '快剪视频' }
        ]
      })
    }
    if (options.url === '/api/mine/works') {
      return Promise.resolve({
        page: 1,
        pageSize: 100,
        hasMore: false,
        works: [
          { id: 21, mediaType: 'IMAGE', title: '户外仪式', coverUrl: 'https://example.com/21.jpg', aspectRatio: '4:3', tags: [{ id: 8, name: '户外案例' }] },
          { id: 22, mediaType: 'VIDEO', title: '户外快剪', coverUrl: 'https://example.com/22.jpg', aspectRatio: '16:9', tags: [{ id: 8, name: '户外案例' }, { id: 10, name: '快剪视频' }] },
          { id: 31, mediaType: 'IMAGE', title: '室内迎宾', coverUrl: 'https://example.com/31.jpg', aspectRatio: '1:1', tags: [{ id: 9, name: '室内案例' }] }
        ]
      })
    }
    return Promise.resolve({})
  }
  const page = loadPortfolioEditorPage(fakeRequest)
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.WORK_GRID, {
        componentKey: 'c_grid',
        sortOrder: 1000,
        config: {
          groups: [
            { groupKey: 'tag_8', name: '户外案例', sortOrder: 1000, workIds: [22, 21] },
            { groupKey: 'tag_9', name: '室内案例', sortOrder: 2000, workIds: [] }
          ]
        }
      })
    ]
  })

  await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_grid', type: COMPONENT_TYPES.WORK_GRID } } })
  await flushPromises()

  assert.equal(page.data.displayGroupSheetVisible, true)
  assert.equal(page.data.editingDisplayComponentKey, 'c_grid')
  assert.equal(page.data.activeDisplayGroupKey, 'tag_8')
  assert.deepEqual(requests.map((item) => item.url), ['/api/mine/works/tags', '/api/mine/works'])
  assert.deepEqual(requests[1].data, { auditStatus: 'PASSED', page: 1, pageSize: 100 })
  assert.deepEqual(page.data.displayGroupOptions.map((item) => item.name), ['户外案例', '室内案例', '快剪视频'])
  assert.deepEqual(page.data.displayGroupOptions.map((item) => item.selectionOrder), [1, 2, 0])
  assert.deepEqual(page.data.displayGroupOptions.map((item) => item.selected), [true, true, false])
  assert.deepEqual(page.data.displayGroupOptions.map((item) => item.countText), ['2', '0', '0'])
  assert.deepEqual(page.data.displayGroupWorkOptions.map((item) => item.id), [21, 22])
  assert.deepEqual(page.data.displayGroupWorkOptions.map((item) => item.aspectRatioText), ['4:3', '16:9'])
  assert.deepEqual(page.data.displayGroupWorkOptions.map((item) => item.selectionOrder), [2, 1])
  assert.deepEqual(page.data.displayGroupWorkOptions.map((item) => item.selected), [true, true])
})

test('work grid and list editors keep saved selected works visible without catalog tag relations', async () => {
  for (const componentType of [COMPONENT_TYPES.WORK_GRID, COMPONENT_TYPES.WORK_LIST]) {
    const requests = []
    const fakeRequest = (options) => {
      requests.push(options)
      if (options.url === '/api/mine/works/tags') {
        return Promise.resolve({
          tags: [
            { id: 8, name: '户外案例' }
          ]
        })
      }
      if (options.url === '/api/mine/works') {
        return Promise.resolve({
          page: 1,
          pageSize: 100,
          hasMore: false,
          works: [
            { id: 21, mediaType: 'IMAGE', title: '户外仪式', coverUrl: 'https://example.com/21.jpg' },
            { id: 22, mediaType: 'VIDEO', title: '户外快剪', coverUrl: 'https://example.com/22.jpg' }
          ]
        })
      }
      return Promise.resolve({})
    }
    const page = loadPortfolioEditorPage(fakeRequest)
    page.data.config = normalizePortfolioConfig({
      components: [
        createComponent(componentType, {
          componentKey: 'c_display',
          sortOrder: 1000,
          config: {
            groups: [
              { groupKey: 'tag_8', name: '户外案例', sortOrder: 1000, workIds: [22, 21] }
            ]
          }
        })
      ]
    })

    await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_display', type: componentType } } })
    await flushPromises()

    const workRequest = requests.find((item) => item.url === '/api/mine/works')
    assert.deepEqual(workRequest.data, { auditStatus: 'PASSED', page: 1, pageSize: 100 })
    assert.equal(page.data.activeDisplayGroupWorkCountText, '2 个已选')
    assert.deepEqual(page.data.displayGroupWorkOptions.map((item) => item.id), [22, 21])
    assert.deepEqual(page.data.displayGroupWorkOptions.map((item) => item.selectionOrder), [1, 2])
    assert.deepEqual(page.data.displayGroupWorkOptions.map((item) => item.selected), [true, true])
  }
})

test('work list display group records tag and work selection order independently', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/works/tags') {
      return Promise.resolve({
        tags: [
          { id: 8, name: '户外案例' },
          { id: 9, name: '室内案例' }
        ]
      })
    }
    if (options.url === '/api/mine/works') {
      return Promise.resolve({
        page: 1,
        pageSize: 100,
        hasMore: false,
        works: [
          { id: 21, mediaType: 'IMAGE', title: '户外仪式', coverUrl: 'https://example.com/21.jpg', tags: [{ id: 8, name: '户外案例' }] },
          { id: 22, mediaType: 'VIDEO', title: '户外快剪', coverUrl: 'https://example.com/22.jpg', tags: [{ id: 8, name: '户外案例' }] },
          { id: 31, mediaType: 'IMAGE', title: '室内迎宾', coverUrl: 'https://example.com/31.jpg', tags: [{ id: 9, name: '室内案例' }] },
          { id: 32, mediaType: 'VIDEO', title: '室内快剪', coverUrl: 'https://example.com/32.jpg', tags: [{ id: 9, name: '室内案例' }] }
        ]
      })
    }
    return Promise.resolve({})
  }
  const page = loadPortfolioEditorPage(fakeRequest)
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.WORK_LIST, {
        componentKey: 'c_list',
        sortOrder: 1000,
        config: {
          groups: []
        }
      })
    ]
  })

  await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_list', type: COMPONENT_TYPES.WORK_LIST } } })
  await flushPromises()

  assert.equal(requests[0].url, '/api/mine/works/tags')
  assert.equal(requests[1].url, '/api/mine/works')
  assert.deepEqual(requests[1].data, { auditStatus: 'PASSED', page: 1, pageSize: 100 })
  assert.equal(page.data.componentWorkSheetVisible, false)

  page.handleToggleDisplayGroupTag({ currentTarget: { dataset: { tagId: 8 } } })
  page.handleToggleDisplayGroupTag({ currentTarget: { dataset: { tagId: 9 } } })

  assert.deepEqual(page.data.config.components[0].config.groups.map((item) => item.groupKey), ['tag_8', 'tag_9'])
  assert.deepEqual(page.data.config.components[0].config.groups.map((item) => item.sortOrder), [1000, 2000])
  assert.deepEqual(page.data.displayGroupOptions.map((item) => item.selectionOrder), [1, 2])

  page.handleSelectDisplayGroup({ currentTarget: { dataset: { groupKey: 'tag_9' } } })
  page.handleToggleDisplayGroupWork({ currentTarget: { dataset: { id: 31 } } })
  page.handleToggleDisplayGroupWork({ currentTarget: { dataset: { id: 32 } } })

  assert.deepEqual(page.data.config.components[0].config.groups[1].workIds, [31, 32])
  assert.deepEqual(page.data.displayGroupWorkOptions.map((item) => item.selectionOrder), [1, 2])

  page.handleSelectDisplayGroup({ currentTarget: { dataset: { groupKey: 'tag_8' } } })
  page.handleToggleDisplayGroupWork({ currentTarget: { dataset: { id: 22 } } })
  page.handleToggleDisplayGroupWork({ currentTarget: { dataset: { id: 21 } } })

  assert.deepEqual(page.data.config.components[0].config.groups[0].workIds, [22, 21])
  assert.deepEqual(page.data.config.components[0].config.groups[1].workIds, [31, 32])
  assert.deepEqual(page.data.displayGroupWorkOptions.map((item) => item.selectionOrder), [2, 1])

  page.handleToggleDisplayGroupTag({ currentTarget: { dataset: { tagId: 8 } } })

  assert.deepEqual(page.data.config.components[0].config.groups.map((item) => item.groupKey), ['tag_9'])
  assert.deepEqual(page.data.config.components[0].config.groups.map((item) => item.sortOrder), [1000])
  assert.deepEqual(page.data.displayGroupOptions.map((item) => item.selectionOrder), [0, 1])
})

test('display group sheet cancel restores selections and confirm keeps them', async () => {
  const fakeRequest = (options) => {
    if (options.url === '/api/mine/works/tags') {
      return Promise.resolve({
        tags: [
          { id: 8, name: '户外案例' },
          { id: 9, name: '室内案例' }
        ]
      })
    }
    if (options.url === '/api/mine/works') {
      return Promise.resolve({
        page: 1,
        pageSize: 100,
        hasMore: false,
        works: [
          { id: 21, mediaType: 'IMAGE', title: '户外仪式', coverUrl: 'https://example.com/21.jpg', tags: [{ id: 8, name: '户外案例' }] },
          { id: 31, mediaType: 'IMAGE', title: '室内迎宾', coverUrl: 'https://example.com/31.jpg', tags: [{ id: 9, name: '室内案例' }] }
        ]
      })
    }
    return Promise.resolve({})
  }
  const page = loadPortfolioEditorPage(fakeRequest)
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.WORK_GRID, {
        componentKey: 'c_grid',
        sortOrder: 1000,
        config: {
          groups: [
            { groupKey: 'tag_8', name: '户外案例', sortOrder: 1000, workIds: [21] }
          ]
        }
      })
    ]
  })

  await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_grid', type: COMPONENT_TYPES.WORK_GRID } } })
  await flushPromises()
  page.handleToggleDisplayGroupTag({ currentTarget: { dataset: { tagId: 9 } } })
  page.handleCancelDisplayGroupSheet()

  assert.deepEqual(page.data.config.components[0].config.groups.map((item) => item.groupKey), ['tag_8'])
  assert.equal(page.data.displayGroupSheetVisible, false)

  await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_grid', type: COMPONENT_TYPES.WORK_GRID } } })
  await flushPromises()
  page.handleToggleDisplayGroupTag({ currentTarget: { dataset: { tagId: 9 } } })
  page.handleConfirmDisplayGroupSheet()

  assert.deepEqual(page.data.config.components[0].config.groups.map((item) => item.groupKey), ['tag_8', 'tag_9'])
  assert.equal(page.data.displayGroupSheetVisible, false)
})

test('saving draft in create mode creates portfolio before saving draft', async () => {
  const requests = []
  const toasts = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/portfolios/standard-personal') {
      return Promise.resolve({ portfolioId: 88, draftRevision: 0, publishedRevision: 0 })
    }
    return Promise.resolve({ portfolioId: 88, draftRevision: 1, publishedRevision: 0 })
  }
  const page = loadPortfolioEditorPage(fakeRequest, {
    showToast(options) {
      toasts.push(options)
    }
  })
  page.data.portfolioId = null
  page.data.config = normalizePortfolioConfig({
    share: { title: '新建作品集' },
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_profile', sortOrder: 1000 })
    ]
  })

  await page.handleSaveDraft()

  assert.equal(requests[0].url, '/api/mine/portfolios/standard-personal')
  assert.equal(requests[0].method, 'POST')
  assert.equal(requests[0].data.config.share.title, '新建作品集')
  assert.equal(requests[1].url, '/api/mine/portfolios/88/draft')
  assert.equal(requests[1].method, 'PUT')
  assert.equal(requests[1].data.clientRevision, 0)
  assert.equal(page.data.portfolioId, 88)
  assert.equal(page.data.draftRevision, 1)
  assert.equal(toasts[0].title, '草稿已保存')
})

test('saving draft in create mode does not send local image paths when creating portfolio', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/portfolios/standard-personal') {
      return Promise.resolve({ portfolioId: 88, draftRevision: 0, publishedRevision: 0 })
    }
    return Promise.resolve({ portfolioId: 88, draftRevision: 1, publishedRevision: 0 })
  }
  const page = loadPortfolioEditorPage(fakeRequest, {}, {
    uploadPortfolioImageAsset(portfolioId, filePath, options) {
      return Promise.resolve(`https://cos.example.com/${portfolioId}/${options.assetType}.jpg`)
    }
  })
  page.data.portfolioId = null
  page.data.config = normalizePortfolioConfig({
    share: {
      title: '新建作品集',
      coverUrl: 'wxfile://tmp/local-cover.jpg'
    },
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, {
        componentKey: 'c_profile',
        sortOrder: 1000,
        config: {
          profile: {
            avatarUrl: 'wxfile://tmp/profile-avatar.jpg',
            wechatQrUrl: 'wxfile://tmp/profile-wechat-qr.jpg',
            displayName: '林安'
          }
        }
      }),
      createComponent(COMPONENT_TYPES.QR_CONTACT, {
        componentKey: 'c_qr',
        sortOrder: 2000,
        config: {
          qrUrlSource: 'CUSTOM',
          qrUrl: 'wxfile://tmp/qr-contact.jpg'
        }
      })
    ]
  })

  await page.handleSaveDraft()

  assert.equal(requests[0].url, '/api/mine/portfolios/standard-personal')
  assert.equal(requests[0].data.config.share.coverUrl, '')
  assert.equal(requests[0].data.config.components[0].config.profile.avatarUrl, '')
  assert.equal(requests[0].data.config.components[0].config.profile.wechatQrUrl, '')
  assert.equal(requests[0].data.config.components[1].config.qrUrl, '')
  assert.equal(requests[1].data.config.share.coverUrl, 'https://cos.example.com/88/COVER.jpg')
  assert.equal(requests[1].data.config.components[0].config.profile.avatarUrl, 'https://cos.example.com/88/PROFILE_AVATAR.jpg')
  assert.equal(requests[1].data.config.components[0].config.profile.wechatQrUrl, 'https://cos.example.com/88/QR_CONTACT.jpg')
  assert.equal(requests[1].data.config.components[1].config.qrUrl, 'https://cos.example.com/88/QR_CONTACT.jpg')
})

test('saving draft in edit mode updates current portfolio directly', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    return Promise.resolve({ portfolioId: 88, draftRevision: 4 })
  }
  const page = loadPortfolioEditorPage(fakeRequest)
  page.data.portfolioId = 88
  page.data.draftRevision = 3

  await page.handleSaveDraft()

  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/mine/portfolios/88/draft')
  assert.equal(requests[0].method, 'PUT')
  assert.equal(requests[0].data.clientRevision, 3)
  assert.equal(page.data.draftRevision, 4)
})

test('saving draft preserves earlier uploaded local assets when later uploads update config', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    return Promise.resolve({ portfolioId: 88, draftRevision: 4 })
  }
  const page = loadPortfolioEditorPage(fakeRequest, {}, {
    uploadPortfolioImageAsset(portfolioId, filePath, options) {
      return Promise.resolve(`https://cos.example.com/${portfolioId}/${options.assetType}.jpg`)
    }
  }, {
    deferFirstConfigSetData: true
  })
  page.data.portfolioId = 88
  page.data.draftRevision = 3
  page.data.config = normalizePortfolioConfig({
    share: {
      title: '林安婚礼司仪',
      coverUrl: 'wxfile://tmp/local-cover.jpg'
    },
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, {
        componentKey: 'c_profile',
        sortOrder: 1000,
        config: {
          profile: {
            avatarUrl: 'wxfile://tmp/profile-avatar.jpg',
            displayName: '林安'
          }
        }
      }),
      createComponent(COMPONENT_TYPES.QR_CONTACT, {
        componentKey: 'c_qr',
        sortOrder: 2000,
        config: {
          qrUrlSource: 'CUSTOM',
          qrUrl: 'wxfile://tmp/qr-contact.jpg'
        }
      })
    ]
  })

  await page.handleSaveDraft()

  assert.equal(requests[0].url, '/api/mine/portfolios/88/draft')
  assert.equal(requests[0].data.config.share.coverUrl, 'https://cos.example.com/88/COVER.jpg')
  assert.equal(requests[0].data.config.components[0].config.profile.avatarUrl, 'https://cos.example.com/88/PROFILE_AVATAR.jpg')
  assert.equal(requests[0].data.config.components[1].config.qrUrl, 'https://cos.example.com/88/QR_CONTACT.jpg')
})

test('saving draft uploads local portfolio cover before saving config', async () => {
  const requests = []
  const uploads = []
  const fakeRequest = (options) => {
    requests.push(options)
    return Promise.resolve({ portfolioId: 88, draftRevision: 4 })
  }
  const page = loadPortfolioEditorPage(fakeRequest, {}, {
    uploadPortfolioImageAsset(portfolioId, filePath, options) {
      uploads.push({ portfolioId, filePath, assetType: options.assetType })
      return Promise.resolve('https://cos.example.com/WFA3B1E7A2/protfolio/cover-88-20260702120000-a1b2c3d4.jpg')
    }
  })
  page.data.portfolioId = 88
  page.data.draftRevision = 3
  page.data.config = normalizePortfolioConfig({
    share: {
      title: '林安婚礼司仪',
      coverUrl: 'wxfile://tmp/local-cover.jpg'
    },
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_profile', sortOrder: 1000 })
    ]
  })

  await page.handleSaveDraft()

  assert.deepEqual(uploads, [
    { portfolioId: 88, filePath: 'wxfile://tmp/local-cover.jpg', assetType: 'COVER' }
  ])
  assert.equal(requests[0].url, '/api/mine/portfolios/88/draft')
  assert.equal(
    requests[0].data.config.share.coverUrl,
    'https://cos.example.com/WFA3B1E7A2/protfolio/cover-88-20260702120000-a1b2c3d4.jpg'
  )
  assert.equal(
    page.data.config.share.coverUrl,
    'https://cos.example.com/WFA3B1E7A2/protfolio/cover-88-20260702120000-a1b2c3d4.jpg'
  )
})

test('saving draft uploads local profile avatar before saving config', async () => {
  const requests = []
  const uploads = []
  const fakeRequest = (options) => {
    requests.push(options)
    return Promise.resolve({ portfolioId: 88, draftRevision: 4 })
  }
  const page = loadPortfolioEditorPage(fakeRequest, {}, {
    uploadPortfolioImageAsset(portfolioId, filePath, options) {
      uploads.push({ portfolioId, filePath, assetType: options.assetType })
      return Promise.resolve('https://cos.example.com/WFA3B1E7A2/protfolio/profile-avatar-88-20260702120000-a1b2c3d4.jpg')
    }
  })
  page.data.portfolioId = 88
  page.data.draftRevision = 3
  page.data.config = normalizePortfolioConfig({
    share: {
      title: '林安婚礼司仪'
    },
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, {
        componentKey: 'c_profile',
        sortOrder: 1000,
        config: {
          profile: {
            avatarUrl: 'wxfile://tmp/profile-avatar.jpg',
            displayName: '林安'
          }
        }
      })
    ]
  })

  await page.handleSaveDraft()

  assert.deepEqual(uploads, [
    { portfolioId: 88, filePath: 'wxfile://tmp/profile-avatar.jpg', assetType: 'PROFILE_AVATAR' }
  ])
  assert.equal(requests[0].url, '/api/mine/portfolios/88/draft')
  assert.equal(
    requests[0].data.config.components[0].config.profile.avatarUrl,
    'https://cos.example.com/WFA3B1E7A2/protfolio/profile-avatar-88-20260702120000-a1b2c3d4.jpg'
  )
  assert.equal(
    page.data.config.components[0].config.profile.avatarUrl,
    'https://cos.example.com/WFA3B1E7A2/protfolio/profile-avatar-88-20260702120000-a1b2c3d4.jpg'
  )
})

test('saving draft uploads local profile wechat qr before saving config', async () => {
  const requests = []
  const uploads = []
  const fakeRequest = (options) => {
    requests.push(options)
    return Promise.resolve({ portfolioId: 88, draftRevision: 4 })
  }
  const page = loadPortfolioEditorPage(fakeRequest, {}, {
    uploadPortfolioImageAsset(portfolioId, filePath, options) {
      uploads.push({ portfolioId, filePath, assetType: options.assetType })
      return Promise.resolve('https://cos.example.com/WFA3B1E7A2/protfolio/qr-contact-88-20260702120000-a1b2c3d4.jpg')
    }
  })
  page.data.portfolioId = 88
  page.data.draftRevision = 3
  page.data.config = normalizePortfolioConfig({
    share: {
      title: '林安婚礼司仪'
    },
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, {
        componentKey: 'c_profile',
        sortOrder: 1000,
        config: {
          profile: {
            wechatQrUrl: 'wxfile://tmp/profile-wechat-qr.jpg',
            displayName: '林安'
          }
        }
      })
    ]
  })

  await page.handleSaveDraft()

  assert.deepEqual(uploads, [
    { portfolioId: 88, filePath: 'wxfile://tmp/profile-wechat-qr.jpg', assetType: 'QR_CONTACT' }
  ])
  assert.equal(requests[0].url, '/api/mine/portfolios/88/draft')
  assert.equal(
    requests[0].data.config.components[0].config.profile.wechatQrUrl,
    'https://cos.example.com/WFA3B1E7A2/protfolio/qr-contact-88-20260702120000-a1b2c3d4.jpg'
  )
  assert.equal(
    page.data.config.components[0].config.profile.wechatQrUrl,
    'https://cos.example.com/WFA3B1E7A2/protfolio/qr-contact-88-20260702120000-a1b2c3d4.jpg'
  )
})

test('saving draft uploads local qr contact image before saving config', async () => {
  const requests = []
  const uploads = []
  const fakeRequest = (options) => {
    requests.push(options)
    return Promise.resolve({ portfolioId: 88, draftRevision: 4 })
  }
  const page = loadPortfolioEditorPage(fakeRequest, {}, {
    uploadPortfolioImageAsset(portfolioId, filePath, options) {
      uploads.push({ portfolioId, filePath, assetType: options.assetType })
      return Promise.resolve('https://cos.example.com/WFA3B1E7A2/protfolio/qr-contact-88-20260702120000-a1b2c3d4.jpg')
    }
  })
  page.data.portfolioId = 88
  page.data.draftRevision = 3
  page.data.config = normalizePortfolioConfig({
    share: {
      title: '林安婚礼司仪'
    },
    components: [
      createComponent(COMPONENT_TYPES.QR_CONTACT, {
        componentKey: 'c_qr',
        sortOrder: 1000,
        config: {
          qrUrlSource: 'CUSTOM',
          qrUrl: 'wxfile://tmp/qr-contact.jpg'
        }
      })
    ]
  })

  await page.handleSaveDraft()

  assert.deepEqual(uploads, [
    { portfolioId: 88, filePath: 'wxfile://tmp/qr-contact.jpg', assetType: 'QR_CONTACT' }
  ])
  assert.equal(requests[0].url, '/api/mine/portfolios/88/draft')
  assert.equal(
    requests[0].data.config.components[0].config.qrUrl,
    'https://cos.example.com/WFA3B1E7A2/protfolio/qr-contact-88-20260702120000-a1b2c3d4.jpg'
  )
  assert.equal(
    page.data.config.components[0].config.qrUrl,
    'https://cos.example.com/WFA3B1E7A2/protfolio/qr-contact-88-20260702120000-a1b2c3d4.jpg'
  )
})

test('saving draft stores basic profile qr url into profile-source qr contact config', async () => {
  const requests = []
  const fakeRequest = (options) => {
    requests.push(options)
    if (options.url === '/api/mine/profile') {
      return Promise.resolve({ wechatQrUrl: 'https://cdn.example.com/basic-profile-qr-current.jpg' })
    }
    return Promise.resolve({ portfolioId: 88, draftRevision: 4 })
  }
  const page = loadPortfolioEditorPage(fakeRequest)
  page.data.portfolioId = 88
  page.data.draftRevision = 3
  page.data.config = normalizePortfolioConfig({
    share: {
      title: '林安婚礼司仪'
    },
    components: [
      createComponent(COMPONENT_TYPES.QR_CONTACT, {
        componentKey: 'c_qr',
        sortOrder: 1000,
        config: {
          qrUrlSource: 'PROFILE',
          qrUrl: 'https://cdn.example.com/basic-profile-qr-old.jpg',
          qrSize: 240
        }
      })
    ]
  })

  await page.handleSaveDraft()

  const profileRequest = requests.find((options) => options.url === '/api/mine/profile')
  const draftRequest = requests.find((options) => options.url === '/api/mine/portfolios/88/draft')
  assert.ok(profileRequest)
  assert.equal(draftRequest.data.config.components[0].config.qrUrlSource, 'PROFILE')
  assert.equal(
    draftRequest.data.config.components[0].config.qrUrl,
    'https://cdn.example.com/basic-profile-qr-current.jpg'
  )
  assert.equal(draftRequest.data.config.components[0].config.qrSize, 240)
  assert.equal(page.data.config.components[0].config.qrUrl, 'https://cdn.example.com/basic-profile-qr-current.jpg')
})

test('loading a published portfolio shows published status and publish action', async () => {
  const page = loadPortfolioEditorPage((options) => {
    if (options.url === '/api/mine/portfolios/88') {
      return Promise.resolve({
        portfolioId: 88,
        publicationStatus: 'PUBLISHED',
        draftRevision: 5,
        publishedRevision: 4,
        config: {
          share: { title: '林安婚礼司仪' },
          components: []
        }
      })
    }
    return Promise.resolve({})
  })

  await page.onLoad({ portfolioId: 88 })

  assert.equal(page.data.publicationStatus, 'PUBLISHED')
  assert.equal(page.data.statusText, '已发布')
  assert.equal(page.data.statusTone, 'published')
  assert.equal(page.data.showPublishAction, true)
})

test('publishing locates an invalid component before disclaimer or network requests', async () => {
  const requests = []
  const modals = []
  const toasts = []
  const page = loadPortfolioEditorPage((options) => {
    requests.push(options)
    return Promise.resolve({
      portfolioId: 88,
      draftRevision: 6,
      publishedRevision: 5,
      publicationStatus: 'PUBLISHED'
    })
  }, {
    showModal(options) {
      modals.push(options)
      options.success({ confirm: true })
    },
    showToast(options) {
      toasts.push(options)
    },
    navigateBack() {},
    redirectTo() {}
  })
  page.data.portfolioId = 88
  page.data.draftRevision = 5
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_home' })
    ],
    bottomNav: {
      enabled: true,
      items: [
        { key: 'nav_home', title: '主页' },
        {
          key: 'nav_works',
          title: '作品',
          components: [
            createComponent(COMPONENT_TYPES.TEXT_SECTION, {
              componentKey: 'c_empty_text',
              config: { content: '' }
            })
          ]
        }
      ]
    }
  })

  await page.handlePublish()

  assert.deepEqual(modals, [])
  assert.deepEqual(requests, [])
  assert.equal(page.data.activeMenuKey, 'nav_works')
  assert.equal(page.data.validationMenuKey, 'nav_works')
  assert.equal(page.data.validationComponentKey, 'c_empty_text')
  assert.equal(page.data.validationComponentAnchor, 'component-row-c_empty_text')
  assert.equal(page.data.validationMenuMessage, '【作品】文字说明内容不能为空')
  assert.equal(toasts.at(-1).title, '【作品】文字说明内容不能为空')
})

test('publishing from editor saves current draft before publishing and returns to portfolio list', async (t) => {
  const requests = []
  const navigations = []
  const toasts = []
  const modals = []
  const originalGetCurrentPages = global.getCurrentPages
  global.getCurrentPages = () => [
    { route: 'pages/portfolios/portfolios' },
    { route: 'pages/portfolios/standard-edit/portfolio-standard-edit' }
  ]
  t.after(() => {
    if (originalGetCurrentPages) {
      global.getCurrentPages = originalGetCurrentPages
    } else {
      delete global.getCurrentPages
    }
  })
  const page = loadPortfolioEditorPage((options) => {
    requests.push(options)
    if (options.url === '/api/mine/portfolios/88/draft') {
      return Promise.resolve({
        portfolioId: 88,
        publicationStatus: 'PUBLISHED',
        draftRevision: 6,
        publishedRevision: 4
      })
    }
    if (options.url === '/api/mine/portfolios/88/publish') {
      return Promise.resolve({
        portfolioId: 88,
        publicationStatus: 'PUBLISHED',
        draftRevision: 5,
        publishedRevision: 6
      })
    }
    return Promise.resolve({})
  }, {
    navigateBack(options) {
      navigations.push({ type: 'back', options })
    },
    redirectTo(options) {
      navigations.push({ type: 'redirect', options })
    },
    showModal(options) {
      modals.push(options)
      options.success({ confirm: true })
    },
    showToast(options) {
      toasts.push(options)
    }
  })
  page.data.portfolioId = 88
  page.data.draftRevision = 5
  page.data.publicationStatus = 'PUBLISHED'

  await page.handlePublish()

  assert.equal(modals.length, 1)
  assert.equal(modals[0].title, '发布免责声明')
  assert.match(modals[0].content, /肖像/)
  assert.match(modals[0].content, /侵权/)
  assert.equal(modals[0].confirmText, '确认发布')
  assert.deepEqual(requests.map((item) => [item.url, item.method || 'GET']), [
    ['/api/mine/portfolios/88/draft', 'PUT'],
    ['/api/mine/portfolios/88/publish', 'POST']
  ])
  assert.equal(requests[0].data.clientRevision, 5)
  assert.match(requests[0].data.idempotencyKey, /^draft-/)
  assert.equal(requests[1].data.draftRevision, 6)
  assert.match(requests[1].data.idempotencyKey, /^publish-/)
  assert.deepEqual(toasts.map((item) => item.title), ['已发布'])
  assert.deepEqual(navigations, [
    { type: 'back', options: { delta: 1 } }
  ])
})

test('canceling publish disclaimer from editor skips saving and publishing', async () => {
  const requests = []
  const modals = []
  const page = loadPortfolioEditorPage((options) => {
    requests.push(options)
    return Promise.resolve({})
  }, {
    showModal(options) {
      modals.push(options)
      options.success({ confirm: false })
    }
  }, {
    uploadPortfolioImageAsset() {
      throw new Error('should not upload assets after cancel')
    }
  })
  page.data.portfolioId = 88
  page.data.draftRevision = 5
  page.data.publicationStatus = 'DRAFT'

  await page.handlePublish()

  assert.equal(modals.length, 1)
  assert.deepEqual(requests, [])
})

test('saving draft returns to portfolio list so list onShow reloads data', async (t) => {
  const navigations = []
  const originalGetCurrentPages = global.getCurrentPages
  global.getCurrentPages = () => [
    { route: 'pages/portfolios/portfolios' },
    { route: 'pages/portfolios/standard-edit/portfolio-standard-edit' }
  ]
  t.after(() => {
    if (originalGetCurrentPages) {
      global.getCurrentPages = originalGetCurrentPages
    } else {
      delete global.getCurrentPages
    }
  })
  const page = loadPortfolioEditorPage(() => Promise.resolve({ portfolioId: 88, draftRevision: 4 }), {
    navigateBack(options) {
      navigations.push({ type: 'back', options })
    },
    redirectTo(options) {
      navigations.push({ type: 'redirect', options })
    }
  })
  page.data.portfolioId = 88
  page.data.draftRevision = 3

  await page.handleSaveDraft()

  assert.deepEqual(navigations, [
    { type: 'back', options: { delta: 1 } }
  ])
})

test('saving draft redirects to portfolio list when opened without list stack', async (t) => {
  const navigations = []
  const originalGetCurrentPages = global.getCurrentPages
  global.getCurrentPages = () => [
    { route: 'pages/portfolios/standard-edit/portfolio-standard-edit' }
  ]
  t.after(() => {
    if (originalGetCurrentPages) {
      global.getCurrentPages = originalGetCurrentPages
    } else {
      delete global.getCurrentPages
    }
  })
  const page = loadPortfolioEditorPage(() => Promise.resolve({ portfolioId: 88, draftRevision: 4 }), {
    navigateBack(options) {
      navigations.push({ type: 'back', options })
    },
    redirectTo(options) {
      navigations.push({ type: 'redirect', options })
    }
  })
  page.data.portfolioId = 88
  page.data.draftRevision = 3

  await page.handleSaveDraft()

  assert.deepEqual(navigations, [
    { type: 'redirect', options: { url: '/pages/portfolios/portfolios' } }
  ])
})

test('preview action does not navigate before portfolio is created', () => {
  const navigations = []
  const page = loadPortfolioEditorPage(() => Promise.resolve({}), {
    navigateTo(options) {
      navigations.push(options)
    }
  })
  page.data.portfolioId = null

  page.handlePreview()

  assert.deepEqual(navigations, [])
})

test('portfolio page settings match the continuous color picker and guarded menu deletion design', () => {
  const pageRoot = path.join(__dirname, '../pages/portfolios/standard-edit')
  const wxml = fs.readFileSync(path.join(pageRoot, 'portfolio-standard-edit.wxml'), 'utf8')
  const js = fs.readFileSync(path.join(pageRoot, 'portfolio-standard-edit.js'), 'utf8')
  const wxss = fs.readFileSync(path.join(pageRoot, 'portfolio-standard-edit.wxss'), 'utf8')

  assert.match(wxml, /class="background-color-current"/)
  assert.match(wxml, /class="background-color-pad"[\s\S]*bindtouchstart="handleBackgroundColorPadTouch"/)
  assert.match(wxml, /class="background-hue-slider"[\s\S]*bindchanging="handleBackgroundHueChange"/)
  assert.match(wxml, /class="background-hex-input"[\s\S]*bindinput="handleBackgroundHexInput"/)
  assert.match(wxml, /class="background-hex-input"[\s\S]*bindblur="handleBackgroundHexBlur"/)
  assert.match(wxml, /item === 1 \? '不开启' : item \+ ' 个'/)
  assert.match(wxml, /class="editor-component-empty"/)
  assert.match(js, /BACKGROUND_COLOR_OPTIONS\s*=\s*\['#151515', '#FFFFFF', '#F5F6F8'\]/)
  assert.doesNotMatch(js, /请输入 6 位 HEX 色值/)
  assert.match(js, /请输入正确的颜色值/)
  assert.match(js, /handleRemoveEditorMenu\(event\)[\s\S]*wx\.showModal\([\s\S]*confirmText:\s*'删除'/)
  assert.match(js, /message\.match\(\/\^【\(\.\+\?\)】\//)
  assert.match(wxml, /validationMenuKey === item\.key/)
  assert.match(wxml, /scroll-into-view="\{\{validationComponentAnchor\}\}"/)
  assert.match(wxml, /id="component-row-\{\{item\.componentKey\}\}"/)
  assert.match(wxml, /validationComponentKey === item\.componentKey \? 'validation-error' : ''/)
  assert.match(wxss, /\.component-row\.validation-error\s*\{[\s\S]*border-color:\s*#b55656;/)
})

test('production color picker hue thumb matches the centered white-ring design', () => {
  const pageRoot = path.join(__dirname, '../pages/portfolios/standard-edit')
  const wxml = fs.readFileSync(path.join(pageRoot, 'portfolio-standard-edit.wxml'), 'utf8')
  const wxss = fs.readFileSync(path.join(pageRoot, 'portfolio-standard-edit.wxss'), 'utf8')

  assert.match(
    wxml,
    /class="background-hue-thumb"\s+style="left: \{\{backgroundColorHsv\.hue \/ 3\.59\}\}%; background-color: \{\{backgroundHueColor\}\};"/
  )
  assert.match(wxml, /block-color="transparent"/)
  assert.match(
    wxss,
    /\.background-hue-thumb\s*\{[\s\S]*top:\s*50%;[\s\S]*width:\s*44rpx;[\s\S]*height:\s*44rpx;[\s\S]*border:\s*6rpx solid #ffffff;[\s\S]*transform:\s*translate\(-50%, -50%\);/
  )
  assert.doesNotMatch(wxss, /\.background-hue-slider slider\s*\{[^}]*margin:\s*-14rpx/)
})

test('reducing bottom navigation confirms component loss and keeps the previous menu active', () => {
  const modals = []
  const page = loadPortfolioEditorPage(() => Promise.resolve({}), {
    showModal(options) {
      modals.push(options)
    }
  })
  page.data.config = normalizePortfolioConfig({
    components: [createComponent(COMPONENT_TYPES.PROFILE, { componentKey: 'c_home' })],
    bottomNav: {
      enabled: true,
      items: [
        { key: 'nav_home', title: '主页' },
        {
          key: 'nav_works',
          title: '作品',
          components: [createComponent(COMPONENT_TYPES.WORK_GRID, { componentKey: 'c_works' })]
        },
        {
          key: 'nav_contact',
          title: '联系',
          components: [createComponent(COMPONENT_TYPES.CONTACT_FORM, { componentKey: 'c_contact' })]
        }
      ]
    }
  })
  page.data.activeMenuKey = 'nav_contact'

  page.handleBottomNavCountTap({ currentTarget: { dataset: { count: 2 } } })

  assert.equal(modals.length, 1)
  assert.equal(modals[0].content, '删除菜单「联系」将同时删除其下 1 个组件')
  assert.equal(page.data.config.bottomNav.items.length, 3)

  modals[0].success({ confirm: true })

  assert.equal(page.data.config.bottomNav.items.length, 2)
  assert.equal(page.data.activeMenuKey, 'nav_works')
})

test('removing first navigation menu warns about legacy display and promotes the next menu', () => {
  const modals = []
  const page = loadPortfolioEditorPage(() => Promise.resolve({}), {
    showModal(options) {
      modals.push(options)
    }
  })
  page.data.config = normalizePortfolioConfig({
    components: [],
    bottomNav: {
      enabled: true,
      items: [
        { key: 'nav_home', title: '主页' },
        {
          key: 'nav_works',
          title: '作品',
          components: [createComponent(COMPONENT_TYPES.WORK_GRID, { componentKey: 'c_works' })]
        },
        { key: 'nav_contact', title: '联系', components: [] }
      ]
    }
  })
  page.data.activeMenuKey = 'nav_home'

  page.handleRemoveEditorMenu({ currentTarget: { dataset: { key: 'nav_home' } } })

  assert.equal(modals.length, 1)
  assert.equal(
    modals[0].content,
    '删除菜单「主页」，「作品」将成为第一个菜单，旧版本小程序将展示「作品」的内容'
  )

  modals[0].success({ confirm: true })

  assert.equal(page.data.config.bottomNav.items[0].key, 'nav_works')
  assert.equal(page.data.activeMenuKey, 'nav_works')
})
