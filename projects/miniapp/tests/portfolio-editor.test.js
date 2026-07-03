const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const {
  COMPONENT_TYPES,
  createComponent,
  normalizePortfolioConfig
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

function loadPortfolioEditorPage(fakeRequest, wxOverrides = {}, assetOverrides = {}, harnessOptions = {}) {
  const pagePath = path.join(__dirname, '../pages/portfolio-standard-edit/portfolio-standard-edit.js')
  const requestPath = path.join(__dirname, '../utils/request.js')
  const sessionPath = path.join(__dirname, '../utils/session.js')
  const assetsPath = path.join(__dirname, '../utils/portfolio-assets.js')
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
      handleAuthRequired() {},
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

test('standard portfolio component row reveals delete only after left swipe', () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))

  page.handleComponentTouchStart(touchEvent({ key: 'c_carousel', index: 1 }, { clientX: 220, clientY: 40 }))
  page.handleComponentTouchEnd(touchEvent({ key: 'c_carousel', index: 1 }, { clientX: 150, clientY: 44 }))

  assert.equal(page.data.revealedComponentKey, 'c_carousel')

  page.handleComponentTap({ currentTarget: { dataset: { key: 'c_carousel', type: COMPONENT_TYPES.CAROUSEL } } })

  assert.equal(page.data.revealedComponentKey, '')
  assert.equal(page.data.componentWorkSheetVisible, false)
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

test('share fields update character limit counters while editing', () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))

  assert.equal(page.data.shareFieldCounters.title, '0 / 50')
  assert.equal(page.data.shareFieldCounters.intro, '0 / 500')

  page.handleShareInput({
    currentTarget: { dataset: { path: 'share.title' } },
    detail: { value: '婚礼主持作品集' }
  })
  page.handleShareInput({
    currentTarget: { dataset: { path: 'share.intro' } },
    detail: { value: '温暖沉稳\n' }
  })

  assert.equal(page.data.config.share.title, '婚礼主持作品集')
  assert.equal(page.data.config.share.intro, '温暖沉稳\n')
  assert.equal(page.data.shareFieldCounters.title, '7 / 50')
  assert.equal(page.data.shareFieldCounters.intro, '5 / 500')
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
  assert.deepEqual(requests[0].data, { page: 1, pageSize: 100 })
  assert.equal(page.data.componentWorkSheetVisible, true)
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.id), [11, 13])
  assert.deepEqual(page.data.componentWorkOptions.map((item) => item.selected), [false, true])

  page.handleToggleComponentWork({ currentTarget: { dataset: { id: 11 } } })
  page.handleConfirmComponentWorks()

  assert.deepEqual(page.data.config.components[0].config.workIds, [13, 11])
  assert.equal(page.data.componentWorkSheetVisible, false)
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
  assert.equal(page.data.profileForm.tagsText, '高端婚礼')
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

  page.handleProfileInput({ currentTarget: { dataset: { field: 'displayName' } }, detail: { value: '沈佳磊' } })
  page.handleProfileInput({ currentTarget: { dataset: { field: 'tagsText' } }, detail: { value: '主持,双语' } })
  page.handleProfileVisibleFieldChange({ currentTarget: { dataset: { field: 'profession' } }, detail: { value: false } })
  page.handleConfirmProfileSheet()

  assert.equal(page.data.profileSheetVisible, false)
  assert.equal(page.data.editingProfileComponentKey, '')
  assert.equal(page.data.config.components[0].config.profile.displayName, '沈佳磊')
  assert.deepEqual(page.data.config.components[0].config.profile.tags.map((item) => item.name), ['主持', '双语'])
  assert.equal(page.data.config.components[0].config.visibleFields.profession, false)
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
        tags: [{ content: '主持' }]
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
  assert.deepEqual(profile.tags.map((item) => item.name), ['主持'])
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

test('tapping work grid component opens portfolio display tag sheet', async () => {
  const page = loadPortfolioEditorPage(() => Promise.resolve({}))
  page.data.config = normalizePortfolioConfig({
    components: [
      createComponent(COMPONENT_TYPES.WORK_GRID, {
        componentKey: 'c_grid',
        sortOrder: 1000,
        config: {
          groups: [
            { groupKey: 'g_all', name: '全部案例', sortOrder: 1000, workIds: [11] }
          ]
        }
      })
    ]
  })

  await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_grid', type: COMPONENT_TYPES.WORK_GRID } } })

  assert.equal(page.data.displayGroupSheetVisible, true)
  assert.equal(page.data.editingDisplayComponentKey, 'c_grid')
  assert.equal(page.data.activeDisplayGroupKey, 'g_all')
  assert.deepEqual(page.data.displayGroupOptions.map((item) => item.name), ['全部案例'])
})

test('portfolio display tag sheet copies work tags and imports works by tag', async () => {
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
      createComponent(COMPONENT_TYPES.WORK_LIST, {
        componentKey: 'c_list',
        sortOrder: 1000,
        config: {
          groups: [
            { groupKey: 'g_all', name: '全部案例', sortOrder: 1000, workIds: [] }
          ]
        }
      })
    ]
  })

  await page.handleComponentTap({ currentTarget: { dataset: { key: 'c_list', type: COMPONENT_TYPES.WORK_LIST } } })
  await page.handleCopyWorkTagsToDisplayGroups()
  page.handleSelectDisplayGroup({ currentTarget: { dataset: { groupKey: 'g_1' } } })
  await page.handleImportWorksByTag({ currentTarget: { dataset: { tagId: 8 } } })
  await flushPromises()

  assert.equal(requests[0].url, '/api/mine/works/tags')
  assert.equal(requests[1].url, '/api/mine/works')
  assert.deepEqual(requests[1].data, { page: 1, pageSize: 100, tagId: 8 })
  assert.deepEqual(page.data.config.components[0].config.groups.map((item) => item.name), ['户外案例', '室内案例'])
  assert.deepEqual(page.data.config.components[0].config.groups[0].workIds, [21, 22])
  assert.deepEqual(page.data.displayGroupOptions.map((item) => item.name), ['户外案例', '室内案例'])
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

test('saving draft returns to portfolio list so list onShow reloads data', async (t) => {
  const navigations = []
  const originalGetCurrentPages = global.getCurrentPages
  global.getCurrentPages = () => [
    { route: 'pages/portfolios/portfolios' },
    { route: 'pages/portfolio-standard-edit/portfolio-standard-edit' }
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
    { route: 'pages/portfolio-standard-edit/portfolio-standard-edit' }
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
