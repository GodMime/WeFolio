const { request } = require('../../../utils/request.js')
const { isRemoteUrl } = require('../../../utils/upload-file.js')
const { normalizeId } = require('../../../utils/id.js')

const IMAGE_MAX_SIZE_BYTES = 300 * 1024
const MIME_IMAGE_JPEG = 'image/jpeg'
const MIME_IMAGE_PNG = 'image/png'
const IMAGE_COMPRESS_ATTEMPTS = Object.freeze([
  Object.freeze({ quality: 85, compressedSize: 1080 }),
  Object.freeze({ quality: 78, compressedSize: 900 }),
  Object.freeze({ quality: 70, compressedSize: 720 }),
  Object.freeze({ quality: 62, compressedSize: 560 }),
  Object.freeze({ quality: 54, compressedSize: 420 })
])

function getRuntimeWx(wxApi) {
  const runtimeWx = wxApi || (typeof wx !== 'undefined' ? wx : null)
  if (!runtimeWx) throw new Error('wx 运行环境不可用')
  return runtimeWx
}

function getLocalFileSize(filePath, wxApi) {
  try {
    const stats = getRuntimeWx(wxApi).getFileSystemManager().statSync(filePath)
    const size = stats && Number(stats.size)
    return Number.isFinite(size) && size > 0 ? size : 0
  } catch (error) {
    return 0
  }
}

function compressTeamPortfolioImage(filePath, attempt, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  if (!runtimeWx.compressImage) return Promise.reject(new Error('当前微信版本不支持图片压缩'))
  return new Promise((resolve, reject) => runtimeWx.compressImage({
    src: filePath,
    quality: attempt.quality,
    compressedWidth: attempt.compressedSize,
    success(response) {
      if (response && response.tempFilePath) return resolve(response.tempFilePath)
      reject(new Error('团队素材压缩失败'))
    },
    fail(error) { reject(new Error(error && error.errMsg ? error.errMsg : '团队素材压缩失败')) }
  }))
}

async function prepareTeamPortfolioImageFilePath(filePath, wxApi) {
  const originalSize = getLocalFileSize(filePath, wxApi)
  if (originalSize > 0 && originalSize <= IMAGE_MAX_SIZE_BYTES) return filePath
  for (const attempt of IMAGE_COMPRESS_ATTEMPTS) {
    const compressedPath = await compressTeamPortfolioImage(filePath, attempt, wxApi)
    const compressedSize = getLocalFileSize(compressedPath, wxApi)
    if (compressedSize > 0 && compressedSize <= IMAGE_MAX_SIZE_BYTES) return compressedPath
  }
  throw new Error('团队素材不能超过 300KB')
}

function normalizeImageMimeType(value) {
  const normalized = String(value || '').trim().toLowerCase()
  if (normalized === 'jpg' || normalized === 'jpeg' || normalized === 'image/jpg' || normalized === MIME_IMAGE_JPEG) return MIME_IMAGE_JPEG
  if (normalized === 'png' || normalized === MIME_IMAGE_PNG) return MIME_IMAGE_PNG
  return ''
}

function mimeTypeFromPath(filePath) {
  if (/\.png($|\?)/i.test(filePath)) return MIME_IMAGE_PNG
  if (/\.jpe?g($|\?)/i.test(filePath)) return MIME_IMAGE_JPEG
  return ''
}

function getImageInfo(filePath, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  if (!runtimeWx.getImageInfo) return Promise.resolve({})
  return new Promise((resolve, reject) => runtimeWx.getImageInfo({
    src: filePath,
    success: resolve,
    fail(error) { reject(new Error(error && error.errMsg ? error.errMsg : '无法读取团队素材格式')) }
  }))
}

async function resolveFinalImageMimeType(filePath, wxApi) {
  const pathMimeType = mimeTypeFromPath(filePath)
  if (pathMimeType) return pathMimeType
  const imageInfo = await getImageInfo(filePath, wxApi)
  const imageMimeType = normalizeImageMimeType(imageInfo.type || imageInfo.mimeType)
  if (!imageMimeType) throw new Error('团队素材仅支持 JPG、JPEG 或 PNG')
  return imageMimeType
}

function defaultUpload(options, wxApi) {
  const runtimeWx = wxApi || (typeof wx !== 'undefined' ? wx : null)
  if (!runtimeWx || !runtimeWx.uploadFile) return Promise.reject(new Error('wx 上传环境不可用'))
  return new Promise((resolve, reject) => {
    runtimeWx.uploadFile(Object.assign({}, options, {
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) resolve(response)
        else reject(new Error('团队素材上传失败'))
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : '团队素材上传失败'))
      }
    }))
  })
}

function createTeamAssetUploadTicket(requestFn = request, portfolioId, payload) {
  const id = normalizeId(portfolioId)
  if (!id) return Promise.reject(new Error('作品集 ID 无效'))
  return requestFn({
    url: `/api/mine/team-portfolios/${id}/asset/upload-ticket`,
    method: 'POST',
    data: payload
  })
}

async function uploadTeamPortfolioAsset(options = {}) {
  if (isRemoteUrl(options.filePath)) return options.filePath
  const preparedPath = await prepareTeamPortfolioImageFilePath(options.filePath, options.wxApi)
  const finalFileSize = getLocalFileSize(preparedPath, options.wxApi)
  if (!finalFileSize) throw new Error('无法读取团队素材文件大小')
  if (finalFileSize > IMAGE_MAX_SIZE_BYTES) throw new Error('团队素材不能超过 300KB')
  const finalMimeType = await resolveFinalImageMimeType(preparedPath, options.wxApi)
  const payload = {
    clientId: String(options.clientId || '').trim(),
    assetType: String(options.assetType || '').trim(),
    mimeType: finalMimeType,
    fileSize: finalFileSize
  }
  const ticket = await createTeamAssetUploadTicket(options.requestFn || request, options.portfolioId, payload)
  if (!ticket || !ticket.uploadUrl || !ticket.publicUrl) throw new Error('团队素材上传票据无效')
  const uploadOptions = {
    url: ticket.uploadUrl,
    filePath: preparedPath,
    name: 'file',
    formData: ticket.formData || {}
  }
  await (options.uploadFn || ((value) => defaultUpload(value, options.wxApi)))(uploadOptions)
  return ticket.publicUrl
}

module.exports = {
  IMAGE_MAX_SIZE_BYTES,
  createTeamAssetUploadTicket,
  uploadTeamPortfolioAsset
}
