const assert = require('node:assert/strict')
const test = require('node:test')

const { TOKEN_STORAGE_KEY } = require('../utils/request')
const {
  TEAM_AVATAR_MAX_SIZE_BYTES,
  uploadTeamAvatar
} = require('../utils/team-avatar')

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

test('upload team avatar skips remote urls and rejects oversized local files', async () => {
  assert.equal(
    await uploadTeamAvatar(100, 'https://cos.example.com/TM2048/others/avatar.png'),
    'https://cos.example.com/TM2048/others/avatar.png'
  )

  const wxApi = {
    getFileSystemManager() {
      return {
        statSync() {
          return {
            size: TEAM_AVATAR_MAX_SIZE_BYTES + 1
          }
        }
      }
    }
  }

  await assert.rejects(
    () => uploadTeamAvatar(100, 'tmp/large.png', { wxApi }),
    /团队图标不能超过 5MB/
  )
})
