const THUMB_MAX_BYTES = 100 * 1024

function normalizeSize(value) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : 0
}

function normalizeDimension(value) {
  const numberValue = normalizeSize(value)
  return numberValue > 0 ? Math.round(numberValue) : 0
}

function gcd(left, right) {
  let a = Math.abs(left)
  let b = Math.abs(right)
  while (b > 0) {
    const remainder = a % b
    a = b
    b = remainder
  }
  return a
}

function buildAspectRatio(width, height) {
  const normalizedWidth = normalizeDimension(width)
  const normalizedHeight = normalizeDimension(height)
  if (normalizedWidth <= 0 || normalizedHeight <= 0) {
    return ''
  }
  const divisor = gcd(normalizedWidth, normalizedHeight)
  return `${normalizedWidth / divisor}:${normalizedHeight / divisor}`
}

module.exports = {
  THUMB_MAX_BYTES,
  buildAspectRatio,
  normalizeDimension
}
