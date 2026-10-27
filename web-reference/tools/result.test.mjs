import assert from 'node:assert/strict';
import test from 'node:test';
import { createSpeakerIdentityActions, readFinalTranscript, renderFinalResult,
  resultLinkExpired, speakerEditingAllowed } from '../src/result.mjs';

/** 人物注册后会重绘同一页；textContent 赋值像浏览器 DOM 一样清除原有子节点。 */
function identityTestPage() {
  const node = tagName => ({ tagName, hidden: true, children: [], value: '',
    get textContent() { return this.text || ''; },
    set textContent(value) { this.text = value; this.children = []; },
    append(...items) { this.children.push(...items); } });
  const nodes = Object.fromEntries(['#status', '#title', '#transcript', '#transcript-segments',
    '#result', '#translations', '#translation-result', '#summary-result', '#summary-content',
    '#presentation-result', '#presentation-frames', '#audio-result', '#audio-player']
    .map(selector => [selector, node()]));
  return { nodes, querySelector: selector => nodes[selector], createElement: node };
}

/** 含清单状态／告警区和可承载姓名按钮 dataset 的最小结果页。 */
function noticesTestPage() {
  const node = tagName => ({ tagName, hidden: true, children: [], dataset: {}, value: '',
    get textContent() { return this.text || ''; },
    set textContent(value) { this.text = value; this.children = []; },
    append(...items) { this.children.push(...items); },
    setAttribute() {} });
  const nodes = Object.fromEntries(['#status', '#title', '#result-status', '#result-warnings',
    '#transcript', '#transcript-segments', '#result', '#translations', '#translation-result',
    '#summary-result', '#summary-content', '#presentation-result', '#presentation-frames',
    '#audio-result', '#audio-player'].map(selector => [selector, node()]));
  return { nodes, querySelector: selector => nodes[selector], createElement: node };
}

const identityArtifact = speakers => ({ schemaVersion: 'speaker-identity/v1',
  artifactType: 'speaker.identity', speakers });

test('标准会议时间来自Manifest，不用链接生成时间替代', async () => {
  const result = await readFinalTranscript('http://api.test', 'mtg_time', '123', async () => ({
    ok: true, status: 200, json: async () => ({ startedAt: '2026-10-20T00:00:00Z', durationMs: 42000, artifacts: [] })
  }));
  assert.equal(result.startedAt, '2026-10-20T00:00:00Z');
  assert.equal(result.durationMs, 42000);
});

test('时间段落关联多语译文，未匹配译文独立保留', () => {
  const page = identityTestPage();
  renderFinalResult({ segments: [{ segmentId: 'a', startMs: 1500, text: '你好' }], translations: [
    { sourceSegmentId: 'b', text: '独立译文' }, { sourceSegmentId: 'a', text: 'Hello' },
    { sourceSegmentId: 'a', text: 'こんにちは' }
  ] }, page);
  const row = page.nodes['#transcript-segments'].children[0];
  assert.equal(row.children[2].textContent, '00:01');
  assert.deepEqual(row.children.slice(3).map(item => item.children[1].textContent), ['Hello', 'こんにちは']);
  assert.equal(page.nodes['#translations'].children[0].children[1].textContent, '独立译文');
  assert.equal(page.nodes['#translation-result'].hidden, false);
});

test('结果链接按后端同一有效期在边界时刻失效', () => {
  assert.equal(resultLinkExpired('1000', 86400000, 1000 + 86400000 - 1), false);
  assert.equal(resultLinkExpired('1000', 86400000, 1000 + 86400000), true);
  assert.equal(resultLinkExpired('-1', 86400000, 2000), false);
  assert.equal(resultLinkExpired('9999', 86400000, 2000), false);
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

test('READY 原始录音只生成流式 Artifact 地址并显示播放器', async () => {
  const requests = [];
  const fetcher = async url => {
    requests.push(url);
    return { ok: true, status: 200, json: async () => ({ title: '录音会议', artifacts: [
      { type: 'audio.original', status: 'READY', artifactId: 'art_audio',
        mediaType: 'audio/ogg', size: 32000 }
    ] }) };
  };
  const result = await readFinalTranscript('http://api.test', 'mtg_audio', '123', fetcher);
  assert.deepEqual(result.audio, {
    artifactId: 'art_audio', mediaType: 'audio/ogg', size: 32000,
    url: 'http://api.test/v1/artifacts/art_audio?genTime=123'
  });
  assert.deepEqual(requests, [
    'http://api.test/v1/meetings/mtg_audio/manifest?genTime=123'
  ]);
  const node = () => ({ hidden: true, textContent: '', children: [],
    append(...items) { this.children.push(...items); } });
  const nodes = Object.fromEntries([
    '#status', '#title', '#transcript', '#transcript-segments', '#result', '#translations',
    '#translation-result', '#summary-result', '#summary-content', '#presentation-result',
    '#presentation-frames', '#audio-result', '#audio-player'
  ].map(selector => [selector, node()]));
  renderFinalResult(result, { querySelector: selector => nodes[selector], createElement: node });
  assert.equal(nodes['#audio-result'].hidden, false);
  assert.equal(nodes['#audio-player'].src, result.audio.url);
  assert.equal(nodes['#audio-player'].controls, true);
  assert.equal(nodes['#audio-player'].preload, 'metadata');
  assert.equal(nodes['#status'].textContent, '结果已生成');
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
    ['发言人 speaker-2', '你好。'], ['发言人未知', '继续。']
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

test('READY 标准人物产物按本场 ID 映射字幕名称，稳定人物 ID 不冒充本场编号', async () => {
  const requests = [];
  const transcript = { text: '你好。\n继续。', segments: [
    { text: '你好。', sessionSpeakerId: 'speaker-2' },
    { text: '继续。', sessionSpeakerId: 'speaker-1' }
  ] };
  const identities = identityArtifact([
    { sessionSpeakerId: 'speaker-1', personId: null, displayName: null },
    { sessionSpeakerId: 'speaker-2', personId: 'person_stable', displayName: '陈先生' }
  ]);
  const responses = [{ title: '人物会议', artifacts: [
    { type: 'transcript.final', status: 'READY', artifactId: 'art_transcript' },
    { type: 'speaker.identity', status: 'READY', artifactId: 'art_identity' }
  ] }, transcript, identities];
  const fetcher = async url => {
    requests.push(url);
    return { ok: true, status: 200, json: async () => responses.shift() };
  };
  const result = await readFinalTranscript('http://api.test/', 'mtg_people', '123', fetcher);
  assert.deepEqual(requests, ['http://api.test/v1/meetings/mtg_people/manifest?genTime=123',
    'http://api.test/v1/artifacts/art_transcript?genTime=123',
    'http://api.test/v1/artifacts/art_identity?genTime=123']);
  const page = identityTestPage();
  renderFinalResult(result, page);
  assert.deepEqual(page.nodes['#transcript-segments'].children.map(row => row.children[0].textContent),
    ['陈先生', '发言人 speaker-1']);
  assert.equal(page.querySelector('#speaker-identity-result'), undefined, '默认页面不需要人物栏目');
});

test('无 READY 人物产物时保留本场字幕，不读取身份或显示命名注册区', async () => {
  const requests = [];
  const responses = [{ title: '只有本场发言人', artifacts: [
    { type: 'transcript.final', status: 'READY', artifactId: 'art_transcript' },
    { type: 'speaker.identity', status: 'PENDING', artifactId: 'art_not_ready' }
  ] }, { text: '原文', segments: [{ text: '原文', sessionSpeakerId: 'speaker-1' }] }];
  const result = await readFinalTranscript('http://api.test', 'mtg_read_only', '456', async url => {
    requests.push(url);
    return { ok: true, status: 200, json: async () => responses.shift() };
  });
  const page = identityTestPage();
  renderFinalResult(result, page);
  assert.equal(result.speakerIdentity, undefined);
  assert.equal(requests.length, 2);
  assert.equal(page.querySelector('#speaker-identity-result'), undefined);
  assert.equal(page.nodes['#transcript-segments'].children[0].children[0].textContent,
    '发言人 speaker-1');
});

test('显式登录后才能注册，成功只重读原 genTime 清单且刷新页面不重复字幕', async () => {
  const requests = [];
  const transcript = { text: '原文', segments: [{ text: '原文', sessionSpeakerId: 'speaker-1' }] };
  const actual = identityArtifact([
    { sessionSpeakerId: 'speaker-1', personId: 'person_accepted', displayName: '实际清单名称' }
  ]);
  const responses = [{ accessToken: 'fixture-memory-token', expiresAt: '2030-01-01T00:00:00Z' },
    { personId: 'person_accepted', displayName: '注册响应名称', manifestRevision: 2 },
    { title: '原会议', manifestRevision: 2, artifacts: [
      { type: 'transcript.final', status: 'READY', artifactId: 'art_transcript' },
      { type: 'speaker.identity', status: 'READY', artifactId: 'art_identity_v2' }
    ] }, transcript, actual];
  const actions = createSpeakerIdentityActions('http://api.test/', 'mtg_same', '789', async (url, options) => {
    requests.push({ url, options });
    const body = responses.shift();
    return { ok: true, status: 200, json: async () => body };
  });
  assert.equal(actions.isLoggedIn(), false);
  await assert.rejects(actions.enroll('speaker-1', '名称'), /请先.*登录/);
  assert.equal(requests.length, 0);
  await actions.login('参考账号', 'fixture-password');
  assert.equal(actions.isLoggedIn(), true);
  const refreshed = await actions.enroll('speaker-1', ' 名称 ');
  assert.equal(requests[0].url, 'http://api.test/v1/reference/session:login');
  assert.deepEqual(JSON.parse(requests[0].options.body), { username: '参考账号', password: 'fixture-password' });
  assert.equal(requests[1].url, 'http://api.test/v1/meetings/mtg_same/speakers/speaker-1:enroll');
  assert.equal(requests[1].options.method, 'POST');
  assert.equal(requests[1].options.headers.Authorization, 'Bearer fixture-memory-token');
  assert.deepEqual(JSON.parse(requests[1].options.body), { displayName: '名称' });
  assert.deepEqual(requests.slice(2).map(item => item.url), [
    'http://api.test/v1/meetings/mtg_same/manifest?genTime=789',
    'http://api.test/v1/artifacts/art_transcript?genTime=789',
    'http://api.test/v1/artifacts/art_identity_v2?genTime=789'
  ]);
  assert.ok(requests.slice(2).every(item => item.options === undefined));
  assert.ok(requests.every(item => !item.url.includes('fixture-memory-token')));
  const page = identityTestPage();
  renderFinalResult({ ...transcript, speakerIdentity: identityArtifact([
    { sessionSpeakerId: 'speaker-1', personId: null, displayName: null }
  ]) }, page);
  renderFinalResult(refreshed, page);
  assert.equal(page.nodes['#transcript-segments'].children.length, 1);
  assert.equal(page.nodes['#transcript-segments'].children[0].children[0].textContent, '实际清单名称');
});

test('伙伴登录仅替换 authenticate 回调，不改变人物注册协议', async () => {
  const logins = [];
  const requests = [];
  const actions = createSpeakerIdentityActions('http://partner.test', 'mtg_partner', '987', async (url, options) => {
    requests.push({ url, options });
    return { ok: true, status: 200, json: async () => ({ title: '伙伴会议', artifacts: [] }) };
  }, async (username, password) => {
    logins.push({ username, password });
    return { accessToken: 'fixture-partner-token' };
  });
  await actions.login('伙伴账号', 'fixture-password');
  await actions.enroll('speaker-2', '伙伴人物');
  assert.deepEqual(logins, [{ username: '伙伴账号', password: 'fixture-password' }]);
  assert.equal(requests.length, 2);
  assert.equal(requests[0].url, 'http://partner.test/v1/meetings/mtg_partner/speakers/speaker-2:enroll');
  assert.equal(requests[0].options.headers.Authorization, 'Bearer fixture-partner-token');
  assert.equal(requests[1].url, 'http://partner.test/v1/meetings/mtg_partner/manifest?genTime=987');
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

test('READY_WITH_WARNINGS 清单展示告警但隐藏姓名编辑，READY 清单正常开放', async () => {
  const transcript = { text: '你好', segments: [
    { segmentId: 'seg-1', text: '你好', sessionSpeakerId: 'speaker-1' }
  ] };
  const warnings = [{ code: 'PROVIDER_CAPABILITY_MISSING', capability: 'speaker.identity',
    messageKey: 'ai_meeting.capability.provider_capability_missing', diagnosticId: null }];
  const fetcher = manifest => async url => ({ ok: true, status: 200,
    json: async () => url.includes('/manifest') ? manifest : transcript });
  const discovery = { resultSpeakerEditingEnabled: true };

  const warnedManifest = { title: '告警会议', status: 'READY_WITH_WARNINGS', warnings,
    artifacts: [{ type: 'transcript.final', status: 'READY', artifactId: 'art_transcript' }] };
  const warned = await readFinalTranscript('http://api.test', 'mtg_warned', '123',
    fetcher(warnedManifest));
  assert.equal(warned.status, 'READY_WITH_WARNINGS');
  assert.deepEqual(warned.warnings, warnings);
  assert.equal(speakerEditingAllowed(warned, discovery), false);
  warned.speakerEditingEnabled = speakerEditingAllowed(warned, discovery);

  const warnedPage = noticesTestPage();
  renderFinalResult(warned, warnedPage);
  const warnedSpeaker = warnedPage.nodes['#transcript-segments'].children[0].children[0];
  assert.equal(warnedSpeaker.tagName, 'h3', '告警清单不应生成可点击的姓名按钮');
  assert.equal(warnedSpeaker.children.length, 0);
  assert.equal(warnedSpeaker.dataset.editSpeaker, undefined);
  assert.equal(warnedSpeaker.textContent, '发言人 speaker-1');
  assert.equal(warnedPage.nodes['#result-warnings'].hidden, false);
  assert.equal(warnedPage.nodes['#result-warnings'].children.length, 1);
  assert.match(warnedPage.nodes['#result-warnings'].children[0].textContent, /speaker\.identity/);
  assert.equal(warnedPage.nodes['#result-status'].hidden, false);
  assert.match(warnedPage.nodes['#result-status'].textContent, /降级告警/);
  assert.equal(warnedPage.nodes['#status'].textContent, '结果已生成（有降级告警）');

  const readyManifest = { ...warnedManifest, status: 'READY', warnings: [] };
  const ready = await readFinalTranscript('http://api.test', 'mtg_ready', '456',
    fetcher(readyManifest));
  assert.equal(speakerEditingAllowed(ready, discovery), true);
  assert.equal(speakerEditingAllowed(ready, { resultSpeakerEditingEnabled: false }), false);
  ready.speakerEditingEnabled = speakerEditingAllowed(ready, discovery);

  const readyPage = noticesTestPage();
  renderFinalResult(ready, readyPage);
  const readySpeaker = readyPage.nodes['#transcript-segments'].children[0].children[0];
  const readyEdit = readySpeaker.children[0];
  assert.equal(readyEdit.tagName, 'button');
  assert.equal(readyEdit.dataset.editSpeaker, 'seg-1');
  assert.equal(readyPage.nodes['#result-warnings'].hidden, true);
  assert.equal(readyPage.nodes['#result-status'].hidden, true);
});

test('超过旧1MiB的最终正文仍能读取并渲染，不整页中断', async () => {
  // 旧后端在 1 MiB 处 409；这里验证页面自身没有第二套大小限制，读到即可渲染。
  const text = '会'.repeat(400_000); // 约 1.2 MB UTF-8
  const transcript = { schemaVersion: 'client-transcript/v1', artifactType: 'transcript.final',
    text, segments: [{ segmentId: 'long-1', startMs: 0, text }] };
  const manifest = { title: '长会议', artifacts: [
    { type: 'transcript.final', status: 'READY', artifactId: 'art_long' }
  ] };
  const fetcher = async url => ({ ok: true, status: 200,
    json: async () => url.includes('/manifest') ? manifest : transcript });
  const result = await readFinalTranscript('http://api.test', 'mtg_long', '123', fetcher);
  assert.equal(result.text.length, text.length);

  const page = identityTestPage();
  renderFinalResult(result, page);
  assert.equal(page.nodes['#result'].hidden, false);
  assert.equal(page.nodes['#transcript-segments'].hidden, false);
  const rendered = page.nodes['#transcript-segments'].children[0];
  assert.equal(rendered.children[1].textContent.length, text.length);
  assert.equal(page.nodes['#status'].textContent, '结果已生成');
});
