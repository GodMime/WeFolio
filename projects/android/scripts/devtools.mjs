import fs from 'node:fs/promises'
import path from 'node:path'
import { spawn } from 'node:child_process'
import { ANDROID_ROOT } from './paths.mjs'
import { check } from './check.mjs'

const DEFAULT_CLI = '/Applications/wechatwebdevtools.app/Contents/MacOS/cli'
const DEBUG_PASSWORD = 'android'
const DEBUG_ALIAS = 'androiddebugkey'

/** 参数数组直接传给子进程，避免目录名或证书路径被解释为 shell 指令。 */
function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: ANDROID_ROOT, stdio: 'inherit' })
    child.on('error', reject)
    child.on('exit', code => code === 0 ? resolve() : reject(new Error(`命令未成功，退出码 ${code}`)))
  })
}

await check()
const cli = process.env.WEFOLIO_WECHAT_CLI || DEFAULT_CLI
await fs.access(cli)
const mode = process.argv[2]
if (mode === 'open') {
  await run(cli, ['open', '--project', ANDROID_ROOT])
} else if (mode === 'build-apk') {
  // 这里只生成可安装测试包；正式签名由发布者配置，禁止自动生成正式发布身份。
  const keyStore = path.join(ANDROID_ROOT, '.local/android-debug.keystore')
  await fs.mkdir(path.dirname(keyStore), { recursive: true })
  try { await fs.access(keyStore) } catch {
    const keytool = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/keytool') : 'keytool'
    await run(keytool, ['-genkeypair', '-noprompt', '-keystore', keyStore,
      '-storepass', DEBUG_PASSWORD, '-keypass', DEBUG_PASSWORD, '-alias', DEBUG_ALIAS,
      '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650',
      '-dname', 'CN=Android Debug,O=WeFolio Development,C=CN'])
  }
  const dist = path.join(ANDROID_ROOT, 'dist')
  await fs.mkdir(dist, { recursive: true })
  const output = await fs.mkdtemp(path.join(dist, 'build-'))
  await run(cli, ['open', '--project', ANDROID_ROOT])
  await run(cli, ['build-apk', '--project', ANDROID_ROOT, '--keyStore', keyStore,
    '--keyAlias', DEBUG_ALIAS, '--storePass', DEBUG_PASSWORD, '--keyPass', DEBUG_PASSWORD,
    '--output', output, '--desc', 'WeFolio Android 测试构建，支付关闭'])
  const apks = (await fs.readdir(output)).filter(name => name.endsWith('.apk'))
  if (!apks.length) throw new Error('构建命令已结束，但未发现 APK；请检查微信开发者工具中的项目窗口和构建记录')
  for (const apk of apks) {
    const bytes = await fs.readFile(path.join(output, apk))
    if (bytes.length < 1024 || bytes[0] !== 0x50 || bytes[1] !== 0x4b) throw new Error('构建产物不是有效 APK 容器')
  }
  console.log(`已生成 APK：${apks.map(name => path.join(output, name)).join(', ')}`)
} else {
  throw new Error('用法：node scripts/devtools.mjs open|build-apk')
}
