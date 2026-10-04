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

  const artifact = Array.isArray(manifest.artifacts)
    ? manifest.artifacts.find(item => item.type === 'transcript.final'
        && item.status === 'READY')
    : undefined;
  if (!artifact) return { title: manifest.title, text: null };

  const response = await fetcher(
    `${base}/v1/artifacts/${encodeURIComponent(artifact.artifactId)}?${query}`);
  if (!response.ok) throw new Error(`读取最终转写失败：HTTP ${response.status}`);
  const content = await response.json();
  return { title: manifest.title, text: content.text };
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
    if (result.title) document.querySelector('#title').textContent = result.title;
    if (typeof result.text !== 'string') {
      status.textContent = '本场会议没有识别到字幕';
      return;
    }
    document.querySelector('#transcript').textContent = result.text;
    document.querySelector('#result').hidden = false;
    status.textContent = '结果已生成';
  } catch (error) {
    status.textContent = error.message;
  }
}

if (typeof document !== 'undefined') showResult();
