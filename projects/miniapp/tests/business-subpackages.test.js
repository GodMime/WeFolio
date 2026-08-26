const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const { getRegisteredPageRoutes } = require('./helpers/app-pages')

const MINIAPP_ROOT = path.join(__dirname, '..')
const EXPECTED_PACKAGES = {
  mock: 'pages/mock',
  works: 'pages/works',
  portfolio: 'pages/portfolios',
  schedule: 'pages/schedule',
  teamPortfolio: 'pages/team-portfolios'
}
const PAGE_EXTENSIONS = ['.js', '.json', '.wxml', '.wxss']
const PACKAGE_LOCAL_UTILS = {
  works: [
    'frame-canvas.js',
    'media.js',
    'sha256.js',
    'video-frame-decoder.js',
    'work-thumbnail-crop.js',
    'work-upload.js',
    'work-media.js',
    'works.js'
  ],
  portfolio: [
    'contact-lead.js',
    'display-switching.js',
    'portfolio-assets.js',
    'portfolio-contact-form.js',
    'portfolio-hyperlink.js',
    'portfolio-publish-disclaimer.js',
    'portfolio-render-events.js',
    'portfolio-work-media.js',
    'single-page-mode.js',
    'team-portfolio-list.js',
    'visitor-profile.js',
    'work-media.js',
    'works.js'
  ],
  schedule: ['schedule.js'],
  teamPortfolio: [
    'team-contact-leads.js',
    'team-portfolio-assets.js',
    'team-portfolio-list.js',
    'portfolio-publish-disclaimer.js',
    'single-page-mode.js',
    'team-portfolios.js',
    'team-visitor-portfolio.js',
    'team-visitor-profile.js',
    'team-visitor-session.js',
    'work-media.js'
  ]
}
const BUSINESS_SUBPACKAGE_ROOTS = [
  'pages/works',
  'pages/portfolios',
  'pages/schedule',
  'pages/team-portfolios'
].map((relativePath) => path.join(MINIAPP_ROOT, relativePath))

function readJson(relativePath) {
  return JSON.parse(fs.readFileSync(path.join(MINIAPP_ROOT, relativePath), 'utf8'))
}

function listFiles(directory, extension) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      return listFiles(entryPath, extension)
    }
    return entryPath.endsWith(extension) ? [entryPath] : []
  })
}

function isInside(directory, filePath) {
  const relativePath = path.relative(directory, filePath)
  return relativePath === '' || (!relativePath.startsWith('..') && !path.isAbsolute(relativePath))
}

function resolveJavaScriptRequest(jsPath, requestPath) {
  const unresolvedPath = path.resolve(path.dirname(jsPath), requestPath)
  const candidates = [
    unresolvedPath,
    `${unresolvedPath}.js`,
    path.join(unresolvedPath, 'index.js')
  ]
  return candidates.find((candidate) => candidate.endsWith('.js') && fs.existsSync(candidate)) || null
}

function collectComponentJavaScript(configPaths) {
  const componentJavaScript = new Set()
  const pendingConfigs = [...configPaths]
  const visitedConfigs = new Set()

  while (pendingConfigs.length > 0) {
    const configPath = pendingConfigs.pop()
    if (visitedConfigs.has(configPath) || !fs.existsSync(configPath)) {
      continue
    }
    visitedConfigs.add(configPath)
    const config = JSON.parse(fs.readFileSync(configPath, 'utf8'))
    for (const componentPath of Object.values(config.usingComponents || {})) {
      if (typeof componentPath !== 'string' || componentPath.startsWith('plugin://')) {
        continue
      }
      const componentBase = componentPath.startsWith('/')
        ? path.join(MINIAPP_ROOT, componentPath.slice(1))
        : path.resolve(path.dirname(configPath), componentPath)
      const jsPath = `${componentBase}.js`
      const jsonPath = `${componentBase}.json`
      if (fs.existsSync(jsPath)) {
        componentJavaScript.add(jsPath)
      }
      if (fs.existsSync(jsonPath)) {
        pendingConfigs.push(jsonPath)
      }
    }
  }
  return componentJavaScript
}

function collectJavaScriptEntries(pageRoutes, includeApp = false) {
  const entries = new Set(pageRoutes.map((route) => path.join(MINIAPP_ROOT, `${route}.js`)))
  const configPaths = pageRoutes.map((route) => path.join(MINIAPP_ROOT, `${route}.json`))
  if (includeApp) {
    entries.add(path.join(MINIAPP_ROOT, 'app.js'))
    configPaths.push(path.join(MINIAPP_ROOT, 'app.json'))
    for (const componentPath of listFiles(path.join(MINIAPP_ROOT, 'components'), '.js')) {
      entries.add(componentPath)
    }
  }
  for (const componentPath of collectComponentJavaScript(configPaths)) {
    entries.add(componentPath)
  }
  return entries
}

function collectReachableJavaScript(entries) {
  const reachable = new Set()
  const pending = [...entries]
  while (pending.length > 0) {
    const jsPath = pending.pop()
    if (reachable.has(jsPath) || !fs.existsSync(jsPath)) {
      continue
    }
    reachable.add(jsPath)
    const source = fs.readFileSync(jsPath, 'utf8')
    const requests = Array.from(source.matchAll(/require\s*\(\s*["']([^"']+)["']\s*\)/g))
      .map((match) => match[1])
      .filter((requestPath) => requestPath.startsWith('.'))
    for (const requestPath of requests) {
      const resolvedPath = resolveJavaScriptRequest(jsPath, requestPath)
      if (resolvedPath) {
        pending.push(resolvedPath)
      }
    }
  }
  return reachable
}

test('app registers business pages in named subpackages without duplicate routes', () => {
  const appJson = readJson('app.json')
  const packagesByName = Object.fromEntries(
    (appJson.subPackages || []).map((pkg) => [pkg.name, pkg])
  )

  for (const [name, root] of Object.entries(EXPECTED_PACKAGES)) {
    assert.ok(packagesByName[name], `${name} subpackage should exist`)
    assert.equal(packagesByName[name].root, root)
    assert.notEqual(packagesByName[name].independent, true)
  }
  assert.deepEqual(appJson.preloadRule, {
    'pages/index/index': {
      network: 'all',
      packages: ['portfolio']
    },
    'pages/portfolios/portfolios': {
      network: 'all',
      packages: ['teamPortfolio']
    }
  })

  const routes = getRegisteredPageRoutes(appJson)
  assert.equal(new Set(routes).size, routes.length, 'registered page routes should not repeat')

  for (const route of routes) {
    for (const extension of PAGE_EXTENSIONS) {
      assert.equal(
        fs.existsSync(path.join(MINIAPP_ROOT, `${route}${extension}`)),
        true,
        `${route}${extension} should exist`
      )
    }
  }
})

test('team portfolio subpackage is appended without changing existing route baselines', () => {
  const appJson = readJson('app.json')
  const expectedMainPages = [
    'pages/index/index', 'pages/points/points', 'pages/points-rules/points-rules',
    'pages/recharge/recharge', 'pages/recharge-records/recharge-records', 'pages/teams/teams',
    'pages/team-maintenance/team-maintenance', 'pages/team-member-add/team-member-add', 'pages/team-invitations/team-invitations',
    'pages/team-member-change/team-member-change', 'pages/messages/messages', 'pages/feedback/feedback',
    'pages/feedback-history/feedback-history', 'pages/visits/visits', 'pages/profile/profile',
    'pages/login/login', 'pages/visitor-portfolio/visitor-portfolio', 'pages/visitor-schedule/visitor-schedule'
  ]
  const expectedTeamPages = [
    'portfolios', 'team-select/team-select', 'standard-edit/team-portfolio-standard-edit',
    'standard-preview/team-portfolio-standard-preview', 'contact-leads/team-contact-leads',
    'visitor-portfolio/team-visitor-portfolio'
  ]
  assert.deepEqual(appJson.pages, expectedMainPages)
  assert.deepEqual(appJson.subPackages.slice(0, 4).map((pkg) => pkg.name), ['mock', 'works', 'portfolio', 'schedule'])
  assert.deepEqual(appJson.subPackages[4], { name: 'teamPortfolio', root: 'pages/team-portfolios', pages: expectedTeamPages })
  assert.equal(appJson.subPackages[4].independent, undefined)
  assert.deepEqual(appJson.preloadRule['pages/portfolios/portfolios'], {
    network: 'all',
    packages: ['teamPortfolio']
  })
})

test('unused personal and team component library artifacts stay removed from packages', () => {
  const appJson = readJson('app.json')
  const routes = getRegisteredPageRoutes(appJson)
  const removedBases = [
    'pages/portfolios/component-library/portfolio-component-library',
    'pages/team-portfolios/component-library/team-portfolio-component-library'
  ]

  for (const removedBase of removedBases) {
    assert.equal(routes.includes(removedBase), false)
    for (const extension of PAGE_EXTENSIONS) {
      assert.equal(fs.existsSync(path.join(MINIAPP_ROOT, `${removedBase}${extension}`)), false)
    }
  }
  assert.equal(
    fs.existsSync(path.join(MINIAPP_ROOT, 'pages/team-portfolios/utils/team-portfolio-registry.js')),
    false
  )
})

test('tests stay excluded from the uploaded package', () => {
  const projectConfig = readJson('project.config.json')
  const ignoredFolders = (projectConfig.packOptions && projectConfig.packOptions.ignore || [])
    .filter((item) => item.type === 'folder')
    .map((item) => item.value)

  assert.ok(ignoredFolders.includes('tests'))
})

test('mock style imports resolve without crossing into another business subpackage', () => {
  const mockRoot = path.join(MINIAPP_ROOT, 'pages/mock')

  for (const wxssPath of listFiles(mockRoot, '.wxss')) {
    const source = fs.readFileSync(wxssPath, 'utf8')
    const imports = Array.from(source.matchAll(/@import\s+["']([^"']+)["']/g))
      .map((match) => match[1])

    for (const importedPath of imports) {
      const resolvedPath = path.resolve(path.dirname(wxssPath), importedPath)
      assert.equal(
        fs.existsSync(resolvedPath),
        true,
        `${path.relative(MINIAPP_ROOT, wxssPath)} imports missing ${importedPath}`
      )
      for (const businessRoot of BUSINESS_SUBPACKAGE_ROOTS) {
        const relativeToBusinessRoot = path.relative(businessRoot, resolvedPath)
        const isInsideBusinessRoot = relativeToBusinessRoot === '' || (
          !relativeToBusinessRoot.startsWith('..') && !path.isAbsolute(relativeToBusinessRoot)
        )
        assert.equal(
          isInsideBusinessRoot,
          false,
          `${path.relative(MINIAPP_ROOT, wxssPath)} crosses into ${path.relative(MINIAPP_ROOT, businessRoot)}`
        )
      }
    }
  }
})

test('mock custom components stay in the main package or mock subpackage', () => {
  const mockRoot = path.join(MINIAPP_ROOT, 'pages/mock')

  for (const jsonPath of listFiles(mockRoot, '.json')) {
    const pageConfig = JSON.parse(fs.readFileSync(jsonPath, 'utf8'))
    const componentPaths = Object.values(pageConfig.usingComponents || {})

    for (const componentPath of componentPaths) {
      const resolvedPath = componentPath.startsWith('/')
        ? path.join(MINIAPP_ROOT, componentPath.slice(1))
        : path.resolve(path.dirname(jsonPath), componentPath)
      for (const businessRoot of BUSINESS_SUBPACKAGE_ROOTS) {
        const relativeToBusinessRoot = path.relative(businessRoot, resolvedPath)
        const isInsideBusinessRoot = relativeToBusinessRoot === '' || (
          !relativeToBusinessRoot.startsWith('..') && !path.isAbsolute(relativeToBusinessRoot)
        )
        assert.equal(
          isInsideBusinessRoot,
          false,
          `${path.relative(MINIAPP_ROOT, jsonPath)} crosses into ${path.relative(MINIAPP_ROOT, businessRoot)}`
        )
      }
    }
  }
})

test('subpackage pages keep business-only JavaScript out of the main package', () => {
  const appJson = readJson('app.json')
  const packagesByName = Object.fromEntries(
    appJson.subPackages.map((pkg) => [pkg.name, pkg])
  )
  const mainUtilsRoot = path.join(MINIAPP_ROOT, 'utils')
  const businessOnlyNames = new Set(Object.values(PACKAGE_LOCAL_UTILS).flat())

  assert.equal(
    fs.existsSync(path.join(mainUtilsRoot, 'team-portfolio-list.js')),
    false,
    'team portfolio list utility should not be shipped in the main package'
  )
  assert.equal(
    fs.existsSync(path.join(mainUtilsRoot, 'portfolio-publish-disclaimer.js')),
    false,
    'portfolio publish disclaimer should not be shipped in the main package'
  )

  for (const [packageName, utilNames] of Object.entries(PACKAGE_LOCAL_UTILS)) {
    const packageRoot = path.join(MINIAPP_ROOT, packagesByName[packageName].root)
    for (const utilName of utilNames) {
      assert.equal(
        fs.existsSync(path.join(packageRoot, 'utils', utilName)),
        true,
        `${packageName} should own utils/${utilName}`
      )
    }
  }

  for (const pkg of appJson.subPackages) {
    const packageRoot = path.join(MINIAPP_ROOT, pkg.root)
    for (const jsPath of listFiles(packageRoot, '.js')) {
      const source = fs.readFileSync(jsPath, 'utf8')
      const requires = Array.from(source.matchAll(/require\(["']([^"']+)["']\)/g))
        .map((match) => match[1])
        .filter((requestPath) => requestPath.startsWith('.'))
      for (const requestPath of requires) {
        const resolvedPath = path.resolve(path.dirname(jsPath), `${requestPath}.js`)
        assert.equal(
          businessOnlyNames.has(path.basename(resolvedPath)) && path.dirname(resolvedPath) === mainUtilsRoot,
          false,
          `${path.relative(MINIAPP_ROOT, jsPath)} keeps ${path.basename(resolvedPath)} in the main package`
        )
      }
    }
  }
})

test('JavaScript dependencies respect main and subpackage boundaries', () => {
  const appJson = readJson('app.json')
  const packageRoots = appJson.subPackages.map((pkg) => ({
    name: pkg.name,
    root: path.join(MINIAPP_ROOT, pkg.root)
  }))
  const sourceRoots = ['pages', 'components', 'utils']
    .map((relativePath) => path.join(MINIAPP_ROOT, relativePath))

  function packageOwner(filePath) {
    const matchedPackage = packageRoots.find((pkg) => {
      const relativePath = path.relative(pkg.root, filePath)
      return relativePath === '' || (!relativePath.startsWith('..') && !path.isAbsolute(relativePath))
    })
    return matchedPackage ? matchedPackage.name : 'main'
  }

  for (const sourceRoot of sourceRoots) {
    for (const jsPath of listFiles(sourceRoot, '.js')) {
      const sourceOwner = packageOwner(jsPath)
      const source = fs.readFileSync(jsPath, 'utf8')
      const requires = Array.from(source.matchAll(/require\(["']([^"']+)["']\)/g))
        .map((match) => match[1])
        .filter((requestPath) => requestPath.startsWith('.'))

      for (const requestPath of requires) {
        const unresolvedPath = path.resolve(path.dirname(jsPath), requestPath)
        const candidates = [
          unresolvedPath,
          `${unresolvedPath}.js`,
          `${unresolvedPath}.json`,
          path.join(unresolvedPath, 'index.js')
        ]
        const resolvedPath = candidates.find((candidate) => fs.existsSync(candidate))
        assert.ok(resolvedPath, `${path.relative(MINIAPP_ROOT, jsPath)} cannot resolve ${requestPath}`)

        const targetOwner = packageOwner(resolvedPath)
        if (targetOwner === 'main') {
          continue
        }
        assert.equal(
          sourceOwner,
          targetOwner,
          `${path.relative(MINIAPP_ROOT, jsPath)} (${sourceOwner}) cannot depend on ${path.relative(MINIAPP_ROOT, resolvedPath)} (${targetOwner})`
        )
      }
    }
  }
})

test('main package JavaScript used by subpackages stays reachable from a main package production entry', () => {
  const appJson = readJson('app.json')
  const mainEntries = collectJavaScriptEntries(appJson.pages, true)
  const subpackageRoutes = appJson.subPackages.flatMap((pkg) => (
    pkg.pages.map((pageRoute) => `${pkg.root}/${pageRoute}`)
  ))
  const subpackageEntries = collectJavaScriptEntries(subpackageRoutes)
  const mainReachable = collectReachableJavaScript(mainEntries)
  const subpackageReachable = collectReachableJavaScript(subpackageEntries)
  const subpackageRoots = appJson.subPackages.map((pkg) => path.join(MINIAPP_ROOT, pkg.root))
  const subpackageOnlyMainJavaScript = Array.from(subpackageReachable)
    .filter((filePath) => (
      !subpackageRoots.some((subpackageRoot) => isInside(subpackageRoot, filePath)) &&
      !mainReachable.has(filePath)
    ))
    .map((filePath) => path.relative(MINIAPP_ROOT, filePath))
    .sort()

  assert.deepEqual(
    subpackageOnlyMainJavaScript,
    [],
    `move subpackage-only JavaScript under its subpackage root: ${subpackageOnlyMainJavaScript.join(', ')}`
  )
})

test('works utility copies stay byte-for-byte aligned across business subpackages', () => {
  const worksSource = fs.readFileSync(path.join(MINIAPP_ROOT, 'pages/works/utils/works.js'), 'utf8')
  const portfolioSource = fs.readFileSync(path.join(MINIAPP_ROOT, 'pages/portfolios/utils/works.js'), 'utf8')

  assert.equal(portfolioSource, worksSource)
})

test('work media utilities stay inside their business subpackages', () => {
  const mainUtilityPath = path.join(MINIAPP_ROOT, 'utils/work-media.js')
  const packageUtilityPaths = [
    'pages/works/utils/work-media.js',
    'pages/portfolios/utils/work-media.js',
    'pages/team-portfolios/utils/work-media.js'
  ].map((relativePath) => path.join(MINIAPP_ROOT, relativePath))

  assert.equal(fs.existsSync(mainUtilityPath), false, 'work media utility should not be shipped in the main package')
  packageUtilityPaths.forEach((utilityPath) => {
    assert.equal(fs.existsSync(utilityPath), true, `${path.relative(MINIAPP_ROOT, utilityPath)} should exist`)
  })
  const packageSources = packageUtilityPaths.map((utilityPath) => fs.readFileSync(utilityPath, 'utf8'))
  assert.equal(new Set(packageSources).size, 1, 'work media utility copies should stay aligned')
  const works = [
    { id: 1, mediaType: 'IMAGE' },
    { id: 2, mediaType: 'ANIMATION' }
  ]
  packageUtilityPaths.forEach((utilityPath) => {
    delete require.cache[require.resolve(utilityPath)]
    const { selectableWorksFor } = require(utilityPath)
    assert.deepEqual(
      selectableWorksFor('HYPERLINK', works),
      [],
      `${path.relative(MINIAPP_ROOT, utilityPath)} should not expose personal hyperlink rules`
    )
  })
})

test('team portfolio list utility copies stay byte-for-byte aligned across business subpackages', () => {
  const portfolioSource = fs.readFileSync(path.join(MINIAPP_ROOT, 'pages/portfolios/utils/team-portfolio-list.js'), 'utf8')
  const teamPortfolioSource = fs.readFileSync(path.join(MINIAPP_ROOT, 'pages/team-portfolios/utils/team-portfolio-list.js'), 'utf8')

  assert.equal(teamPortfolioSource, portfolioSource)
})

test('portfolio publish disclaimer copies stay byte-for-byte aligned across business subpackages', () => {
  const portfolioSource = fs.readFileSync(
    path.join(MINIAPP_ROOT, 'pages/portfolios/utils/portfolio-publish-disclaimer.js'),
    'utf8'
  )
  const teamPortfolioSource = fs.readFileSync(
    path.join(MINIAPP_ROOT, 'pages/team-portfolios/utils/portfolio-publish-disclaimer.js'),
    'utf8'
  )

  assert.equal(teamPortfolioSource, portfolioSource)
})

test('single-page mode utility copies stay byte-for-byte aligned across business subpackages', () => {
  const portfolioSource = fs.readFileSync(
    path.join(MINIAPP_ROOT, 'pages/portfolios/utils/single-page-mode.js'),
    'utf8'
  )
  const teamPortfolioSource = fs.readFileSync(
    path.join(MINIAPP_ROOT, 'pages/team-portfolios/utils/single-page-mode.js'),
    'utf8'
  )

  assert.equal(teamPortfolioSource, portfolioSource)
})
