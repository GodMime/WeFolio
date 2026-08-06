const DISPLAY_GROUP_WORK_ITEM_HEIGHT_RPX = 96
const DISPLAY_GROUP_WORK_ITEM_GAP_RPX = 12
const DISPLAY_GROUP_WORK_SCROLL_MAX_HEIGHT_RPX = 360

function buildDisplayGroupWorkScrollHeight(works = []) {
  const count = Array.isArray(works)
    ? works.length
    : Math.max(0, Math.round(Number(works) || 0))
  if (!count) {
    return 0
  }
  return Math.min(
    DISPLAY_GROUP_WORK_SCROLL_MAX_HEIGHT_RPX,
    count * DISPLAY_GROUP_WORK_ITEM_HEIGHT_RPX + (count - 1) * DISPLAY_GROUP_WORK_ITEM_GAP_RPX
  )
}

module.exports = {
  buildDisplayGroupWorkScrollHeight
}
