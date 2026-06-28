const assert = require('node:assert/strict')
const test = require('node:test')

const { TOKEN_STORAGE_KEY } = require('../utils/request')
const {
  TEAM_AVATAR_MAX_SIZE_BYTES,
  prepareTeamAvatarFilePath,
  uploadTeamAvatar
} = require('../utils/team-avatar')

test('prepare team avatar compresses oversized local images before upload', async () => {
  const compressCalls = []
  const wxApi = {
    getFileSystemManager() {
      return {
        statSync(filePath) {
          return {
            size: filePath === 'tmp/original-team.png' ? TEAM_AVATAR_MAX_SIZE_BYTES + 1 : TEAM_AVATAR_MAX_SIZE_BYTES
          }
        }
      }
    },
    compressImage(options) {
      compressCalls.push(options)
      options.success({
        tempFilePath: 'tmp/compressed-team.png'
      })
    }
  }

  const filePath = await prepareTeamAvatarFilePath('tmp/original-team.png', { wxApi })

  assert.equal(filePath, 'tmp/compressed-team.png')
  assert.equal(compressCalls.length, 1)
  assert.equal(compressCalls[0].src, 'tmp/original-team.png')
  assert.equal(compressCalls[0].quality, 95)
  assert.equal(compressCalls[0].compressedWidth, 512)
  assert.equal(compressCalls[0].compressedHeight, 512)
})

test('prepare team avatar rejects images that remain too large after compression', async () => {
  const wxApi = {
    getFileSystemManager() {
      return {
        statSync() {
          return {
            size: TEAM_AVATAR_MAX_SIZE_BYTES + 1
          }
        }
      }
    },
    compressImage(options) {
      options.success({
        tempFilePath: 'tmp/still-large-team.png'
      })
    }
  }

  await assert.rejects(
    () => prepareTeamAvatarFilePath('tmp/original-team.png', { wxApi }),
    /团队图标不能超过 200KB/
  )
})

test('upload team avatar sends bearer token to team avatar endpoint', async () => {
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
            url: 'https://cos.example.com/TM2048/others/avatar.png'
          }
        })
      })
    }
  }

  const url = await uploadTeamAvatar(100, 'tmp/team.png', {
    wxApi,
    baseUrl: 'https://api.example.com'
  })

  assert.equal(url, 'https://cos.example.com/TM2048/others/avatar.png')
  assert.equal(capturedOptions.url, 'https://api.example.com/api/mine/teams/100/avatar')
  assert.equal(capturedOptions.filePath, 'tmp/team.png')
  assert.equal(capturedOptions.name, 'file')
  assert.equal(capturedOptions.header.Authorization, 'Bearer wf-dev-user-7')
})

test('upload team avatar skips remote urls', async () => {
  assert.equal(
    await uploadTeamAvatar(100, 'https://cos.example.com/TM2048/others/avatar.png'),
    'https://cos.example.com/TM2048/others/avatar.png'
  )
})
