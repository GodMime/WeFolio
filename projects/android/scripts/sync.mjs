import fs from 'node:fs/promises'
import path from 'node:path'
import { ANDROID_ROOT, MINIAPP_ROOT, GENERATED_ROOT } from './paths.mjs'

const SOURCE_EXTENSIONS = new Set(['.js', '.json', '.wxml', '.wxss', '.wxs', '.png', '.jpg', '.jpeg', '.svg', '.webp', '.gif', '.woff', '.woff2', '.ttf'])
const PAYMENT_PAGE = 'pages/recharge/recharge'
const OVERRIDES_ROOT = path.join(ANDROID_ROOT, 'overrides')

/** 仅复制运行资源，拒绝符号链接，避免私有配置和测试资料进入安装包。 */
async function copyTree(from, to, allow) {
  await fs.mkdir(to, { recursive: true })
  for (const entry of await fs.readdir(from, { withFileTypes: true })) {
    if (entry.isSymbolicLink()) throw new Error(`不允许打包符号链接：${entry.name}`)
    if (!allow(entry)) continue
    const source = path.join(from, entry.name)
    const target = path.join(to, entry.name)
    if (entry.isDirectory()) await copyTree(source, target, allow)
    else await fs.copyFile(source, target)
  }
}

/** 源码结构变化时明确失败，防止生成遗漏安卓适配的安装包。 */
async function replaceRequired(relative, before, after) {
  const filename = path.join(GENERATED_ROOT, relative)
  const source = await fs.readFile(filename, 'utf8')
  if (!source.includes(before)) throw new Error(`安卓适配位置已变化：${relative}`)
  await fs.writeFile(filename, source.replace(before, after))
}

async function walk(directory) {
  const files = []
  for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
    const target = path.join(directory, entry.name)
    if (entry.isDirectory()) files.push(...await walk(target))
    else files.push(target)
  }
  return files
}

export async function sync() {
  const defaults = JSON.parse(await fs.readFile(path.join(ANDROID_ROOT, 'android.config.json'), 'utf8'))
  let local = {}
  try { local = JSON.parse(await fs.readFile(path.join(ANDROID_ROOT, 'android.local.json'), 'utf8')) }
  catch (error) { if (error.code !== 'ENOENT') throw error }
  const config = { ...defaults, ...local, paymentsEnabled: false }
  // 客户端配置只能携带公开参数，AppSecret 必须保留在服务端。
  const allowed = ['apiBaseUrl', 'mobileAppId', 'miniProgramOriginalId', 'shareFallbackUrl', 'paymentsEnabled']
  if (Object.keys(config).some(key => !allowed.includes(key))) throw new Error('安卓配置包含未知字段；禁止填写密钥')
  if (new URL(config.apiBaseUrl).protocol !== 'https:') throw new Error('安卓 API 必须使用 HTTPS')
  let frameworkConfig
  try { frameworkConfig = await fs.readFile(path.join(GENERATED_ROOT, 'app.miniapp.json')) }
  catch (error) { if (error.code !== 'ENOENT') throw error }
  await fs.rm(GENERATED_ROOT, { recursive: true, force: true })
  await copyTree(MINIAPP_ROOT, GENERATED_ROOT, entry => {
    if (entry.name.startsWith('.') || ['tests', 'node_modules'].includes(entry.name)) return false
    if (entry.isDirectory()) return true
    return SOURCE_EXTENSIONS.has(path.extname(entry.name)) && !entry.name.startsWith('project.')
  })
  await copyTree(OVERRIDES_ROOT, GENERATED_ROOT, () => true)
  if (frameworkConfig) await fs.writeFile(path.join(GENERATED_ROOT, 'app.miniapp.json'), frameworkConfig)
  await fs.writeFile(path.join(GENERATED_ROOT, 'utils/android-config.js'),
    `// 自动生成的安卓公开配置，不包含任何服务端密钥。\nmodule.exports = ${JSON.stringify(config, null, 2)}\n`)

  const appFile = path.join(GENERATED_ROOT, 'app.json')
  const app = JSON.parse(await fs.readFile(appFile, 'utf8'))
  const originalPages = [...app.pages, ...app.subPackages.flatMap(pkg => pkg.pages.map(page => `${pkg.root}/${page}`))]
  app.pages = app.pages.filter(page => page !== PAYMENT_PAGE)
  await fs.writeFile(appFile, JSON.stringify(app, null, 2) + '\n')
  await fs.rm(path.join(GENERATED_ROOT, 'pages/recharge'), { recursive: true, force: true })

  await replaceRequired('utils/request.js', "const DEFAULT_BASE_URL = 'https://api.we-folio.dingchenyong.top'",
    "const { apiBaseUrl: DEFAULT_BASE_URL } = require('./android-config')\nconst { assertRequestAllowed } = require('./android-policy')")
  await replaceRequired('utils/request.js', 'const method = requestOptions.method || \'GET\'',
    "const method = requestOptions.method || 'GET'\n    try { assertRequestAllowed(requestOptions.url, method) } catch (error) { return Promise.reject(error) }")

  await replaceRequired('components/share-channel-sheet/share-channel-sheet.wxml',
    'open-type="share"', 'bindtap="handleFriend"')
  await replaceRequired('pages/works/utils/work-upload.js',
    "typeof runtimeWx.chooseMessageFile !== 'function'",
    "!runtimeWx.miniapp || typeof runtimeWx.miniapp.chooseFile !== 'function'")
  await replaceRequired('pages/works/utils/work-upload.js',
    'runtimeWx.chooseMessageFile({', "require('./android-file-picker').chooseAudioFiles(runtimeWx, {")
  await replaceRequired('pages/recharge-records/recharge-records.js',
    'await this.syncPendingOrders(nextData.records)', '// 安卓仅查看充值记录，不发起支付状态同步。')

  // Android 不暴露充值入口，也不允许通过页面深链恢复支付页面。
  for (const page of ['index/index', 'points/points']) {
    const filename = path.join(GENERATED_ROOT, `pages/${page}.wxml`)
    const source = await fs.readFile(filename, 'utf8')
    const updated = source.replace(/<button\b[^>]*(?:bindtap|catchtap)="handleRechargeTap"[^>]*>[\s\S]*?<\/button>/g,
      '<text class="android-payment-note">安卓端暂不支持充值</text>')
    if (source === updated) throw new Error(`未找到充值入口：${page}`)
    await fs.writeFile(filename, updated)
  }

  // 头像按钮切换到系统相册；保留原上传、裁剪和业务保存函数。
  for (const filename of await walk(GENERATED_ROOT)) {
    if (!filename.endsWith('.wxml')) continue
    let source = await fs.readFile(filename, 'utf8')
    source = source.replace(/open-type="chooseAvatar"\s+bindchooseavatar="([^"]+)"/g,
      'bindtap="androidChooseAvatar" data-android-avatar-handler="$1"')
    await fs.writeFile(filename, source)
  }

  // 每页只增加平台交互，业务 Page 定义和生命周期仍由官方框架执行。
  for (const filename of await walk(GENERATED_ROOT)) {
    if (!filename.endsWith('.js')) continue
    let source = await fs.readFile(filename, 'utf8')
    if (/\/(?:visitor-session|team-visitor-session)\.js$/.test(filename)) {
      let visitorHelper = path.relative(path.dirname(filename), path.join(GENERATED_ROOT, 'utils/android-visitor')).replaceAll('\\', '/')
      if (!visitorHelper.startsWith('.')) visitorHelper = './' + visitorHelper
      const identityExpression = /: \{ loginCode: await wxLogin\((?:runtimeWx|options\.wxApi)\) \}/
      if (!identityExpression.test(source)) throw new Error(`访客身份适配位置变化：${filename}`)
      source = source.replace(identityExpression, `: await require('${visitorHelper}').createVisitorIdentity(${source.includes('await wxLogin(runtimeWx)') ? 'runtimeWx' : 'options.wxApi'})`)
      const requestExpression = /await \((resolved|options)\.requestFn \|\| request\)\(\{/
      if (!requestExpression.test(source)) throw new Error(`访客打开请求适配位置变化：${filename}`)
      source = source.replace(requestExpression, (_, options) => `await require('${visitorHelper}').openVisitorRequest(${options}.requestFn || request, {`)
      await fs.writeFile(filename, source)
    }
    if (!/^Page\(\{/m.test(source)) continue
    let helper = path.relative(path.dirname(filename), path.join(GENERATED_ROOT, 'utils/android-page')).replaceAll('\\', '/')
    if (!helper.startsWith('.')) helper = './' + helper
    await fs.writeFile(filename, `const { registerAndroidPage: Page } = require('${helper}')\n${source}`)
  }

  await fs.writeFile(path.join(GENERATED_ROOT, 'android-build-manifest.json'), JSON.stringify({
    source: '../miniapp', sdk: '1.7.0', sourcePages: originalPages,
    excludedPages: [PAYMENT_PAGE], paymentsEnabled: false,
    verification: 'SOURCE_CHECKED_DEVICE_VALIDATION_REQUIRED'
  }, null, 2) + '\n')
  console.log(`已生成 ${originalPages.length - 1} 个页面；关闭充值页面，保留积分和充值记录。`)
  return { originalPages, generatedPages: originalPages.filter(page => page !== PAYMENT_PAGE) }
}

if (process.argv[1] === new URL(import.meta.url).pathname) await sync()
