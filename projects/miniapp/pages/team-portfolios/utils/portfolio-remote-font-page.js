const { createRemoteFontSession, collectFontNodes } = require('./portfolio-remote-font-session')
const { createPortfolioOpening } = require('./portfolio-opening')
const { request } = require('../../../utils/request.js')
const { PORTFOLIO_TEXT_SELECTION_OPTIONS, buildPortfolioTextTypography, LEGACY_PERSONAL_FONT_SIZE_RPX, LEGACY_TEAM_FONT_SIZE_RPX } = require('../../../utils/portfolio-text-typography.js')

/** 页面统一处理目录、版本与资源；仅显式操作调用 prepare，失败不重发。 */
function installRemoteFontPage(page, { editor = false, team = false } = {}) {
  const originalSetData = page.setData.bind(page)
  let disposed = false
  let fontFallback = false
  let openingTicket = 0
  const opening = editor ? null : createPortfolioOpening(page, { setData: originalSetData, onTimeout() { fontFallback = true; session.dispose() } })
  let catalog = null
  let catalogPromise = null
  let lastSignature = ''
  let editingDraft = null
  let edit = null
  let editSequence = 0
  let catalogMessage = ''
  const clone = value => JSON.parse(JSON.stringify(value))
  // 会话只持有候选根表；确认入口才把版本与组件一起提交。
  function syncEdit() {
    if (!editor) return
    const data = page.data
    const descriptor = data.textSectionSheetVisible
      ? ['TEXT_SECTION', data.textSectionEditingComponentKey, data.activeMenuKey]
      : data.structuredTextSheetVisible
        ? ['STRUCTURED_TEXT_SECTION', data.structuredTextEditingComponentKey, page.structuredTextMenuKey || data.structuredTextEditingMenuKey || data.activeMenuKey]
        : data.newComponentSheetVisible && data.newComponentType === 'TEXT_GRID'
          ? ['TEXT_GRID', data.newComponentKey, page.newComponentMenuKey || data.activeMenuKey] : null
    const identity = descriptor && JSON.stringify(descriptor)
    if (identity === (edit && edit.identity)) return
    if (edit && !edit.committed && edit.prepared) originalSetData({ fontAssets: edit.assets || null })
    editingDraft = null
    edit = descriptor ? { identity, id: ++editSequence, type: descriptor[0], key: descriptor[1], menuKey: descriptor[2],
      assets: data.fontAssets, fonts: clone((data.config || {}).fonts || {}), history: new Map() } : null
    originalSetData({ fontEditSessionId: edit ? edit.id : 0, moreFontsExpanded: false })
  }
  function candidateConfig() {
    syncEdit()
    const config = clone(page.data.config || {})
    if (!edit) return config
    const value = edit.type === 'TEXT_SECTION' ? page.data.textSectionForm
      : editingDraft ? editingDraft.config : edit.type === 'TEXT_GRID' ? page.data.newComponentConfig : page.data.structuredTextConfig
    const items = (config.bottomNav || {}).items || []
    const index = items.findIndex(item => item.key === edit.menuKey)
    if (edit.menuKey && index < 0) return config
    const components = index > 0 ? items[index].components || [] : config.components || []
    const target = edit.key ? components.findIndex(item => item.componentKey === edit.key) : -1
    if (edit.key && target < 0) return config
    const candidate = { ...(target >= 0 ? components[target] : {}), componentKey: edit.key || `font_candidate_${edit.id}`,
      componentType: edit.type, enabled: target >= 0 ? components[target].enabled !== false : true,
      sortOrder: target >= 0 ? components[target].sortOrder : (components.length + 1) * 1000, config: clone(value || {}) }
    if (target >= 0) components[target] = candidate
    else components.push(candidate)
    if (index > 0) items[index].components = components
    else config.components = components
    config.fonts = clone(edit.fonts)
    return config
  }
  function commit(config) {
    syncEdit()
    if (!edit) return config
    edit.committed = true
    const used = new Set(allNodes(config).map(node => node.fontId).filter(Boolean))
    const fonts = { ...((config || {}).fonts || {}) }
    Object.keys(edit.fonts).forEach(id => { if (used.has(id)) fonts[id] = clone(edit.fonts[id]) })
    return { ...config, fonts }
  }
  function publishContext(context) {
    if (disposed) return
    const patch = { fontContext: context }
    if (context.failed && context.failed.length) patch.fontMessage = '字体加载失败，当前使用系统字体'
    if (editor && page.data.textSectionForm) patch.textSectionTypography = buildPortfolioTextTypography(
      page.data.textSectionForm, team ? LEGACY_TEAM_FONT_SIZE_RPX : LEGACY_PERSONAL_FONT_SIZE_RPX, context)
    originalSetData(patch)
  }
  function allNodes(config) {
    const components = [...(config.components || [])]
    ;((config.bottomNav || {}).items || []).slice(1).forEach(item => components.push(...(item.components || [])))
    return collectFontNodes(components)
  }
  function activeComponents(config) {
    const items = (config.bottomNav || {}).items || []
    const index = items.findIndex(item => item.key === page.data.activeMenuKey)
    return index > 0 ? items[index].components || [] : config.components || []
  }
  const session = createRemoteFontSession(typeof wx === 'undefined' ? {} : wx, context => { if (editor) publishContext(context) })
  function refresh() {
    if (disposed) return
    const data = page.data
    const view = data.portfolio || {}
    if (!editor && view.underMaintenance) {
      // 维护态立即撤销排队需求，失效在途回调；恢复访问后仍可正常使用已有字体。
      lastSignature = ''
      session.load(null, [])
      if (page.browserContext) page.browserContext.setDisplayable(false)
      if (page.backgroundAudioPlayer && page.syncBackgroundAudio) page.syncBackgroundAudio(null, false)
      return
    }
    const config = editor ? candidateConfig() : view
    const manifest = editor ? data.fontAssets : view.fonts
    const components = editor ? activeComponents(config) : config.activeComponents || config.components || []
    const nodes = collectFontNodes(components)
    if (editor) originalSetData({ hasRemoteFonts: allNodes(config).some(node => !!node.fontId) })
    const signature = JSON.stringify([manifest, config.fonts, nodes, data.activeMenuKey, edit && edit.id])
    if (signature === lastSignature) { if (editor) publishContext(session.context()); return }
    lastSignature = signature
    const effective = manifest && editor ? { ...manifest, versions: config.fonts || {} } : manifest
    const loading = fontFallback ? Promise.resolve({ families: {}, versions: {} }) : session.load(effective, nodes)
    if (editor) {
      publishContext(session.context())
      loading.then(publishContext)
    } else {
      const ticket = openingTicket
      loading.then(context => opening.commit(ticket, { fontContext: context }))
    }
  }
  function applyCatalog() {
    if (disposed) return null
    const remoteAvailable = !!catalog && (typeof wx === 'undefined' || typeof wx.loadFontFace === 'function')
    const options = (page.data.textSectionFontOptions || PORTFOLIO_TEXT_SELECTION_OPTIONS).map(option => {
      if (!option.remote) return option
      const entry = catalog && (catalog.fonts || []).find(font => font.fontId === option.value)
      const { fontVersion, ...display } = option
      return { ...display, available: !!entry && remoteAvailable, previewImageUrl: entry && entry.previewImageUrl || '',
        sample: option.languageLabel === '只适合英文' ? 'Timeless Moments' : '以光为笔，记录心动' }
    })
    originalSetData({ remoteFontAvailable: remoteAvailable, textSectionFontOptions: options, fontMessage: catalogMessage || (catalog && !remoteAvailable ? '当前设备暂不支持远程字体，当前使用系统字体' : '') })
    return catalog
  }
  function ensureCatalog() {
    if (!catalogPromise) catalogPromise = request({ url: '/api/mine/portfolio-fonts' })
      .then(result => {
        catalog = result && result.available ? result : null
        catalogMessage = catalog ? '' : '字体服务暂未开放，当前使用系统字体'
        return catalog
      })
      .catch(() => { catalogMessage = '字体目录获取失败，当前使用系统字体'; return null })
    return catalogPromise.then(applyCatalog)
  }
  function expand() { originalSetData({ moreFontsExpanded: true }); return ensureCatalog() }
  page.setData = function (patch, callback) {
    if (disposed) return
    if (opening && patch.portfolio) {
      const view = patch.portfolio
      const nodes = collectFontNodes(view.activeComponents || view.components || [])
      const signature = JSON.stringify([view.fonts, view.fonts, nodes, page.data.activeMenuKey, null])
      if (view.underMaintenance) opening.cancel()
      else if (signature !== lastSignature) openingTicket = opening.begin(!fontFallback && !view.underMaintenance && nodes.some(node => !!node.fontId))
    }
    originalSetData(patch, callback)
    if (opening && patch.errorMessage) opening.cancel()
    if (opening && patch.loading === false) opening.notify()
    if (editor && patch.textSectionFontOptions) applyCatalog()
    if (Object.keys(patch).some(key => /^(?:config|portfolio|fontAssets|activeMenuKey|textSectionForm|textSectionTypography|textSectionSheetVisible|newComponentSheetVisible|structuredTextSheetVisible)(?:\.|$)/.test(key))) refresh()
  }
  function select(fontId, sessionId) {
    if (!editor || disposed || !fontId || !catalog) return
    syncEdit()
    if (!edit || (sessionId && sessionId !== edit.id)) return
    const config = page.data.config || {}
    if (edit.fonts[fontId] && edit.fonts[fontId].fontVersion) return
    if (allNodes(config).some(node => node.fontId === fontId)) return
    const entry = (catalog.fonts || []).find(font => font.fontId === fontId)
    if (entry && entry.fontVersion) edit.fonts[fontId] = { fontVersion: entry.fontVersion }
  }
  function editDraft(draft) {
    syncEdit()
    if (!edit || (draft.sessionId && draft.sessionId !== edit.id) || draft.componentType !== edit.type) return
    if (edit.type === 'TEXT_GRID' && Number.isInteger(draft.historyKey)) {
      if (draft.operation === 'undo' && edit.history.has(draft.historyKey)) edit.fonts = clone(edit.history.get(draft.historyKey))
      else if (!edit.history.has(draft.historyKey)) edit.history.set(draft.historyKey, clone(edit.fonts))
      // 对齐现有网格最多 40 次撤销，加当前条目；不另建全局撤销栈。
      while (edit.history.size > 41) edit.history.delete(edit.history.keys().next().value)
    }
    editingDraft = clone(draft)
    refresh()
  }
  async function prepare() {
    if (!editor || disposed || !page.data.portfolioId || page.data.fontPreparing) return
    const config = candidateConfig()
    const identity = edit && edit.id
    const menuKey = page.data.activeMenuKey
    const signature = JSON.stringify(config)
    const isCurrent = () => !disposed && (edit && edit.id) === identity && page.data.activeMenuKey === menuKey && JSON.stringify(candidateConfig()) === signature
    page.setData({ fontPreparing: true, fontMessage: '' })
    try {
      const response = await request({ url: `/api/mine/${team ? 'team-portfolios' : 'portfolios'}/${page.data.portfolioId}/fonts/prepare`,
        method: 'POST', data: { config, clientCapabilities: { portfolioRemoteFont: 1 } } })
      if (isCurrent()) {
        if (edit) edit.prepared = true
        page.setData({ fontAssets: response.fontAssets || null,
          fontMessage: response.fontAssets && response.fontAssets.unavailable && response.fontAssets.unavailable.length
            ? (response.fontAssets.unavailable.some(item => ['MISSING_VERSION', 'UNKNOWN_VERSION'].includes(item.reason))
              ? '字体版本缺失或未知，当前使用系统字体；可修复字体版本' : '字体暂不可用，当前使用系统字体') : '' })
      }
    } catch (_) { if (isCurrent()) page.setData({ fontMessage: '字体暂不可用，当前使用系统字体' }) }
    finally { if (!disposed) page.setData({ fontPreparing: false }) }
  }
  async function repairVersions() {
    const available = await ensureCatalog()
    if (!available || disposed) return
    const candidate = candidateConfig()
    const identity = edit && edit.id
    const signature = JSON.stringify(candidate)
    wx.showModal({ title: '修复字体版本', content: '将缺失或不可用的版本更新为目录中的版本，影响草稿中使用同款字体的所有文字。',
      success(result) {
        if (!result.confirm || disposed || (edit && edit.id) !== identity || JSON.stringify(candidateConfig()) !== signature) return
        const config = candidate
        const fonts = { ...(config.fonts || {}) }
        const unavailable = ((page.data.fontAssets || {}).unavailable || []).filter(item =>
          item.reason === 'MISSING_VERSION' || item.reason === 'UNKNOWN_VERSION')
        allNodes(config).forEach(node => {
          const entry = available.fonts.find(font => font.fontId === node.fontId)
          const existing = fonts[node.fontId]
          const invalid = !existing || !existing.fontVersion || unavailable.some(item =>
            item.fontId === node.fontId && item.reason === 'UNKNOWN_VERSION' && item.fontVersion === existing.fontVersion)
          if (entry && invalid) fonts[node.fontId] = { fontVersion: entry.fontVersion }
        })
        if (edit) { edit.fonts = fonts; refresh() }
        else page.setData({ config: { ...config, fonts } })
      } })
  }
  if (editor) refresh()
  return { hide() { if (opening) opening.hide() }, show() { if (opening) opening.show() }, select, editDraft, ensureCatalog, expand, candidateConfig, commit, prepare, repairVersions, dispose() {
    disposed = true
    if (opening) opening.dispose()
    session.dispose()
    page.setData = originalSetData
  } }
}
module.exports = { installRemoteFontPage }
