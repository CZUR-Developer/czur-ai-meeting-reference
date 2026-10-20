#!/usr/bin/env node

/**
 * 校验设备实际 WebView 的同场结果及录音播放；只读页面，不创建或修改会议。
 *
 * 先将本 App 的 webview_devtools_remote_<pid> 转发到本机端口，再运行本脚本。
 * 使用 Node.js 22+ 的内置 WebSocket，不需要 npm 安装。只输出状态和数量，不输出
 * 会议正文、账号、Token 或结果链接。--reload 重新加载页面；--play/--seek 会短暂
 * 播放录音，结束后暂停。--interactions 操作工作区按钮、播放及预览后暂停；
 * 没有这些交互参数时不主动播放。
 */
import { checkResultInteractions } from '../../web-reference/tools/result-ui-checks.mjs';

const argumentsByName = new Map(process.argv.slice(2).map(value => {
  const [name, ...parts] = value.replace(/^--/, '').split('=');
  return [name, parts.length ? parts.join('=') : true];
}));
const port = Number(argumentsByName.get('port') || 19094);
const meetingId = argumentsByName.get('meeting-id');
const required = String(argumentsByName.get('expect') || 'transcript').split(',');
if (!Number.isInteger(port) || port < 1 || port > 65535 || !meetingId) {
  console.error('用法：node check-web-result.mjs --meeting-id=mtg_... [--port=19094] '
    + '[--expect=transcript,translation,summary,audio,identity,presentation] [--reload] [--play] [--seek] [--interactions]');
  process.exit(1);
}

let socket;
try {
  const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
  const target = targets.find(item => {
    try { return new URL(item.url).searchParams.get('meetingId') === meetingId; }
    catch { return false; }
  });
  if (!target?.webSocketDebuggerUrl) throw new Error('未找到本场结果的 WebView 调试页面');
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', () => reject(new Error('WebView 调试连接失败')), { once: true });
  });
  let nextId = 0;
  const pending = new Map();
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    clearTimeout(request.timeout);
    if (message.error) request.reject(new Error('WebView 调试命令失败'));
    else request.resolve(message.result);
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++nextId;
    const timeout = setTimeout(() => {
      pending.delete(id);
      reject(new Error('WebView 校验超时'));
    }, 25000);
    pending.set(id, { resolve, reject, timeout });
    socket.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async expression => {
    const result = await send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true, userGesture: true,
    });
    if (result.exceptionDetails) throw new Error('结果页面校验未通过，请查看页面状态');
    return result.result.value;
  };
  if (argumentsByName.has('reload')) await send('Page.reload', { ignoreCache: true });
  const state = await evaluate(`(async () => {
    // 未选中的tab也包含已生成产物；可用性与当前可见性分别检查。
    const visible = id => { const node = document.getElementById(id);
      return !!node && (node.dataset.available == null ? !node.hidden : node.dataset.available === 'true'); };
    for (let attempt = 0; attempt < 60; attempt++) {
      if (document.querySelector('#status')?.textContent === '结果已生成') break;
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    const audio = document.querySelector('#audio-player');
    const images = [...document.querySelectorAll('#presentation-frames img')];
    // 图片可能位于未选中的演示标签；逐张解码核实际内容，避免只验容器可用。
    if (${required.includes('presentation')}) for (const image of images) {
      image.loading = 'eager';
      try { await image.decode(); } catch { /* 数量核对会将失败标为 FAIL。 */ }
    }
    const loadedImages = images.filter(image => image.complete && image.naturalWidth > 0).length;
    return {
      ready: document.querySelector('#status')?.textContent === '结果已生成',
      transcript: visible('result'), translation: visible('translation-result'),
      summary: visible('summary-result'), audio: visible('audio-result'),
      identity: visible('speaker-identity-result'),
      presentation: visible('presentation-result') && images.length > 0 && loadedImages === images.length,
      presentationFrames: images.length, loadedPresentationFrames: loadedImages,
      transcriptSegments: document.querySelector('#transcript-segments')?.children.length || 0,
      translationSegments: document.querySelectorAll('.translation-item').length,
      identityCount: document.querySelector('#speaker-identities')?.children.length || 0,
      audioDurationSeconds: Number.isFinite(audio?.duration) ? audio.duration : null,
    };
  })()`);
  if (!state.ready || required.some(name => state[name] !== true)) {
    console.log(JSON.stringify({ status: 'FAIL', stage: 'result', ...state }));
    process.exitCode = 1;
  } else {
    if (argumentsByName.has('play') || argumentsByName.has('seek')) {
      state.playback = await evaluate(`(async () => {
        const audio = document.querySelector('#audio-player');
        if (!audio) return { played: false, seeked: false };
        const seek = ${argumentsByName.has('seek')};
        let seeked = false;
        try {
          await audio.play();
          if (seek) {
            for (let attempt = 0; attempt < 20 && !Number.isFinite(audio.duration); attempt++) {
              await new Promise(resolve => setTimeout(resolve, 250));
            }
            if (!Number.isFinite(audio.duration) || audio.duration <= 2) {
              return { played: false, seeked: false };
            }
            const target = Math.min(audio.duration / 2, 10);
            audio.currentTime = target;
            for (let attempt = 0; attempt < 20 && audio.seeking; attempt++) {
              await new Promise(resolve => setTimeout(resolve, 100));
            }
            seeked = !audio.seeking && Math.abs(audio.currentTime - target) < 2;
          }
          const before = audio.currentTime;
          await new Promise(resolve => setTimeout(resolve, 1200));
          return { played: audio.currentTime > before + 0.2, seeked,
            durationSeconds: Number.isFinite(audio.duration) ? audio.duration : null };
        } finally { audio.pause(); }
      })()`);
      if (!state.playback.played || (argumentsByName.has('seek') && !state.playback.seeked)) {
        process.exitCode = 1;
      }
    }
    if (argumentsByName.has('interactions')) {
      state.interactions = await evaluate(`(${checkResultInteractions.toString()})()`);
    }
    console.log(JSON.stringify({ status: process.exitCode ? 'FAIL' : 'PASS', stage: 'result', ...state }));
  }
} catch (error) {
  console.error(JSON.stringify({ status: 'FAIL', message: error.message }));
  process.exitCode = 1;
} finally { socket?.close(); }
