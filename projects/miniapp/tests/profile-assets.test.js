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
