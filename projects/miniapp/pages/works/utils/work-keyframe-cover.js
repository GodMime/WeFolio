const { isRemoteUrl } = require('../../../utils/upload-file')
const PREPARE_FAILED = '封面准备失败，请重试'
const FILE_EXPIRED = '封面文件已失效，请重新选择'
const LINK_EXPIRED = '图片链接已失效，请重新加载后选择'
const IMAGE_FORMATS = {
  jpeg: ['jpg', 'image/jpeg'], jpg: ['jpg', 'image/jpeg'], png: ['png', 'image/png'],
  webp: ['webp', 'image/webp'], gif: ['gif', 'image/gif'], bmp: ['bmp', 'image/bmp']
}

function isLocalFile(filePath) {
  return typeof filePath === 'string' && Boolean(filePath.trim()) && !isRemoteUrl(filePath.trim().toLowerCase())
}

function isExpiredDownload(response, wxApi) {
  if (response.statusCode !== 403 || !isLocalFile(response.tempFilePath)) return false
  try {
    const fs = wxApi.getFileSystemManager()
    if (fs.statSync(response.tempFilePath).size > 16384) return false
    const body = fs.readFileSync(response.tempFilePath, 'utf8')
    return /<Code>(?:RequestExpired|SignatureExpired)<\/Code>|<Message>Request has expired\.?<\/Message>/i.test(body)
  } catch (_) { return false }
}

function hasUsableKeyframes(content = {}, loading = false, error = '') {
  return !loading && !error && (content.frames || []).some(frame => frame.url && !frame.loadFailed)
}

// 只登记本次下载或压缩的文件；不接触远端对象与相册来源文件。
function createCoverFiles(wxApi) {
  const owned = new Set()
  let retained = new Set()
  let disposed = false
  function release(filePath) {
    if (retained.has(filePath)) return
    if (!owned.delete(filePath)) return
    try {
      wxApi.getFileSystemManager().unlink({ filePath, fail() {} })
    } catch (_) { /* 临时文件清理失败不覆盖保存或取消结果。 */ }
  }
  return {
    track(filePath) {
      if (!isLocalFile(filePath)) return
      owned.add(filePath)
      if (disposed) release(filePath)
    },
    release,
    releaseExcept(paths = []) {
      retained = new Set(paths)
      owned.forEach(filePath => { if (!retained.has(filePath)) release(filePath) })
    },
    dispose() {
      disposed = true
      retained.clear()
      owned.forEach(release)
    }
  }
}

async function readCoverImage(filePath, wxApi) {
  let fileSize
  try {
    if (!isLocalFile(filePath)) throw new Error(FILE_EXPIRED)
    fileSize = wxApi.getFileSystemManager().statSync(filePath).size
    if (!(fileSize > 0)) throw new Error(FILE_EXPIRED)
  } catch (_) { throw new Error(FILE_EXPIRED) }
  const info = await new Promise((resolve, reject) => {
    wxApi.getImageInfo({ src: filePath, success: resolve, fail: () => reject(new Error(FILE_EXPIRED)) })
  })
  const format = IMAGE_FORMATS[String(info.type || '').toLowerCase()]
  if (!format || !(info.width > 0 && info.height > 0)) throw new Error(FILE_EXPIRED)
  return { filePath, fileSize, width: info.width, height: info.height, fileName: `cover.${format[0]}`, mimeType: format[1] }
}

// 取消既中止下载，也让已进入图片解码阶段的回调失效。
function downloadKeyframeCover(url, { wxApi, files }) {
  let task
  let cancelled = false
  let localPath = ''
  let rejectPromise
  const promise = new Promise((resolve, reject) => {
    rejectPromise = reject
    task = wxApi.downloadFile({
      url,
      timeout: 60000,
      async success(response) {
        localPath = response.tempFilePath || ''
        files.track(localPath)
        if (cancelled) { files.release(localPath); return }
        try {
          if (response.statusCode !== 200) {
            const expired = isExpiredDownload(response, wxApi)
            const error = new Error(expired ? LINK_EXPIRED : PREPARE_FAILED)
            error.linkExpired = expired
            throw error
          }
          const image = await readCoverImage(localPath, wxApi)
          if (cancelled) { files.release(localPath); return }
          resolve(image)
        } catch (error) {
          files.release(localPath)
          if (!cancelled) reject(error.linkExpired ? error : new Error(PREPARE_FAILED))
        }
      },
      fail() { if (!cancelled) reject(new Error(PREPARE_FAILED)) }
    })
  })
  return {
    promise,
    cancel() {
      cancelled = true
      rejectPromise(Object.assign(new Error('已取消封面准备'), { cancelled: true }))
      if (task && task.abort) task.abort()
      files.release(localPath)
    }
  }
}

module.exports = { createCoverFiles, downloadKeyframeCover, readCoverImage, hasUsableKeyframes }
