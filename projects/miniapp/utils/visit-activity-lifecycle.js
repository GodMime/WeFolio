const VISIT_ACTIVITY_STORAGE_KEY = 'wefolio_visit_activity_v1'

// App 与访客页面共用前后台状态；同一时刻只允许一个页面持有计时权。
function createVisitActivityLifecycle() {
  let foreground = true
  let identityRevision = 0
  let active = null
  let owner = null
  const contexts = new Set()
  return {
    isForeground: () => foreground,
    getIdentityRevision: () => identityRevision,
    register(context) { contexts.add(context) },
    unregister(context) {
      contexts.delete(context)
      if (active === context) { active = null; owner = null }
    },
    claim(context, page) {
      if (active === context && owner === page) return
      if (active) active.setVisible(false)
      active = context
      owner = page
      context.setVisible(foreground)
    },
    release(context, page) {
      if (active !== context || owner !== page) return
      context.setVisible(false)
      active = null
      owner = null
    },
    onShow() {
      foreground = true
      if (active) active.setVisible(true)
    },
    onHide() {
      foreground = false
      if (active) active.setVisible(false)
    },
    findContext(type, shareCode) {
      if (active && active.portfolioType === type && active.shareCode === shareCode) return active
      return Array.from(contexts).reverse().find((context) => context.portfolioType === type && context.shareCode === shareCode && !context.isInvalid()) || null
    },
    invalidateAll(wxApi) {
      identityRevision += 1
      Array.from(contexts).forEach((context) => context.invalidate())
      active = null
      owner = null
      const runtimeWx = wxApi || (typeof wx !== 'undefined' ? wx : null)
      try { if (runtimeWx && runtimeWx.removeStorageSync) runtimeWx.removeStorageSync(VISIT_ACTIVITY_STORAGE_KEY) } catch (error) { /* 无本地存储时仅清理内存上下文。 */ }
    }
  }
}

const visitActivityLifecycle = createVisitActivityLifecycle()

module.exports = { VISIT_ACTIVITY_STORAGE_KEY, createVisitActivityLifecycle, visitActivityLifecycle }
