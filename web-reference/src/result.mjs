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
 * 会后人物编辑的窄入口。authenticate(username, password) 返回 {accessToken}，伙伴可替换它
 * 对接自己的账号；会议结果读取与注册协议无需改变。Token 只存在此页内存闭包中，不写入
 * URL、浏览器持久存储或日志。genTime 只供匿名 GET，不能代替注册所需的 Bearer 身份。
 * 注册 ACK 不生成新的本地结果状态；始终重新读取原 meetingId／genTime 的实际 Manifest。
 */
export function createSpeakerIdentityActions(apiBaseUrl, meetingId, genTime,
    fetcher = globalThis.fetch,
    authenticate = (username, password) => loginReferenceIdentity(apiBaseUrl, username, password, fetcher)) {
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
  // 注册后重读的是同一场完整结果；先清已有内容，避免字幕、纪要及人物名单重复追加。
  for (const selector of ['#result', '#translation-result', '#summary-result',
    '#presentation-result', '#audio-result', '#speaker-identity-result',
    '#transcript', '#transcript-segments']) {
    const section = page.querySelector(selector);
    if (section) section.hidden = true;
  }
  for (const selector of ['#transcript', '#transcript-segments', '#translations',
    '#summary-content', '#presentation-frames', '#speaker-identities', '#speaker-selection']) {
    const content = page.querySelector(selector);
    if (content) content.textContent = '';
  }
  const hasSegments = Array.isArray(result.segments) && result.segments.length > 0;
  const hasTranscript = typeof result.text === 'string' || hasSegments;
  const hasTranslation = Array.isArray(result.translations)
    && result.translations.length > 0;
  const hasSummary = result.summary != null;
  const hasPresentation = Array.isArray(result.presentationFrames)
    && result.presentationFrames.length > 0;
  const hasAudio = result.audio != null;
  const identities = result.speakerIdentity?.speakers || [];
  const hasSpeakerIdentity = identities.length > 0;
  const identityBySpeaker = new Map(identities.map(item => [item.sessionSpeakerId, item]));
  if (!hasTranscript && !hasTranslation && !hasSummary && !hasPresentation && !hasAudio && !hasSpeakerIdentity) {
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
        const identity = identityBySpeaker.get(id);
        speaker.textContent = identity?.displayName
          ? `${identity.displayName} · 本场发言人 ID：${id}`
          : id ? `本场发言人 ID：${id}` : '发言人未知';
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
  if (hasAudio) {
    const player = page.querySelector('#audio-player');
    player.controls = true;
    player.preload = 'metadata';
    player.src = result.audio.url;
    page.querySelector('#audio-result').hidden = false;
  }
  if (hasSpeakerIdentity) {
    const entries = page.querySelector('#speaker-identities');
    const selection = page.querySelector('#speaker-selection');
    for (const identity of identities) {
      const row = page.createElement('article');
      row.className = 'speaker-identity';
      const name = page.createElement('h3');
      name.textContent = identity.displayName || '未登记姓名';
      const session = page.createElement('p');
      session.textContent = `本场发言人 ID：${identity.sessionSpeakerId}`;
      const person = page.createElement('p');
      person.textContent = identity.personId
        ? `跨会议人物 ID：${identity.personId}` : '尚无跨会议人物 ID';
      row.append(name, session, person);
      entries.append(row);
      const option = page.createElement('option');
      option.value = identity.sessionSpeakerId;
      option.textContent = `${identity.displayName || '未登记姓名'}（本场：${identity.sessionSpeakerId}）`;
      selection.append(option);
    }
    page.querySelector('#speaker-identity-result').hidden = false;
  }
  status.textContent = '结果已生成';
}

/** 登录与提交仅由用户显式操作；无人物产物时 showResult 不装配这组编辑行为。 */
function bindSpeakerIdentityEditing(page, actions) {
  const login = page.querySelector('#speaker-login-form');
  const password = page.querySelector('#speaker-password');
  const authStatus = page.querySelector('#speaker-auth-status');
  const enrollment = page.querySelector('#speaker-enrollment-form');
  const fields = page.querySelector('#speaker-enrollment-fields');
  const enrollmentStatus = page.querySelector('#speaker-enrollment-status');
  login.addEventListener('submit', async event => {
    event.preventDefault();
    const button = page.querySelector('#speaker-login-button');
    button.disabled = true;
    fields.disabled = true;
    authStatus.textContent = '正在登录…';
    try {
      await actions.login(page.querySelector('#speaker-username').value, password.value);
      authStatus.textContent = '已登录，可命名并注册本场发言人';
      page.querySelector('#speaker-login-panel').open = false;
    } catch (error) { authStatus.textContent = error.message; }
    finally {
      password.value = '';
      button.disabled = false;
      fields.disabled = !actions.isLoggedIn();
    }
  });
  enrollment.addEventListener('submit', async event => {
    event.preventDefault();
    fields.disabled = true;
    enrollmentStatus.textContent = '正在命名并注册…';
    try {
      const refreshed = await actions.enroll(page.querySelector('#speaker-selection').value,
        page.querySelector('#speaker-display-name').value);
      renderFinalResult(refreshed, page);
      enrollmentStatus.textContent = '人物名称已更新';
      page.querySelector('#speaker-display-name').value = '';
    } catch (error) { enrollmentStatus.textContent = error.message; }
    finally { fields.disabled = !actions.isLoggedIn(); }
  });
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
    if (result.speakerIdentity?.speakers.length > 0) {
      bindSpeakerIdentityEditing(document,
        createSpeakerIdentityActions(config.apiBaseUrl || '', meetingId, genTime));
    }
  } catch (error) {
    status.textContent = error.message;
  }
}

if (typeof document !== 'undefined') showResult();
