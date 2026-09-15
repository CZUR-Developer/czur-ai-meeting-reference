import { cp, mkdir, rm } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// 构建只复制静态资源，不嵌入后端地址、凭据或模型实现；部署时由宿主系统
// 注入 config.json，使同一份 Web 产物可以连接不同合作伙伴后端。
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const output = resolve(root, 'dist');
await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });
await cp(resolve(root, 'src'), output, { recursive: true });
console.log(`WEB_BUILD_OK output=${output}`);
