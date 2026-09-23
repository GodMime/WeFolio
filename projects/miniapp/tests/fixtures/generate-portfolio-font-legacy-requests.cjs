// 从固定旧提交执行真实归一化和编辑白名单，生成兼容请求夹具；在仓库根目录运行。
const cp = require('node:child_process'), vm = require('node:vm'), path = require('node:path'), fs = require('node:fs'), crypto = require('node:crypto')
const root = path.resolve(__dirname, '../../../..'); process.chdir(root)
const base = '971336c90e1421453047df57dd6db330047f26df', sources = {}, cache = {}
function load(file) {
  file = path.posix.normalize(file.endsWith('.js') ? file : file + '.js')
  if (cache[file]) return cache[file].exports
  let source = cp.execFileSync('git', ['show', `${base}:${file}`], { encoding: 'utf8' })
  sources[file] = crypto.createHash('sha256').update(source).digest('hex')
  if (file.includes('/standard-edit/')) source += '\nmodule.exports = { buildTextSectionForm }'
  const module = { exports: {} }; cache[file] = module
  vm.runInNewContext(`(function(require,module,exports){${source}\n})`, { wx: {}, Page() {}, console, Date, Math, setTimeout, clearTimeout })(relative => load(path.posix.join(path.posix.dirname(file), relative)), module, module.exports)
  return module.exports
}
(async () => {
const files = {}
for (const pkg of ['portfolios','team-portfolios']) {
 const api = load(`projects/miniapp/pages/${pkg}/utils/${pkg === 'portfolios' ? 'portfolios' : 'team-portfolios'}.js`)
 const grid = load(`projects/miniapp/pages/${pkg}/utils/portfolio-text-grid.js`).createTextGrid(1,1)
 const section = { content: '旧端保存', fontFamily: 'SYSTEM', fontId: 'ALLURA' }
 grid.cells[0].blocks[0].runs[0] = { ...grid.cells[0].blocks[0].runs[0], text: '旧端网格', fontId: 'ALLURA' }
 const config = { fonts: { ALLURA: { fontVersion: 'legacy-fixed' } }, components: [
  { componentKey:'plain', componentType:'TEXT_SECTION', config: section },
  { componentKey:'structured', componentType:'STRUCTURED_TEXT_SECTION', config:{ blocks:[{blockKey:'title',type:'TITLE',content:'旧端标题',fontId:'ALLURA'}] } },
  { componentKey:'grid', componentType:'TEXT_GRID', config:grid }
 ] }
 const normalized = (api.normalizePortfolioConfig || api.normalizeTeamPortfolioConfig)(config)
 let payload
 if (pkg === 'portfolios') payload = api.buildDraftPayload(normalized,7,'legacy-font-contract')
 else await api.saveTeamPortfolioDraft(options => { payload = options.data; return Promise.resolve({}) },1,normalized,7,'legacy-font-contract')
 files[pkg] = { untouched: JSON.parse(JSON.stringify(payload)) }
 const editor = load(`projects/miniapp/pages/${pkg}/standard-edit/${pkg === 'portfolios' ? 'portfolio' : 'team-portfolio'}-standard-edit.js`)
 const sections = load(`projects/miniapp/pages/${pkg}/utils/portfolio-text-sections.js`)
 normalized.components[0].config = editor.buildTextSectionForm(normalized.components[0].config)
 normalized.components[1].config = sections.finalizeStructuredTextConfig(normalized.components[1].config)
 // 普通编辑表单和结构化完成操作使用旧白名单；网格仍以旧副本透传。
 if (pkg === 'portfolios') payload = api.buildDraftPayload(normalized,7,'legacy-font-edited-contract')
 else await api.saveTeamPortfolioDraft(options => { payload = options.data; return Promise.resolve({}) },1,normalized,7,'legacy-font-edited-contract')
 files[pkg].edited = payload
}
fs.mkdirSync('projects/miniapp/tests/fixtures', {recursive:true})
fs.writeFileSync('projects/miniapp/tests/fixtures/portfolio-font-legacy-requests.json', JSON.stringify({ sourceCommit:base, sourceSha256:sources, requests:files }, null, 2)+'\n')
})()
