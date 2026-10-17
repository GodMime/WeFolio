const { PORTFOLIO_TEXT_SELECTION_OPTIONS, applyPortfolioFontSelection, buildRemoteFontStyle, isValidPortfolioTextLineHeight, buildPortfolioTextLineHeightStyle, PORTFOLIO_TEXT_LINE_HEIGHT_ERROR, PORTFOLIO_TEXT_LINE_HEIGHT_STEP, parsePortfolioTextLineHeightInput, stepPortfolioTextLineHeight, buildPortfolioTextLineHeightEditor } = require('../../../utils/portfolio-text-typography.js')
// 分包组件通过本地适配层使用维护者请求和字体能力。
const { request } = require('../../../utils/request.js')
const { loadPortfolioFonts, getPortfolioFontCapability, isPortfolioFontAvailable } = require('../../../utils/portfolio-font-loader.js')
const loadContactProfile = () => request({ url: '/api/mine/profile' })
module.exports = { PORTFOLIO_TEXT_SELECTION_OPTIONS, applyPortfolioFontSelection, buildRemoteFontStyle, isValidPortfolioTextLineHeight, buildPortfolioTextLineHeightStyle, PORTFOLIO_TEXT_LINE_HEIGHT_ERROR, PORTFOLIO_TEXT_LINE_HEIGHT_STEP, parsePortfolioTextLineHeightInput, stepPortfolioTextLineHeight, buildPortfolioTextLineHeightEditor, loadContactProfile, loadPortfolioFonts, getPortfolioFontCapability, isPortfolioFontAvailable }
