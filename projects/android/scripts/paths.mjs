import { fileURLToPath } from 'node:url'
import path from 'node:path'

// 开发者工具按完整路径匹配项目窗口，统一移除目录末尾分隔符。
export const ANDROID_ROOT = path.resolve(fileURLToPath(new URL('..', import.meta.url)))
export const MINIAPP_ROOT = path.resolve(ANDROID_ROOT, '../miniapp')
export const GENERATED_ROOT = path.join(ANDROID_ROOT, 'generated')
export const DOCUMENT_ROOT = path.resolve(ANDROID_ROOT, '../../docs')
