const { request } = require('./request')
const { isRemoteUrl } = require('./upload-file')

const PROFILE_ASSET_UPLOAD_TICKET_URL = '/api/mine/profile/assets/upload-ticket'
const PROFILE_ASSET_UPLOAD_TIMEOUT = 10 * 60 * 1000
const DEFAULT_IMAGE_MIME_TYPE = 'image/jpeg'
const PNG_MIME_TYPE = 'image/png'
const WECHAT_QR_CROP_OUTPUT_WIDTH = 720
const WECHAT_QR_CROP_FILE_TYPE = 'jpg'
const WECHAT_QR_CROP_QUALITY = 0.92
const DEFAULT_WECHAT_QR_CROP_BOX_WIDTH = 320
const IMAGE_CROP_UNSUPPORTED_MESSAGE = '当前微信版本不支持图片裁剪'
const WECHAT_QR_IMAGE_REQUIRED_MESSAGE = '请选择二维码图片'
const WECHAT_QR_IMAGE_SIZE_ERROR_MESSAGE = '无法读取二维码图片尺寸'
const WECHAT_QR_IMAGE_LOAD_ERROR_MESSAGE = '二维码图片加载失败'
const WECHAT_QR_CROP_FAILED_MESSAGE = '二维码裁剪失败'
const WECHAT_QR_CROP_CANVAS_MISSING_MESSAGE = '未找到二维码裁剪画布'
const WECHAT_QR_CROP_PARAMS_ERROR_MESSAGE = '裁剪参数无效'
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

function toPositiveNumber(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : 0
}

function clampNumber(value, minValue, maxValue) {
  return Math.min(Math.max(value, minValue), maxValue)
}

function roundNumber(value) {
  return Math.round(Number(value) * 1000) / 1000
}

function formatPx(value) {
  return `${roundNumber(value)}px`
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

function normalizeWechatQrImageInfo(imageInfo = {}) {
  return {
    path: trimText(imageInfo.path || imageInfo.tempFilePath || imageInfo.filePath),
    width: toPositiveNumber(imageInfo.width),
    height: toPositiveNumber(imageInfo.height)
  }
}

function getWechatQrImageInfo(imageFile = {}, options = {}) {
  const initialInfo = normalizeWechatQrImageInfo(imageFile)
  if (!initialInfo.path) {
    return Promise.reject(new Error(WECHAT_QR_IMAGE_REQUIRED_MESSAGE))
  }
  if (initialInfo.width && initialInfo.height) {
    return Promise.resolve(initialInfo)
  }

  const runtimeWx = getRuntimeWx(options.wxApi)
  if (!runtimeWx.getImageInfo) {
    return Promise.reject(new Error(WECHAT_QR_IMAGE_SIZE_ERROR_MESSAGE))
  }
  return new Promise((resolve, reject) => {
    runtimeWx.getImageInfo({
      src: initialInfo.path,
      success(response = {}) {
        const resolvedInfo = normalizeWechatQrImageInfo(Object.assign({}, response, {
          path: initialInfo.path
        }))
        if (!resolvedInfo.width || !resolvedInfo.height) {
          reject(new Error(WECHAT_QR_IMAGE_SIZE_ERROR_MESSAGE))
          return
        }
        resolve(resolvedInfo)
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : WECHAT_QR_IMAGE_SIZE_ERROR_MESSAGE))
      }
    })
  })
}

function buildWechatQrCropBoxStyle(cropBoxWidth, cropBoxHeight) {
  return `width: ${formatPx(cropBoxWidth)}; height: ${formatPx(cropBoxHeight)};`
}

function buildWechatQrCropImageStyle(state = {}) {
  return [
    `width: ${formatPx(state.displayWidth)}`,
    `height: ${formatPx(state.displayHeight)}`,
    `transform: translate3d(${formatPx(state.offsetX)}, ${formatPx(state.offsetY)}, 0)`
  ].join('; ')
}

function withWechatQrCropStyles(state = {}) {
  return Object.assign({}, state, {
    cropBoxStyle: buildWechatQrCropBoxStyle(state.cropBoxWidth, state.cropBoxHeight),
    imageStyle: buildWechatQrCropImageStyle(state)
  })
}

function buildWechatQrCropState(imageInfo = {}, options = {}) {
  const normalizedInfo = normalizeWechatQrImageInfo(imageInfo)
  if (!normalizedInfo.path || !normalizedInfo.width || !normalizedInfo.height) {
    throw new Error(WECHAT_QR_IMAGE_SIZE_ERROR_MESSAGE)
  }

  const cropBoxWidth = toPositiveNumber(options.cropBoxWidth) || DEFAULT_WECHAT_QR_CROP_BOX_WIDTH
  const cropBoxHeight = cropBoxWidth
  const scale = Math.max(cropBoxWidth / normalizedInfo.width, cropBoxHeight / normalizedInfo.height)
  const displayWidth = normalizedInfo.width * scale
  const displayHeight = normalizedInfo.height * scale
  const minOffsetX = Math.min(0, cropBoxWidth - displayWidth)
  const maxOffsetX = 0
  const minOffsetY = Math.min(0, cropBoxHeight - displayHeight)
  const maxOffsetY = 0
  const offsetX = clampNumber((cropBoxWidth - displayWidth) / 2, minOffsetX, maxOffsetX)
  const offsetY = clampNumber((cropBoxHeight - displayHeight) / 2, minOffsetY, maxOffsetY)

  return withWechatQrCropStyles({
    imagePath: normalizedInfo.path,
    imageWidth: normalizedInfo.width,
    imageHeight: normalizedInfo.height,
    cropBoxWidth,
    cropBoxHeight,
    displayWidth,
    displayHeight,
    offsetX,
    offsetY,
    minOffsetX,
    maxOffsetX,
    minOffsetY,
    maxOffsetY,
    scale
  })
}

function moveWechatQrCropState(state = {}, movement = {}) {
  const minOffsetX = Number.isFinite(Number(state.minOffsetX)) ? Number(state.minOffsetX) : 0
  const maxOffsetX = Number.isFinite(Number(state.maxOffsetX)) ? Number(state.maxOffsetX) : 0
  const minOffsetY = Number.isFinite(Number(state.minOffsetY)) ? Number(state.minOffsetY) : 0
  const maxOffsetY = Number.isFinite(Number(state.maxOffsetY)) ? Number(state.maxOffsetY) : 0
  const offsetX = Number.isFinite(Number(state.offsetX)) ? Number(state.offsetX) : 0
  const offsetY = Number.isFinite(Number(state.offsetY)) ? Number(state.offsetY) : 0
  const nextOffsetX = clampNumber(
    maxOffsetX > minOffsetX ? offsetX + Number(movement.deltaX || 0) : 0,
    minOffsetX,
    maxOffsetX
  )
  const nextOffsetY = clampNumber(
    maxOffsetY > minOffsetY ? offsetY + Number(movement.deltaY || 0) : 0,
    minOffsetY,
    maxOffsetY
  )
  return withWechatQrCropStyles(Object.assign({}, state, {
    offsetX: nextOffsetX,
    offsetY: nextOffsetY
  }))
}

function buildWechatQrCropFrame(state = {}, options = {}) {
  const scale = toPositiveNumber(state.scale)
  const imageWidth = toPositiveNumber(state.imageWidth)
  const imageHeight = toPositiveNumber(state.imageHeight)
  const cropBoxWidth = toPositiveNumber(state.cropBoxWidth)
  const cropBoxHeight = toPositiveNumber(state.cropBoxHeight)
  if (!scale || !imageWidth || !imageHeight || !cropBoxWidth || !cropBoxHeight) {
    throw new Error(WECHAT_QR_CROP_PARAMS_ERROR_MESSAGE)
  }

  const sourceWidth = Math.min(imageWidth, Math.round(cropBoxWidth / scale))
  const sourceHeight = Math.min(imageHeight, Math.round(cropBoxHeight / scale))
  const sx = clampNumber(Math.round(-Number(state.offsetX || 0) / scale), 0, Math.max(0, imageWidth - sourceWidth))
  const sy = clampNumber(Math.round(-Number(state.offsetY || 0) / scale), 0, Math.max(0, imageHeight - sourceHeight))
  const destWidth = Math.round(toPositiveNumber(options.outputWidth) || WECHAT_QR_CROP_OUTPUT_WIDTH)

  return {
    sx,
    sy,
    sWidth: sourceWidth,
    sHeight: sourceHeight,
    destWidth,
    destHeight: destWidth
  }
}

function getCanvasNode(page, canvasId, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  if (!runtimeWx.createSelectorQuery) {
    return Promise.reject(new Error(IMAGE_CROP_UNSUPPORTED_MESSAGE))
  }
  return new Promise((resolve, reject) => {
    const query = runtimeWx.createSelectorQuery()
    const scopedQuery = query.in ? query.in(page) : query
    scopedQuery
      .select(`#${canvasId}`)
      .fields({ node: true, size: true })
      .exec((results = []) => {
        const canvas = results[0] && results[0].node
        if (!canvas) {
          reject(new Error(WECHAT_QR_CROP_CANVAS_MISSING_MESSAGE))
          return
        }
        resolve(canvas)
      })
  })
}

function loadCanvasImage(canvas, imagePath) {
  if (!canvas || !canvas.createImage) {
    return Promise.reject(new Error(IMAGE_CROP_UNSUPPORTED_MESSAGE))
  }
  return new Promise((resolve, reject) => {
    const image = canvas.createImage()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error(WECHAT_QR_IMAGE_LOAD_ERROR_MESSAGE))
    image.src = imagePath
  })
}

async function cropWechatQrToTempFilePath(options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  const canvas = await getCanvasNode(options.page, options.canvasId, runtimeWx)
  const cropFrame = options.cropFrame || {}
  const destWidth = Math.round(toPositiveNumber(cropFrame.destWidth) || WECHAT_QR_CROP_OUTPUT_WIDTH)
  const destHeight = Math.round(toPositiveNumber(cropFrame.destHeight) || destWidth)
  canvas.width = destWidth
  canvas.height = destHeight
  const context = canvas.getContext('2d')
  if (!context) {
    throw new Error(IMAGE_CROP_UNSUPPORTED_MESSAGE)
  }
  const image = await loadCanvasImage(canvas, options.imagePath)
  context.clearRect(0, 0, destWidth, destHeight)
  context.drawImage(
    image,
    cropFrame.sx,
    cropFrame.sy,
    cropFrame.sWidth,
    cropFrame.sHeight,
    0,
    0,
    destWidth,
    destHeight
  )
  return new Promise((resolve, reject) => {
    runtimeWx.canvasToTempFilePath({
      canvas,
      x: 0,
      y: 0,
      width: destWidth,
      height: destHeight,
      destWidth,
      destHeight,
      fileType: options.fileType || WECHAT_QR_CROP_FILE_TYPE,
      quality: options.quality || WECHAT_QR_CROP_QUALITY,
      success(response) {
        if (response && response.tempFilePath) {
          resolve(response.tempFilePath)
          return
        }
        reject(new Error(WECHAT_QR_CROP_FAILED_MESSAGE))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : WECHAT_QR_CROP_FAILED_MESSAGE))
      }
    })
  })
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
  WECHAT_QR_CROP_FILE_TYPE,
  WECHAT_QR_CROP_OUTPUT_WIDTH,
  WECHAT_QR_CROP_QUALITY,
  buildWechatQrCropFrame,
  buildWechatQrCropState,
  cropWechatQrToTempFilePath,
  getWechatQrImageInfo,
  moveWechatQrCropState,
  prepareProfileAssetFile,
  uploadProfileAsset,
  uploadProfileAssetToCos,
  uploadProfileAvatar,
  uploadWechatQr
}
