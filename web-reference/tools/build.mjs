import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// 一步构建：复制静态资源，并在同一步生成 dist/config.json。
// 后端地址来自仓外本地配置（默认 deployment.local.json，可用 REFERENCE_WEB_DEPLOYMENT_FILE
// 指定另一份，用来区分测试与生产）；缺失或没填时构建直接失败，不产出连不上后端的页面。
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

// 两种构建模式，产物语义不同：
//   默认：部署构建，必须拿到真实 apiBaseUrl，缺失或占位就失败，不产出跑不起来的页面。
//   --check：只验证构建流程本身（CI／本机自检用）。输出到 dist-check，地址是固定测试值，
//            明确告知不可部署；这样既不会把坏产物当成交付物，也不会让 CI 依赖仓外配置。
const checkMode = process.argv.includes('--check');
const output = resolve(root, checkMode ? 'dist-check' : 'dist');
const CHECK_API_BASE_URL = 'http://127.0.0.1:8080';

const configFileName = (process.env.REFERENCE_WEB_DEPLOYMENT_FILE || '').trim() || 'deployment.local.json';
const configPath = resolve(root, configFileName);
// 直接给值的入口：本机脚本和 CI 不必先落一个文件；优先级高于文件。
const directApiBaseUrl = (process.env.REFERENCE_API_BASE_URL || '').trim();

function fail(reason) {
  console.error(`WEB_CONFIG_MISSING ${reason}`);
  console.error(`文件：${configPath}`);
  console.error('');
  console.error('请先复制模板并填写：');
  console.error('  cp web-reference/deployment.local.example.json web-reference/deployment.local.json');
  console.error('需要 apiBaseUrl（设备浏览器可达的后端根地址）。');
  console.error('多环境可保留多个文件，用 REFERENCE_WEB_DEPLOYMENT_FILE=deployment.prod.json 选择。');
  console.error('');
  console.error('详见 docs/partner-getting-started.md 第 3.3 节「两个本地配置文件」。');
  process.exit(1);
}

let apiBaseUrl = checkMode ? CHECK_API_BASE_URL : directApiBaseUrl;
if (!checkMode && !apiBaseUrl) {
  try {
    const raw = await readFile(configPath, 'utf8');
    apiBaseUrl = (JSON.parse(raw).apiBaseUrl || '').trim();
  } catch (error) {
    fail('文件不存在或不是合法 JSON');
  }
}
if (!apiBaseUrl) fail('apiBaseUrl 为空');
let apiUrl;
try {
  apiUrl = new URL(apiBaseUrl);
} catch (error) {
  fail('apiBaseUrl 不是合法 URL');
}
if (apiUrl.protocol !== 'http:' && apiUrl.protocol !== 'https:') {
  fail('apiBaseUrl 必须是 http/https 地址');
}
const apiHost = apiUrl.hostname.toLowerCase();
if (!checkMode && (apiHost.includes('your-backend') || apiHost.endsWith('.example')
    || apiHost === 'example.com' || apiHost.endsWith('.invalid'))) {
  fail('apiBaseUrl 还是模板或测试占位值');
}

await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });
await cp(resolve(root, 'src'), output, { recursive: true });
await writeFile(resolve(output, 'config.json'),
  JSON.stringify({ apiBaseUrl }) + '\n', 'utf8');
if (checkMode) {
  console.warn('WEB_BUILD_CHECK 仅供自检：地址是固定测试值，产物不可部署；');
  console.warn('  要出可部署产物，请配置 ' + configFileName + ' 或 REFERENCE_API_BASE_URL 后执行 npm run build。');
  console.log(`WEB_BUILD_CHECK_OK output=${output}`);
} else {
  console.log(`WEB_CONFIG ${directApiBaseUrl ? 'REFERENCE_API_BASE_URL' : configFileName} apiBaseUrl=${apiBaseUrl}`);
  console.log(`WEB_BUILD_OK output=${output}`);
}
