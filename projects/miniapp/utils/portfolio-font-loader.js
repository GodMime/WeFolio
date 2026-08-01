const {
  PORTFOLIO_TEXT_FONT_FAMILIES
} = require('./portfolio-text-typography')

const BUILT_IN_FONT_DEFINITIONS = Object.freeze([
  Object.freeze({
    configValue: PORTFOLIO_TEXT_FONT_FAMILIES.WECHAT_SANS_SS,
    family: 'WeFolioWechatSansSS',
    source: 'WeChatSansSS'
  })
])

function createEmptyCapability(apiAvailable = false) {
  return {
    apiAvailable,
    loadedFamilies: {
      WECHAT_SANS_SS: false
    }
  }
}

function cloneCapability(capability) {
  return {
    apiAvailable: capability.apiAvailable === true,
    loadedFamilies: Object.assign({}, capability.loadedFamilies)
  }
}

function createPortfolioFontLoader(wxApi) {
  let capability = createEmptyCapability(false)
  let loadPromise = null

  function loadBuiltInFont(definition) {
    return new Promise((resolve) => {
      try {
        wxApi.loadBuiltInFontFace({
          family: definition.family,
          source: definition.source,
          global: true,
          success() {
            resolve(true)
          },
          fail(error) {
            console.warn(`注册作品集内置字体失败: ${definition.source}`, error)
            resolve(false)
          }
        })
      } catch (error) {
        console.warn(`启动作品集内置字体注册失败: ${definition.source}`, error)
        resolve(false)
      }
    })
  }

  function load() {
    if (loadPromise) return loadPromise
    if (!wxApi || typeof wxApi.loadBuiltInFontFace !== 'function') {
      capability = createEmptyCapability(false)
      loadPromise = Promise.resolve(cloneCapability(capability))
      return loadPromise
    }

    capability = createEmptyCapability(true)
    loadPromise = Promise.all(
      BUILT_IN_FONT_DEFINITIONS.map(loadBuiltInFont)
    ).then((results) => {
      BUILT_IN_FONT_DEFINITIONS.forEach((definition, index) => {
        capability.loadedFamilies[definition.configValue] = results[index]
      })
      return cloneCapability(capability)
    })
    return loadPromise
  }

  function getCapability() {
    return cloneCapability(capability)
  }

  return {
    getCapability,
    load
  }
}

function isPortfolioFontAvailable(fontFamily, capability = {}) {
  if (fontFamily === PORTFOLIO_TEXT_FONT_FAMILIES.SYSTEM) return true
  const loadedFamilies = capability.loadedFamilies || {}
  return capability.apiAvailable === true
    && loadedFamilies[fontFamily] === true
}

const runtimeWxApi = typeof wx === 'undefined' ? undefined : wx
const portfolioFontLoader = createPortfolioFontLoader(runtimeWxApi)

function loadPortfolioFonts() {
  return portfolioFontLoader.load()
}

function getPortfolioFontCapability() {
  return portfolioFontLoader.getCapability()
}

module.exports = {
  createPortfolioFontLoader,
  getPortfolioFontCapability,
  isPortfolioFontAvailable,
  loadPortfolioFonts
}
