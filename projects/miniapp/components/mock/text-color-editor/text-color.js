const DEFAULT_BACKGROUND_COLOR = '#FFFFFF'
const HEX_COLOR_PATTERN = /^#[0-9A-Fa-f]{6}$/

function clamp(value, minimum, maximum) {
  return Math.min(maximum, Math.max(minimum, Number(value) || 0))
}

function normalizeHexColor(value) {
  const color = String(value || '').trim()
  return HEX_COLOR_PATTERN.test(color) ? color.toUpperCase() : DEFAULT_BACKGROUND_COLOR
}

function hexToRgb(value) {
  const color = normalizeHexColor(value)
  return {
    red: Number.parseInt(color.slice(1, 3), 16),
    green: Number.parseInt(color.slice(3, 5), 16),
    blue: Number.parseInt(color.slice(5, 7), 16)
  }
}

function rgbToHex(red, green, blue) {
  return `#${[red, green, blue]
    .map((channel) => Math.round(clamp(channel, 0, 255)).toString(16).padStart(2, '0'))
    .join('')
    .toUpperCase()}`
}

function hexToHsv(value) {
  const { red, green, blue } = hexToRgb(value)
  const redValue = red / 255
  const greenValue = green / 255
  const blueValue = blue / 255
  const maximum = Math.max(redValue, greenValue, blueValue)
  const minimum = Math.min(redValue, greenValue, blueValue)
  const difference = maximum - minimum
  let hue = 0
  if (difference > 0) {
    if (maximum === redValue) {
      hue = 60 * (((greenValue - blueValue) / difference) % 6)
    } else if (maximum === greenValue) {
      hue = 60 * (((blueValue - redValue) / difference) + 2)
    } else {
      hue = 60 * (((redValue - greenValue) / difference) + 4)
    }
  }
  return {
    hue: (hue + 360) % 360,
    saturation: maximum === 0 ? 0 : difference / maximum,
    value: maximum
  }
}

function hsvToHex(hsv = {}) {
  const hue = ((Number(hsv.hue) || 0) % 360 + 360) % 360
  const saturation = clamp(hsv.saturation, 0, 1)
  const value = clamp(hsv.value, 0, 1)
  const chroma = value * saturation
  const intermediate = chroma * (1 - Math.abs(((hue / 60) % 2) - 1))
  const match = value - chroma
  let channels
  if (hue < 60) {
    channels = [chroma, intermediate, 0]
  } else if (hue < 120) {
    channels = [intermediate, chroma, 0]
  } else if (hue < 180) {
    channels = [0, chroma, intermediate]
  } else if (hue < 240) {
    channels = [0, intermediate, chroma]
  } else if (hue < 300) {
    channels = [intermediate, 0, chroma]
  } else {
    channels = [chroma, 0, intermediate]
  }
  return rgbToHex(...channels.map((channel) => (channel + match) * 255))
}

function themeModeFromHex(value) {
  const { red, green, blue } = hexToRgb(value)
  const yiq = (red * 299 + green * 587 + blue * 114) / 1000
  return yiq < 128 ? 'dark' : 'light'
}

module.exports = {
  DEFAULT_BACKGROUND_COLOR,
  hexToHsv,
  hsvToHex,
  normalizeHexColor,
  themeModeFromHex
}
