import fs from 'node:fs/promises'
import path from 'node:path'
import { ANDROID_ROOT, GENERATED_ROOT } from './paths.mjs'
import { sync } from './sync.mjs'

export async function check() {
  const { generatedPages } = await sync()
  for (const route of generatedPages) {
    for (const extension of ['.js', '.json', '.wxml', '.wxss']) {
      await fs.access(path.join(GENERATED_ROOT, route + extension))
    }
  }
  const config = JSON.parse(await fs.readFile(path.join(ANDROID_ROOT, 'android.config.json'), 'utf8'))
  const platform = JSON.parse(await fs.readFile(path.join(ANDROID_ROOT, 'project.miniapp.json'), 'utf8'))
  if (config.paymentsEnabled !== false) throw new Error('当前安卓版本必须关闭支付')
  for (const sdk of ['media', 'network', 'open', 'xweb', 'skyline']) {
    if (!platform['mini-android'].useExtendedSdk[sdk]) throw new Error(`缺少 SDK 模块：${sdk}`)
  }
  console.log(`源码校验通过：${generatedPages.length} 个页面资源齐全。此检查不代表 APK 构建或真机验收通过。`)
  return generatedPages
}

if (process.argv[1] === new URL(import.meta.url).pathname) await check()
