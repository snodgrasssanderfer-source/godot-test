import { useState } from 'react'
import { ChatsCircle } from '@phosphor-icons/react'
import { field, nativeCompanion, type Entry, type Run, type Snapshot } from './bridge'
import { SocialPanel } from './social/SocialPanel'
import { PageHeader, Section } from './ui'

const states: Record<string, string> = {queued: '排队', running: '处理中', waiting: '等待恢复', draft: '待审', approved: '已放行', sending: '发送中', unknown: '待核对', done: '完成', failed: '失败', cancelled: '已取消'}

export function ActivityPage({snapshot, run}: {snapshot: Snapshot; run: Run}) {
  const s = snapshot.settings
  const tasks = snapshot.tasks.slice().reverse().filter(t => !['done', 'cancelled'].includes(field(t, 'state')))
  return <div className="xk-scroll"><div className="xk-page">
    <PageHeader title="动态"/>
    <label className="xk-card xk-switch-row xk-master">
      <span><strong>让她自己出去逛</strong><small>{s.activityEnabled ? '会在群里、B站和空间里自己找事做' : '现在只在你找她时说话'}</small></span>
      <input type="checkbox" checked={s.activityEnabled === true} onChange={e => void run('settings', {...s, activityEnabled: e.target.checked}).catch(() => undefined)}/>
    </label>
    <SocialPanel state={snapshot.modules?.social ?? {}} available={nativeCompanion && snapshot.modules?.social.available === true} run={(action, data) => run('module', {domain: 'social', action, data})}/>
    <Section icon={<ChatsCircle size={20} weight="duotone"/>} title="聊天任务与旧版草稿" summary={tasks.length ? `${tasks.length} 条未完成` : '没有未完成的'}>
      {tasks.length === 0 ? <p className="xk-hint">都处理完了。</p> : <div className="xk-stack">{tasks.map(t => <TaskCard key={t.id} task={t} run={run}/>)}</div>}
    </Section>
  </div></div>
}

function TaskCard({task, run}: {task: Entry; run: Run}) {
  const [body, setBody] = useState(field(task, 'body')), [account, setAccount] = useState(field(task, 'account'))
  const state = field(task, 'state'), isSend = field(task, 'type') === 'send', isBili = task.package === 'tv.danmaku.bili'
  const edited = body !== field(task, 'body') || (isBili && account !== field(task, 'account'))
  const execute = (action: string, data: object = {}) => void run(action, {id: task.id, ...data}).catch(() => undefined)
  return <article className="xk-card inset">
    <div className="xk-card-head"><h3>{isSend ? `${isBili ? 'B站评论' : 'QQ 回复'} · ${field(task, 'target')}` : '聊天任务'}</h3><span className={'xk-pill state-' + state}>{states[state] ?? state}</span></div>
    {isSend && <label>正文<textarea rows={4} value={body} maxLength={4000} disabled={!['draft', 'approved'].includes(state)} onChange={e => setBody(e.target.value)}/></label>}
    {isBili && <label>B站账号<input value={account} disabled={!['draft', 'approved'].includes(state)} maxLength={80} onChange={e => setAccount(e.target.value)} placeholder="昵称或 UID"/></label>}
    {field(task, 'progress') && <p className="xk-hint">{field(task, 'progress')}</p>}
    {field(task, 'error') && <p className="xk-error-text">{field(task, 'error')}</p>}
    <div className="xk-actions">
      {isSend && ['draft', 'approved'].includes(state) && edited && <button className="xk-btn" onClick={() => execute('editDraft', {body, ...(isBili ? {account} : {})})}>保存修改</button>}
      {isSend && state === 'draft' && !edited && <button className="xk-btn primary" disabled={isBili && !account.trim()} onClick={() => { if (confirm(`发给「${field(task, 'target')}」？`)) execute('approve') }}>放行</button>}
      {isSend && task.route !== 'onebot' && ['draft', 'approved'].includes(state) && <button className="xk-btn" onClick={() => execute('prepareUi')}>打开目标手动发</button>}
      {isSend && state === 'approved' && task.package === 'com.tencent.mobileqq' && <button className="xk-btn primary" onClick={() => execute('send', {route: task.route === 'onebot' ? 'onebot' : 'notification'})}>发送</button>}
      {state === 'unknown' && <><button className="xk-btn" onClick={() => execute('resolveUnknown', {delivered: true})}>确认发出了</button><button className="xk-btn" onClick={() => execute('resolveUnknown', {delivered: false})}>没发出去</button></>}
      {!isSend && ['failed', 'waiting', 'cancelled'].includes(state) && <button className="xk-btn" onClick={() => execute('retry')}>重试</button>}
      {!['done', 'cancelled', 'unknown', 'sending'].includes(state) && <button className="xk-btn ghost" onClick={() => execute('cancel')}>取消</button>}
    </div>
  </article>
}
