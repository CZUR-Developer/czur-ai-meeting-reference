import assert from 'node:assert/strict';
import test from 'node:test';
import { readFinalTranscript, renderFinalResult, resultLinkExpired } from '../src/result.mjs';

test('结果链接按后端同一有效期在边界时刻失效', () => {
  assert.equal(resultLinkExpired('1000', 86400000, 1000 + 86400000 - 1), false);
  assert.equal(resultLinkExpired('1000', 86400000, 1000 + 86400000), true);
  assert.equal(resultLinkExpired('-1', 86400000, 2000), true);
  assert.equal(resultLinkExpired('9999', 86400000, 2000), true);
  assert.throws(() => resultLinkExpired('1000', undefined), /有效期未配置/);
});

test('生成中的结果完成后只读取最终转写', async () => {
  const requests = [];
  const responses = [
    { status: 202 },
    { status: 200, body: { title: '参考模拟会议', artifacts: [
      { type: 'transcript.final', status: 'READY', artifactId: 'art_final' },
      { type: 'translation.final', status: 'PENDING', artifactId: 'art_not_ready' },
      { type: 'summary.structured', status: 'PENDING', artifactId: 'art_summary_pending' }
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

test('只有 READY 结构化纪要时读取并展示纪要，不误报无字幕', async () => {
  const requests = [];
  const summary = { schemaVersion: 'client-summary/v1', artifactType: 'summary.structured',
    language: 'zh-CN', title: '项目讨论', overview: '确认下周方案。',
    keyPoints: ['需要保留现有流程'], actionItems: ['周五前完成评审'] };
  const fetcher = async url => {
    requests.push(url);
    return { ok: true, status: 200, json: async () => requests.length === 1
      ? { title: '会议', artifacts: [
        { type: 'summary.structured', status: 'READY', artifactId: 'art_summary' }
      ] } : summary };
  };
  const result = await readFinalTranscript('http://api.test', 'mtg_summary', '321', fetcher);
  assert.deepEqual(requests, [
    'http://api.test/v1/meetings/mtg_summary/manifest?genTime=321',
    'http://api.test/v1/artifacts/art_summary?genTime=321'
  ]);
  assert.deepEqual(result.summary, summary);

  const node = tagName => ({ tagName, hidden: true, textContent: '', children: [],
    append(...items) { this.children.push(...items); } });
  const nodes = Object.fromEntries([
    '#status', '#title', '#transcript', '#transcript-segments', '#result', '#translations',
    '#translation-result', '#summary-result', '#summary-content',
    '#presentation-result', '#presentation-frames'
  ].map(selector => [selector, node()]));
  renderFinalResult(result, { querySelector: selector => nodes[selector], createElement: node });
  assert.equal(nodes['#status'].textContent, '结果已生成');
  assert.equal(nodes['#result'].hidden, true);
  assert.equal(nodes['#translation-result'].hidden, true);
  assert.equal(nodes['#presentation-result'].hidden, true);
  assert.equal(nodes['#summary-result'].hidden, false);
  assert.deepEqual(nodes['#summary-content'].children.map(item =>
    [item.tagName, item.textContent || item.children.map(child => child.textContent)]), [
    ['h3', '项目讨论'], ['p', '确认下周方案。'],
    ['h4', '关键要点'], ['ul', ['需要保留现有流程']],
    ['h4', '待办事项'], ['ul', ['周五前完成评审']]
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

test('READY 演示截图包按修订顺序读取，包内画面顺序保持不变', async () => {
  const requests = [];
  const content = {
    art_capture_1: { schemaVersion: 'presentation-capture/v1', chunkSequence: 1,
      frames: [
        { offsetMs: 0, jpegBase64: '/9j/2Q==' },
        { offsetMs: 10000, jpegBase64: '/9j/2Q==' }
      ] },
    art_capture_2: { schemaVersion: 'presentation-capture/v1', chunkSequence: 2,
      frames: [{ offsetMs: 20000, jpegBase64: '/9j/2Q==' }] }
  };
  const manifest = { title: '演示会议', artifacts: [
    { type: 'presentation.capture', status: 'READY', artifactId: 'art_capture_2', revision: 2 },
    { type: 'presentation.capture', status: 'PENDING', artifactId: 'art_pending', revision: 3 },
    { type: 'presentation.capture', status: 'READY', artifactId: 'art_capture_1', revision: 1 }
  ] };
  const fetcher = async url => {
    requests.push(url);
    const artifactId = url.match(/\/v1\/artifacts\/([^?]+)/)?.[1];
    return { ok: true, status: 200,
      json: async () => artifactId ? content[artifactId] : manifest };
  };
  const result = await readFinalTranscript('http://api.test', 'mtg_presentation', '789', fetcher);
  assert.deepEqual(result.presentationFrames.map(frame => frame.offsetMs), [0, 10000, 20000]);
  assert.deepEqual(requests, [
    'http://api.test/v1/meetings/mtg_presentation/manifest?genTime=789',
    'http://api.test/v1/artifacts/art_capture_1?genTime=789',
    'http://api.test/v1/artifacts/art_capture_2?genTime=789'
  ]);
});

test('本场发言人随最终字幕产物读取，逐段展示真实 ID 且缺失时显示未知', async () => {
  const requests = [];
  const manifest = { title: '发言人会议', artifacts: [
    { type: 'transcript.final', status: 'READY', artifactId: 'art_speaker' }
  ] };
  const artifact = { schemaVersion: 'client-transcript/v1', artifactType: 'transcript.final',
    text: '你好。\n继续。', segments: [
      { segmentId: 'seg-1', text: '你好。', sessionSpeakerId: 'speaker-2' },
      { segmentId: 'seg-2', text: '继续。' }
    ] };
  const fetcher = async url => {
    requests.push(url);
    return { ok: true, status: 200,
      json: async () => requests.length === 1 ? manifest : artifact };
  };
  const result = await readFinalTranscript('http://api.test', 'mtg_speaker', '123', fetcher);
  assert.deepEqual(requests, [
    'http://api.test/v1/meetings/mtg_speaker/manifest?genTime=123',
    'http://api.test/v1/artifacts/art_speaker?genTime=123'
  ]);
  assert.deepEqual(result.segments, artifact.segments);

  const node = () => ({ hidden: true, textContent: '', children: [],
    append(...items) { this.children.push(...items); } });
  const nodes = Object.fromEntries([
    '#status', '#title', '#transcript', '#transcript-segments', '#result',
    '#translations', '#translation-result', '#presentation-result', '#presentation-frames'
  ].map(selector => [selector, node()]));
  renderFinalResult(result, { querySelector: selector => nodes[selector], createElement: node });
  assert.equal(nodes['#result'].hidden, false);
  assert.equal(nodes['#transcript'].hidden, true);
  assert.equal(nodes['#transcript-segments'].hidden, false);
  assert.deepEqual(nodes['#transcript-segments'].children.map(row =>
    [row.children[0].textContent, row.children[1].textContent]), [
    ['本场发言人 ID：speaker-2', '你好。'], ['发言人未知', '继续。']
  ]);
  assert.equal(nodes['#status'].textContent, '结果已生成');
});

test('没有真实发言人 ID 的分段产物仍展示原有纯文字', () => {
  const node = () => ({ hidden: true, textContent: '', children: [],
    append(...items) { this.children.push(...items); } });
  const nodes = Object.fromEntries([
    '#status', '#title', '#transcript', '#transcript-segments', '#result',
    '#translations', '#translation-result', '#presentation-result', '#presentation-frames'
  ].map(selector => [selector, node()]));
  renderFinalResult({ text: '你好。\n继续。', segments: [
    { text: '你好。' }, { text: '继续。', sessionSpeakerId: '  ' }
  ] }, { querySelector: selector => nodes[selector], createElement: node });
  assert.equal(nodes['#transcript'].textContent, '你好。\n继续。');
  assert.equal(nodes['#transcript'].hidden, false);
  assert.equal(nodes['#transcript-segments'].hidden, true);
});

test('纯字幕隐藏译文区，双向译文显示两个语言方向', () => {
  const node = () => ({ hidden: true, textContent: '', children: [],
    append(...items) { this.children.push(...items); } });
  const page = () => {
    const nodes = Object.fromEntries([
      '#status', '#title', '#transcript', '#transcript-segments', '#result', '#translations',
      '#translation-result', '#presentation-result', '#presentation-frames'
    ].map(selector => [selector, node()]));
    return { nodes, querySelector: selector => nodes[selector], createElement: node };
  };
  const transcriptOnly = page();
  renderFinalResult({ title: '字幕会议', text: '今天开会。' }, transcriptOnly);
  assert.equal(transcriptOnly.nodes['#result'].hidden, false);
  assert.equal(transcriptOnly.nodes['#translation-result'].hidden, true);
  assert.equal(transcriptOnly.nodes['#presentation-result'].hidden, true);
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

test('只有演示画面也显示结果，字幕与译文缺席不误报空会议', () => {
  const node = () => ({ hidden: true, textContent: '', children: [],
    append(...items) { this.children.push(...items); } });
  const nodes = Object.fromEntries([
    '#status', '#title', '#transcript', '#transcript-segments', '#result', '#translations',
    '#translation-result', '#presentation-result', '#presentation-frames'
  ].map(selector => [selector, node()]));
  const page = { querySelector: selector => nodes[selector], createElement: node };
  renderFinalResult({ title: '演示会议', text: null, presentationFrames: [
    { offsetMs: 65000, jpegBase64: '/9j/2Q==' }
  ] }, page);
  assert.equal(nodes['#status'].textContent, '结果已生成');
  assert.equal(nodes['#result'].hidden, true);
  assert.equal(nodes['#translation-result'].hidden, true);
  assert.equal(nodes['#presentation-result'].hidden, false);
  const figure = nodes['#presentation-frames'].children[0];
  assert.equal(figure.children[0].src, 'data:image/jpeg;base64,/9j/2Q==');
  assert.equal(figure.children[0].alt, '会议 01:05 的演示画面');
  assert.equal(figure.children[1].textContent, '会议 01:05');
});

test('结构化纪要与字幕、译文和演示画面正交展示，缺席时模块仍隐藏', () => {
  const node = () => ({ hidden: true, textContent: '', children: [],
    append(...items) { this.children.push(...items); } });
  const page = () => {
    const nodes = Object.fromEntries([
      '#status', '#title', '#transcript', '#transcript-segments', '#result', '#translations',
      '#translation-result', '#summary-result', '#summary-content',
      '#presentation-result', '#presentation-frames'
    ].map(selector => [selector, node()]));
    return { nodes, querySelector: selector => nodes[selector], createElement: node };
  };
  const withoutSummary = page();
  renderFinalResult({ text: '原文', translations: [
    { sourceLanguage: 'zh-CN', targetLanguage: 'en-US', text: 'Translation' }
  ], presentationFrames: [{ offsetMs: 0, jpegBase64: '/9j/2Q==' }] }, withoutSummary);
  assert.equal(withoutSummary.nodes['#summary-result'].hidden, true);
  assert.equal(withoutSummary.nodes['#result'].hidden, false);
  assert.equal(withoutSummary.nodes['#translation-result'].hidden, false);
  assert.equal(withoutSummary.nodes['#presentation-result'].hidden, false);

  const withSummary = page();
  renderFinalResult({ text: '原文', translations: [
    { sourceLanguage: 'zh-CN', targetLanguage: 'en-US', text: 'Translation' }
  ], presentationFrames: [{ offsetMs: 0, jpegBase64: '/9j/2Q==' }],
  summary: { title: '纪要标题', overview: '概述', keyPoints: [], actionItems: [] }
  }, withSummary);
  assert.equal(withSummary.nodes['#summary-result'].hidden, false);
  assert.equal(withSummary.nodes['#result'].hidden, false);
  assert.equal(withSummary.nodes['#translation-result'].hidden, false);
  assert.equal(withSummary.nodes['#presentation-result'].hidden, false);
  assert.deepEqual(withSummary.nodes['#summary-content'].children.map(item => item.textContent),
    ['纪要标题', '概述']);
});
