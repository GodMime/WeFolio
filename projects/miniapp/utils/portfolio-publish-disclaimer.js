const PUBLISH_DISCLAIMER_TITLE = '发布免责声明'
const PUBLISH_DISCLAIMER_CONTENT = '请确认本作品集使用的肖像、图片、视频、文字、音乐及其他素材均已取得合法授权。因素材侵权、肖像权争议或其他合规问题产生的责任，将由发布者自行承担。'
const PUBLISH_DISCLAIMER_CONFIRM_TEXT = '确认发布'
const PUBLISH_DISCLAIMER_CANCEL_TEXT = '取消'
const PUBLISH_DISCLAIMER_CONFIRM_COLOR = '#17202a'

/**
 * 每次发布前确认素材授权与侵权责任提示。
 *
 * @param {object} wxApi 微信 API，用于测试注入。
 * @returns {Promise<boolean>} 用户同意时返回 true。
 */
function confirmPortfolioPublishDisclaimer(wxApi) {
  return new Promise((resolve) => {
    const modalApi = wxApi || (typeof wx !== 'undefined' ? wx : null)
    if (!modalApi || typeof modalApi.showModal !== 'function') {
      resolve(false)
      return
    }
    modalApi.showModal({
      title: PUBLISH_DISCLAIMER_TITLE,
      content: PUBLISH_DISCLAIMER_CONTENT,
      confirmText: PUBLISH_DISCLAIMER_CONFIRM_TEXT,
      cancelText: PUBLISH_DISCLAIMER_CANCEL_TEXT,
      confirmColor: PUBLISH_DISCLAIMER_CONFIRM_COLOR,
      success(result = {}) {
        resolve(Boolean(result.confirm))
      },
      fail() {
        resolve(false)
      }
    })
  })
}

module.exports = {
  PUBLISH_DISCLAIMER_CONTENT,
  PUBLISH_DISCLAIMER_TITLE,
  confirmPortfolioPublishDisclaimer
}
