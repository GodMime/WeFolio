const { DEFAULT_BASE_URL, TOKEN_STORAGE_KEY } = require('./request')

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

function readMessage(messages, key, fallback) {
  return messages && messages[key] ? messages[key] : fallback
}

function compressImageFile(filePath, options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  const messages = options.messages || {}
  if (!runtimeWx.compressImage) {
    return Promise.reject(new Error(readMessage(messages, 'unsupportedCompress', '当前微信版本不支持图片压缩')))
  }

  return new Promise((resolve, reject) => {
    runtimeWx.compressImage({
      src: filePath,
      quality: options.compressQuality,
      compressedWidth: options.compressedSize,
      compressedHeight: options.compressedSize,
      success(response) {
        if (response && response.tempFilePath) {
          resolve(response.tempFilePath)
          return
        }
        reject(new Error(readMessage(messages, 'compressFailed', '图片压缩失败')))
      },
      fail(error) {
        reject(new Error(error && error.errMsg
          ? error.errMsg
          : readMessage(messages, 'compressFailed', '图片压缩失败')))
      }
    })
  })
}

async function prepareLocalUploadFilePath(filePath, options = {}) {
  if (!filePath || isRemoteUrl(filePath)) {
    return filePath || ''
  }

  const originalSize = getLocalFileSize(filePath, options.wxApi)
  if (!options.maxSizeBytes || originalSize <= options.maxSizeBytes) {
    return filePath
  }

  const compressedPath = await compressImageFile(filePath, options)
  const compressedSize = getLocalFileSize(compressedPath, options.wxApi)
  if (compressedSize > options.maxSizeBytes) {
    throw new Error(readMessage(options.messages, 'tooLarge', '图片文件不能超过限制'))
  }
  return compressedPath
}

function parseUploadResponse(response, parseErrorMessage) {
  try {
    return JSON.parse(response.data || '{}')
  } catch (error) {
    throw new Error(parseErrorMessage)
  }
}

function uploadPreparedFile(filePath, endpointUrl, options = {}, messages = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  const token = options.token !== undefined
    ? options.token
    : (runtimeWx.getStorageSync ? runtimeWx.getStorageSync(TOKEN_STORAGE_KEY) : '')
  const header = Object.assign({}, options.header || {})
  if (token && !header.Authorization) {
    header.Authorization = `Bearer ${token}`
  }

  return new Promise((resolve, reject) => {
    runtimeWx.uploadFile({
      url: joinUrl(options.baseUrl || DEFAULT_BASE_URL, endpointUrl),
      filePath,
      name: 'file',
      header,
      success(response) {
        let body
        try {
          body = parseUploadResponse(response, readMessage(messages, 'parseFailed', '上传响应解析失败'))
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
          reject(new Error(body.message || readMessage(messages, 'uploadFailed', '上传失败')))
          return
        }
        if (body.data && body.data.url) {
          resolve(body.data.url)
        } else {
          reject(new Error(readMessage(messages, 'missingUrl', '上传成功但未获取到文件地址')))
        }
      },
      fail(error) {
        reject(new Error(error && error.errMsg
          ? error.errMsg
          : readMessage(messages, 'uploadFailed', '上传失败')))
      }
    })
  })
}

module.exports = {
  isRemoteUrl,
  prepareLocalUploadFilePath,
  uploadPreparedFile
}
