const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

function loadPortfolioCoverUtils(fakeRequest) {
  const utilPath = path.join(__dirname, '../pages/portfolios/utils/portfolio-assets.js')
  const requestPath = path.join(__dirname, '../utils/request.js')
  const requestCacheKey = require.resolve(requestPath)
  const originalRequestCache = require.cache[requestCacheKey]

  delete require.cache[require.resolve(utilPath)]
  require.cache[requestCacheKey] = {
    id: requestPath,
    filename: requestPath,
    loaded: true,
    exports: {
      request: fakeRequest
    }
  }

  const utils = require(utilPath)
  if (originalRequestCache) {
    require.cache[requestCacheKey] = originalRequestCache
  } else {
    delete require.cache[requestCacheKey]
  }
  return utils
}

function wxApiWithSizes(sizes, hooks = {}) {
  return {
    getFileSystemManager() {
      return {
        statSync(filePath) {
          if (hooks.throwStatFor && hooks.throwStatFor.includes(filePath)) {
            throw new Error('file missing')
          }
          return { size: sizes[filePath] || 0 }
        }
      }
    },
    compressImage(options) {
      if (hooks.onCompress) {
        hooks.onCompress(options)
      }
      options.success({ tempFilePath: hooks.compressedPath || '/tmp/portfolio-cover-compressed.jpg' })
    },
    uploadFile(options) {
      if (hooks.onUpload) {
        hooks.onUpload(options)
      }
      options.success({ statusCode: hooks.uploadStatusCode || 204 })
      return {
        onProgressUpdate() {}
      }
    }
  }
}

test('portfolio cover accepts native 5:4 images without crop', () => {
  const {
    isPortfolioCoverFiveFour,
    shouldCropPortfolioCover
  } = loadPortfolioCoverUtils(() => Promise.resolve({}))

  assert.equal(isPortfolioCoverFiveFour({ width: 1000, height: 800 }), true)
  assert.equal(shouldCropPortfolioCover({ width: 1000, height: 800 }), false)
  assert.equal(shouldCropPortfolioCover({ width: 1200, height: 800 }), true)
  assert.equal(shouldCropPortfolioCover({ width: 800, height: 1200 }), true)
})

test('portfolio cover crop state centers wide and tall images and clamps drag', () => {
  const {
    buildPortfolioCoverCropState,
    movePortfolioCoverCropState
  } = loadPortfolioCoverUtils(() => Promise.resolve({}))

  const wideState = buildPortfolioCoverCropState({
    path: 'wxfile://tmp/wide.jpg',
    width: 1200,
    height: 800
  }, {
    cropBoxWidth: 500
  })
  const tallState = buildPortfolioCoverCropState({
    path: 'wxfile://tmp/tall.jpg',
    width: 800,
    height: 1200
  }, {
    cropBoxWidth: 500
  })

  assert.equal(wideState.cropBoxWidth, 500)
  assert.equal(wideState.cropBoxHeight, 400)
  assert.equal(wideState.displayWidth, 600)
  assert.equal(wideState.displayHeight, 400)
  assert.equal(wideState.offsetX, -50)
  assert.equal(wideState.offsetY, 0)
  assert.equal(movePortfolioCoverCropState(wideState, { deltaX: -80, deltaY: 0 }).offsetX, -100)
  assert.equal(movePortfolioCoverCropState(wideState, { deltaX: 80, deltaY: 0 }).offsetX, 0)

  assert.equal(tallState.displayWidth, 500)
  assert.equal(tallState.displayHeight, 750)
  assert.equal(tallState.offsetX, 0)
  assert.equal(tallState.offsetY, -175)
  assert.equal(movePortfolioCoverCropState(tallState, { deltaX: 0, deltaY: -240 }).offsetY, -350)
  assert.equal(movePortfolioCoverCropState(tallState, { deltaX: 0, deltaY: 240 }).offsetY, 0)
})

test('portfolio cover crop frame maps preview offset back to source pixels', () => {
  const {
    buildPortfolioCoverCropState,
    buildPortfolioCoverCropFrame,
    movePortfolioCoverCropState
  } = loadPortfolioCoverUtils(() => Promise.resolve({}))

  const centeredState = buildPortfolioCoverCropState({
    path: 'wxfile://tmp/wide.jpg',
    width: 1200,
    height: 800
  }, {
    cropBoxWidth: 500
  })
  const leftState = movePortfolioCoverCropState(centeredState, { deltaX: 80, deltaY: 0 })

  assert.deepEqual(buildPortfolioCoverCropFrame(centeredState, { outputWidth: 1000 }), {
    sx: 100,
    sy: 0,
    sWidth: 1000,
    sHeight: 800,
    destWidth: 1000,
    destHeight: 800
  })
  assert.deepEqual(buildPortfolioCoverCropFrame(leftState, { outputWidth: 1000 }), {
    sx: 0,
    sy: 0,
    sWidth: 1000,
    sHeight: 800,
    destWidth: 1000,
    destHeight: 800
  })
})

test('portfolio cover crops selected image through Canvas 2D', async () => {
  const {
    cropPortfolioCoverToTempFilePath
  } = loadPortfolioCoverUtils(() => Promise.resolve({}))
  const drawCalls = []
  const canvas = {
    width: 0,
    height: 0,
    createImage() {
      return {
        set src(value) {
          this.path = value
          this.onload()
        }
      }
    },
    getContext(type) {
      assert.equal(type, '2d')
      return {
        clearRect(x, y, width, height) {
          drawCalls.push(['clearRect', x, y, width, height])
        },
        drawImage(...args) {
          drawCalls.push(['drawImage'].concat(args.slice(1)))
        }
      }
    }
  }
  const page = {}
  const wxApi = {
    createSelectorQuery() {
      return {
        in(target) {
          assert.equal(target, page)
          return this
        },
        select(selector) {
          assert.equal(selector, '#portfolioCoverCropCanvas')
          return this
        },
        fields(options) {
          assert.deepEqual(options, { node: true, size: true })
          return this
        },
        exec(callback) {
          callback([{ node: canvas }])
        }
      }
    },
    canvasToTempFilePath(options) {
      assert.equal(options.canvas, canvas)
      assert.equal(options.width, 1000)
      assert.equal(options.height, 800)
      assert.equal(options.destWidth, 1000)
      assert.equal(options.destHeight, 800)
      assert.equal(options.fileType, 'jpg')
      assert.equal(options.quality, 0.92)
      options.success({ tempFilePath: 'wxfile://tmp/cropped-cover.jpg' })
    }
  }

  const filePath = await cropPortfolioCoverToTempFilePath({
    page,
    wxApi,
    canvasId: 'portfolioCoverCropCanvas',
    imagePath: 'wxfile://tmp/wide.jpg',
    cropFrame: {
      sx: 100,
      sy: 0,
      sWidth: 1000,
      sHeight: 800,
      destWidth: 1000,
      destHeight: 800
    },
    fileType: 'jpg',
    quality: 0.92
  })

  assert.equal(filePath, 'wxfile://tmp/cropped-cover.jpg')
  assert.equal(canvas.width, 1000)
  assert.equal(canvas.height, 800)
  assert.deepEqual(drawCalls, [
    ['clearRect', 0, 0, 1000, 800],
    ['drawImage', 100, 0, 1000, 800, 0, 0, 1000, 800]
  ])
})

test('portfolio cover keeps image unchanged when it is within 300KB', async () => {
  const { preparePortfolioCoverFile } = loadPortfolioCoverUtils(() => Promise.resolve({}))
  let compressCount = 0
  const wxApi = wxApiWithSizes({
    '/tmp/cover.jpg': 120 * 1024
  }, {
    onCompress() {
      compressCount += 1
    }
  })

  const prepared = await preparePortfolioCoverFile('/tmp/cover.jpg', { wxApi })

  assert.equal(prepared.filePath, '/tmp/cover.jpg')
  assert.equal(prepared.fileSize, 120 * 1024)
  assert.equal(prepared.mimeType, 'image/jpeg')
  assert.equal(compressCount, 0)
})

test('portfolio cover compresses oversized image and rejects if still above 300KB', async () => {
  const { preparePortfolioCoverFile } = loadPortfolioCoverUtils(() => Promise.resolve({}))
  const wxApi = wxApiWithSizes({
    '/tmp/cover.png': 480 * 1024,
    '/tmp/portfolio-cover-compressed.jpg': 320 * 1024
  })

  await assert.rejects(
    () => preparePortfolioCoverFile('/tmp/cover.png', { wxApi }),
    /封面图片不能超过 300KB/
  )
})

test('portfolio cover compression preserves aspect ratio by omitting fixed height', async () => {
  const { preparePortfolioCoverFile } = loadPortfolioCoverUtils(() => Promise.resolve({}))
  const compressOptions = []
  const wxApi = wxApiWithSizes({
    '/tmp/wide-cover.jpg': 480 * 1024,
    '/tmp/portfolio-cover-compressed.jpg': 180 * 1024
  }, {
    onCompress(options) {
      compressOptions.push(options)
    }
  })

  const prepared = await preparePortfolioCoverFile('/tmp/wide-cover.jpg', { wxApi })

  assert.equal(prepared.filePath, '/tmp/portfolio-cover-compressed.jpg')
  assert.equal(compressOptions[0].compressedWidth, 1080)
  assert.equal(Object.prototype.hasOwnProperty.call(compressOptions[0], 'compressedHeight'), false)
})

test('portfolio cover stat failure falls back to compression instead of throwing raw file error', async () => {
  const { preparePortfolioCoverFile } = loadPortfolioCoverUtils(() => Promise.resolve({}))
  const wxApi = wxApiWithSizes({
    '/tmp/portfolio-cover-compressed.jpg': 180 * 1024
  }, {
    throwStatFor: ['/tmp/missing-cover.jpg']
  })

  const prepared = await preparePortfolioCoverFile('/tmp/missing-cover.jpg', { wxApi })

  assert.equal(prepared.filePath, '/tmp/portfolio-cover-compressed.jpg')
  assert.equal(prepared.fileSize, 180 * 1024)
})

test('portfolio cover uploads local cover through unified asset ticket and returns public url', async () => {
  const requests = []
  const uploads = []
  const { uploadPortfolioCover } = loadPortfolioCoverUtils((options) => {
    requests.push(options)
    return Promise.resolve({
      uploadUrl: 'https://cos-upload.example.com',
      objectKey: 'WFA3B1E7A2/protfolio/cover-88-20260702120000-a1b2c3d4.jpg',
      publicUrl: 'https://cos.example.com/WFA3B1E7A2/protfolio/cover-88-20260702120000-a1b2c3d4.jpg',
      formData: { key: 'WFA3B1E7A2/protfolio/cover-88-20260702120000-a1b2c3d4.jpg' }
    })
  })
  const wxApi = wxApiWithSizes({
    '/tmp/cover.jpg': 180 * 1024
  }, {
    onUpload(options) {
      uploads.push(options)
    }
  })

  const url = await uploadPortfolioCover(88, '/tmp/cover.jpg', { wxApi })

  assert.equal(requests[0].url, '/api/mine/portfolios/88/asset/upload-ticket')
  assert.equal(requests[0].method, 'POST')
  assert.equal(requests[0].data.assetType, 'COVER')
  assert.equal(requests[0].data.fileSize, 180 * 1024)
  assert.equal(requests[0].data.mimeType, 'image/jpeg')
  assert.equal(uploads[0].url, 'https://cos-upload.example.com')
  assert.equal(uploads[0].filePath, '/tmp/cover.jpg')
  assert.deepEqual(uploads[0].formData, {
    key: 'WFA3B1E7A2/protfolio/cover-88-20260702120000-a1b2c3d4.jpg'
  })
  assert.equal(url, 'https://cos.example.com/WFA3B1E7A2/protfolio/cover-88-20260702120000-a1b2c3d4.jpg')
})

test('portfolio qr contact uploads local image through unified asset ticket and returns public url', async () => {
  const requests = []
  const uploads = []
  const { uploadPortfolioImageAsset, PORTFOLIO_ASSET_TYPES } = loadPortfolioCoverUtils((options) => {
    requests.push(options)
    return Promise.resolve({
      uploadUrl: 'https://cos-upload.example.com',
      objectKey: 'WFA3B1E7A2/protfolio/qr-contact-88-20260702120000-a1b2c3d4.jpg',
      publicUrl: 'https://cos.example.com/WFA3B1E7A2/protfolio/qr-contact-88-20260702120000-a1b2c3d4.jpg',
      formData: { key: 'WFA3B1E7A2/protfolio/qr-contact-88-20260702120000-a1b2c3d4.jpg' }
    })
  })
  const wxApi = wxApiWithSizes({
    '/tmp/qr.jpg': 96 * 1024
  }, {
    onUpload(options) {
      uploads.push(options)
    }
  })

  const url = await uploadPortfolioImageAsset(88, '/tmp/qr.jpg', {
    wxApi,
    assetType: PORTFOLIO_ASSET_TYPES.QR_CONTACT,
    assetLabel: '二维码图片',
    clientIdPrefix: 'qr-contact'
  })

  assert.equal(requests[0].url, '/api/mine/portfolios/88/asset/upload-ticket')
  assert.equal(requests[0].method, 'POST')
  assert.equal(requests[0].data.assetType, 'QR_CONTACT')
  assert.equal(requests[0].data.fileSize, 96 * 1024)
  assert.equal(requests[0].data.mimeType, 'image/jpeg')
  assert.equal(uploads[0].url, 'https://cos-upload.example.com')
  assert.equal(uploads[0].filePath, '/tmp/qr.jpg')
  assert.equal(url, 'https://cos.example.com/WFA3B1E7A2/protfolio/qr-contact-88-20260702120000-a1b2c3d4.jpg')
})
