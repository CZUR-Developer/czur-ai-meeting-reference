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
  const presentation = artifacts.filter(item => item.type === 'presentation.capture'
    && item.status === 'READY').sort((a, b) => a.revision - b.revision);
  const result = { title: manifest.title, text: null };
  if (transcript) {
    const response = await fetcher(
      `${base}/v1/artifacts/${encodeURIComponent(transcript.artifactId)}?${query}`);
    if (!response.ok) throw new Error(`读取最终转写失败：HTTP ${response.status}`);
    const content = await response.json();
    result.text = content.text;
  }
  if (translation) {
    const response = await fetcher(
      `${base}/v1/artifacts/${encodeURIComponent(translation.artifactId)}?${query}`);
    if (!response.ok) throw new Error(`读取最终译文失败：HTTP ${response.status}`);
    const content = await response.json();
    result.translations = content.translations;
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
  const hasTranscript = typeof result.text === 'string';
  const hasTranslation = Array.isArray(result.translations)
    && result.translations.length > 0;
  const hasPresentation = Array.isArray(result.presentationFrames)
    && result.presentationFrames.length > 0;
  if (!hasTranscript && !hasTranslation && !hasPresentation) {
    status.textContent = '本场会议没有识别到字幕';
    return;
  }
  if (hasTranscript) {
    page.querySelector('#transcript').textContent = result.text;
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
    const result = await readFinalTranscript(config.apiBaseUrl || '', meetingId, genTime,
      globalThis.fetch, ms => new Promise(resolve => setTimeout(resolve, ms)),
      () => { status.textContent = '结果生成中…'; });
    renderFinalResult(result, document);
  } catch (error) {
    status.textContent = error.message;
  }
}

if (typeof document !== 'undefined') showResult();
