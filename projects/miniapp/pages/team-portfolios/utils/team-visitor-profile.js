const { request } = require('../../../utils/request.js')
const { isRemoteUrl, prepareLocalUploadFilePath } = require('../../../utils/upload-file.js')

const VISITOR_AVATAR_MAX_SIZE_BYTES = 200 * 1024
const MIME_IMAGE_JPEG = 'image/jpeg'
const MIME_IMAGE_PNG = 'image/png'
const MIME_IMAGE_WEBP = 'image/webp'

function text(value) {
  return String(value || '').trim()
}

function getRuntimeWx(wxApi) {
  const runtimeWx = wxApi || (typeof wx !== 'undefined' ? wx : null)
  if (!runtimeWx) throw new Error('wx 运行环境不可用')
  return runtimeWx
}

function getLocalFileSize(filePath, wxApi) {
  const stats = getRuntimeWx(wxApi).getFileSystemManager().statSync(filePath)
  const size = stats && Number(stats.size)
  if (!Number.isFinite(size) || size <= 0) throw new Error('无法读取访客头像文件大小')
  return size
}

function normalizeImageMimeType(value) {
  const normalized = text(value).toLowerCase()
  if (normalized === 'jpg' || normalized === 'jpeg' || normalized === 'image/jpg' || normalized === MIME_IMAGE_JPEG) return MIME_IMAGE_JPEG
  if (normalized === 'png' || normalized === MIME_IMAGE_PNG) return MIME_IMAGE_PNG
  if (normalized === 'webp' || normalized === MIME_IMAGE_WEBP) return MIME_IMAGE_WEBP
  return ''
}

function mimeTypeFromPath(filePath) {
  if (/\.png($|\?)/i.test(filePath)) return MIME_IMAGE_PNG
  if (/\.webp($|\?)/i.test(filePath)) return MIME_IMAGE_WEBP
  if (/\.jpe?g($|\?)/i.test(filePath)) return MIME_IMAGE_JPEG
  return ''
}

function getImageInfo(filePath, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  if (!runtimeWx.getImageInfo) return Promise.resolve({})
  return new Promise((resolve, reject) => runtimeWx.getImageInfo({
    src: filePath,
    success: resolve,
    fail(error) { reject(new Error(error && error.errMsg ? error.errMsg : '无法读取访客头像格式')) }
  }))
}

async function resolveFinalImageMimeType(filePath, wxApi) {
  const pathMimeType = mimeTypeFromPath(filePath)
  if (pathMimeType) return pathMimeType
  const imageInfo = await getImageInfo(filePath, wxApi)
  const imageMimeType = normalizeImageMimeType(imageInfo.type || imageInfo.mimeType)
  if (!imageMimeType) throw new Error('访客头像格式不支持')
  return imageMimeType
}

function uploadByTicket(options, uploadFn) {
  if (uploadFn) return uploadFn(options)
  const runtimeWx = typeof wx !== 'undefined' ? wx : null
  if (!runtimeWx || !runtimeWx.uploadFile) return Promise.reject(new Error('wx 上传环境不可用'))
  return new Promise((resolve, reject) => runtimeWx.uploadFile(Object.assign({}, options, {
    success(response) { response.statusCode >= 200 && response.statusCode < 300 ? resolve(response) : reject(new Error('访客头像上传失败')) },
    fail(error) { reject(new Error(error && error.errMsg ? error.errMsg : '访客头像上传失败')) }
  })))
}

async function uploadTeamVisitorProfile(options = {}) {
  const shareCode = text(options.shareCode)
  if (!shareCode) throw new Error('分享码无效')
  const prefix = `/api/visitor/team-portfolios/${encodeURIComponent(shareCode)}`
  let avatarUrl = text(options.avatarPath)
  if (avatarUrl && !isRemoteUrl(avatarUrl)) {
    const preparedPath = await prepareLocalUploadFilePath(avatarUrl, {
      wxApi: options.wxApi,
      maxSizeBytes: VISITOR_AVATAR_MAX_SIZE_BYTES,
      compressQuality: 90,
      compressedSize: 512,
      messages: { tooLarge: '访客头像不能超过 200KB' }
    })
    const finalFileSize = getLocalFileSize(preparedPath, options.wxApi)
    if (finalFileSize > VISITOR_AVATAR_MAX_SIZE_BYTES) throw new Error('访客头像不能超过 200KB')
    const finalMimeType = await resolveFinalImageMimeType(preparedPath, options.wxApi)
    const ticket = await (options.requestFn || request)({
      url: `${prefix}/visitor-avatar/upload-ticket`,
      method: 'POST',
      authMode: 'visitor',
      data: {
        visitorProfileToken: text(options.visitorProfileToken),
        mimeType: finalMimeType,
        fileSize: finalFileSize
      }
    })
    if (!ticket || !ticket.uploadUrl || !ticket.publicUrl) throw new Error('访客头像上传票据无效')
    await uploadByTicket({ url: ticket.uploadUrl, filePath: preparedPath, name: 'file', formData: ticket.formData || {} }, options.uploadFn)
    avatarUrl = ticket.publicUrl
  }
  const profile = { nickname: text(options.nickname), avatarUrl }
  await (options.requestFn || request)({
    url: `${prefix}/visitor-profile`,
    method: 'PUT',
    authMode: 'visitor',
    data: Object.assign({ visitorProfileToken: text(options.visitorProfileToken) }, profile)
  })
  return profile
}

module.exports = {
  VISITOR_AVATAR_MAX_SIZE_BYTES,
  uploadTeamVisitorProfile
}
