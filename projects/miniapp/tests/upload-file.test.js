const assert = require('node:assert/strict')
const test = require('node:test')

const {
  prepareLocalUploadFilePath,
  uploadPreparedFile
} = require('../utils/upload-file')

function uploadWxApi(response, failure) {
  return {
    getStorageSync() {
      return 'maintainer-token'
    },
    uploadFile(options) {
      if (failure) {
        options.fail(failure)
      } else {
        options.success(response)
      }
    }
  }
}

test('upload file resolves url and attaches maintainer authorization', async () => {
  let captured = null
  const wxApi = uploadWxApi({
    statusCode: 200,
    data: JSON.stringify({ success: true, data: { url: 'https://cos.example.com/avatar.jpg' } })
  })
  const originalUpload = wxApi.uploadFile
  wxApi.uploadFile = (options) => {
    captured = options
    originalUpload(options)
  }

  const url = await uploadPreparedFile('/tmp/avatar.jpg', '/api/auth/avatar', { wxApi })

  assert.equal(url, 'https://cos.example.com/avatar.jpg')
  assert.equal(captured.header.Authorization, 'Bearer maintainer-token')
})

test('upload file maps 401 to authRequired error', async () => {
  const wxApi = uploadWxApi({
    statusCode: 401,
    data: JSON.stringify({ success: false, message: '登录已失效' })
  })

  await assert.rejects(
    uploadPreparedFile('/tmp/avatar.jpg', '/api/auth/avatar', { wxApi }),
    (error) => error.authRequired === true && error.message === '登录已失效'
  )
})

test('upload file rejects non-2xx and explicit business failure responses', async () => {
  await assert.rejects(
    uploadPreparedFile('/tmp/a.jpg', '/upload', {
      wxApi: uploadWxApi({ statusCode: 503, data: JSON.stringify({ message: '服务暂不可用' }) })
    }),
    /服务暂不可用/
  )
  await assert.rejects(
    uploadPreparedFile('/tmp/a.jpg', '/upload', {
      wxApi: uploadWxApi({ statusCode: 200, data: JSON.stringify({ success: false, message: '文件不合法' }) })
    }),
    /文件不合法/
  )
})

test('upload file rejects missing url and malformed json responses', async () => {
  await assert.rejects(
    uploadPreparedFile('/tmp/a.jpg', '/upload', {
      wxApi: uploadWxApi({ statusCode: 200, data: JSON.stringify({ success: true, data: {} }) })
    }),
    /上传成功但未获取到文件地址/
  )
  await assert.rejects(
    uploadPreparedFile('/tmp/a.jpg', '/upload', {
      wxApi: uploadWxApi({ statusCode: 200, data: '{broken-json' })
    }),
    /上传响应解析失败/
  )
})

test('upload file maps wx upload failure', async () => {
  await assert.rejects(
    uploadPreparedFile('/tmp/a.jpg', '/upload', {
      wxApi: uploadWxApi(null, { errMsg: 'uploadFile:fail timeout' })
    }),
    /uploadFile:fail timeout/
  )
})

test('oversized local file rejects when current wx version cannot compress images', async () => {
  const wxApi = {
    getFileSystemManager() {
      return {
        statSync() {
          return { size: 300 * 1024 }
        }
      }
    }
  }

  await assert.rejects(
    prepareLocalUploadFilePath('/tmp/a.jpg', { wxApi, maxSizeBytes: 200 * 1024 }),
    /当前微信版本不支持图片压缩/
  )
})
