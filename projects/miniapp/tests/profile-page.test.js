const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

function flushPromises() {
  return new Promise((resolve) => {
    setImmediate(resolve)
  })
}

function applyData(target, patch) {
  Object.keys(patch).forEach((key) => {
    if (!key.includes('.')) {
      target[key] = patch[key]
      return
    }
    const parts = key.split('.')
    let current = target
    parts.slice(0, -1).forEach((part) => {
      if (!current[part]) {
        current[part] = {}
      }
      current = current[part]
    })
    current[parts[parts.length - 1]] = patch[key]
  })
}

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function loadProfilePage(fakeRequest, wxOverrides = {}, assetOverrides = {}) {
  const pagePath = path.join(__dirname, '../pages/profile/profile.js')
  const requestPath = path.join(__dirname, '../utils/request.js')
  const sessionPath = path.join(__dirname, '../utils/session.js')
  const assetsPath = path.join(__dirname, '../utils/profile-assets.js')
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
      clearToken() {},
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
      WECHAT_QR_CROP_FILE_TYPE: 'jpg',
      WECHAT_QR_CROP_OUTPUT_WIDTH: 720,
      WECHAT_QR_CROP_QUALITY: 0.92,
      buildWechatQrCropFrame() {
        return {
          sx: 0,
          sy: 0,
          sWidth: 800,
          sHeight: 800,
          destWidth: 720,
          destHeight: 720
        }
      },
      buildWechatQrCropState(imageInfo) {
        return {
          imagePath: imageInfo.path,
          cropBoxWidth: 320,
          cropBoxHeight: 320,
          offsetX: -80,
          offsetY: 0,
          cropBoxStyle: 'width: 320px; height: 320px;',
          imageStyle: 'width: 480px; height: 320px; transform: translate3d(-80px, 0px, 0);'
        }
      },
      cropWechatQrToTempFilePath() {
        return Promise.resolve('wxfile://tmp/wechat-qr-cropped.jpg')
      },
      getWechatQrImageInfo(imageFile) {
        return Promise.resolve({
          path: imageFile.path,
          width: imageFile.width,
          height: imageFile.height
        })
      },
      moveWechatQrCropState(state) {
        return state
      },
      uploadProfileAvatar(filePath) {
        return Promise.resolve(filePath)
      },
      uploadWechatQr(filePath) {
        return Promise.resolve(filePath)
      }
    }, assetOverrides)
  }

  let pageDefinition
  global.Page = (definition) => {
    pageDefinition = definition
  }
  global.wx = Object.assign({
    getWindowInfo() {
      return { windowWidth: 375 }
    },
    getSystemInfoSync() {
      throw new Error('不应调用已废弃的 wx.getSystemInfoSync')
    },
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

  return Object.assign({}, pageDefinition, {
    data: clone(pageDefinition.data),
    setData(patch, callback) {
      applyData(this.data, patch)
      if (callback) {
        callback()
      }
    }
  })
}

test('wechat qr chooser opens a square crop sheet before updating profile form', async () => {
  let chooseOptions = null
  let cropBoxWidth = 0
  const page = loadProfilePage(() => Promise.resolve({}), {
    chooseMedia(options) {
      chooseOptions = options
      options.success({
        tempFiles: [
          { tempFilePath: 'wxfile://tmp/wechat-qr-wide.jpg', width: 1200, height: 800 }
        ]
      })
    }
  }, {
    buildWechatQrCropState(imageInfo, options) {
      cropBoxWidth = options.cropBoxWidth
      return {
        imagePath: imageInfo.path,
        cropBoxWidth,
        cropBoxHeight: cropBoxWidth,
        offsetX: -80,
        offsetY: 0,
        cropBoxStyle: `width: ${cropBoxWidth}px; height: ${cropBoxWidth}px;`,
        imageStyle: 'width: 480px; height: 320px; transform: translate3d(-80px, 0px, 0);'
      }
    }
  })

  page.handleChooseWechatQr()
  await flushPromises()

  assert.deepEqual(chooseOptions.mediaType, ['image'])
  assert.equal(page.data.form.wechatQrUrl, '')
  assert.equal(page.data.wechatQrCropVisible, true)
  assert.equal(page.data.wechatQrCropState.imagePath, 'wxfile://tmp/wechat-qr-wide.jpg')
  assert.equal(page.data.wechatQrCropState.cropBoxWidth, page.data.wechatQrCropState.cropBoxHeight)
  assert.ok(cropBoxWidth > 0)
})

test('confirming wechat qr crop writes the cropped path and keeps upload deferred to save', async () => {
  const cropCalls = []
  const page = loadProfilePage(() => Promise.resolve({}), {
    chooseMedia(options) {
      options.success({
        tempFiles: [
          { tempFilePath: 'wxfile://tmp/wechat-qr-wide.jpg', width: 1200, height: 800 }
        ]
      })
    }
  }, {
    cropWechatQrToTempFilePath(options) {
      cropCalls.push(options)
      return Promise.resolve('wxfile://tmp/wechat-qr-cropped.jpg')
    }
  })

  page.handleChooseWechatQr()
  await flushPromises()
  await page.handleConfirmWechatQrCrop()

  assert.equal(cropCalls[0].canvasId, 'wechatQrCropCanvas')
  assert.equal(cropCalls[0].imagePath, 'wxfile://tmp/wechat-qr-wide.jpg')
  assert.equal(page.data.form.wechatQrUrl, 'wxfile://tmp/wechat-qr-cropped.jpg')
  assert.equal(page.data.wechatQrCropVisible, false)
})
