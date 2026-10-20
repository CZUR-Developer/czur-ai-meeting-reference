import assert from 'node:assert/strict';
import test from 'node:test';
import { speakerDisplayName, canRememberSpeaker, registrationMessage } from '../src/speaker-editor.mjs';
import { createSpeakerIdentityActions } from '../src/result.mjs';
import { resultText } from '../src/result-model.mjs';

test('人工名称优先；单个登记不连带改变已冻结的未命名段', () => {
  const people = [{ sessionSpeakerId: '1', displayName: '已登记' }];
  assert.equal(speakerDisplayName({ sessionSpeakerId: '1' }, people), '已登记');
  assert.equal(speakerDisplayName({ sessionSpeakerId: '1', speakerDisplayName: null }, people), '发言人 1');
  assert.equal(speakerDisplayName({ sessionSpeakerId: '1', speakerDisplayName: '本段名字' }, people), '本段名字');
});
test('缺配置或本场无身份产物时不提供自动识别', () => {
  const result = { speakerIdentity: { speakers: [{ sessionSpeakerId: '1' }] } };
  const discovery = { capabilities: ['speaker.identity.cross_meeting'] };
  assert.equal(canRememberSpeaker(result, '1', discovery), true);
  assert.equal(canRememberSpeaker(result, '2', discovery), false);
  assert.equal(canRememberSpeaker({}, '1', discovery), false);
  assert.equal(canRememberSpeaker(result, '1', {}), false);
});
test('复制与导出同样使用本场人工名称', () => {
  const output = resultText({ segments: [{ segmentId: 's', sessionSpeakerId: '1',
    speakerDisplayName: '新名字', text: '原文' }] }, 'original');
  assert.match(output, /新名字/);
  assert.doesNotMatch(output, /发言人 1/);
});
test('部分成功反馈不把本场保存冒充登记成功', () => {
  assert.equal(registrationMessage('NOT_REQUESTED'), '修改成功');
  assert.match(registrationMessage('REGISTERED'), /下次将自动识别/);
  for (const status of ['SAMPLE_UNAVAILABLE', 'NOT_GRANTED', 'UNAVAILABLE'])
    assert.match(registrationMessage(status), /本场名称已保存/);
});
test('编辑使用可替换登录和标准段落协议，保存后重读原链接', async () => {
  const calls = [];
  const actions = createSpeakerIdentityActions('http://api.test', 'mtg_demo', '123', async (url, options) => {
    calls.push({ url, options });
    return { ok: true, status: 200, json: async () => options
      ? { registration: 'NOT_REQUESTED' } : { artifacts: [] } };
  }, async () => ({ accessToken: 'synthetic-test-token' }));
  await assert.rejects(actions.rename('1', '甲', 'SINGLE', 's1', false), /先使用/);
  await actions.login('demo', 'synthetic');
  const saved = await actions.rename('1', '甲', 'SINGLE', 's1', false);
  assert.deepEqual(JSON.parse(calls[0].options.body),
    { displayName: '甲', scope: 'SINGLE', segmentId: 's1', remember: false });
  assert.match(calls[1].url, /manifest\?genTime=123$/);
  assert.equal(saved.registration, 'NOT_REQUESTED');
});
