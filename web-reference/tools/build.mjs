import { cp, mkdir, rm, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// 构建只复制静态资源，不嵌入后端地址、凭据或模型实现；部署时由宿主系统
// 注入 config.json，使同一份 Web 产物可以连接不同合作伙伴后端。
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const output = resolve(root, 'dist');
await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });
await cp(resolve(root, 'src'), output, { recursive: true });

// 部署约定仍是"产物不带地址，由宿主注入 config.json"；但本机反复构建时手放的文件
// 会被上面的 rm 冲掉，所以这里支持两种可选的本地注入，都优先于"不生成"：
//   1. REFERENCE_API_BASE_URL 环境变量（适合部署脚本/CI）
//   2. web-reference/config.local.json（本地开发，已被 Git 忽略）
const apiBaseUrl = (process.env.REFERENCE_API_BASE_URL || '').trim();
const localConfig = resolve(root, 'config.local.json');
if (apiBaseUrl) {
  await writeFile(resolve(output, 'config.json'),
    JSON.stringify({ apiBaseUrl }) + '\n', 'utf8');
  console.log(`WEB_CONFIG_INJECTED source=env apiBaseUrl=${apiBaseUrl}`);
} else if (existsSync(localConfig)) {
  await cp(localConfig, resolve(output, 'config.json'));
  console.log('WEB_CONFIG_INJECTED source=config.local.json');
}
console.log(`WEB_BUILD_OK output=${output}`);
