import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// 一步构建：复制静态资源，并在同一步生成 dist/config.json。
// 后端地址来自仓外本地配置（默认 deployment.local.json，可用 REFERENCE_WEB_DEPLOYMENT_FILE
// 指定另一份，用来区分测试与生产）；缺失或没填时构建直接失败，不产出连不上后端的页面。
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const output = resolve(root, 'dist');

const configFileName = (process.env.REFERENCE_WEB_DEPLOYMENT_FILE || '').trim() || 'deployment.local.json';
const configPath = resolve(root, configFileName);

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

let apiBaseUrl;
try {
  const raw = await readFile(configPath, 'utf8');
  apiBaseUrl = (JSON.parse(raw).apiBaseUrl || '').trim();
} catch (error) {
  fail('文件不存在或不是合法 JSON');
}
if (!apiBaseUrl) fail('apiBaseUrl 为空');
if (apiBaseUrl.includes('your-backend.example')) fail('apiBaseUrl 还是模板里的占位值');

await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });
await cp(resolve(root, 'src'), output, { recursive: true });
await writeFile(resolve(output, 'config.json'),
  JSON.stringify({ apiBaseUrl }) + '\n', 'utf8');
console.log(`WEB_CONFIG ${configFileName} apiBaseUrl=${apiBaseUrl}`);
console.log(`WEB_BUILD_OK output=${output}`);
