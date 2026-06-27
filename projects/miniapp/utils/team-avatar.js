const { DEFAULT_BASE_URL, TOKEN_STORAGE_KEY } = require('./request')

const TEAM_AVATAR_MAX_SIZE_BYTES = 5 * 1024 * 1024
const TEAM_AVATAR_COMPRESS_QUALITY = 95
const TEAM_AVATAR_COMPRESSED_SIZE = 512

// 测试时允许注入 wxApi；真机运行时使用微信小程序全局 wx。
function getRuntimeWx(wxApi) {
  if (wxApi) {
    return wxApi
  }
  if (typeof wx !== 'undefined') {
    return wx
  }
  throw new Error('wx 运行环境不可用')
}

// 已经是远程地址的头像无需上传；微信临时文件路径需要走 uploadFile。
function isRemoteUrl(url) {
  const value = url || ''
  return /^https?:\/\//.test(value) && !/^https?:\/\/tmp\//.test(value)
}

// 兼容 request.js 的默认 baseUrl，也允许测试传入完整地址。
function joinUrl(baseUrl, url) {
  if (/^https?:\/\//.test(url)) {
    return url
  }
  return `${baseUrl.replace(/\/$/, '')}${url.startsWith('/') ? url : `/${url}`}`
}

// 上传前读取本地文件大小，提前给出清晰错误，减少无意义的网络请求。
function getLocalFileSize(filePath, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  const fileSystemManager = runtimeWx.getFileSystemManager()
  const stats = fileSystemManager.statSync(filePath)
  return stats && typeof stats.size === 'number' ? stats.size : 0
}

// 团队图标与个人头像保持一致：超过 5MB 时压缩为 512x512，减少无效上传。
function compressTeamAvatar(filePath, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  if (!runtimeWx.compressImage) {
    return Promise.reject(new Error('当前微信版本不支持团队图标压缩'))
  }

  return new Promise((resolve, reject) => {
    runtimeWx.compressImage({
      src: filePath,
      quality: TEAM_AVATAR_COMPRESS_QUALITY,
      compressedWidth: TEAM_AVATAR_COMPRESSED_SIZE,
      compressedHeight: TEAM_AVATAR_COMPRESSED_SIZE,
      success(response) {
        if (response && response.tempFilePath) {
          resolve(response.tempFilePath)
          return
        }
        reject(new Error('团队图标压缩失败'))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '团队图标压缩失败'))
      }
    })
  })
}

async function prepareTeamAvatarFilePath(filePath, options = {}) {
  if (!filePath || isRemoteUrl(filePath)) {
    return filePath || ''
  }

  const wxApi = options.wxApi
  const originalSize = getLocalFileSize(filePath, wxApi)
  if (originalSize <= TEAM_AVATAR_MAX_SIZE_BYTES) {
    return filePath
  }

  const compressedPath = await compressTeamAvatar(filePath, wxApi)
  const compressedSize = getLocalFileSize(compressedPath, wxApi)
  if (compressedSize > TEAM_AVATAR_MAX_SIZE_BYTES) {
    throw new Error('团队图标不能超过 5MB')
  }
  return compressedPath
}

// 后端 Response 包装体通过 uploadFile 原样返回字符串，这里统一解析并抛出可读错误。
function parseUploadResponse(response) {
  try {
    return JSON.parse(response.data || '{}')
  } catch (error) {
    throw new Error('团队图标上传响应解析失败')
  }
}

// 上传团队图标到后端；成功后返回可直接保存到团队资料的公开 URL。
async function uploadTeamAvatar(teamId, filePath, options = {}) {
  if (!filePath || isRemoteUrl(filePath)) {
    return filePath || ''
  }
  if (!teamId) {
    throw new Error('团队 ID 不能为空')
  }

  const runtimeWx = getRuntimeWx(options.wxApi)
  const preparedFilePath = await prepareTeamAvatarFilePath(filePath, options)

  const token = options.token !== undefined
    ? options.token
    : (runtimeWx.getStorageSync ? runtimeWx.getStorageSync(TOKEN_STORAGE_KEY) : '')
  const header = Object.assign({}, options.header || {})
  // uploadFile 不走 request.js 封装，需要在这里手动补 Bearer token。
  if (token && !header.Authorization) {
    header.Authorization = `Bearer ${token}`
  }

  return new Promise((resolve, reject) => {
    runtimeWx.uploadFile({
      url: joinUrl(options.baseUrl || DEFAULT_BASE_URL, `/api/mine/teams/${teamId}/avatar`),
      filePath: preparedFilePath,
      name: 'file',
      header,
      success(response) {
        let body
        try {
          body = parseUploadResponse(response)
        } catch (error) {
          reject(error)
          return
        }
        if (response.statusCode === 401) {
          const error = new Error(body.message || '未登录')
          error.authRequired = true
          reject(error)
          return
        }
        if (response.statusCode < 200 || response.statusCode >= 300 || body.success === false) {
          reject(new Error(body.message || '团队图标上传失败'))
          return
        }
        if (body.data && body.data.url) {
          resolve(body.data.url)
        } else {
          reject(new Error('团队图标上传成功但未获取到文件地址'))
        }
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '团队图标上传失败'))
      }
    })
  })
}

module.exports = {
  TEAM_AVATAR_MAX_SIZE_BYTES,
  prepareTeamAvatarFilePath,
  uploadTeamAvatar
}
