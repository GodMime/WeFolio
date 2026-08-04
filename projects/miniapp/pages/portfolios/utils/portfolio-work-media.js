const { selectableWorksFor: selectableSharedWorksFor } = require('./work-media')

const HYPERLINK_MEDIA_TYPES = Object.freeze(['IMAGE', 'ANIMATION'])

/**
 * 按个人作品集组件类型过滤可选作品。
 * 超链接是个人作品集专属组件，其媒体规则不下沉到其它业务分包副本。
 *
 * @param {string} componentType 个人作品集组件类型
 * @param {Array<object>} works 作品列表
 * @returns {Array<object>} 可供组件选择的作品
 */
function selectableWorksFor(componentType, works = []) {
  const normalizedType = String(componentType || '').trim()
  if (normalizedType !== 'HYPERLINK') {
    return selectableSharedWorksFor(normalizedType, works)
  }
  return (Array.isArray(works) ? works : []).filter((work) => {
    return HYPERLINK_MEDIA_TYPES.includes(String(work && work.mediaType || '').trim())
  })
}

module.exports = {
  selectableWorksFor
}
