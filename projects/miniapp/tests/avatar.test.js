const assert = require('node:assert/strict')
const test = require('node:test')

const { TOKEN_STORAGE_KEY } = require('../utils/request')
const {
  AVATAR_MAX_SIZE_BYTES,
  prepareAvatarFilePath,
  uploadAvatar
} = require('../utils/avatar')

test('prepare avatar compresses oversized local images before upload', async () => {
  const compressCalls = []
  const wxApi = {
    getFileSystemManager() {
      return {
        statSync(filePath) {
          return {
            size: filePath === 'tmp/original.jpg' ? AVATAR_MAX_SIZE_BYTES + 1 : AVATAR_MAX_SIZE_BYTES
          }
        }
      }
    },
    compressImage(options) {
      compressCalls.push(options)
      options.success({
        tempFilePath: 'tmp/compressed.jpg'
      })
    }
  }

  const filePath = await prepareAvatarFilePath('tmp/original.jpg', { wxApi })

  assert.equal(filePath, 'tmp/compressed.jpg')
  assert.equal(compressCalls.length, 1)
  assert.equal(compressCalls[0].src, 'tmp/original.jpg')
  assert.equal(compressCalls[0].quality, 95)
  assert.equal(compressCalls[0].compressedWidth, 512)
  assert.equal(compressCalls[0].compressedHeight, 512)
})

test('prepare avatar rejects images that remain too large after compression', async () => {
  const wxApi = {
    getFileSystemManager() {
      return {
        statSync() {
          return {
            size: AVATAR_MAX_SIZE_BYTES + 1
          }
        }
      }
    },
    compressImage(options) {
      options.success({
        tempFilePath: 'tmp/still-large.jpg'
      })
    }
  }

  await assert.rejects(
    () => prepareAvatarFilePath('tmp/original.jpg', { wxApi }),
    /头像文件不能超过 200KB/
  )
})

test('upload avatar sends bearer token and returns uploaded COS url', async () => {
  let capturedOptions = null
  const wxApi = {
    getStorageSync(key) {
      return key === TOKEN_STORAGE_KEY ? 'wf-dev-user-7' : ''
    },
    getFileSystemManager() {
      return {
        statSync() {
          return {
            size: 1024
          }
        }
      }
    },
    uploadFile(options) {
      capturedOptions = options
      options.success({
        statusCode: 200,
        data: JSON.stringify({
          success: true,
          data: {
            url: 'https://cos.example.com/WF123/others/avatar.jpg'
          }
        })
      })
    }
  }

  const url = await uploadAvatar('tmp/avatar.jpg', {
    wxApi,
    baseUrl: 'https://api.example.com'
  })

  assert.equal(url, 'https://cos.example.com/WF123/others/avatar.jpg')
  assert.equal(capturedOptions.url, 'https://api.example.com/api/auth/avatar')
  assert.equal(capturedOptions.filePath, 'tmp/avatar.jpg')
  assert.equal(capturedOptions.name, 'file')
  assert.equal(capturedOptions.header.Authorization, 'Bearer wf-dev-user-7')
})

test('upload avatar treats http tmp path as local file instead of remote url', async () => {
  let capturedOptions = null
  const wxApi = {
    getStorageSync() {
      return ''
    },
    getFileSystemManager() {
      return {
        statSync() {
          return {
            size: 2048
          }
        }
      }
    },
    uploadFile(options) {
      capturedOptions = options
      options.success({
        statusCode: 200,
        data: JSON.stringify({
          success: true,
          data: {
            url: 'https://cos.example.com/WF123/others/avatar-tmp.jpg'
          }
        })
      })
    }
  }

  const url = await uploadAvatar('http://tmp/SPHqzE_nZHMJ3b1a7e8bd3534e70852fc5b6481395be.jpg', {
    wxApi,
    baseUrl: 'https://api.example.com'
  })

  assert.equal(url, 'https://cos.example.com/WF123/others/avatar-tmp.jpg')
  assert.equal(capturedOptions.filePath, 'http://tmp/SPHqzE_nZHMJ3b1a7e8bd3534e70852fc5b6481395be.jpg')
})
