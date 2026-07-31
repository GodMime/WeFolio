const COMPONENT_MEDIA_TYPES = Object.freeze({
  SINGLE_WORK: Object.freeze(['IMAGE', 'VIDEO', 'ANIMATION']),
  WORK_GRID: Object.freeze(['IMAGE', 'VIDEO']),
  WORK_LIST: Object.freeze(['IMAGE', 'VIDEO']),
  CAROUSEL: Object.freeze(['IMAGE'])
})

/**
 * 按作品集组件类型过滤可选作品，未知组件或媒体类型一律不纳入。
 *
 * @param {string} componentType 组件类型
 * @param {Array<object>} works 作品列表
 * @returns {Array<object>} 可供该组件选择的作品
 */
function selectableWorksFor(componentType, works = []) {
  const allowedMediaTypes = COMPONENT_MEDIA_TYPES[String(componentType || '').trim()]
  if (!allowedMediaTypes) {
    return []
  }
  return (Array.isArray(works) ? works : []).filter((work) => {
    return allowedMediaTypes.includes(String(work && work.mediaType || '').trim())
  })
}

module.exports = {
  selectableWorksFor
}
