const { request } = require('./request')
const { isRemoteUrl } = require('./upload-file')

const PORTFOLIO_COVER_MAX_SIZE_BYTES = 300 * 1024
const COS_UPLOAD_TIMEOUT = 10 * 60 * 1000
const DEFAULT_COVER_MIME_TYPE = 'image/jpeg'
const PNG_MIME_TYPE = 'image/png'
const COVER_COMPRESS_ATTEMPTS = [
  { quality: 85, compressedSize: 1080 },
  { quality: 78, compressedSize: 900 },
  { quality: 70, compressedSize: 720 },
  { quality: 62, compressedSize: 560 },
  { quality: 54, compressedSize: 420 }
]
const COVER_TOO_LARGE_MESSAGE = '封面图片不能超过 300KB'

function getRuntimeWx(wxApi) {
  if (wxApi) {
    return wxApi
  }
  if (typeof wx !== 'undefined') {
    return wx
  }
  throw new Error('wx 运行环境不可用')
}

function trimText(value) {
  return String(value || '').trim()
}

function createChoosePortfolioCoverOptions() {
  return {
    count: 1,
    mediaType: ['image'],
    sourceType: ['album']
  }
}

function getLocalFileSize(filePath, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  const fileSystemManager = runtimeWx.getFileSystemManager()
  const stats = fileSystemManager.statSync(filePath)
  return stats && typeof stats.size === 'number' ? stats.size : 0
}

function mimeTypeFromPath(filePath) {
  return /\.png($|\?)/i.test(trimText(filePath)) ? PNG_MIME_TYPE : DEFAULT_COVER_MIME_TYPE
}

function compressImageFile(filePath, attempt, options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  if (!runtimeWx.compressImage) {
    return Promise.reject(new Error('当前微信版本不支持图片压缩'))
  }
  return new Promise((resolve, reject) => {
    runtimeWx.compressImage({
      src: filePath,
      quality: attempt.quality,
      compressedWidth: attempt.compressedSize,
      compressedHeight: attempt.compressedSize,
      success(response) {
        if (response && response.tempFilePath) {
          resolve(response.tempFilePath)
          return
        }
        reject(new Error('封面图片压缩失败'))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '封面图片压缩失败'))
      }
    })
  })
}

async function preparePortfolioCoverFile(filePath, options = {}) {
  const normalizedPath = trimText(filePath)
  if (!normalizedPath || isRemoteUrl(normalizedPath)) {
    return {
      filePath: normalizedPath,
      fileSize: 0,
      mimeType: ''
    }
  }

  const originalSize = getLocalFileSize(normalizedPath, options.wxApi)
  if (originalSize > 0 && originalSize <= PORTFOLIO_COVER_MAX_SIZE_BYTES) {
    return {
      filePath: normalizedPath,
      fileSize: originalSize,
      mimeType: mimeTypeFromPath(normalizedPath)
    }
  }

  for (const attempt of COVER_COMPRESS_ATTEMPTS) {
    const compressedPath = await compressImageFile(normalizedPath, attempt, options)
    const compressedSize = getLocalFileSize(compressedPath, options.wxApi)
    if (compressedSize > 0 && compressedSize <= PORTFOLIO_COVER_MAX_SIZE_BYTES) {
      return {
        filePath: compressedPath,
        fileSize: compressedSize,
        mimeType: mimeTypeFromPath(compressedPath)
      }
    }
  }
  throw new Error(COVER_TOO_LARGE_MESSAGE)
}

function uploadCoverToCos(filePath, ticket, options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  return new Promise((resolve, reject) => {
    runtimeWx.uploadFile({
      url: ticket.uploadUrl,
      filePath,
      name: 'file',
      formData: ticket.formData || {},
      timeout: options.timeout || COS_UPLOAD_TIMEOUT,
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve()
          return
        }
        reject(new Error(`封面上传失败(${response.statusCode})`))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '封面上传失败'))
      }
    })
  })
}

async function uploadPortfolioCover(portfolioId, filePath, options = {}) {
  const normalizedPath = trimText(filePath)
  if (!normalizedPath || isRemoteUrl(normalizedPath)) {
    return normalizedPath
  }
  if (!portfolioId) {
    throw new Error('作品集不存在')
  }

  const prepared = await preparePortfolioCoverFile(normalizedPath, options)
  const ticket = await request({
    url: `/api/mine/portfolios/${portfolioId}/cover/upload-ticket`,
    method: 'POST',
    data: {
      clientId: `cover-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`,
      mimeType: prepared.mimeType,
      fileSize: prepared.fileSize
    }
  })
  await uploadCoverToCos(prepared.filePath, ticket, options)
  if (!ticket.publicUrl) {
    throw new Error('封面上传成功但未获取到文件地址')
  }
  return ticket.publicUrl
}

module.exports = {
  PORTFOLIO_COVER_MAX_SIZE_BYTES,
  createChoosePortfolioCoverOptions,
  preparePortfolioCoverFile,
  uploadPortfolioCover
}
