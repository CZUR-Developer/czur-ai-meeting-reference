import { activeSegmentIndex, formatTime, resultSections, resultText, timelineSegments } from './result-model.mjs';

const mounted = new WeakMap();
const panels = [
  ['summary', '总结纪要', 'summary-result', 'summary-icon'],
  ['todos', '待办事项', 'todos-result', 'todo-icon'],
  ['presentation', '演示文稿', 'presentation-result', 'draft-icon'],
  ['identity', '人物', 'speaker-identity-result', 'speaker'],
];

/**
 * 绑定默认结果工作区。只消费已读取的标准结果，不另建会议状态、网络协议或账号逻辑。
 * 伙伴可替换此层；原正文渲染也能用于自己的简化HTML容器。
 * 重绘时撤销旧事件／定时器，注册人物后不会叠加播放回调或重复触发操作。
 */
export function bindResultUi(result, page) {
  if (!page.querySelector('#result-workspace')) return;
  mounted.get(page)?.();
  const window = page.defaultView;
  const controller = new window.AbortController();
  const on = (node, event, listener, options = {}) => node.addEventListener(event, listener,
    { ...options, signal: controller.signal });
  const get = id => page.getElementById(id);
  const available = resultSections(result);
  const hasOriginal = available.transcript || available.translation;
  const visiblePanels = panels.filter(([key]) => available[key]);
  const hasAnything = hasOriginal || visiblePanels.length > 0 || available.audio;
  let messageTimer;
  let playTimer;
  mounted.set(page, () => {
    controller.abort(); window.clearTimeout(messageTimer); window.clearInterval(playTimer);
  });
  const message = text => {
    get('action-message').textContent = text;
    get('action-message').hidden = false;
    window.clearTimeout(messageTimer);
    messageTimer = window.setTimeout(() => { get('action-message').hidden = true; }, 3000);
  };
  // 发言人编辑等独立工具共用可见反馈，避免只更新供读屏使用的隐藏状态文字。
  on(page, 'result-action-message', event => message(event.detail));

  page.body.dataset.ready = String(hasAnything);
  get('result-columns').hidden = !hasOriginal && !visiblePanels.length;
  get('original-panel').hidden = !hasOriginal;
  get('insight-panel').hidden = !visiblePanels.length;
  get('result-columns').classList.toggle('single-panel', !hasOriginal || !visiblePanels.length);
  get('original-panel').querySelector('h2').lastChild.textContent = available.transcript ? '原文' : '译文';
  get('ai-notice').hidden = !available.summary;
  const startedAt = result.startedAt ? new Date(result.startedAt) : null;
  get('meeting-date').hidden = !startedAt || !Number.isFinite(startedAt.getTime());
  if (!get('meeting-date').hidden) {
    get('meeting-date').textContent = new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    }).format(startedAt);
    get('meeting-date').dateTime = startedAt.toISOString();
  }
  get('meeting-duration').hidden = !Number.isFinite(result.durationMs);
  if (!get('meeting-duration').hidden) get('meeting-duration').textContent = formatTime(result.durationMs / 1000);

  // 可用性单独标明；当前未选中的tab隐藏，不能把隐藏误判为模型能力缺失。
  for (const [key, , id] of panels) get(id).dataset.available = String(available[key]);
  for (const [key, id] of [['transcript', 'result'], ['translation', 'translation-result'], ['audio', 'audio-result']]) {
    get(id).dataset.available = String(available[key]);
  }
  const previous = get('result-tabs').querySelector('[aria-selected="true"]')?.dataset.tab;
  const previousMobile = get('mobile-tabs').querySelector('[aria-selected="true"]')?.dataset.tab;
  let selected = visiblePanels.some(([key]) => key === previous) ? previous : visiblePanels[0]?.[0];
  let mobileSelected = previousMobile === 'original' && hasOriginal ? 'original'
    : visiblePanels.some(([key]) => key === previousMobile) ? previousMobile : hasOriginal ? 'original' : selected;
  const redrawTabs = () => {
    for (const [key, , id] of panels) get(id).hidden = !available[key] || key !== selected;
    for (const button of get('result-tabs').children) button.setAttribute('aria-selected', String(button.dataset.tab === selected));
    for (const button of get('mobile-tabs').children) button.setAttribute('aria-selected', String(button.dataset.tab === mobileSelected));
    get('original-panel').classList.toggle('mobile-inactive', mobileSelected !== 'original');
    get('insight-panel').classList.toggle('mobile-inactive', mobileSelected === 'original');
  };
  const makeTab = (container, key, title, icon, mobile) => {
    const button = page.createElement('button');
    button.type = 'button'; button.dataset.tab = key; button.setAttribute('role', 'tab');
    const image = page.createElement('img'); image.src = `./assets/${icon}.png`; image.alt = '';
    button.append(image, page.createTextNode(title));
    on(button, 'click', () => {
      if (key !== 'original') { selected = key; get('insight-scroll').scrollTop = 0; }
      if (mobile) mobileSelected = key;
      redrawTabs();
    });
    container.append(button);
  };
  get('result-tabs').replaceChildren(); get('mobile-tabs').replaceChildren();
  get('result-tabs').setAttribute('role', 'tablist'); get('mobile-tabs').setAttribute('role', 'tablist');
  if (hasOriginal) makeTab(get('mobile-tabs'), 'original', available.transcript ? '原文' : '译文', 'original-icon', true);
  for (const [key, title, , icon] of visiblePanels) {
    makeTab(get('result-tabs'), key, title, icon, false);
    makeTab(get('mobile-tabs'), key, title, icon, true);
  }
  redrawTabs();

  const translate = get('toggle-translation');
  translate.hidden = !available.translation || !available.transcript;
  get('original-panel').classList.remove('hide-translation');
  translate.textContent = '隐藏译文'; translate.setAttribute('aria-pressed', 'true');
  on(translate, 'click', () => {
    const hide = get('original-panel').classList.toggle('hide-translation');
    translate.textContent = hide ? '显示译文' : '隐藏译文';
    translate.setAttribute('aria-pressed', String(!hide));
  });

  const copy = async text => {
    try {
      if (window.navigator.clipboard?.writeText && window.isSecureContext) await window.navigator.clipboard.writeText(text);
      else {
        // 局域网HTTP及部分WebView没有Clipboard API，沿用系统选择区复制。
        const input = page.createElement('textarea'); input.value = text;
        input.style.cssText = 'position:fixed;left:-9999px;top:0';
        page.body.append(input); input.select();
        let copied;
        try { copied = page.execCommand('copy'); } finally { input.remove(); }
        if (!copied) throw new Error('clipboard unavailable');
      }
      message('已复制');
    } catch { message('复制未完成，请选中文字后复制'); }
  };
  on(get('copy-original'), 'click', () => copy(resultText(result, available.transcript ? 'transcript' : 'translation')));
  for (const button of page.querySelectorAll('[data-copy]')) {
    on(button, 'click', () => copy(resultText(result, button.dataset.copy)));
  }

  // Android WebView 未提供文件下载／打印宿主，默认只开放阅读和复制；与CZUR设备页边界一致。
  // 浏览器可导出标准内容，伙伴的本地PDF等动作仍在App结果插件装配，不转接CZUR下载服务。
  const browserActions = !/\bwv\b/i.test(window.navigator.userAgent);
  get('export-menu').hidden = !browserActions || !hasAnything;
  get('share-result').hidden = !browserActions || !hasAnything;
  on(get('share-result'), 'click', () => copy(window.location.href));
  const download = (blob, name) => {
    const url = window.URL.createObjectURL(blob);
    const link = page.createElement('a'); link.href = url; link.download = name;
    page.body.append(link); link.click(); link.remove();
    window.setTimeout(() => window.URL.revokeObjectURL(url), 30000);
  };
  const exports = get('export-items'); exports.replaceChildren();
  const addExport = (name, action) => {
    const button = page.createElement('button'); button.type = 'button'; button.textContent = name;
    on(button, 'click', async () => {
      get('export-menu').open = false;
      try { await action(); } catch { message('导出未完成，请稍后重试'); }
    });
    exports.append(button);
  };
  for (const [key, label] of [['transcript', '原文及译文'], ['translation', '译文'], ['summary', '总结纪要'], ['todos', '待办事项']]) {
    if (available[key]) addExport(`${label}.txt`, () => download(new window.Blob(['\uFEFF', resultText(result, key)], { type: 'text/plain;charset=utf-8' }), `${label}.txt`));
  }
  if (available.audio) addExport('会议录音', async () => {
    message('正在下载录音…');
    const response = await window.fetch(result.audio.url);
    if (!response.ok) throw new Error('audio download failed');
    const extension = result.audio.mediaType?.includes('wav') ? 'wav' : result.audio.mediaType?.includes('mpeg') ? 'mp3' : 'ogg';
    download(await response.blob(), `会议录音.${extension}`); message('已交给浏览器保存');
  });
  if (available.presentation) addExport('演示文稿（打印／PDF）', () => printPresentation(result, page, message));

  const audio = get('audio-player');
  get('audio-controls').hidden = !available.audio;
  audio.controls = false; audio.hidden = true;
  get('audio-error').hidden = true;
  const timeline = timelineSegments(result.segments);
  const rows = [...get('transcript-segments').children];
  const scroll = get('original-scroll');
  get('follow-playback').hidden = true;
  let follow = true, activeIndex = -1, dragging = false;
  const positionActive = () => {
    const row = rows[activeIndex];
    if (!row || !scroll.clientHeight) return;
    const box = row.getBoundingClientRect(), view = scroll.getBoundingClientRect();
    if (box.top < view.top || box.bottom > view.bottom) scroll.scrollTop += box.top - view.top - 24;
  };
  const update = () => {
    const duration = audio.duration;
    const ready = Number.isFinite(duration) && duration > 0;
    get('audio-duration').textContent = formatTime(duration);
    get('audio-current').textContent = formatTime(audio.currentTime);
    get('audio-seek').disabled = !ready;
    get('audio-back').disabled = get('audio-forward').disabled = !ready;
    if (ready) {
      get('audio-seek').max = String(duration);
      if (!dragging) get('audio-seek').value = String(audio.currentTime);
      get('meeting-duration').textContent = formatTime(duration); get('meeting-duration').hidden = false;
    }
    const index = activeSegmentIndex(timeline, audio.currentTime * 1000);
    if (index !== activeIndex) {
      rows[activeIndex]?.classList.remove('active');
      activeIndex = index; rows[index]?.classList.add('active');
      if (follow) positionActive();
    }
    get('follow-playback').hidden = follow || activeIndex < 0 || !available.audio;
    get('audio-play').setAttribute('aria-label', audio.paused ? '播放录音' : '暂停录音');
    get('audio-play').querySelector('img').src = `./assets/${audio.paused ? 'play' : 'pause'}-btn.png`;
  };
  const play = async () => {
    try { await audio.play(); get('audio-error').hidden = true; }
    catch { get('audio-error').textContent = '录音暂时无法播放，请重试'; get('audio-error').hidden = false; }
  };
  const seek = (seconds, resume = false) => {
    if (!Number.isFinite(audio.duration) || audio.duration <= 0) return;
    audio.currentTime = Math.max(0, Math.min(audio.duration, seconds));
    follow = true; update(); positionActive();
    if (resume) void play();
  };
  if (available.audio) {
    on(get('audio-play'), 'click', () => audio.paused ? void play() : audio.pause());
    on(get('audio-back'), 'click', () => seek(audio.currentTime - 15));
    on(get('audio-forward'), 'click', () => seek(audio.currentTime + 15));
    on(get('audio-rate'), 'change', event => { audio.playbackRate = Number(event.target.value); });
    get('audio-rate').value = String(audio.playbackRate);
    on(get('audio-seek'), 'input', event => { dragging = true; get('audio-current').textContent = formatTime(Number(event.target.value)); });
    on(get('audio-seek'), 'change', event => { dragging = false; seek(Number(event.target.value)); });
    for (const event of ['loadedmetadata', 'durationchange', 'timeupdate', 'seeked', 'pause', 'ended']) on(audio, event, update);
    on(audio, 'play', () => {
      window.clearInterval(playTimer); playTimer = window.setInterval(update, 50); update();
    });
    on(audio, 'pause', () => window.clearInterval(playTimer));
    on(audio, 'ended', () => window.clearInterval(playTimer));
    on(audio, 'error', () => { get('audio-error').textContent = '录音加载失败，请重新打开结果页'; get('audio-error').hidden = false; });
    for (const item of timeline) {
      const row = rows[item.index]; if (!row) continue;
      row.dataset.seekable = 'true';
      row.tabIndex = 0; row.setAttribute('role', 'button');
      row.setAttribute('aria-label', `从 ${formatTime(item.startMs / 1000)} 播放此段`);
      on(row, 'click', () => seek(item.startMs / 1000, true));
      on(row, 'keydown', event => {
        // 姓名编辑等嵌套控件保留原生键盘行为，不让 Enter/Space 同时触发整段播放。
        if (event.target !== row) return;
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); seek(item.startMs / 1000, true); }
      });
    }
    for (const event of ['wheel', 'touchstart', 'pointerdown', 'keydown']) {
      on(scroll, event, () => { follow = false; update(); }, { passive: true });
    }
    on(get('follow-playback'), 'click', () => { follow = true; update(); positionActive(); });
    update();
    if (!audio.paused) playTimer = window.setInterval(update, 50);
  } else {
    audio.pause(); audio.removeAttribute('src'); audio.load();
  }

  // 图源仍为标准截图产物；只做预览，不再加工／去重，也不将图片发往新服务。
  const frames = result.presentationFrames || [];
  const dialog = get('image-preview');
  let frameIndex = 0, zoom = 1;
  const showFrame = () => {
    const frame = frames[frameIndex]; if (!frame) return;
    get('preview-image').src = `data:image/jpeg;base64,${frame.jpegBase64}`;
    get('preview-image').alt = `会议 ${formatTime(frame.offsetMs / 1000)} 的演示画面`;
    get('preview-image').style.width = `${zoom * 100}%`;
    get('preview-image').style.height = zoom === 1 ? 'calc(94dvh - 60px)' : 'auto';
    get('preview-caption').textContent = `${frameIndex + 1} / ${frames.length} · ${formatTime(frame.offsetMs / 1000)}`;
    get('preview-previous').disabled = frameIndex === 0;
    get('preview-next').disabled = frameIndex === frames.length - 1;
  };
  for (const [index, image] of [...get('presentation-frames').querySelectorAll('img')].entries()) {
    image.tabIndex = 0; image.setAttribute('role', 'button');
    const open = () => { frameIndex = index; zoom = 1; showFrame(); dialog.showModal(); };
    on(image, 'click', open);
    on(image, 'keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open(); } });
  }
  on(get('preview-close'), 'click', () => dialog.close());
  on(get('preview-previous'), 'click', () => { frameIndex = Math.max(0, frameIndex - 1); zoom = 1; showFrame(); });
  on(get('preview-next'), 'click', () => { frameIndex = Math.min(frames.length - 1, frameIndex + 1); zoom = 1; showFrame(); });
  on(get('preview-zoom-out'), 'click', () => { zoom = Math.max(1, zoom - .5); showFrame(); });
  on(get('preview-zoom-in'), 'click', () => { zoom = Math.min(3, zoom + .5); showFrame(); });
}

/** 浏览器的打印／保存PDF能力；打开同源空白页，仅复制本场标准图片，不访问旧业务PDF。 */
async function printPresentation(result, page, message) {
  const popup = page.defaultView.open('', '_blank');
  if (!popup) { message('请允许弹出打印窗口后重试'); return; }
  const doc = popup.document;
  doc.title = `${result.title || '会议'}-演示文稿`;
  const style = doc.createElement('style');
  style.textContent = '@page{size:A4 landscape;margin:12mm}body{font-family:sans-serif}figure{margin:0;break-after:page}img{width:100%;max-height:165mm;object-fit:contain}figcaption{font-size:12px}figure:last-child{break-after:auto}';
  doc.head.append(style);
  for (const frame of result.presentationFrames) {
    const figure = doc.createElement('figure'), image = doc.createElement('img'), caption = doc.createElement('figcaption');
    image.src = `data:image/jpeg;base64,${frame.jpegBase64}`;
    caption.textContent = formatTime(frame.offsetMs / 1000); figure.append(image, caption); doc.body.append(figure);
  }
  await Promise.all([...doc.images].map(image => image.decode()));
  popup.focus(); popup.print();
}
