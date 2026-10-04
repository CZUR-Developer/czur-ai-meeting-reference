import assert from 'node:assert/strict';
import test from 'node:test';
import { readFinalTranscript } from '../src/result.mjs';

test('生成中的结果完成后只读取最终转写', async () => {
  const requests = [];
  const responses = [
    { status: 202 },
    { status: 200, body: { title: '参考模拟会议', artifacts: [
      { type: 'transcript.final', status: 'READY', artifactId: 'art_final' }
    ] } },
    { status: 200, body: { text: '今天我们讨论 AI 会议纪要。' } }
  ];
  let pending = 0;
  const fetcher = async url => {
    requests.push(url);
    const next = responses.shift();
    return { ok: next.status === 200, status: next.status,
      json: async () => next.body };
  };
  const result = await readFinalTranscript('http://api.test/', 'mtg_demo', '12345',
    fetcher, async () => {}, () => { pending += 1; });
  assert.deepEqual(result, { title: '参考模拟会议',
    text: '今天我们讨论 AI 会议纪要。' });
  assert.equal(pending, 1);
  assert.deepEqual(requests, [
    'http://api.test/v1/meetings/mtg_demo/manifest?genTime=12345',
    'http://api.test/v1/meetings/mtg_demo/manifest?genTime=12345',
    'http://api.test/v1/artifacts/art_final?genTime=12345'
  ]);
});

test('无字幕结果只读取空产物清单，不请求不存在的正文', async () => {
  const requests = [];
  const fetcher = async url => {
    requests.push(url);
    return { ok: true, status: 200,
      json: async () => ({ title: null, artifacts: [] }) };
  };
  const result = await readFinalTranscript('http://api.test', 'mtg_empty', '12345',
    fetcher);
  assert.deepEqual(result, { title: null, text: null });
  assert.deepEqual(requests,
    ['http://api.test/v1/meetings/mtg_empty/manifest?genTime=12345']);
});
