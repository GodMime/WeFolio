const DISPLAY_SWITCH_ANIMATION_MS = 180

function clearDisplaySwitchingTimer(page) {
  if (!page.displaySwitchingTimer) {
    return
  }
  clearTimeout(page.displaySwitchingTimer)
  page.displaySwitchingTimer = null
}

function markDisplaySwitching(page, componentKey) {
  clearDisplaySwitchingTimer(page)
  page.setData({ displaySwitchingComponentKey: '' }, () => {
    page.setData({ displaySwitchingComponentKey: componentKey })
    page.displaySwitchingTimer = setTimeout(() => {
      page.displaySwitchingTimer = null
      if (page.data.displaySwitchingComponentKey === componentKey) {
        page.setData({ displaySwitchingComponentKey: '' })
      }
    }, DISPLAY_SWITCH_ANIMATION_MS)
  })
}

module.exports = {
  DISPLAY_SWITCH_ANIMATION_MS,
  clearDisplaySwitchingTimer,
  markDisplaySwitching
}
