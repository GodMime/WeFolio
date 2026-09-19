// 使用已确认的固定像素分享名片模板；不乘设备 DPR，原码始终一比一绘制。
const WIDTH = 1080
const HEIGHT = 1440
const CODE_SIZE = 800
const CODE_LEFT = 140
const CODE_TOP = 390
// 按当前 APP 原码截图，中心标志圆区占边长约 45%；兼容旧后端的 120px 提示。
const AVATAR_SIZE = 360
const AVATAR_LEFT = CODE_LEFT + (CODE_SIZE - AVATAR_SIZE) / 2
const AVATAR_TOP = CODE_TOP + (CODE_SIZE - AVATAR_SIZE) / 2
const MAX_AVATAR_SIDE = 2048
const CANVAS_ID = 'portfolio-miniapp-code-canvas'
const FONT_FAMILY = 'sans-serif'
const GOLD = '#ae8b4c'
const TEXT = '#282828'
const WHITE = '#ffffff'
const GREY = '#808080'
const FOOTER = '映期 Folio · 让作品被看见'
const IMAGE_ERROR = '图片加载失败，请重试'
const RENDER_ERROR = '图片绘制失败，请重试'

function cleanText(value) { return String(value || '').replace(/[\r\n\t]+/g, ' ').trim() }

// Array.from 按 Unicode 码点迭代，避免省略号前截断 emoji 的代理对。
function ellipsize(context, value, width) {
  if (context.measureText(value).width <= width) return value
  const points = Array.from(value)
  while (points.length && context.measureText(points.join('') + '…').width > width) points.pop()
  return points.join('') + '…'
}

function titleLines(context, value) {
  const points = Array.from(value)
  let offset = 0
  let first = ''
  while (offset < points.length && context.measureText(first + points[offset]).width <= 870) first += points[offset++]
  return offset < points.length ? [first, ellipsize(context, points.slice(offset).join(''), 870)] : [first]
}

// 接受真实 Canvas/Context，也供桌面 Canvas 视觉验收直接调用同一绘制逻辑。
function drawPortfolioMiniappCode(canvas, context, resources, codeImage, avatarImage, options = {}) {
  const useAvatar = options.useAvatar === true
  if (codeImage.width !== CODE_SIZE || codeImage.height !== CODE_SIZE) throw new Error('小程序码图片尺寸异常，请重试')
  if (useAvatar && (!avatarImage || !(avatarImage.width > 0 && avatarImage.height > 0) || avatarImage.width > MAX_AVATAR_SIDE || avatarImage.height > MAX_AVATAR_SIDE)) throw new Error('头像图片尺寸异常，请重试')
  canvas.width = WIDTH
  canvas.height = HEIGHT
  context.clearRect(0, 0, WIDTH, HEIGHT)
  context.fillStyle = WHITE
  context.fillRect(0, 0, WIDTH, HEIGHT)
  context.fillStyle = GOLD
  context.fillRect(504, 66, 72, 5)
  context.textAlign = 'center'
  context.textBaseline = 'alphabetic'
  context.fillStyle = TEXT
  context.font = `bold 56px ${FONT_FAMILY}`
  context.fillText(ellipsize(context, cleanText(resources.displayName), 880), WIDTH / 2, 155)
  context.font = `30px ${FONT_FAMILY}`
  context.fillStyle = GREY
  context.fillText(ellipsize(context, cleanText(resources.subtitle), 880), WIDTH / 2, 211)
  context.font = `38px ${FONT_FAMILY}`
  context.fillStyle = TEXT
  titleLines(context, cleanText(resources.shareTitle)).forEach((line, index) => context.fillText(line, WIDTH / 2, 292 + index * 50))
  context.drawImage(codeImage, CODE_LEFT, CODE_TOP)
  // 默认完整保留微信原码，只有用户明确开启开关才覆盖中心标志。
  if (useAvatar) {
    context.save()
    context.beginPath()
    context.arc(CODE_LEFT + CODE_SIZE / 2, CODE_TOP + CODE_SIZE / 2, AVATAR_SIZE / 2, 0, Math.PI * 2)
    context.clip()
    context.fillStyle = WHITE
    context.fillRect(AVATAR_LEFT, AVATAR_TOP, AVATAR_SIZE, AVATAR_SIZE)
    const side = Math.min(avatarImage.width, avatarImage.height)
    context.drawImage(avatarImage, (avatarImage.width - side) / 2, (avatarImage.height - side) / 2, side, side, AVATAR_LEFT, AVATAR_TOP, AVATAR_SIZE, AVATAR_SIZE)
    context.restore()
  }
  context.font = `33px ${FONT_FAMILY}`
  context.fillStyle = TEXT
  context.fillText(resources.ownerType === 'TEAM' ? '微信扫码，查看团队作品集' : '微信扫码，查看我的作品集', WIDTH / 2, 1270)
  context.fillStyle = GOLD
  context.font = `26px ${FONT_FAMILY}`
  context.fillText(FOOTER, WIDTH / 2, 1362)
}

function createPortfolioMiniappCodeRenderer(options = {}) {
  const wxApi = options.wxApi || wx
  let disposed = false
  let markReady
  const readiness = new Promise(resolve => { markReady = resolve })
  const check = input => { if (disposed || !input.isActive()) throw new Error('页面已关闭') }
  const imageError = path => Object.assign(new Error(IMAGE_ERROR), { imagePath: path })
  const imageInfo = path => new Promise((resolve, reject) => wxApi.getImageInfo({ src: path, success: resolve, fail: () => reject(imageError(path)) }))
  const loadImage = (canvas, path) => new Promise((resolve, reject) => {
    const image = canvas.createImage()
    image.onload = () => resolve(image)
    image.onerror = () => reject(imageError(path))
    image.src = path
  })
  const getCanvas = () => new Promise((resolve, reject) => {
    if (!wxApi.createSelectorQuery) { reject(new Error('当前微信版本不支持图片绘制')); return }
    const query = wxApi.createSelectorQuery()
    const scoped = query.in ? query.in(options.page) : query
    scoped.select(`#${CANVAS_ID}`).fields({ node: true, size: true }).exec((results = []) => {
      const canvas = results[0] && results[0].node
      if (!canvas || !canvas.createImage) reject(new Error('未找到图片绘制画布，请重试'))
      else resolve(canvas)
    })
  })
  return {
    ready() { markReady() },
    dispose() { disposed = true; markReady() },
    async render(input) {
      await readiness
      check(input)
      const canvas = await getCanvas()
      check(input)
      // 尺寸预检在 Canvas createImage 前完成，拒绝异常大图占用额外解码/绘制内存。
      const codeInfo = await imageInfo(input.codePath)
      check(input)
      if (codeInfo.width !== CODE_SIZE || codeInfo.height !== CODE_SIZE) throw new Error('小程序码图片尺寸异常，请重试')
      const useAvatar = input.useAvatar === true
      if (useAvatar) {
        const avatarInfo = await imageInfo(input.avatarPath)
        check(input)
        if (!(avatarInfo.width > 0 && avatarInfo.height > 0) || avatarInfo.width > MAX_AVATAR_SIDE || avatarInfo.height > MAX_AVATAR_SIDE) throw new Error('头像图片尺寸异常，请重试')
      }
      const code = await loadImage(canvas, input.codePath)
      check(input)
      let avatar
      if (useAvatar) {
        avatar = await loadImage(canvas, input.avatarPath)
        check(input)
      }
      const context = canvas.getContext('2d')
      if (!context) throw new Error('当前微信版本不支持图片绘制')
      drawPortfolioMiniappCode(canvas, context, input.resources, code, avatar, { useAvatar })
      check(input)
      const path = await new Promise((resolve, reject) => wxApi.canvasToTempFilePath({
        canvas, x: 0, y: 0, width: WIDTH, height: HEIGHT, destWidth: WIDTH, destHeight: HEIGHT, fileType: 'png',
        success(result) { result && result.tempFilePath ? resolve(result.tempFilePath) : reject(new Error(RENDER_ERROR)) },
        fail() { reject(new Error(RENDER_ERROR)) }
      }, options.page))
      check(input)
      return path
    }
  }
}

module.exports = { createPortfolioMiniappCodeRenderer, drawPortfolioMiniappCode }
