import { bindResultUi } from './result-ui.mjs';
import { bindSpeakerNameEditing, speakerDisplayName } from './speaker-editor.mjs';
import { formatTime, languageName, resultSections, translationsBySegment } from './result-model.mjs';

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
  const speakerIdentity = artifacts.find(item => item.type === 'speaker.identity'
    && item.status === 'READY');
  const presentation = artifacts.filter(item => item.type === 'presentation.capture'
    && item.status === 'READY').sort((a, b) => a.revision - b.revision);
  const result = { title: manifest.title, text: null };
  if (manifest.startedAt != null) result.startedAt = manifest.startedAt;
  if (manifest.durationMs != null) result.durationMs = manifest.durationMs;
  const audio = artifacts.find(item => item.type === 'audio.original'
    && item.status === 'READY');
  // 音频正文不随结果 JSON 下载；页面只拿到统一 Artifact 引用并让浏览器流式播放。
  if (audio) {
    result.audio = {
      artifactId: audio.artifactId,
      mediaType: audio.mediaType,
      size: audio.size,
      url: `${base}/v1/artifacts/${encodeURIComponent(audio.artifactId)}?${query}`
    };
  }
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
  if (speakerIdentity) {
    const response = await fetcher(
      `${base}/v1/artifacts/${encodeURIComponent(speakerIdentity.artifactId)}?${query}`);
    if (!response.ok) throw new Error(`读取人物身份失败：HTTP ${response.status}`);
    const content = await response.json();
    if (content.schemaVersion !== 'speaker-identity/v1'
        || content.artifactType !== 'speaker.identity' || !Array.isArray(content.speakers)) {
      throw new Error('人物身份产物格式不支持');
    }
    result.speakerIdentity = content;
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

/** 默认参考登录，只交换本部署 Bearer；伙伴可通过下方 authenticate 回调替换账号来源。 */
export async function loginReferenceIdentity(apiBaseUrl, username, password,
    fetcher = globalThis.fetch) {
  const response = await fetcher(`${apiBaseUrl.replace(/\/+$/, '')}/v1/reference/session:login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  if (!response.ok) throw new Error(response.status === 401
    ? '账号或密码错误' : `登录失败：HTTP ${response.status}`);
  const session = await response.json();
  if (!session.accessToken) throw new Error('登录响应缺少会话信息');
  return session;
}

/**
 * 会后人物编辑的窄入口。参考宿主声明 requiresLogin=false，使用原结果链接的 genTime
 * 直接编辑；伙伴可在后端替换策略，并声明 requiresLogin=true 使用可替换 authenticate。
 * Token 只存在此页内存闭包中，不写入 URL、浏览器持久存储或日志。旧调用默认保持登录。
 * 注册 ACK 不生成新的本地结果状态；始终重新读取原 meetingId／genTime 的实际 Manifest。
 */
export function createSpeakerIdentityActions(apiBaseUrl, meetingId, genTime,
    fetcher = globalThis.fetch,
    authenticate = (username, password) => loginReferenceIdentity(apiBaseUrl, username, password, fetcher),
    { requiresLogin = true } = {}) {
  const base = apiBaseUrl.replace(/\/+$/, '');
  let accessToken = null;
  return {
    async login(username, password) {
      accessToken = null;
      const session = await authenticate(username, password);
      if (!session?.accessToken) throw new Error('登录响应缺少会话信息');
      accessToken = session.accessToken;
    },
    isLoggedIn: () => accessToken != null,
    canEdit: () => !requiresLogin || accessToken != null,
    requiresLogin: () => requiresLogin,
    async rename(sessionSpeakerId, displayName, scope, segmentId, remember) {
      if (requiresLogin && !accessToken) throw new Error('请先使用本场会议的账号登录');
      const headers = { 'Content-Type': 'application/json' };
      if (requiresLogin) headers.Authorization = `Bearer ${accessToken}`;
      const query = requiresLogin ? '' : `?genTime=${encodeURIComponent(genTime)}`;
      const response = await fetcher(`${base}/v1/meetings/${encodeURIComponent(meetingId)}`
        + `/speakers/${encodeURIComponent(sessionSpeakerId)}:rename${query}`, {
        method: 'POST', headers,
        body: JSON.stringify({ displayName: displayName.trim(), scope, segmentId, remember })
      });
      if (response.status === 401) accessToken = null;
      if (!response.ok) throw new Error(response.status === 401 ? '登录已失效，请重新登录'
        : response.status === 403 ? (requiresLogin ? '请使用本场会议所属账号登录' : '结果链接已失效，无法修改')
          : `保存失败：HTTP ${response.status}`);
      const saved = await response.json();
      // 保存 ACK 和读取结果分开；读取失败时不能把已完成的写入描述成未保存。
      try { return { result: await readFinalTranscript(apiBaseUrl, meetingId, genTime, fetcher),
        registration: saved.registration }; }
      catch { throw new Error('名称已保存，结果刷新失败，请重新打开本场结果'); }
    },
    async enroll(sessionSpeakerId, displayName) {
      if (!accessToken) throw new Error('请先使用本场会议的账号登录');
      const response = await fetcher(`${base}/v1/meetings/${encodeURIComponent(meetingId)}`
        + `/speakers/${encodeURIComponent(sessionSpeakerId)}:enroll`, {
        method: 'POST', headers: { 'Content-Type': 'application/json',
          Authorization: `Bearer ${accessToken}` },
        body: JSON.stringify({ displayName: displayName.trim() })
      });
      if (response.status === 401) accessToken = null;
      if (!response.ok) throw new Error(response.status === 401
        ? '登录已失效，请重新登录' : response.status === 403
          ? '请使用本场会议所属账号登录' : `人物注册失败：HTTP ${response.status}`);
      return readFinalTranscript(apiBaseUrl, meetingId, genTime, fetcher);
    }
  };
}

/** 与后端公开的同一个有效期配置判断链接展示状态。 */
export function resultLinkExpired(genTime, ttlMs, now = Date.now()) {
  if (!Number.isSafeInteger(ttlMs) || ttlMs <= 0) {
    throw new Error('结果链接有效期未配置');
  }
  if (genTime === '-1') return false;
  if (!/^(0|[1-9][0-9]*)$/.test(genTime)) return true;
  const generatedAt = Number(genTime);
  return !Number.isSafeInteger(generatedAt) || now - generatedAt >= ttlMs;
}

function meetingTime(offsetMs) {
  return formatTime(offsetMs / 1000);
}

export function renderFinalResult(result, page) {
  const status = page.querySelector('#status');
  page.querySelector('#title').textContent = result.title || '会议结果';
  // 注册后重读的是同一场完整结果；先清已有内容，避免字幕、纪要及人物名单重复追加。
  for (const selector of ['#result', '#translation-result', '#summary-result', '#todos-result',
    '#presentation-result', '#audio-result',
    '#transcript', '#transcript-segments']) {
    const section = page.querySelector(selector);
    if (section) section.hidden = true;
  }
  for (const selector of ['#transcript', '#transcript-segments', '#translations',
    '#summary-content', '#todos-content', '#presentation-frames']) {
    const content = page.querySelector(selector);
    if (content) content.textContent = '';
  }
  const hasSegments = Array.isArray(result.segments) && result.segments.length > 0;
  const available = resultSections(result);
  const { transcript: hasTranscript, translation: hasTranslation, summary: hasSummary,
    presentation: hasPresentation, audio: hasAudio } = available;
  const identities = result.speakerIdentity?.speakers || [];
  // 人物产物只供原文姓名投影／编辑使用，不建立独立“人物”栏目或显示内部ID。
  const workspace = page.querySelector('#result-workspace');
  if (workspace) workspace.dataset.speakerIdentityCount = String(identities.length);
  const segmentElements = new Map();
  const groupedTranslations = translationsBySegment(result.translations);
  if (!hasTranscript && !hasTranslation && !hasSummary && !hasPresentation && !hasAudio) {
    status.textContent = '本场会议没有识别到字幕';
    bindResultUi(result, page);
    return;
  }
  if (hasTranscript) {
    const transcript = page.querySelector('#transcript');
    const structuredSegments = hasSegments && result.segments.some(item =>
      (typeof item.sessionSpeakerId === 'string' && item.sessionSpeakerId.trim() !== '')
      || (Number.isFinite(item.startMs) && item.startMs >= 0)
      || groupedTranslations.has(item.segmentId));
    if (structuredSegments) {
      const entries = page.querySelector('#transcript-segments');
      for (const item of result.segments) {
        const row = page.createElement('article');
        row.className = 'transcript-segment';
        const speaker = page.createElement('h3');
        const id = typeof item.sessionSpeakerId === 'string'
          ? item.sessionSpeakerId.trim() : '';
        const label = speakerDisplayName(item, identities);
        if (result.speakerEditingEnabled && id && item.segmentId) {
          const edit = page.createElement('button');
          edit.type = 'button'; edit.className = 'speaker-edit-trigger';
          edit.textContent = label;
          edit.dataset.editSpeaker = item.segmentId;
          edit.setAttribute('aria-label', `修改发言人：${label}`);
          speaker.append(edit);
        } else speaker.textContent = label;
        const text = page.createElement('p');
        text.textContent = item.text || '';
        row.append(speaker, text);
        // 没有说话人或时间的数据只显示原文；不制造身份或零秒定位。
        speaker.hidden = !id;
        if (Number.isFinite(item.startMs) && item.startMs >= 0) {
          const time = page.createElement('span');
          time.className = 'segment-time';
          time.textContent = formatTime(item.startMs / 1000);
          row.append(time);
        }
        entries.append(row);
        if (item.segmentId) segmentElements.set(item.segmentId, row);
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
      (segmentElements.get(item.sourceSegmentId) || entries).append(row);
    }
    page.querySelector('#translation-result').hidden = entries.children.length === 0;
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
      const target = label === '待办事项' ? page.querySelector('#todos-content') || content : content;
      if (target === content) target.append(heading, list);
      else target.append(list);
    }
    const todos = page.querySelector('#todos-content');
    if (todos && !result.summary.actionItems?.length) todos.textContent = '暂无待办事项';
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
  if (hasAudio) {
    const player = page.querySelector('#audio-player');
    player.controls = true;
    player.preload = 'metadata';
    if (player.src !== result.audio.url) player.src = result.audio.url;
    page.querySelector('#audio-result').hidden = false;
  }
  status.textContent = '结果已生成';
  bindResultUi(result, page);
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
    if (!configResponse.ok) throw new Error(
      '结果页缺少后端配置（dist/config.json）：部署方需在静态站目录放置该文件，'
      + '或在构建时设置 REFERENCE_API_BASE_URL／web-reference/config.local.json');
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
    result.speakerEditingEnabled = discovery.resultSpeakerEditingEnabled === true;
    renderFinalResult(result, document);
    const actions = createSpeakerIdentityActions(config.apiBaseUrl || '', meetingId, genTime,
      globalThis.fetch, undefined, { requiresLogin: discovery.resultSpeakerEditingRequiresLogin !== false });
    if (result.speakerEditingEnabled) {
      bindSpeakerNameEditing(document, actions, result, renderFinalResult, discovery);
    }
  } catch (error) {
    status.textContent = error.message;
  }
}

if (typeof document !== 'undefined') showResult();
