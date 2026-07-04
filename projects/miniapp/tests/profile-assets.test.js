const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

function loadProfileAssetsUtils(fakeRequest) {
  const utilPath = path.join(__dirname, '../utils/profile-assets.js')
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
          return { size: sizes[filePath] || 0 }
        }
      }
    },
    compressImage(options) {
      if (hooks.onCompress) {
        hooks.onCompress(options)
      }
      options.success({ tempFilePath: hooks.compressedPath || '/tmp/profile-asset-compressed.jpg' })
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

test('wechat qr crop state uses a fixed square frame and clamps drag', () => {
  const {
    buildWechatQrCropState,
    moveWechatQrCropState
  } = loadProfileAssetsUtils(() => Promise.resolve({}))

  const wideState = buildWechatQrCropState({
    path: 'wxfile://tmp/qr-wide.jpg',
    width: 1200,
    height: 800
  }, {
    cropBoxWidth: 360
  })
  const tallState = buildWechatQrCropState({
    path: 'wxfile://tmp/qr-tall.jpg',
    width: 800,
    height: 1200
  }, {
    cropBoxWidth: 360
  })

  assert.equal(wideState.cropBoxWidth, 360)
  assert.equal(wideState.cropBoxHeight, 360)
  assert.equal(wideState.displayWidth, 540)
  assert.equal(wideState.displayHeight, 360)
  assert.equal(wideState.offsetX, -90)
  assert.equal(wideState.offsetY, 0)
  assert.equal(moveWechatQrCropState(wideState, { deltaX: -120, deltaY: 0 }).offsetX, -180)
  assert.equal(moveWechatQrCropState(wideState, { deltaX: 120, deltaY: 0 }).offsetX, 0)

  assert.equal(tallState.cropBoxWidth, tallState.cropBoxHeight)
  assert.equal(tallState.displayWidth, 360)
  assert.equal(tallState.displayHeight, 540)
  assert.equal(tallState.offsetX, 0)
  assert.equal(tallState.offsetY, -90)
  assert.equal(moveWechatQrCropState(tallState, { deltaX: 0, deltaY: -120 }).offsetY, -180)
  assert.equal(moveWechatQrCropState(tallState, { deltaX: 0, deltaY: 120 }).offsetY, 0)
})

test('wechat qr crop frame maps the square preview to source pixels', () => {
  const {
    buildWechatQrCropFrame,
    buildWechatQrCropState,
    moveWechatQrCropState
  } = loadProfileAssetsUtils(() => Promise.resolve({}))

  const centeredState = buildWechatQrCropState({
    path: 'wxfile://tmp/qr-wide.jpg',
    width: 1200,
    height: 800
  }, {
    cropBoxWidth: 360
  })
  const leftState = moveWechatQrCropState(centeredState, { deltaX: 120, deltaY: 0 })

  assert.deepEqual(buildWechatQrCropFrame(centeredState, { outputWidth: 720 }), {
    sx: 200,
    sy: 0,
    sWidth: 800,
    sHeight: 800,
    destWidth: 720,
    destHeight: 720
  })
  assert.deepEqual(buildWechatQrCropFrame(leftState, { outputWidth: 720 }), {
    sx: 0,
    sy: 0,
    sWidth: 800,
    sHeight: 800,
    destWidth: 720,
    destHeight: 720
  })
})

test('wechat qr crop exports a square temporary file through Canvas 2D', async () => {
  const {
    cropWechatQrToTempFilePath
  } = loadProfileAssetsUtils(() => Promise.resolve({}))
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
          assert.equal(selector, '#wechatQrCropCanvas')
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
      assert.equal(options.width, 720)
      assert.equal(options.height, 720)
      assert.equal(options.destWidth, 720)
      assert.equal(options.destHeight, 720)
      assert.equal(options.fileType, 'jpg')
      assert.equal(options.quality, 0.92)
      options.success({ tempFilePath: 'wxfile://tmp/wechat-qr-cropped.jpg' })
    }
  }

  const filePath = await cropWechatQrToTempFilePath({
    page,
    wxApi,
    canvasId: 'wechatQrCropCanvas',
    imagePath: 'wxfile://tmp/qr-wide.jpg',
    cropFrame: {
      sx: 200,
      sy: 0,
      sWidth: 800,
      sHeight: 800,
      destWidth: 720,
      destHeight: 720
    },
    fileType: 'jpg',
    quality: 0.92
  })

  assert.equal(filePath, 'wxfile://tmp/wechat-qr-cropped.jpg')
  assert.equal(canvas.width, 720)
  assert.equal(canvas.height, 720)
  assert.deepEqual(drawCalls, [
    ['clearRect', 0, 0, 720, 720],
    ['drawImage', 200, 0, 800, 800, 0, 0, 720, 720]
  ])
})

test('profile wechat qr uploads local image through profile asset ticket', async () => {
  const requests = []
  const uploads = []
  const { uploadWechatQr } = loadProfileAssetsUtils((options) => {
    requests.push(options)
    return Promise.resolve({
      uploadUrl: 'https://cos-upload.example.com',
      objectKey: 'WF8392/others/wechat-qr-20260703140512-a1b2c3d4.jpg',
      publicUrl: 'https://cos.example.com/WF8392/others/wechat-qr-20260703140512-a1b2c3d4.jpg',
      formData: { key: 'WF8392/others/wechat-qr-20260703140512-a1b2c3d4.jpg' }
    })
  })
  const wxApi = wxApiWithSizes({
    '/tmp/wechat-qr.jpg': 96 * 1024
  }, {
    onUpload(options) {
      uploads.push(options)
    }
  })

  const url = await uploadWechatQr('/tmp/wechat-qr.jpg', { wxApi })

  assert.equal(requests[0].url, '/api/mine/profile/assets/upload-ticket')
  assert.equal(requests[0].method, 'POST')
  assert.equal(requests[0].data.assetType, 'WECHAT_QR')
  assert.equal(requests[0].data.fileSize, 96 * 1024)
  assert.equal(requests[0].data.mimeType, 'image/jpeg')
  assert.equal(uploads[0].url, 'https://cos-upload.example.com')
  assert.equal(uploads[0].filePath, '/tmp/wechat-qr.jpg')
  assert.deepEqual(uploads[0].formData, {
    key: 'WF8392/others/wechat-qr-20260703140512-a1b2c3d4.jpg'
  })
  assert.equal(url, 'https://cos.example.com/WF8392/others/wechat-qr-20260703140512-a1b2c3d4.jpg')
})

test('profile wechat qr compresses oversized local image before ticket request', async () => {
  const requests = []
  const compressOptions = []
  const uploads = []
  const { uploadWechatQr } = loadProfileAssetsUtils((options) => {
    requests.push(options)
    return Promise.resolve({
      uploadUrl: 'https://cos-upload.example.com',
      publicUrl: 'https://cos.example.com/WF8392/others/wechat-qr-20260703140512-a1b2c3d4.jpg',
      formData: { key: 'WF8392/others/wechat-qr-20260703140512-a1b2c3d4.jpg' }
    })
  })
  const wxApi = wxApiWithSizes({
    '/tmp/large-qr.png': 420 * 1024,
    '/tmp/profile-asset-compressed.jpg': 240 * 1024
  }, {
    onCompress(options) {
      compressOptions.push(options)
    },
    onUpload(options) {
      uploads.push(options)
    }
  })

  const url = await uploadWechatQr('/tmp/large-qr.png', { wxApi })

  assert.equal(compressOptions[0].src, '/tmp/large-qr.png')
  assert.equal(requests[0].data.assetType, 'WECHAT_QR')
  assert.equal(requests[0].data.fileSize, 240 * 1024)
  assert.equal(requests[0].data.mimeType, 'image/jpeg')
  assert.equal(uploads[0].filePath, '/tmp/profile-asset-compressed.jpg')
  assert.equal(url, 'https://cos.example.com/WF8392/others/wechat-qr-20260703140512-a1b2c3d4.jpg')
})

test('profile wechat qr rejects image that remains above 300KB after compression', async () => {
  const { uploadWechatQr } = loadProfileAssetsUtils(() => Promise.resolve({}))
  const wxApi = wxApiWithSizes({
    '/tmp/large-qr.png': 420 * 1024,
    '/tmp/profile-asset-compressed.jpg': 320 * 1024
  })

  await assert.rejects(
    () => uploadWechatQr('/tmp/large-qr.png', { wxApi }),
    /微信二维码不能超过 300KB/
  )
})

test('profile avatar uploads local image as AVATAR asset type', async () => {
  const requests = []
  const { uploadProfileAvatar } = loadProfileAssetsUtils((options) => {
    requests.push(options)
    return Promise.resolve({
      uploadUrl: 'https://cos-upload.example.com',
      publicUrl: 'https://cos.example.com/WF8392/others/avatar-20260703140512-a1b2c3d4.jpg',
      formData: { key: 'WF8392/others/avatar-20260703140512-a1b2c3d4.jpg' }
    })
  })
  const wxApi = wxApiWithSizes({
    '/tmp/avatar.jpg': 80 * 1024
  })

  const url = await uploadProfileAvatar('/tmp/avatar.jpg', { wxApi })

  assert.equal(requests[0].url, '/api/mine/profile/assets/upload-ticket')
  assert.equal(requests[0].method, 'POST')
  assert.equal(requests[0].data.assetType, 'AVATAR')
  assert.equal(requests[0].data.fileSize, 80 * 1024)
  assert.equal(url, 'https://cos.example.com/WF8392/others/avatar-20260703140512-a1b2c3d4.jpg')
})

test('profile asset upload returns remote url unchanged', async () => {
  const requests = []
  const { uploadWechatQr } = loadProfileAssetsUtils((options) => {
    requests.push(options)
    return Promise.resolve({})
  })

  const url = await uploadWechatQr('https://cos.example.com/WF8392/others/wechat-qr.jpg')

  assert.equal(url, 'https://cos.example.com/WF8392/others/wechat-qr.jpg')
  assert.equal(requests.length, 0)
})
