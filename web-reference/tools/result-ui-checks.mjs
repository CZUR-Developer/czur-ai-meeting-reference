/**
 * 同一组正常路径交互校验供浏览器和设备CDP复用；在页面上下文中运行。
 * 只切换栏目、预览及播放，不登录、不修改会议。仅返回布尔回执，不返回正文和链接。
 */
export async function checkResultInteractions() {
  const get = id => document.getElementById(id);
  const shown = node => !!node && node.getClientRects().length > 0;
  const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
  const check = (value, label) => { if (!value) throw new Error(`交互校验失败：${label}`); };
  const checks = {};
  check(!!get('result-workspace'), '结果工作区');
  check(document.documentElement.scrollWidth <= innerWidth + 1, '无水平溢出');
  checks.layout = true;
  const nav = innerWidth <= 768 ? get('mobile-tabs') : get('result-tabs');
  const active = nav.querySelector('[aria-selected="true"]');
  const ids = { original: 'original-panel', summary: 'summary-result', todos: 'todos-result',
    presentation: 'presentation-result', identity: 'speaker-identity-result' };
  for (const tab of nav.children) {
    tab.click(); check(shown(get(ids[tab.dataset.tab])), `栏目${tab.dataset.tab}`);
  }
  active?.click();
  checks.tabs = true;
  if (!get('toggle-translation').hidden) {
    get('toggle-translation').click();
    check(get('original-panel').classList.contains('hide-translation'), '隐藏译文');
    get('toggle-translation').click(); checks.translation = true;
  }
  if (get('audio-result').dataset.available === 'true') {
    const audio = get('audio-player');
    try {
      get('audio-play').click();
      for (let i = 0; i < 30 && (audio.paused || !Number.isFinite(audio.duration)); i++) await wait(100);
      check(!audio.paused && Number.isFinite(audio.duration), '播放按钮');
      const first = audio.currentTime; await wait(400);
      check(audio.currentTime > first, '真实录音推进');
      get('audio-play').click(); check(audio.paused, '暂停按钮');
      const duration = audio.duration;
      const seek = get('audio-seek'); seek.value = String(Math.min(5, duration / 4));
      seek.dispatchEvent(new Event('input')); seek.dispatchEvent(new Event('change'));
      await wait(200); check(Math.abs(audio.currentTime - Number(seek.value)) < .5, '拖动进度');
      const before = audio.currentTime;
      get('audio-forward').click(); await wait(200);
      check(Math.abs(audio.currentTime - Math.min(duration, before + 15)) < .5, '前进15秒');
      get('audio-back').click(); await wait(200);
      check(Math.abs(audio.currentTime - Math.max(0, Math.min(duration, before + 15) - 15)) < .5, '后退15秒');
      get('audio-rate').value = '1.5'; get('audio-rate').dispatchEvent(new Event('change'));
      check(audio.playbackRate === 1.5, '播放倍速');
      const rows = [...document.querySelectorAll('[data-seekable="true"]')];
      if (rows.length) {
        const row = rows[Math.min(1, rows.length - 1)];
        const seconds = row.querySelector('.segment-time').textContent.split(':').reduce((n, p) => n * 60 + Number(p), 0);
        row.click(); await wait(300); audio.pause();
        check(Math.abs(audio.currentTime - Math.min(seconds, duration)) < 1.5, '原文点击定位');
        check(document.querySelectorAll('.transcript-segment.active').length === 1, '当前发言高亮');
        get('original-scroll').dispatchEvent(new Event('wheel'));
        check(!get('follow-playback').hidden, '手动滚动暂停跟随');
        get('follow-playback').click(); check(get('follow-playback').hidden, '恢复跟随');
        checks.transcriptSeek = true;
      }
      checks.player = true;
    } finally {
      audio.pause(); audio.playbackRate = 1; get('audio-rate').value = '1';
    }
  }
  if (get('presentation-result').dataset.available === 'true') {
    nav.querySelector('[data-tab="presentation"]').click();
    get('presentation-frames').querySelector('img').click();
    check(get('image-preview').open, '图片大图');
    await get('preview-image').decode();
    const before = get('preview-caption').textContent;
    if (!get('preview-next').disabled) {
      get('preview-next').click(); check(get('preview-caption').textContent !== before, '下一张');
      get('preview-previous').click(); check(get('preview-caption').textContent === before, '上一张');
    }
    get('preview-zoom-in').click(); check(get('preview-image').style.width === '150%', '图片放大');
    get('preview-zoom-out').click(); check(get('preview-image').style.width === '100%', '图片缩小');
    get('preview-close').click(); check(!get('image-preview').open, '退出大图');
    active?.click(); checks.preview = true;
  }
  if (/\bwv\b/i.test(navigator.userAgent)) {
    check(get('export-menu').hidden && get('share-result').hidden, 'WebView不提供无宿主下载');
    checks.webViewActions = true;
  }
  return checks;
}
