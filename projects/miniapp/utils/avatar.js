const { DEFAULT_BASE_URL, TOKEN_STORAGE_KEY } = require('./request')

const AVATAR_MAX_SIZE_BYTES = 5 * 1024 * 1024
const AVATAR_COMPRESS_QUALITY = 95
const AVATAR_COMPRESSED_SIZE = 512

function getRuntimeWx(wxApi) {
  if (wxApi) {
    return wxApi
  }
  if (typeof wx !== 'undefined') {
    return wx
  }
  throw new Error('wx 运行环境不可用')
}

function isRemoteUrl(url) {
  const value = url || ''
  return /^https?:\/\//.test(value) && !/^https?:\/\/tmp\//.test(value)
}

function joinUrl(baseUrl, url) {
  if (/^https?:\/\//.test(url)) {
    return url
  }
  return `${baseUrl.replace(/\/$/, '')}${url.startsWith('/') ? url : `/${url}`}`
}

function getLocalFileSize(filePath, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  const fileSystemManager = runtimeWx.getFileSystemManager()
  const stats = fileSystemManager.statSync(filePath)
  return stats && typeof stats.size === 'number' ? stats.size : 0
}

function compressImage(filePath, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  if (!runtimeWx.compressImage) {
    return Promise.reject(new Error('当前微信版本不支持头像压缩'))
  }

  return new Promise((resolve, reject) => {
    runtimeWx.compressImage({
      src: filePath,
      quality: AVATAR_COMPRESS_QUALITY,
      compressedWidth: AVATAR_COMPRESSED_SIZE,
      compressedHeight: AVATAR_COMPRESSED_SIZE,
      success(response) {
        if (response && response.tempFilePath) {
          resolve(response.tempFilePath)
          return
        }
        reject(new Error('头像压缩失败'))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '头像压缩失败'))
      }
    })
  })
}

async function prepareAvatarFilePath(filePath, options = {}) {
  if (!filePath || isRemoteUrl(filePath)) {
    return filePath || ''
  }

  const wxApi = options.wxApi
  const originalSize = getLocalFileSize(filePath, wxApi)
  if (originalSize <= AVATAR_MAX_SIZE_BYTES) {
    return filePath
  }

  const compressedPath = await compressImage(filePath, wxApi)
  const compressedSize = getLocalFileSize(compressedPath, wxApi)
  if (compressedSize > AVATAR_MAX_SIZE_BYTES) {
    throw new Error('头像文件不能超过 5MB')
  }
  return compressedPath
}

function parseUploadResponse(response) {
  try {
    return JSON.parse(response.data || '{}')
  } catch (error) {
    throw new Error('头像上传响应解析失败')
  }
}

async function uploadAvatar(filePath, options = {}) {
  if (!filePath || isRemoteUrl(filePath)) {
    return filePath || ''
  }

  const runtimeWx = getRuntimeWx(options.wxApi)
  const preparedFilePath = options.skipPrepare
    ? filePath
    : await prepareAvatarFilePath(filePath, options)
  const token = options.token !== undefined
    ? options.token
    : (runtimeWx.getStorageSync ? runtimeWx.getStorageSync(TOKEN_STORAGE_KEY) : '')
  const header = Object.assign({}, options.header || {})
  if (token && !header.Authorization) {
    header.Authorization = `Bearer ${token}`
  }

  return new Promise((resolve, reject) => {
    runtimeWx.uploadFile({
      url: joinUrl(options.baseUrl || DEFAULT_BASE_URL, '/api/auth/avatar'),
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
          reject(new Error(body.message || '头像上传失败'))
          return
        }
        if (body.data && body.data.url) {
          resolve(body.data.url)
        } else {
          reject(new Error('头像上传成功但未获取到文件地址'))
        }
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '头像上传失败'))
      }
    })
  })
}

module.exports = {
  AVATAR_MAX_SIZE_BYTES,
  prepareAvatarFilePath,
  uploadAvatar
}
