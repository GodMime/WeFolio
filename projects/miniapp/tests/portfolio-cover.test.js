const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

function loadPortfolioCoverUtils(fakeRequest) {
  const utilPath = path.join(__dirname, '../utils/portfolio-cover.js')
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

test('portfolio cover uploads local cover through backend ticket and returns public url', async () => {
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

  assert.equal(requests[0].url, '/api/mine/portfolios/88/cover/upload-ticket')
  assert.equal(requests[0].method, 'POST')
  assert.equal(requests[0].data.fileSize, 180 * 1024)
  assert.equal(requests[0].data.mimeType, 'image/jpeg')
  assert.equal(uploads[0].url, 'https://cos-upload.example.com')
  assert.equal(uploads[0].filePath, '/tmp/cover.jpg')
  assert.deepEqual(uploads[0].formData, {
    key: 'WFA3B1E7A2/protfolio/cover-88-20260702120000-a1b2c3d4.jpg'
  })
  assert.equal(url, 'https://cos.example.com/WFA3B1E7A2/protfolio/cover-88-20260702120000-a1b2c3d4.jpg')
})
