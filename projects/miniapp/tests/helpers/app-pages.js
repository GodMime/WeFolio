function normalizeRoot(root = '') {
  return String(root).replace(/^\/+|\/+$/g, '')
}

function getRegisteredPageRoutes(appJson = {}) {
  const mainPages = Array.isArray(appJson.pages) ? appJson.pages : []
  const subPackages = appJson.subPackages || appJson.subpackages || []
  const subPages = subPackages.flatMap((pkg) => {
    const root = normalizeRoot(pkg.root)
    return (pkg.pages || []).map((page) => `${root}/${String(page).replace(/^\/+/, '')}`)
  })
  return [...mainPages, ...subPages]
}

module.exports = { getRegisteredPageRoutes }
