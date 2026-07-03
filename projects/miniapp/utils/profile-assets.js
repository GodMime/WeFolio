const { request } = require('./request')
const { isRemoteUrl } = require('./upload-file')

const PROFILE_ASSET_UPLOAD_TICKET_URL = '/api/mine/profile/assets/upload-ticket'
const PROFILE_ASSET_UPLOAD_TIMEOUT = 10 * 60 * 1000
const DEFAULT_IMAGE_MIME_TYPE = 'image/jpeg'
const PNG_MIME_TYPE = 'image/png'
const PROFILE_ASSET_TYPES = {
  AVATAR: 'AVATAR',
  WECHAT_QR: 'WECHAT_QR'
}
const PROFILE_ASSET_LABELS = {
  AVATAR: '头像文件',
  WECHAT_QR: '微信二维码'
}
const PROFILE_ASSET_MAX_SIZE_BYTES = {
  AVATAR: 200 * 1024,
  WECHAT_QR: 300 * 1024 - 1
}
const PROFILE_ASSET_COMPRESS_ATTEMPTS = [
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
  return PROFILE_ASSET_LABELS[assetType] ? assetType : PROFILE_ASSET_TYPES.WECHAT_QR
}

function getAssetLabel(assetType) {
  return PROFILE_ASSET_LABELS[normalizeAssetType(assetType)] || '资料图片'
}

function getMaxSizeBytes(assetType) {
  return PROFILE_ASSET_MAX_SIZE_BYTES[normalizeAssetType(assetType)] || PROFILE_ASSET_MAX_SIZE_BYTES.WECHAT_QR
}

function getTooLargeMessage(assetType) {
  return `${getAssetLabel(assetType)}不能超过 ${assetType === PROFILE_ASSET_TYPES.AVATAR ? '200KB' : '300KB'}`
}

function getLocalFileSize(filePath, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  const fileSystemManager = runtimeWx.getFileSystemManager()
  const stats = fileSystemManager.statSync(filePath)
  return stats && typeof stats.size === 'number' ? stats.size : 0
}

function mimeTypeFromPath(filePath) {
  return /\.png($|\?)/i.test(trimText(filePath)) ? PNG_MIME_TYPE : DEFAULT_IMAGE_MIME_TYPE
}

function compressImageFile(filePath, attempt, options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  const assetType = normalizeAssetType(options.assetType)
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
        reject(new Error(`${getAssetLabel(assetType)}压缩失败`))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : `${getAssetLabel(assetType)}压缩失败`))
      }
    })
  })
}

async function prepareProfileAssetFile(filePath, options = {}) {
  const normalizedPath = trimText(filePath)
  if (!normalizedPath || isRemoteUrl(normalizedPath)) {
    return {
      filePath: normalizedPath,
      fileSize: 0,
      mimeType: ''
    }
  }

  const assetType = normalizeAssetType(options.assetType)
  const maxSizeBytes = getMaxSizeBytes(assetType)
  const originalSize = getLocalFileSize(normalizedPath, options.wxApi)
  if (originalSize > 0 && originalSize <= maxSizeBytes) {
    return {
      filePath: normalizedPath,
      fileSize: originalSize,
      mimeType: mimeTypeFromPath(normalizedPath)
    }
  }

  for (const attempt of PROFILE_ASSET_COMPRESS_ATTEMPTS) {
    const compressedPath = await compressImageFile(normalizedPath, attempt, Object.assign({}, options, { assetType }))
    const compressedSize = getLocalFileSize(compressedPath, options.wxApi)
    if (compressedSize > 0 && compressedSize <= maxSizeBytes) {
      return {
        filePath: compressedPath,
        fileSize: compressedSize,
        mimeType: mimeTypeFromPath(compressedPath)
      }
    }
  }
  throw new Error(getTooLargeMessage(assetType))
}

function uploadProfileAssetToCos(filePath, ticket, options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  const assetType = normalizeAssetType(options.assetType)
  return new Promise((resolve, reject) => {
    runtimeWx.uploadFile({
      url: ticket.uploadUrl,
      filePath,
      name: 'file',
      formData: ticket.formData || {},
      timeout: options.timeout || PROFILE_ASSET_UPLOAD_TIMEOUT,
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve()
          return
        }
        reject(new Error(`${getAssetLabel(assetType)}上传失败(${response.statusCode})`))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : `${getAssetLabel(assetType)}上传失败`))
      }
    })
  })
}

async function uploadProfileAsset(filePath, assetType, options = {}) {
  const normalizedPath = trimText(filePath)
  if (!normalizedPath || isRemoteUrl(normalizedPath)) {
    return normalizedPath
  }

  const normalizedAssetType = normalizeAssetType(assetType)
  const prepared = await prepareProfileAssetFile(normalizedPath, Object.assign({}, options, {
    assetType: normalizedAssetType
  }))
  const ticket = await request({
    url: PROFILE_ASSET_UPLOAD_TICKET_URL,
    method: 'POST',
    data: {
      assetType: normalizedAssetType,
      mimeType: prepared.mimeType,
      fileSize: prepared.fileSize
    }
  })
  await uploadProfileAssetToCos(prepared.filePath, ticket, Object.assign({}, options, {
    assetType: normalizedAssetType
  }))
  if (!ticket.publicUrl) {
    throw new Error(`${getAssetLabel(normalizedAssetType)}上传成功但未获取到文件地址`)
  }
  return ticket.publicUrl
}

function uploadProfileAvatar(filePath, options = {}) {
  return uploadProfileAsset(filePath, PROFILE_ASSET_TYPES.AVATAR, options)
}

function uploadWechatQr(filePath, options = {}) {
  return uploadProfileAsset(filePath, PROFILE_ASSET_TYPES.WECHAT_QR, options)
}

module.exports = {
  PROFILE_ASSET_TYPES,
  PROFILE_ASSET_MAX_SIZE_BYTES,
  prepareProfileAssetFile,
  uploadProfileAsset,
  uploadProfileAssetToCos,
  uploadProfileAvatar,
  uploadWechatQr
}
