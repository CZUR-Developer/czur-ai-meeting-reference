import assert from 'node:assert/strict';
import test from 'node:test';
import { activeSegmentIndex, formatTime, resultSections, resultText,
  timelineSegments, translationsBySegment } from '../src/result-model.mjs';

test('录音时间保留小时，缺少时间不伪装成零秒', () => {
  assert.equal(formatTime(0), '00:00');
  assert.equal(formatTime(3661.4), '01:01:01');
  assert.equal(formatTime(undefined), '--:--');
});

test('真实时间轴排序并返回原段落索引，缺时间段不参与播放定位', () => {
  const timeline = timelineSegments([{ startMs: 2000 }, {}, { startMs: 500 }, { startMs: -1 }]);
  assert.deepEqual(timeline.map(item => item.index), [2, 0]);
  assert.equal(activeSegmentIndex(timeline, 0), -1);
  assert.equal(activeSegmentIndex(timeline, 500), 2);
  assert.equal(activeSegmentIndex(timeline, 1999), 2);
  assert.equal(activeSegmentIndex(timeline, 2000), 0);
  assert.equal(activeSegmentIndex([], 3000), -1);
});

test('译文依sourceSegmentId配对，允许多目标语言，不按位置错配', () => {
  const translations = [{ sourceSegmentId: 'b', text: 'B' },
    { sourceSegmentId: 'a', text: 'A1' }, { sourceSegmentId: 'a', text: 'A2' }, { text: 'unpaired' }];
  const grouped = translationsBySegment(translations);
  assert.deepEqual(grouped.get('a').map(item => item.text), ['A1', 'A2']);
  assert.equal(grouped.has(undefined), false);
});

test('没有模型产物的功能不显示，纯录音和纯演示也能独立显示', () => {
  assert.deepEqual(resultSections({ text: '原文' }), {
    transcript: true, translation: false, summary: false, todos: false,
    presentation: false, audio: false, identity: false,
  });
  const media = resultSections({ audio: { url: '/audio' }, presentationFrames: [{}] });
  assert.equal(media.audio, true); assert.equal(media.presentation, true);
  assert.equal(media.transcript, false); assert.equal(media.summary, false);
});

test('导出原文沿用真实段落时间、人物姓名和关联译文', () => {
  const result = { segments: [{ segmentId: 'a', startMs: 3000, text: '你好', sessionSpeakerId: 's1' },
    { segmentId: 'b', text: '继续' }],
  translations: [{ sourceSegmentId: 'a', text: 'Hello' }],
  speakerIdentity: { speakers: [{ sessionSpeakerId: 's1', displayName: '甲' }] } };
  assert.equal(resultText(result, 'transcript'), '00:03  甲\n你好\nHello\n\n继续');
});

test('总结与待办是独立内容，未生成待办不虚构条目', () => {
  const result = { summary: { title: '标题', overview: '概述', keyPoints: ['要点'], actionItems: ['行动'] } };
  assert.equal(resultText(result, 'summary'), '标题\n\n概述\n\n• 要点');
  assert.equal(resultText(result, 'todos'), '行动');
  assert.equal(resultText({ summary: {} }, 'todos'), '暂无待办事项');
});

test('复制不遗漏只有纯文字时的译文或未配对的译文', () => {
  const translations = [{ sourceSegmentId: 'missing', text: '独立译文', sourceLanguage: 'zh', targetLanguage: 'en' }];
  assert.match(resultText({ text: '原文', translations }, 'transcript'), /原文[\s\S]*独立译文/);
  assert.match(resultText({ segments: [{ segmentId: 'a', text: '分段' }], translations }, 'transcript'), /分段[\s\S]*独立译文/);
});
