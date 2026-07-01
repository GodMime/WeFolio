const K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
  0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
  0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
  0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
  0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
  0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
  0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
  0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
  0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
]

const INITIAL_HASH = [
  0x6a09e667,
  0xbb67ae85,
  0x3c6ef372,
  0xa54ff53a,
  0x510e527f,
  0x9b05688c,
  0x1f83d9ab,
  0x5be0cd19
]

const ARRAY_BUFFER_TAG = '[object ArrayBuffer]'
const SHA256_DIGEST_ALGORITHM = 'sha256'
const SHA256_HEX_PATTERN = /^[0-9a-f]{64}$/i
const FILE_READ_FAILED_MESSAGE = '文件读取失败'
const WX_UNAVAILABLE_MESSAGE = 'wx 运行环境不可用'

function rotateRight(value, bits) {
  return (value >>> bits) | (value << (32 - bits))
}

function getRuntimeWx(wxApi) {
  if (wxApi) {
    return wxApi
  }
  if (typeof wx !== 'undefined') {
    return wx
  }
  throw new Error(WX_UNAVAILABLE_MESSAGE)
}

function isArrayBufferLike(input) {
  return input
    && typeof input.byteLength === 'number'
    && Object.prototype.toString.call(input) === ARRAY_BUFFER_TAG
}

function toBytes(input) {
  if (input instanceof ArrayBuffer || isArrayBufferLike(input)) {
    return new Uint8Array(input)
  }
  if (ArrayBuffer.isView(input)) {
    return new Uint8Array(input.buffer, input.byteOffset, input.byteLength)
  }
  throw new Error('SHA-256 输入必须是 ArrayBuffer')
}

function utf8Bytes(text) {
  const value = String(text || '')
  const bytes = []
  for (let index = 0; index < value.length; index++) {
    let codePoint = value.charCodeAt(index)
    if (codePoint >= 0xd800 && codePoint <= 0xdbff && index + 1 < value.length) {
      const next = value.charCodeAt(index + 1)
      if (next >= 0xdc00 && next <= 0xdfff) {
        codePoint = 0x10000 + ((codePoint - 0xd800) << 10) + (next - 0xdc00)
        index += 1
      }
    }
    if (codePoint < 0x80) {
      bytes.push(codePoint)
    } else if (codePoint < 0x800) {
      bytes.push(0xc0 | (codePoint >> 6), 0x80 | (codePoint & 0x3f))
    } else if (codePoint < 0x10000) {
      bytes.push(0xe0 | (codePoint >> 12), 0x80 | ((codePoint >> 6) & 0x3f), 0x80 | (codePoint & 0x3f))
    } else {
      bytes.push(
        0xf0 | (codePoint >> 18),
        0x80 | ((codePoint >> 12) & 0x3f),
        0x80 | ((codePoint >> 6) & 0x3f),
        0x80 | (codePoint & 0x3f)
      )
    }
  }
  return new Uint8Array(bytes)
}

function sha256Bytes(inputBytes) {
  const bytes = toBytes(inputBytes)
  const bitLength = bytes.length * 8
  const paddedLength = Math.ceil((bytes.length + 9) / 64) * 64
  const padded = new Uint8Array(paddedLength)
  padded.set(bytes)
  padded[bytes.length] = 0x80

  // 小程序作品上限 100MB，长度高 32 位保持 0 即可。
  const dataView = new DataView(padded.buffer)
  dataView.setUint32(paddedLength - 8, Math.floor(bitLength / 0x100000000))
  dataView.setUint32(paddedLength - 4, bitLength >>> 0)

  const hash = INITIAL_HASH.slice()
  const words = new Uint32Array(64)

  for (let offset = 0; offset < paddedLength; offset += 64) {
    for (let index = 0; index < 16; index++) {
      words[index] = dataView.getUint32(offset + index * 4)
    }
    for (let index = 16; index < 64; index++) {
      const s0 = rotateRight(words[index - 15], 7) ^ rotateRight(words[index - 15], 18) ^ (words[index - 15] >>> 3)
      const s1 = rotateRight(words[index - 2], 17) ^ rotateRight(words[index - 2], 19) ^ (words[index - 2] >>> 10)
      words[index] = (words[index - 16] + s0 + words[index - 7] + s1) >>> 0
    }

    let a = hash[0]
    let b = hash[1]
    let c = hash[2]
    let d = hash[3]
    let e = hash[4]
    let f = hash[5]
    let g = hash[6]
    let h = hash[7]

    for (let index = 0; index < 64; index++) {
      const s1 = rotateRight(e, 6) ^ rotateRight(e, 11) ^ rotateRight(e, 25)
      const ch = (e & f) ^ (~e & g)
      const temp1 = (h + s1 + ch + K[index] + words[index]) >>> 0
      const s0 = rotateRight(a, 2) ^ rotateRight(a, 13) ^ rotateRight(a, 22)
      const maj = (a & b) ^ (a & c) ^ (b & c)
      const temp2 = (s0 + maj) >>> 0

      h = g
      g = f
      f = e
      e = (d + temp1) >>> 0
      d = c
      c = b
      b = a
      a = (temp1 + temp2) >>> 0
    }

    hash[0] = (hash[0] + a) >>> 0
    hash[1] = (hash[1] + b) >>> 0
    hash[2] = (hash[2] + c) >>> 0
    hash[3] = (hash[3] + d) >>> 0
    hash[4] = (hash[4] + e) >>> 0
    hash[5] = (hash[5] + f) >>> 0
    hash[6] = (hash[6] + g) >>> 0
    hash[7] = (hash[7] + h) >>> 0
  }

  return hash.map((value) => value.toString(16).padStart(8, '0')).join('')
}

function sha256ArrayBuffer(arrayBuffer) {
  return sha256Bytes(arrayBuffer)
}

function sha256Text(text) {
  return sha256Bytes(utf8Bytes(text))
}

function normalizeSha256Digest(value) {
  const digest = String(value || '').trim().toLowerCase()
  return SHA256_HEX_PATTERN.test(digest) ? digest : ''
}

function getNativeFileSha256(filePath, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  if (typeof runtimeWx.getFileInfo !== 'function') {
    return Promise.resolve('')
  }
  return new Promise((resolve) => {
    runtimeWx.getFileInfo({
      filePath,
      digestAlgorithm: SHA256_DIGEST_ALGORITHM,
      success(response) {
        resolve(normalizeSha256Digest(response && response.digest))
      },
      fail() {
        resolve('')
      }
    })
  })
}

function readLocalFileArrayBuffer(filePath, wxApi) {
  const runtimeWx = getRuntimeWx(wxApi)
  return new Promise((resolve, reject) => {
    runtimeWx.getFileSystemManager().readFile({
      filePath,
      success(response) {
        resolve(response.data)
      },
      fail(error) {
        reject(new Error(error && error.errMsg ? error.errMsg : FILE_READ_FAILED_MESSAGE))
      }
    })
  })
}

async function calculateFileSha256(filePath, options = {}) {
  const nativeDigest = await getNativeFileSha256(filePath, options.wxApi)
  if (nativeDigest) {
    return nativeDigest
  }
  const data = await readLocalFileArrayBuffer(filePath, options.wxApi)
  return sha256ArrayBuffer(data)
}

module.exports = {
  calculateFileSha256,
  sha256ArrayBuffer,
  sha256Text
}
