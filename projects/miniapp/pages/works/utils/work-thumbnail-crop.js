const {
  THUMB_MAX_BYTES,
  buildAspectRatio
} = require('./media')

const DEFAULT_CROP_BOX_WIDTH = 320
const DEFAULT_THUMBNAIL_MIME_TYPE = 'image/jpeg'
const WORK_THUMBNAIL_FILE_TYPE = 'jpg'
const WORK_THUMBNAIL_CROP_QUALITY = 0.9
const WORK_THUMBNAIL_MAX_SIDE = 960
const WORK_THUMBNAIL_MAX_ZOOM_RATIO = 4
const WORK_THUMBNAIL_TOO_LARGE_MESSAGE = '缩略图不能超过 100KB'
const WORK_THUMBNAIL_RATIO_OPTIONS = [
  { key: 'original', label: '原图比例' },
  { key: '1:1', label: '1:1', width: 1, height: 1 },
  { key: '4:3', label: '4:3', width: 4, height: 3 },
  { key: '3:4', label: '3:4', width: 3, height: 4 },
  { key: '16:9', label: '16:9', width: 16, height: 9 },
  { key: '9:16', label: '9:16', width: 9, height: 16 }
]
const WORK_THUMBNAIL_COMPRESS_ATTEMPTS = [
  { quality: 90, maxSide: 960 },
  { quality: 82, maxSide: 840 },
  { quality: 74, maxSide: 720 },
  { quality: 66, maxSide: 600 },
  { quality: 58, maxSide: 480 },
  { quality: 50, maxSide: 360 },
  { quality: 42, maxSide: 300 }
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

function roundNumber(value) {
  return Math.round(Number(value) * 1000) / 1000
}

function formatPx(value) {
  return `${roundNumber(value)}px`
}

function clampNumber(value, minValue, maxValue) {
  return Math.min(Math.max(value, minValue), maxValue)
}

function normalizeWorkThumbnailImageInfo(imageInfo = {}) {
  return {
    path: trimText(imageInfo.path || imageInfo.tempFilePath || imageInfo.filePath),
    width: Math.round(toPositiveNumber(imageInfo.width)),
    height: Math.round(toPositiveNumber(imageInfo.height))
  }
}

function getWorkThumbnailImageInfo(imageFile = {}, options = {}) {
  const initialInfo = normalizeWorkThumbnailImageInfo(imageFile)
  if (!initialInfo.path) {
    return Promise.reject(new Error('无法读取原图尺寸'))
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
        const resolvedInfo = normalizeWorkThumbnailImageInfo(Object.assign({}, response, {
          path: response.path || initialInfo.path
        }))
        if (!resolvedInfo.width || !resolvedInfo.height) {
          reject(new Error('无法读取原图尺寸'))
          return
        }
        resolve(resolvedInfo)
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '无法读取原图尺寸'))
      }
    })
  })
}

function parseRatioValue(ratio = {}, imageInfo = {}) {
  const normalizedInfo = normalizeWorkThumbnailImageInfo(imageInfo)
  if (ratio.key === 'original') {
    const ratioWidth = Math.round(toPositiveNumber(ratio.width))
    const ratioHeight = Math.round(toPositiveNumber(ratio.height))
    if (ratioWidth && ratioHeight) {
      return {
        width: ratioWidth,
        height: ratioHeight
      }
    }
    return {
      width: normalizedInfo.width || 1,
      height: normalizedInfo.height || 1
    }
  }
  return {
    width: Math.round(toPositiveNumber(ratio.width)) || 1,
    height: Math.round(toPositiveNumber(ratio.height)) || 1
  }
}

function simplifyRatioValue(width, height) {
  const ratioText = buildAspectRatio(width, height)
  const parts = ratioText.split(':')
  return {
    width: Math.round(toPositiveNumber(parts[0])) || 1,
    height: Math.round(toPositiveNumber(parts[1])) || 1
  }
}

function buildWorkThumbnailRatioOptions(imageInfo = {}) {
  const originalRatio = simplifyRatioValue(imageInfo.width, imageInfo.height)
  return WORK_THUMBNAIL_RATIO_OPTIONS.map((option) => {
    if (option.key !== 'original') {
      return Object.assign({}, option)
    }
    return Object.assign({}, option, originalRatio)
  })
}

function buildWorkThumbnailCropBoxStyle(cropBoxWidth, cropBoxHeight) {
  return `width: ${formatPx(cropBoxWidth)}; height: ${formatPx(cropBoxHeight)};`
}

function buildWorkThumbnailCropImageStyle(state = {}) {
  return [
    `width: ${formatPx(state.displayWidth)}`,
    `height: ${formatPx(state.displayHeight)}`,
    `transform: translate3d(${formatPx(state.offsetX)}, ${formatPx(state.offsetY)}, 0)`
  ].join('; ')
}

function withWorkThumbnailCropStyles(state = {}) {
  return Object.assign({}, state, {
    cropBoxStyle: buildWorkThumbnailCropBoxStyle(state.cropBoxWidth, state.cropBoxHeight),
    imageStyle: buildWorkThumbnailCropImageStyle(state)
  })
}

function buildWorkThumbnailCropState(imageInfo = {}, ratio = {}, options = {}) {
  const normalizedInfo = normalizeWorkThumbnailImageInfo(imageInfo)
  if (!normalizedInfo.path || !normalizedInfo.width || !normalizedInfo.height) {
    throw new Error('无法读取原图尺寸')
  }

  const ratioValue = parseRatioValue(ratio, normalizedInfo)
  const cropBoxWidth = toPositiveNumber(options.cropBoxWidth) || DEFAULT_CROP_BOX_WIDTH
  const cropBoxHeight = cropBoxWidth * ratioValue.height / ratioValue.width
  const scale = Math.max(cropBoxWidth / normalizedInfo.width, cropBoxHeight / normalizedInfo.height)
  const displayWidth = normalizedInfo.width * scale
  const displayHeight = normalizedInfo.height * scale
  const minOffsetX = Math.min(0, cropBoxWidth - displayWidth)
  const maxOffsetX = 0
  const minOffsetY = Math.min(0, cropBoxHeight - displayHeight)
  const maxOffsetY = 0
  const offsetX = clampNumber((cropBoxWidth - displayWidth) / 2, minOffsetX, maxOffsetX)
  const offsetY = clampNumber((cropBoxHeight - displayHeight) / 2, minOffsetY, maxOffsetY)

  return withWorkThumbnailCropStyles({
    imagePath: normalizedInfo.path,
    imageWidth: normalizedInfo.width,
    imageHeight: normalizedInfo.height,
    ratioKey: ratio.key || 'original',
    ratioWidth: ratioValue.width,
    ratioHeight: ratioValue.height,
    cropBoxWidth: roundNumber(cropBoxWidth),
    cropBoxHeight: roundNumber(cropBoxHeight),
    displayWidth: roundNumber(displayWidth),
    displayHeight: roundNumber(displayHeight),
    offsetX: roundNumber(offsetX),
    offsetY: roundNumber(offsetY),
    minOffsetX: roundNumber(minOffsetX),
    maxOffsetX: roundNumber(maxOffsetX),
    minOffsetY: roundNumber(minOffsetY),
    maxOffsetY: roundNumber(maxOffsetY),
    scale
  })
}

function moveWorkThumbnailCropState(state = {}, movement = {}) {
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
  return withWorkThumbnailCropStyles(Object.assign({}, state, {
    offsetX: roundNumber(nextOffsetX),
    offsetY: roundNumber(nextOffsetY)
  }))
}

function getWorkThumbnailMinimumScale(state = {}) {
  const imageWidth = toPositiveNumber(state.imageWidth)
  const imageHeight = toPositiveNumber(state.imageHeight)
  const cropBoxWidth = toPositiveNumber(state.cropBoxWidth)
  const cropBoxHeight = toPositiveNumber(state.cropBoxHeight)
  if (!imageWidth || !imageHeight || !cropBoxWidth || !cropBoxHeight) {
    return 0
  }
  return Math.max(cropBoxWidth / imageWidth, cropBoxHeight / imageHeight)
}

function zoomWorkThumbnailCropState(state = {}, zoom = {}) {
  const imageWidth = toPositiveNumber(state.imageWidth)
  const imageHeight = toPositiveNumber(state.imageHeight)
  const cropBoxWidth = toPositiveNumber(state.cropBoxWidth)
  const cropBoxHeight = toPositiveNumber(state.cropBoxHeight)
  const minimumScale = getWorkThumbnailMinimumScale(state)
  if (!imageWidth || !imageHeight || !cropBoxWidth || !cropBoxHeight || !minimumScale) {
    return withWorkThumbnailCropStyles(Object.assign({}, state))
  }

  const currentScale = toPositiveNumber(state.scale) || minimumScale
  const scaleRatio = toPositiveNumber(zoom.scaleRatio) || 1
  const nextScale = clampNumber(currentScale * scaleRatio, minimumScale, minimumScale * WORK_THUMBNAIL_MAX_ZOOM_RATIO)
  const displayWidth = imageWidth * nextScale
  const displayHeight = imageHeight * nextScale
  const minOffsetX = Math.min(0, cropBoxWidth - displayWidth)
  const maxOffsetX = 0
  const minOffsetY = Math.min(0, cropBoxHeight - displayHeight)
  const maxOffsetY = 0
  const currentOffsetX = Number.isFinite(Number(state.offsetX)) ? Number(state.offsetX) : 0
  const currentOffsetY = Number.isFinite(Number(state.offsetY)) ? Number(state.offsetY) : 0
  const anchorX = clampNumber(
    Number.isFinite(Number(zoom.anchorX)) ? Number(zoom.anchorX) : cropBoxWidth / 2,
    0,
    cropBoxWidth
  )
  const anchorY = clampNumber(
    Number.isFinite(Number(zoom.anchorY)) ? Number(zoom.anchorY) : cropBoxHeight / 2,
    0,
    cropBoxHeight
  )
  const anchoredImageX = (anchorX - currentOffsetX) / currentScale
  const anchoredImageY = (anchorY - currentOffsetY) / currentScale
  const nextOffsetX = clampNumber(anchorX - anchoredImageX * nextScale, minOffsetX, maxOffsetX)
  const nextOffsetY = clampNumber(anchorY - anchoredImageY * nextScale, minOffsetY, maxOffsetY)

  return withWorkThumbnailCropStyles(Object.assign({}, state, {
    displayWidth: roundNumber(displayWidth),
    displayHeight: roundNumber(displayHeight),
    offsetX: roundNumber(nextOffsetX),
    offsetY: roundNumber(nextOffsetY),
    minOffsetX: roundNumber(minOffsetX),
    maxOffsetX: roundNumber(maxOffsetX),
    minOffsetY: roundNumber(minOffsetY),
    maxOffsetY: roundNumber(maxOffsetY),
    scale: nextScale
  }))
}

function buildWorkThumbnailCropFrame(state = {}, options = {}) {
  const scale = toPositiveNumber(state.scale)
  const imageWidth = toPositiveNumber(state.imageWidth)
  const imageHeight = toPositiveNumber(state.imageHeight)
  const cropBoxWidth = toPositiveNumber(state.cropBoxWidth)
  const cropBoxHeight = toPositiveNumber(state.cropBoxHeight)
  const ratioWidth = toPositiveNumber(state.ratioWidth) || 1
  const ratioHeight = toPositiveNumber(state.ratioHeight) || 1
  if (!scale || !imageWidth || !imageHeight || !cropBoxWidth || !cropBoxHeight) {
    throw new Error('裁剪参数无效')
  }

  const sourceWidth = Math.min(imageWidth, Math.round(cropBoxWidth / scale))
  const sourceHeight = Math.min(imageHeight, Math.round(cropBoxHeight / scale))
  const sx = clampNumber(Math.round(-Number(state.offsetX || 0) / scale), 0, Math.max(0, imageWidth - sourceWidth))
  const sy = clampNumber(Math.round(-Number(state.offsetY || 0) / scale), 0, Math.max(0, imageHeight - sourceHeight))
  const maxSide = Math.round(toPositiveNumber(options.maxSide) || WORK_THUMBNAIL_MAX_SIDE)
  const isLandscape = ratioWidth >= ratioHeight
  const destWidth = isLandscape ? maxSide : Math.round(maxSide * ratioWidth / ratioHeight)
  const destHeight = isLandscape ? Math.round(maxSide * ratioHeight / ratioWidth) : maxSide

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
  const runtimeWx = getRuntimeWx(wxApi)
  const fileSystemManager = runtimeWx.getFileSystemManager()
  const stats = fileSystemManager.statSync(filePath)
  return stats && typeof stats.size === 'number' ? stats.size : 0
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
      compressedWidth: attempt.maxSide,
      success(response) {
        if (response && response.tempFilePath) {
          resolve(response.tempFilePath)
          return
        }
        reject(new Error('缩略图裁剪失败'))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '缩略图裁剪失败'))
      }
    })
  })
}

async function prepareWorkThumbnailUploadFile(filePath, options = {}) {
  const normalizedPath = trimText(filePath)
  if (!normalizedPath) {
    throw new Error('缩略图不能为空')
  }
  const originalSize = getLocalFileSize(normalizedPath, options.wxApi)
  if (originalSize > 0 && originalSize <= THUMB_MAX_BYTES) {
    return {
      filePath: normalizedPath,
      fileSize: originalSize,
      mimeType: DEFAULT_THUMBNAIL_MIME_TYPE
    }
  }
  for (const attempt of WORK_THUMBNAIL_COMPRESS_ATTEMPTS) {
    const tempFilePath = await compressImageFile(normalizedPath, attempt, options)
    const fileSize = getLocalFileSize(tempFilePath, options.wxApi)
    if (fileSize > 0 && fileSize <= THUMB_MAX_BYTES) {
      return {
        filePath: tempFilePath,
        fileSize,
        mimeType: DEFAULT_THUMBNAIL_MIME_TYPE
      }
    }
  }
  throw new Error(WORK_THUMBNAIL_TOO_LARGE_MESSAGE)
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
          reject(new Error('当前微信版本不支持图片裁剪'))
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
    image.onerror = () => reject(new Error('缩略图裁剪失败'))
    image.src = imagePath
  })
}

async function cropWorkThumbnailToTempFilePath(options = {}) {
  const runtimeWx = getRuntimeWx(options.wxApi)
  const canvas = await getCanvasNode(options.page, options.canvasId, runtimeWx)
  const cropFrame = options.cropFrame || {}
  const destWidth = Math.round(toPositiveNumber(cropFrame.destWidth) || WORK_THUMBNAIL_MAX_SIDE)
  const destHeight = Math.round(toPositiveNumber(cropFrame.destHeight) || WORK_THUMBNAIL_MAX_SIDE)
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
      fileType: options.fileType || WORK_THUMBNAIL_FILE_TYPE,
      quality: options.quality || WORK_THUMBNAIL_CROP_QUALITY,
      success(response) {
        if (response && response.tempFilePath) {
          resolve(response.tempFilePath)
          return
        }
        reject(new Error('缩略图裁剪失败'))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '缩略图裁剪失败'))
      }
    })
  })
}

module.exports = {
  DEFAULT_CROP_BOX_WIDTH,
  THUMB_MAX_BYTES,
  WORK_THUMBNAIL_COMPRESS_ATTEMPTS,
  WORK_THUMBNAIL_CROP_QUALITY,
  WORK_THUMBNAIL_FILE_TYPE,
  WORK_THUMBNAIL_MAX_SIDE,
  WORK_THUMBNAIL_MAX_ZOOM_RATIO,
  WORK_THUMBNAIL_RATIO_OPTIONS,
  buildWorkThumbnailCropFrame,
  buildWorkThumbnailCropState,
  buildWorkThumbnailRatioOptions,
  cropWorkThumbnailToTempFilePath,
  getWorkThumbnailImageInfo,
  moveWorkThumbnailCropState,
  prepareWorkThumbnailUploadFile,
  zoomWorkThumbnailCropState
}
