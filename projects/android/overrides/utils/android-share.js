const config = require('./android-config')
const DEFAULT_SHARE_IMAGE = 'https://cdn2.we-folio.dingchenyong.top/system/folio-logo.png'

/** OpenSDK 只接受本地缩略图，因此先下载远程封面，不修改原分享路径与归因参数。 */
function localThumbnail(url, wxApi) {
  if (!/^https:\/\//.test(url)) return Promise.resolve(url)
  return new Promise((resolve, reject) => wxApi.downloadFile({
    url,
    success(result) {
      if (result.statusCode === 200 && result.tempFilePath) resolve(result.tempFilePath)
      else reject(new Error('分享封面下载失败，请重试'))
    },
    fail() { reject(new Error('分享封面下载失败，请重试')) }
  }))
}

/** 用户主动点击后调用微信分享；配置缺失时不会先触发原页面的分享计数。 */
async function sharePage(page, scene = 0, wxApi = wx) {
  if (!config.mobileAppId || !config.miniProgramOriginalId || !config.shareFallbackUrl) {
    throw new Error('微信分享尚未开通，可使用作品集分享码')
  }
  if (!wxApi.miniapp || typeof wxApi.miniapp.shareMiniProgramMessage !== 'function') {
    throw new Error('请在安装后的安卓 App 中使用微信分享')
  }
  const handler = scene === 1 ? page.onShareTimeline : page.onShareAppMessage
  if (typeof handler !== 'function') throw new Error('当前页面没有可分享的作品集')
  let payload = await handler.call(page, { from: 'button' })
  if (payload && payload.promise) payload = { ...payload, ...await payload.promise }
  if (!payload) throw new Error('作品集暂不可分享')
  const sharePath = scene === 1 ? `${page.route}?${payload.query || ''}` : payload.path
  if (!sharePath) throw new Error('作品集分享路径缺失')
  const imagePath = await localThumbnail(payload.imageUrl || DEFAULT_SHARE_IMAGE, wxApi)
  return new Promise((resolve, reject) => wxApi.miniapp.shareMiniProgramMessage({
    userName: config.miniProgramOriginalId,
    path: sharePath.replace(/^\//, ''),
    title: payload.title || '映期Folio',
    imagePath,
    webpageUrl: config.shareFallbackUrl,
    withShareTicket: true,
    miniprogramType: 0,
    scene,
    success: resolve,
    fail(error) { reject(new Error(/cancel/i.test(error.errMsg || '') ? '已取消分享' : '微信分享失败，请重试')) }
  }))
}

module.exports = { localThumbnail, sharePage }
