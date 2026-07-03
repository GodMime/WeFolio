const { request } = require('./request')
const { isRemoteUrl } = require('./upload-file')

const PORTFOLIO_IMAGE_ASSET_MAX_SIZE_BYTES = 300 * 1024
const PORTFOLIO_COVER_MAX_SIZE_BYTES = PORTFOLIO_IMAGE_ASSET_MAX_SIZE_BYTES
const PORTFOLIO_COVER_RATIO_WIDTH = 5
const PORTFOLIO_COVER_RATIO_HEIGHT = 4
const PORTFOLIO_COVER_CROP_OUTPUT_WIDTH = 1000
const PORTFOLIO_COVER_CROP_FILE_TYPE = 'jpg'
const PORTFOLIO_COVER_CROP_QUALITY = 0.92
const DEFAULT_CROP_BOX_WIDTH = 320
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

function normalizePortfolioCoverImageInfo(imageInfo = {}) {
  return {
    path: trimText(imageInfo.path || imageInfo.tempFilePath || imageInfo.filePath),
    width: toPositiveNumber(imageInfo.width),
    height: toPositiveNumber(imageInfo.height)
  }
}

function getPortfolioCoverImageInfo(imageFile = {}, options = {}) {
  const initialInfo = normalizePortfolioCoverImageInfo(imageFile)
  if (!initialInfo.path) {
    return Promise.reject(new Error('请选择封面图片'))
  }
  if (initialInfo.width && initialInfo.height) {
    return Promise.resolve(initialInfo)
  }

  const runtimeWx = getRuntimeWx(options.wxApi)
  if (!runtimeWx.getImageInfo) {
    return Promise.reject(new Error('当前微信版本无法读取图片尺寸'))
  }
  return new Promise((resolve, reject) => {
    runtimeWx.getImageInfo({
      src: initialInfo.path,
      success(response = {}) {
        const resolvedInfo = normalizePortfolioCoverImageInfo(Object.assign({}, response, {
          path: initialInfo.path
        }))
        if (!resolvedInfo.width || !resolvedInfo.height) {
          reject(new Error('无法读取封面图片尺寸'))
          return
        }
        resolve(resolvedInfo)
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '无法读取封面图片尺寸'))
      }
    })
  })
}

function isPortfolioCoverFiveFour(imageInfo = {}) {
  const normalizedInfo = normalizePortfolioCoverImageInfo(imageInfo)
  return normalizedInfo.width > 0 &&
    normalizedInfo.height > 0 &&
    normalizedInfo.width * PORTFOLIO_COVER_RATIO_HEIGHT === normalizedInfo.height * PORTFOLIO_COVER_RATIO_WIDTH
}

function shouldCropPortfolioCover(imageInfo = {}) {
  const normalizedInfo = normalizePortfolioCoverImageInfo(imageInfo)
  return Boolean(normalizedInfo.width && normalizedInfo.height && !isPortfolioCoverFiveFour(normalizedInfo))
}

function buildPortfolioCoverCropBoxStyle(cropBoxWidth, cropBoxHeight) {
  return `width: ${formatPx(cropBoxWidth)}; height: ${formatPx(cropBoxHeight)};`
}

function buildPortfolioCoverCropImageStyle(state = {}) {
  return [
    `width: ${formatPx(state.displayWidth)}`,
    `height: ${formatPx(state.displayHeight)}`,
    `transform: translate3d(${formatPx(state.offsetX)}, ${formatPx(state.offsetY)}, 0)`
  ].join('; ')
}

function withPortfolioCoverCropStyles(state = {}) {
  return Object.assign({}, state, {
    cropBoxStyle: buildPortfolioCoverCropBoxStyle(state.cropBoxWidth, state.cropBoxHeight),
    imageStyle: buildPortfolioCoverCropImageStyle(state)
  })
}

function buildPortfolioCoverCropState(imageInfo = {}, options = {}) {
  const normalizedInfo = normalizePortfolioCoverImageInfo(imageInfo)
  if (!normalizedInfo.path || !normalizedInfo.width || !normalizedInfo.height) {
    throw new Error('无法读取封面图片尺寸')
  }

  const cropBoxWidth = toPositiveNumber(options.cropBoxWidth) || DEFAULT_CROP_BOX_WIDTH
  const cropBoxHeight = cropBoxWidth * PORTFOLIO_COVER_RATIO_HEIGHT / PORTFOLIO_COVER_RATIO_WIDTH
  const scale = Math.max(cropBoxWidth / normalizedInfo.width, cropBoxHeight / normalizedInfo.height)
  const displayWidth = normalizedInfo.width * scale
  const displayHeight = normalizedInfo.height * scale
  const minOffsetX = Math.min(0, cropBoxWidth - displayWidth)
  const maxOffsetX = 0
  const minOffsetY = Math.min(0, cropBoxHeight - displayHeight)
  const maxOffsetY = 0
  const offsetX = clampNumber((cropBoxWidth - displayWidth) / 2, minOffsetX, maxOffsetX)
  const offsetY = clampNumber((cropBoxHeight - displayHeight) / 2, minOffsetY, maxOffsetY)

  return withPortfolioCoverCropStyles({
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

function movePortfolioCoverCropState(state = {}, movement = {}) {
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
  return withPortfolioCoverCropStyles(Object.assign({}, state, {
    offsetX: nextOffsetX,
    offsetY: nextOffsetY
  }))
}

function buildPortfolioCoverCropFrame(state = {}, options = {}) {
  const scale = toPositiveNumber(state.scale)
  const imageWidth = toPositiveNumber(state.imageWidth)
  const imageHeight = toPositiveNumber(state.imageHeight)
  const cropBoxWidth = toPositiveNumber(state.cropBoxWidth)
  const cropBoxHeight = toPositiveNumber(state.cropBoxHeight)
  if (!scale || !imageWidth || !imageHeight || !cropBoxWidth || !cropBoxHeight) {
    throw new Error('裁剪参数无效')
  }

  const sourceWidth = Math.min(imageWidth, Math.round(cropBoxWidth / scale))
  const sourceHeight = Math.min(imageHeight, Math.round(cropBoxHeight / scale))
  const sx = clampNumber(Math.round(-Number(state.offsetX || 0) / scale), 0, Math.max(0, imageWidth - sourceWidth))
  const sy = clampNumber(Math.round(-Number(state.offsetY || 0) / scale), 0, Math.max(0, imageHeight - sourceHeight))
  const destWidth = Math.round(toPositiveNumber(options.outputWidth) || PORTFOLIO_COVER_CROP_OUTPUT_WIDTH)
  const destHeight = Math.round(destWidth * PORTFOLIO_COVER_RATIO_HEIGHT / PORTFOLIO_COVER_RATIO_WIDTH)

  return {
    sx,
    sy,
    sWidth: sourceWidth,
    sHeight: sourceHeight,
    destWidth,
    destHeight
  }
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

function getCanvasNode(page, canvasId, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  if (!runtimeWx.createSelectorQuery) {
    return Promise.reject(new Error('当前微信版本不支持图片裁剪'))
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
          reject(new Error('未找到分享封面裁剪画布'))
          return
        }
        resolve(canvas)
      })
  })
}

function loadCanvasImage(canvas, imagePath) {
  if (!canvas || !canvas.createImage) {
    return Promise.reject(new Error('当前微信版本不支持图片裁剪'))
  }
  return new Promise((resolve, reject) => {
    const image = canvas.createImage()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('封面图片加载失败'))
    image.src = imagePath
  })
}

async function cropPortfolioCoverToTempFilePath(options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  const canvas = await getCanvasNode(options.page, options.canvasId, runtimeWx)
  const cropFrame = options.cropFrame || {}
  const destWidth = Math.round(toPositiveNumber(cropFrame.destWidth) || PORTFOLIO_COVER_CROP_OUTPUT_WIDTH)
  const destHeight = Math.round(toPositiveNumber(cropFrame.destHeight) || destWidth * PORTFOLIO_COVER_RATIO_HEIGHT / PORTFOLIO_COVER_RATIO_WIDTH)
  canvas.width = destWidth
  canvas.height = destHeight
  const context = canvas.getContext('2d')
  if (!context) {
    throw new Error('当前微信版本不支持图片裁剪')
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
      fileType: options.fileType || PORTFOLIO_COVER_CROP_FILE_TYPE,
      quality: options.quality || PORTFOLIO_COVER_CROP_QUALITY,
      success(response) {
        if (response && response.tempFilePath) {
          resolve(response.tempFilePath)
          return
        }
        reject(new Error('封面裁剪失败'))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '封面裁剪失败'))
      }
    })
  })
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
  PORTFOLIO_COVER_CROP_FILE_TYPE,
  PORTFOLIO_COVER_CROP_OUTPUT_WIDTH,
  PORTFOLIO_COVER_CROP_QUALITY,
  PORTFOLIO_COVER_RATIO_HEIGHT,
  PORTFOLIO_COVER_RATIO_WIDTH,
  PORTFOLIO_IMAGE_ASSET_MAX_SIZE_BYTES,
  buildPortfolioCoverCropFrame,
  buildPortfolioCoverCropState,
  createChoosePortfolioCoverOptions,
  createChoosePortfolioImageOptions,
  cropPortfolioCoverToTempFilePath,
  getPortfolioCoverImageInfo,
  isPortfolioCoverFiveFour,
  movePortfolioCoverCropState,
  preparePortfolioCoverFile,
  preparePortfolioImageAssetFile,
  shouldCropPortfolioCover,
  uploadPortfolioCover,
  uploadPortfolioImageAsset
}
