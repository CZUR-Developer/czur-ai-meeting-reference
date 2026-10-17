import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, chmodSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const deploy = dirname(fileURLToPath(import.meta.url));

/** 只替换 mysql 客户端，验证配置注入和脚本闭环；不连接数据库，不执行 DDL。 */
function fixture() {
  const directory = mkdtempSync(join(tmpdir(), 'reference-migrate-'));
  const mysql = join(directory, 'mysql-fixture');
  const configuration = join(directory, 'reference.env');
  writeFileSync(mysql, `#!/usr/bin/env node
const args = process.argv.slice(2);
if (!args.includes('--host=127.0.0.1') || !args.includes('--port=3307')
    || !args.includes('--database=reference_script') || !args.includes('--user=reference_fixture')
    || process.env.MYSQL_PWD !== 'fixture-only password') process.exit(2);
// 模拟已完整应用 V001，测试不会把真实初始化 SQL 交给任何外部依赖。
if (args.includes('--execute')) process.stdout.write('1\\n');
`);
  chmodSync(mysql, 0o700);
  const values = {
    DATABASE_URL: 'jdbc:mysql://127.0.0.1:3307/reference_script?useSSL=false',
    DATABASE_USER: 'reference_fixture',
    DATABASE_PASSWORD: 'fixture-only password',
  };
  writeFileSync(configuration, Object.entries(values).map(([key, value]) => `${key}='${value}'`).join('\n'));
  const environment = { ...process.env, MYSQL_CLIENT: mysql };
  delete environment.DATABASE_URL;
  delete environment.DATABASE_USER;
  delete environment.DATABASE_PASSWORD;
  return { directory, configuration, environment, values };
}

for (const script of ['migrate.sh', 'healthcheck-db.sh']) {
  test(`${script} 在干净数据库环境中显式加载同一配置文件`, () => {
    const data = fixture();
    try {
      const result = spawnSync('bash', [join(deploy, script), '--env-file', data.configuration], {
        env: data.environment, encoding: 'utf8',
      });
      assert.equal(result.status, 0, result.stderr + result.stdout);
      assert.match(result.stdout, /\[INFO\]/);
      assert.ok(!result.stdout.includes(data.values.DATABASE_PASSWORD));
    } finally {
      rmSync(data.directory, { recursive: true, force: true });
    }
  });

  test(`${script} 保持原有已导出环境变量入口`, () => {
    const data = fixture();
    try {
      const result = spawnSync('bash', [join(deploy, script)], {
        env: { ...data.environment, ...data.values }, encoding: 'utf8',
      });
      assert.equal(result.status, 0, result.stderr + result.stdout);
      assert.match(result.stdout, /\[INFO\]/);
    } finally {
      rmSync(data.directory, { recursive: true, force: true });
    }
  });
}
