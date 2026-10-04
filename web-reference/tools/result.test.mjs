import assert from 'node:assert/strict';
import test from 'node:test';
import { readFinalTranscript, renderFinalResult } from '../src/result.mjs';

test('生成中的结果完成后只读取最终转写', async () => {
  const requests = [];
  const responses = [
    { status: 202 },
    { status: 200, body: { title: '参考模拟会议', artifacts: [
      { type: 'transcript.final', status: 'READY', artifactId: 'art_final' },
      { type: 'translation.final', status: 'PENDING', artifactId: 'art_not_ready' }
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

test('READY 译文按原产物顺序展示双向语言，纯字幕请求链不变', async () => {
  const requests = [];
  const responses = [
    { title: '双语会议', artifacts: [
      { type: 'transcript.final', status: 'READY', artifactId: 'art_transcript' },
      { type: 'translation.final', status: 'READY', artifactId: 'art_translation' }
    ] },
    { text: '你好\nGood morning' },
    { schemaVersion: 'client-translation/v1', artifactType: 'translation.final',
      language: 'mul', translations: [
        { translationId: 'tr_1', sourceSegmentId: 'seg_zh',
          sourceLanguage: 'zh-CN', targetLanguage: 'en-US', text: 'Hello' },
        { translationId: 'tr_2', sourceSegmentId: 'seg_en',
          sourceLanguage: 'en-US', targetLanguage: 'zh-CN', text: '早上好' }
      ] }
  ];
  const fetcher = async url => {
    requests.push(url);
    return { ok: true, status: 200, json: async () => responses.shift() };
  };
  const result = await readFinalTranscript('http://api.test', 'mtg_bilingual', '456', fetcher);
  assert.equal(result.text, '你好\nGood morning');
  assert.deepEqual(result.translations.map(item =>
    [item.sourceLanguage, item.targetLanguage, item.text]), [
    ['zh-CN', 'en-US', 'Hello'], ['en-US', 'zh-CN', '早上好']
  ]);
  assert.deepEqual(requests, [
    'http://api.test/v1/meetings/mtg_bilingual/manifest?genTime=456',
    'http://api.test/v1/artifacts/art_transcript?genTime=456',
    'http://api.test/v1/artifacts/art_translation?genTime=456'
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

test('纯字幕隐藏译文区，双向译文显示两个语言方向', () => {
  const node = () => ({ hidden: true, textContent: '', children: [],
    append(...items) { this.children.push(...items); } });
  const page = () => {
    const nodes = Object.fromEntries([
      '#status', '#title', '#transcript', '#result', '#translations',
      '#translation-result'
    ].map(selector => [selector, node()]));
    return { nodes, querySelector: selector => nodes[selector], createElement: node };
  };
  const transcriptOnly = page();
  renderFinalResult({ title: '字幕会议', text: '今天开会。' }, transcriptOnly);
  assert.equal(transcriptOnly.nodes['#result'].hidden, false);
  assert.equal(transcriptOnly.nodes['#translation-result'].hidden, true);
  assert.equal(transcriptOnly.nodes['#transcript'].textContent, '今天开会。');

  const bilingual = page();
  renderFinalResult({ title: '双语会议', text: '你好\nGood morning', translations: [
    { sourceLanguage: 'zh-CN', targetLanguage: 'en-US', text: 'Hello' },
    { sourceLanguage: 'en-US', targetLanguage: 'zh-CN', text: '早上好' }
  ] }, bilingual);
  assert.equal(bilingual.nodes['#translation-result'].hidden, false);
  assert.deepEqual(bilingual.nodes['#translations'].children.map(row =>
    [row.children[0].textContent, row.children[1].textContent]), [
    ['中文 → English', 'Hello'], ['English → 中文', '早上好']
  ]);
});
