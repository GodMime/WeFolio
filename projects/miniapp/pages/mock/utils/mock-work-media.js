/** 各组件允许的静态媒体类型；未知上下文不开放素材。 */
const MOCK_MEDIA_CONTEXTS = Object.freeze({
  CAROUSEL: ['IMAGE'], VIDEO_CAROUSEL: ['VIDEO'],
  WORK_GRID: ['IMAGE', 'VIDEO'], WORK_LIST: ['IMAGE', 'VIDEO'],
  SINGLE_WORK: ['IMAGE', 'VIDEO', 'ANIMATION'],
  HYPERLINK: ['IMAGE', 'ANIMATION'], TEXT_BACKGROUND: ['IMAGE', 'VIDEO', 'ANIMATION'],
  BACKGROUND_AUDIO: ['AUDIO'], QR_CONTACT: ['IMAGE']
})
function selectMockWorksFor(context, works = []) {
  const allowed = MOCK_MEDIA_CONTEXTS[context] || []
  return works.filter(work => work && allowed.includes(work.mediaType))
}
module.exports = { MOCK_MEDIA_CONTEXTS, selectMockWorksFor }
