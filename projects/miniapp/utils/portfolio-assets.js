const { request } = require('./request')
const { isRemoteUrl } = require('./upload-file')

const PORTFOLIO_IMAGE_ASSET_MAX_SIZE_BYTES = 300 * 1024
const PORTFOLIO_COVER_MAX_SIZE_BYTES = PORTFOLIO_IMAGE_ASSET_MAX_SIZE_BYTES
const COS_UPLOAD_TIMEOUT = 10 * 60 * 1000
const DEFAULT_IMAGE_MIME_TYPE = 'image/jpeg'
const PNG_MIME_TYPE = 'image/png'
const PORTFOLIO_ASSET_TYPES = {
  COVER: 'COVER',
  PROFILE_AVATAR: 'PROFILE_AVATAR',
  QR_CONTACT: 'QR_CONTACT'
}
const ASSET_LABELS = {
  COVER: '封面图片',
  PROFILE_AVATAR: '头像图片',
  QR_CONTACT: '二维码图片'
}
const ASSET_CLIENT_ID_PREFIXES = {
  COVER: 'cover',
  PROFILE_AVATAR: 'profile-avatar',
  QR_CONTACT: 'qr-contact'
}
const ASSET_COMPRESS_ATTEMPTS = [
  { quality: 85, compressedSize: 1080 },
  { quality: 78, compressedSize: 900 },
  { quality: 70, compressedSize: 720 },
  { quality: 62, compressedSize: 560 },
  { quality: 54, compressedSize: 420 }
]

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

function normalizeAssetType(assetType) {
  const normalized = trimText(assetType)
  return ASSET_LABELS[normalized] ? normalized : PORTFOLIO_ASSET_TYPES.COVER
}

function getAssetLabel(options = {}) {
  return trimText(options.assetLabel) || ASSET_LABELS[normalizeAssetType(options.assetType)] || '图片'
}

function getTooLargeMessage(options = {}) {
  return trimText(options.tooLargeMessage) || `${getAssetLabel(options)}不能超过 300KB`
}

function createChoosePortfolioImageOptions() {
  return {
    count: 1,
    mediaType: ['image'],
    sourceType: ['album']
  }
}

function createChoosePortfolioCoverOptions() {
  return createChoosePortfolioImageOptions()
}

function getLocalFileSize(filePath, wxApi) {
  try {
    const runtimeWx = getRuntimeWx(wxApi)
    const fileSystemManager = runtimeWx.getFileSystemManager()
    const stats = fileSystemManager.statSync(filePath)
    return stats && typeof stats.size === 'number' ? stats.size : 0
  } catch (error) {
    return 0
  }
}

function mimeTypeFromPath(filePath) {
  return /\.png($|\?)/i.test(trimText(filePath)) ? PNG_MIME_TYPE : DEFAULT_IMAGE_MIME_TYPE
}

function compressImageFile(filePath, attempt, options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  const assetLabel = getAssetLabel(options)
  if (!runtimeWx.compressImage) {
    return Promise.reject(new Error('当前微信版本不支持图片压缩'))
  }
  return new Promise((resolve, reject) => {
    runtimeWx.compressImage({
      src: filePath,
      quality: attempt.quality,
      compressedWidth: attempt.compressedSize,
      success(response) {
        if (response && response.tempFilePath) {
          resolve(response.tempFilePath)
          return
        }
        reject(new Error(`${assetLabel}压缩失败`))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : `${assetLabel}压缩失败`))
      }
    })
  })
}

async function preparePortfolioImageAssetFile(filePath, options = {}) {
  const normalizedPath = trimText(filePath)
  if (!normalizedPath || isRemoteUrl(normalizedPath)) {
    return {
      filePath: normalizedPath,
      fileSize: 0,
      mimeType: ''
    }
  }

  const originalSize = getLocalFileSize(normalizedPath, options.wxApi)
  if (originalSize > 0 && originalSize <= PORTFOLIO_IMAGE_ASSET_MAX_SIZE_BYTES) {
    return {
      filePath: normalizedPath,
      fileSize: originalSize,
      mimeType: mimeTypeFromPath(normalizedPath)
    }
  }

  for (const attempt of ASSET_COMPRESS_ATTEMPTS) {
    const compressedPath = await compressImageFile(normalizedPath, attempt, options)
    const compressedSize = getLocalFileSize(compressedPath, options.wxApi)
    if (compressedSize > 0 && compressedSize <= PORTFOLIO_IMAGE_ASSET_MAX_SIZE_BYTES) {
      return {
        filePath: compressedPath,
        fileSize: compressedSize,
        mimeType: mimeTypeFromPath(compressedPath)
      }
    }
  }
  throw new Error(getTooLargeMessage(options))
}

function preparePortfolioCoverFile(filePath, options = {}) {
  return preparePortfolioImageAssetFile(filePath, Object.assign({}, options, {
    assetType: PORTFOLIO_ASSET_TYPES.COVER,
    assetLabel: ASSET_LABELS.COVER
  }))
}

function uploadPortfolioAssetToCos(filePath, ticket, options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  const assetLabel = getAssetLabel(options)
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
        reject(new Error(`${assetLabel}上传失败(${response.statusCode})`))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : `${assetLabel}上传失败`))
      }
    })
  })
}

function buildAssetClientId(assetType, options = {}) {
  const prefix = trimText(options.clientIdPrefix) || ASSET_CLIENT_ID_PREFIXES[assetType] || 'portfolio-asset'
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`
}

async function uploadPortfolioImageAsset(portfolioId, filePath, options = {}) {
  const normalizedPath = trimText(filePath)
  if (!normalizedPath || isRemoteUrl(normalizedPath)) {
    return normalizedPath
  }
  if (!portfolioId) {
    throw new Error('作品集不存在')
  }

  const assetType = normalizeAssetType(options.assetType)
  const uploadOptions = Object.assign({}, options, { assetType })
  const prepared = await preparePortfolioImageAssetFile(normalizedPath, uploadOptions)
  const ticket = await request({
    url: options.ticketUrl || `/api/mine/portfolios/${portfolioId}/asset/upload-ticket`,
    method: 'POST',
    data: {
      clientId: buildAssetClientId(assetType, options),
      assetType,
      mimeType: prepared.mimeType,
      fileSize: prepared.fileSize
    }
  })
  await uploadPortfolioAssetToCos(prepared.filePath, ticket, uploadOptions)
  if (!ticket.publicUrl) {
    throw new Error(`${getAssetLabel(uploadOptions)}上传成功但未获取到文件地址`)
  }
  return ticket.publicUrl
}

function uploadPortfolioCover(portfolioId, filePath, options = {}) {
  return uploadPortfolioImageAsset(portfolioId, filePath, Object.assign({}, options, {
    assetType: PORTFOLIO_ASSET_TYPES.COVER,
    assetLabel: ASSET_LABELS.COVER,
    clientIdPrefix: ASSET_CLIENT_ID_PREFIXES.COVER
  }))
}

module.exports = {
  PORTFOLIO_ASSET_TYPES,
  PORTFOLIO_COVER_MAX_SIZE_BYTES,
  PORTFOLIO_IMAGE_ASSET_MAX_SIZE_BYTES,
  createChoosePortfolioCoverOptions,
  createChoosePortfolioImageOptions,
  preparePortfolioCoverFile,
  preparePortfolioImageAssetFile,
  uploadPortfolioCover,
  uploadPortfolioImageAsset
}
