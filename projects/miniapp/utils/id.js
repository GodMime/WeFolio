function normalizeId(value) {
  const id = Number(value)
  return Number.isFinite(id) && id > 0 ? id : null
}

module.exports = {
  normalizeId
}
