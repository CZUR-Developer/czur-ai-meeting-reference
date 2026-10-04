/** 结果页只读取会后 Manifest 与最终产物；会议中字幕始终由 Android App 显示。 */
export async function readFinalTranscript(apiBaseUrl, meetingId, genTime,
    fetcher = globalThis.fetch, wait = ms => new Promise(resolve => setTimeout(resolve, ms)),
    onPending = () => {}) {
  const base = apiBaseUrl.replace(/\/+$/, '');
  const query = `genTime=${encodeURIComponent(genTime)}`;
  let manifest;
  while (true) {
    const response = await fetcher(
      `${base}/v1/meetings/${encodeURIComponent(meetingId)}/manifest?${query}`);
    if (response.status === 202) {
      onPending();
      await wait(1500);
      continue;
    }
    if (!response.ok) throw new Error(`读取会议结果失败：HTTP ${response.status}`);
    manifest = await response.json();
    break;
  }

  const artifacts = Array.isArray(manifest.artifacts) ? manifest.artifacts : [];
  const transcript = artifacts.find(item => item.type === 'transcript.final'
    && item.status === 'READY');
  const translation = artifacts.find(item => item.type === 'translation.final'
    && item.status === 'READY');
  const summary = artifacts.find(item => item.type === 'summary.structured'
    && item.status === 'READY');
  const presentation = artifacts.filter(item => item.type === 'presentation.capture'
    && item.status === 'READY').sort((a, b) => a.revision - b.revision);
  const result = { title: manifest.title, text: null };
  if (transcript) {
    const response = await fetcher(
      `${base}/v1/artifacts/${encodeURIComponent(transcript.artifactId)}?${query}`);
    if (!response.ok) throw new Error(`读取最终转写失败：HTTP ${response.status}`);
    const content = await response.json();
    result.text = content.text;
    if (Array.isArray(content.segments)) result.segments = content.segments;
  }
  if (translation) {
    const response = await fetcher(
      `${base}/v1/artifacts/${encodeURIComponent(translation.artifactId)}?${query}`);
    if (!response.ok) throw new Error(`读取最终译文失败：HTTP ${response.status}`);
    const content = await response.json();
    result.translations = content.translations;
  }
  if (summary) {
    const response = await fetcher(
      `${base}/v1/artifacts/${encodeURIComponent(summary.artifactId)}?${query}`);
    if (!response.ok) throw new Error(`读取结构化纪要失败：HTTP ${response.status}`);
    result.summary = await response.json();
  }
  if (presentation.length > 0) {
    result.presentationFrames = [];
    for (const capture of presentation) {
      const response = await fetcher(
        `${base}/v1/artifacts/${encodeURIComponent(capture.artifactId)}?${query}`);
      if (!response.ok) throw new Error(`读取演示文稿失败：HTTP ${response.status}`);
      const content = await response.json();
      result.presentationFrames.push(...content.frames);
    }
  }
  return result;
}

/** 与后端公开的同一个有效期配置判断链接展示状态。 */
export function resultLinkExpired(genTime, ttlMs, now = Date.now()) {
  if (!Number.isSafeInteger(ttlMs) || ttlMs <= 0) {
    throw new Error('结果链接有效期未配置');
  }
  if (!/^(0|[1-9][0-9]*)$/.test(genTime)) return true;
  const generatedAt = Number(genTime);
  return !Number.isSafeInteger(generatedAt) || generatedAt > now
    || now - generatedAt >= ttlMs;
}

function languageName(code) {
  if (code?.startsWith('zh')) return '中文';
  if (code?.startsWith('en')) return 'English';
  return code || '未知语言';
}

function meetingTime(offsetMs) {
  const seconds = Math.floor(offsetMs / 1000);
  return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${
    (seconds % 60).toString().padStart(2, '0')}`;
}

export function renderFinalResult(result, page) {
  const status = page.querySelector('#status');
  if (result.title) page.querySelector('#title').textContent = result.title;
  const hasSegments = Array.isArray(result.segments) && result.segments.length > 0;
  const hasTranscript = typeof result.text === 'string' || hasSegments;
  const hasTranslation = Array.isArray(result.translations)
    && result.translations.length > 0;
  const hasSummary = result.summary != null;
  const hasPresentation = Array.isArray(result.presentationFrames)
    && result.presentationFrames.length > 0;
  if (!hasTranscript && !hasTranslation && !hasSummary && !hasPresentation) {
    status.textContent = '本场会议没有识别到字幕';
    return;
  }
  if (hasTranscript) {
    const transcript = page.querySelector('#transcript');
    const speakerSegments = hasSegments && result.segments.some(item =>
      typeof item.sessionSpeakerId === 'string' && item.sessionSpeakerId.trim() !== '');
    if (speakerSegments) {
      const entries = page.querySelector('#transcript-segments');
      for (const item of result.segments) {
        const row = page.createElement('article');
        row.className = 'transcript-segment';
        const speaker = page.createElement('h3');
        const id = typeof item.sessionSpeakerId === 'string'
          ? item.sessionSpeakerId.trim() : '';
        speaker.textContent = id ? `本场发言人 ID：${id}` : '发言人未知';
        const text = page.createElement('p');
        text.textContent = item.text || '';
        row.append(speaker, text);
        entries.append(row);
      }
      entries.hidden = false;
    } else {
      transcript.textContent = typeof result.text === 'string'
        ? result.text : result.segments.map(item => item.text || '').join('\n');
      transcript.hidden = false;
    }
    page.querySelector('#result').hidden = false;
  }
  if (hasTranslation) {
    const entries = page.querySelector('#translations');
    for (const item of result.translations) {
      const row = page.createElement('article');
      row.className = 'translation-item';
      const direction = page.createElement('h3');
      direction.textContent = `${languageName(item.sourceLanguage)} → ${languageName(item.targetLanguage)}`;
      const text = page.createElement('p');
      text.textContent = item.text;
      row.append(direction, text);
      entries.append(row);
    }
    page.querySelector('#translation-result').hidden = false;
  }
  if (hasSummary) {
    const content = page.querySelector('#summary-content');
    const title = page.createElement('h3');
    title.textContent = result.summary.title;
    const overview = page.createElement('p');
    overview.textContent = result.summary.overview;
    content.append(title, overview);
    for (const [label, items] of [
      ['关键要点', result.summary.keyPoints],
      ['待办事项', result.summary.actionItems]
    ]) {
      if (!Array.isArray(items) || items.length === 0) continue;
      const heading = page.createElement('h4');
      heading.textContent = label;
      const list = page.createElement('ul');
      for (const item of items) {
        const entry = page.createElement('li');
        entry.textContent = item;
        list.append(entry);
      }
      content.append(heading, list);
    }
    page.querySelector('#summary-result').hidden = false;
  }
  if (hasPresentation) {
    const entries = page.querySelector('#presentation-frames');
    for (const frame of result.presentationFrames) {
      const figure = page.createElement('figure');
      figure.className = 'presentation-frame';
      const image = page.createElement('img');
      image.src = `data:image/jpeg;base64,${frame.jpegBase64}`;
      image.alt = `会议 ${meetingTime(frame.offsetMs)} 的演示画面`;
      image.loading = 'lazy';
      const caption = page.createElement('figcaption');
      caption.textContent = `会议 ${meetingTime(frame.offsetMs)}`;
      figure.append(image, caption);
      entries.append(figure);
    }
    page.querySelector('#presentation-result').hidden = false;
  }
  status.textContent = '结果已生成';
}

async function showResult() {
  const status = document.querySelector('#status');
  const params = new URLSearchParams(location.search);
  const meetingId = params.get('meetingId');
  const genTime = params.get('genTime');
  if (!meetingId || !genTime) {
    status.textContent = '结果链接缺少会议编号或生成时间';
    return;
  }
  try {
    const configResponse = await fetch('./config.json');
    if (!configResponse.ok) throw new Error('结果页缺少后端配置');
    const config = await configResponse.json();
    const base = (config.apiBaseUrl || '').replace(/\/+$/, '');
    const discoveryResponse = await fetch(`${base}/v1/capabilities`);
    if (!discoveryResponse.ok) throw new Error('无法读取结果链接有效期');
    const discovery = await discoveryResponse.json();
    if (resultLinkExpired(genTime, discovery.resultLinkTtlMs)) {
      status.textContent = '结果链接已过期';
      return;
    }
    const result = await readFinalTranscript(config.apiBaseUrl || '', meetingId, genTime,
      globalThis.fetch, ms => new Promise(resolve => setTimeout(resolve, ms)),
      () => { status.textContent = '结果生成中…'; });
    renderFinalResult(result, document);
  } catch (error) {
    status.textContent = error.message;
  }
}

if (typeof document !== 'undefined') showResult();
