import { speakerDisplayName } from './speaker-editor.mjs';
/** 标准结果的纯页面投影；无模型、账号、DOM 或 CZUR 私有接口依赖。 */
export function formatTime(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return '--:--';
  const whole = Math.floor(seconds);
  const parts = [Math.floor(whole / 60) % 60, whole % 60];
  if (whole >= 3600) parts.unshift(Math.floor(whole / 3600));
  return parts.map(value => String(value).padStart(2, '0')).join(':');
}

export function languageName(code) {
  if (!code) return '未知语言';
  if (code === 'zh' || code.startsWith('zh-')) return '中文';
  if (code === 'en' || code.startsWith('en-')) return 'English';
  try { return new Intl.DisplayNames(['zh-CN'], { type: 'language' }).of(code) || code; }
  catch { return code; }
}

/** 不按数组序号猜配对；同一原文允许有多个目标语种的译文。 */
export function translationsBySegment(translations = []) {
  const grouped = new Map();
  for (const item of translations) {
    if (!item.sourceSegmentId) continue;
    const list = grouped.get(item.sourceSegmentId) || [];
    list.push(item);
    grouped.set(item.sourceSegmentId, list);
  }
  return grouped;
}

/** 缺时间的段落仍可读；只有真实非负时间才进入录音定位和跟随。 */
export function timelineSegments(segments = []) {
  return segments.map((item, index) => ({ index, startMs: item.startMs, endMs: item.endMs }))
    .filter(item => Number.isFinite(item.startMs) && item.startMs >= 0)
    .sort((a, b) => a.startMs - b.startMs || a.index - b.index);
}

/** 空隙沿用最近发言，未开始返回-1；不对缺时间的文字制造零秒位置。 */
export function activeSegmentIndex(timeline, timeMs) {
  let low = 0, high = timeline.length - 1, found = -1;
  while (low <= high) {
    const middle = (low + high) >>> 1;
    if (timeline[middle].startMs <= timeMs) { found = middle; low = middle + 1; }
    else high = middle - 1;
  }
  return found < 0 ? -1 : timeline[found].index;
}

/** 按钮、布局和导出项共用同一可用性；部署支持不等于本场已生成。 */
export function resultSections(result) {
  return {
    transcript: Boolean(result.text?.trim() || result.segments?.length),
    translation: Boolean(result.translations?.length),
    summary: Boolean(result.summary), todos: Boolean(result.summary),
    presentation: Boolean(result.presentationFrames?.length),
    audio: Boolean(result.audio?.url), identity: Boolean(result.speakerIdentity?.speakers?.length),
  };
}

/** 复制／TXT导出复用标准内容，不包含模型密钥或编辑凭据。 */
export function resultText(result, section) {
  if (section === 'summary') {
    const s = result.summary;
    return s ? [s.title, s.overview, ...(s.keyPoints || []).map(value => `• ${value}`)]
      .filter(Boolean).join('\n\n') : '';
  }
  if (section === 'todos') return result.summary?.actionItems?.join('\n') || '暂无待办事项';
  if (section === 'translation') return (result.translations || []).map(item =>
    `${languageName(item.sourceLanguage)} → ${languageName(item.targetLanguage)}\n${item.text || ''}`).join('\n\n');
  const segments = result.segments || [];
  if (!segments.length) return [result.text, resultText(result, 'translation')].filter(Boolean).join('\n\n');
  const translations = translationsBySegment(result.translations);
  const original = segments.map(item => {
    const time = Number.isFinite(item.startMs) && item.startMs >= 0 ? formatTime(item.startMs / 1000) : '';
    const speaker = item.sessionSpeakerId ? speakerDisplayName(item, result.speakerIdentity?.speakers) : '';
    return [[time, speaker].filter(Boolean).join('  '), item.text,
      ...(translations.get(item.segmentId) || []).map(value => value.text)].filter(Boolean).join('\n');
  }).join('\n\n');
  const ids = new Set(segments.map(item => item.segmentId));
  const unpaired = (result.translations || []).filter(item => !item.sourceSegmentId || !ids.has(item.sourceSegmentId));
  return [original, resultText({ translations: unpaired }, 'translation')].filter(Boolean).join('\n\n');
}
