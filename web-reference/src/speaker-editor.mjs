/** 人工名称优先于自动识别；显式 null 表示本段仍显示编号，不被后来登记连带改名。 */
export function speakerDisplayName(segment, identities = []) {
  const name = Object.hasOwn(segment, 'speakerDisplayName') ? segment.speakerDisplayName
    : identities.find(item => item.sessionSpeakerId === segment.sessionSpeakerId)?.displayName;
  return name || (segment.sessionSpeakerId ? `发言人 ${segment.sessionSpeakerId}` : '发言人未知');
}

/** 本场真实身份产物代表本场取得过声纹授权；单有部署能力不能补签历史会议。 */
export function canRememberSpeaker(result, speakerId, discovery) {
  return discovery.capabilities?.includes('speaker.identity.cross_meeting') === true
    && result.speakerIdentity?.speakers?.some(item => item.sessionSpeakerId === speakerId) === true;
}

export function registrationMessage(status) {
  switch (status) {
    case 'REGISTERED': return '修改成功，已记录，下次将自动识别';
    case 'NOT_REQUESTED': return '修改成功';
    case 'SAMPLE_UNAVAILABLE': return '本场名称已保存；语音样本不足，暂未记录下次自动识别';
    case 'NOT_GRANTED': return '本场名称已保存；本场未启用跨会议识别';
    default: return '本场名称已保存；下次自动识别暂未登记成功，可稍后重试';
  }
}

/**
 * 同一编辑弹窗供桌面、窄屏和 Android WebView 使用。身份来自可替换 actions，
 * 不接 CZUR 私有账号；写完重读同一结果，不在浏览器伪造保存成功。
 * 捕获姓名按钮点击，避免同时触发下方原文的录音定位。监听只绑定一次。
 */
export function bindSpeakerNameEditing(page, actions, initialResult, render, discovery) {
  const get = id => page.querySelector(`#${id}`);
  const dialog = get('speaker-edit-dialog');
  const fields = get('speaker-edit-fields');
  const login = get('speaker-edit-login');
  const status = get('speaker-edit-status');
  let current = initialResult, selected = null, busy = false;
  const syncLogin = () => {
    login.hidden = actions.isLoggedIn();
    fields.disabled = busy || !actions.isLoggedIn();
  };
  get('transcript-segments').addEventListener('click', event => {
    const button = event.target.closest('[data-edit-speaker]');
    if (!button) return;
    event.preventDefault(); event.stopPropagation();
    selected = current.segments.find(item => item.segmentId === button.dataset.editSpeaker);
    if (!selected || busy) return;
    get('speaker-edit-name').value = speakerDisplayName(selected, current.speakerIdentity?.speakers);
    const available = canRememberSpeaker(current, selected.sessionSpeakerId, discovery);
    get('speaker-remember-row').hidden = !available;
    get('speaker-remember').checked = available;
    status.textContent = '';
    syncLogin(); dialog.showModal();
    (actions.isLoggedIn() ? get('speaker-edit-name') : get('speaker-edit-username')).focus();
  }, true);
  get('speaker-edit-close').onclick = () => { if (!busy) dialog.close(); };
  dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
  dialog.addEventListener('close', () => { get('speaker-edit-password').value = ''; });
  login.addEventListener('submit', async event => {
    event.preventDefault();
    const button = get('speaker-edit-login-button');
    button.disabled = true; status.textContent = '正在登录…';
    try {
      await actions.login(get('speaker-edit-username').value, get('speaker-edit-password').value);
      status.textContent = '已登录，请选择修改范围';
    } catch (error) { status.textContent = error.message; }
    finally { get('speaker-edit-password').value = ''; button.disabled = false; syncLogin(); }
  });
  get('speaker-edit-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (busy || !selected) return;
    const scope = event.submitter?.value || 'ALL';
    const remember = !get('speaker-remember-row').hidden && get('speaker-remember').checked;
    busy = true; syncLogin(); status.textContent = '正在保存…';
    try {
      const saved = await actions.rename(selected.sessionSpeakerId,
        get('speaker-edit-name').value, scope, selected.segmentId, remember);
      current = { ...saved.result, speakerEditingEnabled: true };
      render(current, page);
      const message = registrationMessage(saved.registration);
      status.textContent = message;
      get('status').textContent = message;
      page.dispatchEvent(new page.defaultView.CustomEvent('result-action-message', { detail: message }));
      // 声纹登记未完成时保留弹窗及明确提示；本场名称已经持久保存，可取消或重试。
      if (saved.registration === 'REGISTERED' || saved.registration === 'NOT_REQUESTED') dialog.close();
    } catch (error) { status.textContent = error.message; }
    finally { busy = false; syncLogin(); }
  });
}
