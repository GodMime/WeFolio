const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

function loadVisitorProfileUtils(fakeRequest) {
  const utilPath = path.join(__dirname, '../utils/visitor-profile.js')
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
      options.success({ tempFilePath: hooks.compressedPath || '/tmp/visitor-avatar-compressed.jpg' })
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

test('visitor profile helper uploads avatar to visit ticket and saves nickname profile', async () => {
  const requests = []
  const uploads = []
  const { uploadVisitorAvatarProfile } = loadVisitorProfileUtils((options) => {
    requests.push(options)
    if (options.url.endsWith('/visitor-avatar/upload-ticket')) {
      return Promise.resolve({
        uploadUrl: 'https://cos-upload.example.com',
        objectKey: 'visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg',
        publicUrl: 'https://cdn.example.com/visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg',
        formData: { key: 'visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg' }
      })
    }
    return Promise.resolve({})
  })
  const wxApi = wxApiWithSizes({
    '/tmp/avatar.jpg': 80 * 1024
  }, {
    onUpload(options) {
      uploads.push(options)
    }
  })

  const avatarUrl = await uploadVisitorAvatarProfile({
    shareCode: 'PF001',
    visitorProfileToken: 'profile-token-1',
    nickname: '小陈',
    avatarFilePath: '/tmp/avatar.jpg'
  }, { wxApi })

  assert.equal(requests[0].url, '/api/visitor/portfolios/PF001/visitor-avatar/upload-ticket')
  assert.equal(requests[0].method, 'POST')
  assert.deepEqual(requests[0].data, {
    visitorProfileToken: 'profile-token-1',
    mimeType: 'image/jpeg',
    fileSize: 80 * 1024
  })
  assert.equal(uploads[0].url, 'https://cos-upload.example.com')
  assert.equal(uploads[0].filePath, '/tmp/avatar.jpg')
  assert.deepEqual(uploads[0].formData, {
    key: 'visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg'
  })
  assert.equal(requests[1].url, '/api/visitor/portfolios/PF001/visitor-profile')
  assert.equal(requests[1].method, 'PUT')
  assert.deepEqual(requests[1].data, {
    visitorProfileToken: 'profile-token-1',
    nickname: '小陈',
    avatarUrl: 'https://cdn.example.com/visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg'
  })
  assert.equal(avatarUrl, 'https://cdn.example.com/visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg')
})

test('visitor profile helper compresses oversized avatar before ticket request', async () => {
  const requests = []
  const compressOptions = []
  const uploads = []
  const { uploadVisitorAvatarProfile } = loadVisitorProfileUtils((options) => {
    requests.push(options)
    if (options.url.endsWith('/visitor-avatar/upload-ticket')) {
      return Promise.resolve({
        uploadUrl: 'https://cos-upload.example.com',
        publicUrl: 'https://cdn.example.com/visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg',
        formData: { key: 'visit/visitor-avatar-1024-20260705093000-a1b2c3d4.jpg' }
      })
    }
    return Promise.resolve({})
  })
  const wxApi = wxApiWithSizes({
    '/tmp/large-avatar.png': 360 * 1024,
    '/tmp/visitor-avatar-compressed.jpg': 160 * 1024
  }, {
    onCompress(options) {
      compressOptions.push(options)
    },
    onUpload(options) {
      uploads.push(options)
    }
  })

  await uploadVisitorAvatarProfile({
    shareCode: 'PF001',
    visitorProfileToken: 'profile-token-1',
    nickname: '小陈',
    avatarFilePath: '/tmp/large-avatar.png'
  }, { wxApi })

  assert.equal(compressOptions[0].src, '/tmp/large-avatar.png')
  assert.equal(requests[0].data.fileSize, 160 * 1024)
  assert.equal(requests[0].data.mimeType, 'image/jpeg')
  assert.equal(uploads[0].filePath, '/tmp/visitor-avatar-compressed.jpg')
})
