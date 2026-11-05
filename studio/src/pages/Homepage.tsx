import { useRef, useState } from 'react';
import { App, Alert, Button, Checkbox, Empty, Input, Tag } from 'antd';
import { DownloadOutlined, LinkOutlined, CopyOutlined } from '@ant-design/icons';
import { clipboard, shell } from '@tauri-apps/api';
import { errorText, request, safeRemoteImage } from '../api';
import { useStudio } from '../context';
import { Parsed, TaskState, Tier } from '../types';
import { formatBytes, formatDuration } from '../utils/gallery-media';

function defaultPick(tiers: Tier[], preference: string): number[] {
  if (preference === 'none' || tiers.length === 0) return [];
  if (preference === 'origin') return [(tiers.find(t => t.isOrigin) || tiers[0]).idx];
  if (preference === 'h264') return [(tiers.find(t => !t.h265) || tiers[0]).idx];
  return [[...tiers].sort((a, b) => (a.size || a.bps || Infinity) - (b.size || b.bps || Infinity))[0].idx];
}
function statusLabel(status: string) {
  return ({ running: '下载中', cancelling: '取消中', cancelled: '已取消', done: '已完成', fail: '下载失败' } as Record<string, string>)[status] || status;
}
function TaskCard({ task }: { task: TaskState }) {
  const { refreshTasks, openGallery } = useStudio();
  const { message } = App.useApp();
  const [cancelling, setCancelling] = useState(false);
  const active = task.status === 'running' || task.status === 'cancelling';
  const percent = task.total > 0 ? Math.min(100, Math.max(0, task.done / task.total * 100)) : undefined;
  const cancel = async () => {
    setCancelling(true);
    try {
      await request(`/tasks/${encodeURIComponent(task.id)}/cancel`, 'POST');
      void refreshTasks().catch(() => undefined);
    }
    catch (error) { message.error(errorText(error)); setCancelling(false); }
  };
  return <article className="task-card" aria-live="polite">
    <div className="row-between"><strong>{statusLabel(task.status)}</strong><span className="muted">{task.doneJobs} / {task.totalJobs} 个文件</span></div>
    <p className="truncate" title={task.name}>{task.name || '准备下载…'}</p>
    {active && <><progress aria-label="当前文件下载进度" max={100} value={percent} /><div className="row-between muted text-sm"><span>{formatBytes(task.done) || '0 B'}{task.total > 0 && ` / ${formatBytes(task.total)}`}</span><span>{formatBytes(task.speed) || '0 B'}/s</span></div></>}
    {task.error && <Alert type="error" showIcon message={task.error} />}
    <div className="row-wrap">
      {active && <Button danger size="small" disabled={cancelling || task.status === 'cancelling'} onClick={() => void cancel()}>{cancelling ? '已请求取消…' : '取消下载'}</Button>}
      {task.files.length > 0 && <Button size="small" onClick={() => { const file = task.files[0]; openGallery(file.slice(0, Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')))); }}>在图库中查看已保存文件</Button>}
    </div>
  </article>;
}
export function Homepage() {
  const { settings, platforms, tasks, refreshTasks } = useStudio();
  const { message } = App.useApp();
  const [text, setText] = useState('');
  const [parsed, setParsed] = useState<Parsed | null>(null);
  const [pick, setPick] = useState<number[]>([]);
  const [imagesOn, setImagesOn] = useState(true);
  const [auxOn, setAuxOn] = useState<boolean[]>([]);
  const [creator, setCreator] = useState('');
  const [parsing, setParsing] = useState(false);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState('');
  const [taskId, setTaskId] = useState('');
  const requestSequence = useRef(0);
  const result = parsed?.result;
  const activeTask = tasks.find(t => t.status === 'running' || t.status === 'cancelling');
  const task = activeTask || tasks.find(t => t.id === taskId) || tasks[0];
  const parse = async () => {
    if (!text.trim() || parsing) return;
    const sequence = ++requestSequence.current;
    setParsing(true); setError(''); setParsed(null);
    try {
      const value = await request<Parsed>('/parse', 'POST', { text: text.trim() });
      if (sequence !== requestSequence.current) return;
      setParsed(value); setPick(defaultPick(value.result.tiers, settings.config.defaultTier));
      setImagesOn(settings.config.imagesByDefault && value.result.images.length > 0);
      setAuxOn(value.result.aux.map(() => false));
      const alias = ['id', 'name'].flatMap(matchBy => settings.config.aliases.filter(a => a.platform === value.result.platform && a.matchBy === matchBy && a.account === (matchBy === 'id' ? value.result.authorId : value.result.author) && a.account));
      setCreator(alias[0]?.creator || value.result.author);
      void refreshTasks().catch(() => undefined);
    } catch (error) { setError(errorText(error)); }
    finally { if (sequence === requestSequence.current) setParsing(false); }
  };
  const download = async () => {
    if (!parsed || starting) return;
    setStarting(true); setError('');
    try {
      const state = await request<TaskState>('/tasks', 'POST', { resultId: parsed.resultId, pick, imagesOn, auxOn, creator });
      setTaskId(state.id);
      void refreshTasks().catch(error => message.error(errorText(error)));
      message.success('已开始下载');
    } catch (error) { setError(errorText(error)); }
    finally { setStarting(false); }
  };
  const hasSelection = pick.length > 0 || imagesOn || auxOn.some(Boolean);
  return <div className="page parse-page">
    <header className="page-heading"><div><p className="eyebrow">PARSEHUB STUDIO</p><h1>解析工作台</h1><p className="muted">粘贴分享文案或链接，选择媒体并归档到本地。</p></div><LinkOutlined className="heading-icon" /></header>
    <section className="paper-card input-card">
      <label htmlFor="parse-text" className="section-label">分享链接 / 文案</label>
      <Input.TextArea id="parse-text" value={text} maxLength={16384} autoSize={{ minRows: 3, maxRows: 7 }} placeholder="粘贴任意受支持平台的作品链接或分享文案…" onChange={event => setText(event.target.value)} onKeyDown={event => { if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') { event.preventDefault(); void parse(); } }} />
      <div className="row-between"><span className="muted text-sm">Ctrl / ⌘ + Enter 解析</span><div className="row-wrap"><Button icon={<CopyOutlined />} onClick={() => { void clipboard.readText().then(value => { if (value) setText(value.slice(0, 16384)); else message.info('剪贴板中没有文本'); }).catch(error => message.error(errorText(error))); }}>粘贴</Button><Button type="primary" loading={parsing} disabled={!text.trim()} onClick={() => void parse()}>{parsing ? '正在解析' : '解析链接'}</Button></div></div>
      <p className="supported-platforms muted">支持 {platforms.map(p => p.label).join(' · ')}</p>
    </section>
    {error && <Alert className="page-alert" type="error" showIcon message="操作未完成" description={error} closable onClose={() => setError('')} />}
    {parsing && <div className="paper-card"><p className="muted">正在获取作品信息，请稍候…</p><progress aria-label="正在解析" /></div>}
    {result ? <section className="paper-card result-card" aria-busy={parsing}>
      <div className="result-summary">
        {safeRemoteImage(result.cover) && <img className="result-cover" src={safeRemoteImage(result.cover)} alt="作品封面" referrerPolicy="no-referrer" />}
        <div className="min-w-0"><Tag>{platforms.find(p => p.key === result.platform)?.label || result.platform}</Tag><h2>{result.title || '未命名作品'}</h2><p className="muted">{result.author || '未知作者'} · {result.date || '日期未知'}{result.durSec > 0 && ` · ${formatDuration(result.durSec)}`}</p><p className="muted text-sm">{result.statLine}</p></div>
      </div>
      <label className="field-label">归档作者<Input value={creator} maxLength={200} onChange={event => setCreator(event.target.value)} placeholder="作者名称" /></label>
      {result.tiers.length > 0 && <div><div className="row-between"><h3>视频档位</h3><Button type="text" size="small" onClick={() => setPick(pick.length === result.tiers.length ? [] : result.tiers.map(t => t.idx))}>{pick.length === result.tiers.length ? '取消全选' : '全选'}</Button></div><div className="tier-list">{result.tiers.map(tier => <label className={`tier-row ${pick.includes(tier.idx) ? 'selected' : ''}`} key={tier.idx}><Checkbox checked={pick.includes(tier.idx)} onChange={event => setPick(current => event.target.checked ? [...current, tier.idx] : current.filter(idx => idx !== tier.idx))} /><span className="grow"><strong>{tier.gear || `档位 ${tier.idx}`}</strong><small className="muted">{[tier.w && tier.h ? `${tier.w} × ${tier.h}` : '', tier.fps ? `${tier.fps}${tier.fpsEst ? '≈' : ''} fps` : '', tier.h265 ? 'H.265' : 'H.264', formatBytes(tier.size)].filter(Boolean).join(' · ')}</small></span>{tier.isOrigin && <Tag color="orange">原画</Tag>}</label>)}</div></div>}
      {result.images.length > 0 && <div><div className="row-between"><h3>图集 · {result.images.length} 张</h3><Checkbox checked={imagesOn} onChange={event => setImagesOn(event.target.checked)}>下载全部图片及实况视频</Checkbox></div><div className="image-strip">{result.images.slice(0, 24).map(image => <img key={image.index} src={safeRemoteImage(image.url)} alt={`图片 ${image.index + 1}`} loading="lazy" referrerPolicy="no-referrer" />)}{result.images.length > 24 && <span>+{result.images.length - 24}</span>}</div></div>}
      {result.aux.length > 0 && <div><h3>附属资源</h3>{result.aux.map((aux, index) => <label className="tier-row" key={`${aux.kind}-${index}`}><Checkbox checked={!!auxOn[index]} onChange={event => setAuxOn(current => current.map((value, i) => i === index ? event.target.checked : value))} /><span>{aux.label}<small className="muted">{aux.spec} · {aux.ext}</small></span></label>)}</div>}
      {!result.tiers.length && !result.images.length && !result.aux.length && <Empty description="解析完成，但未找到可下载媒体" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
      <div className="download-footer"><span className="muted text-sm truncate" title={settings.outDir}>保存到 {settings.outDir}</span><Button type="primary" size="large" icon={<DownloadOutlined />} loading={starting} disabled={!hasSelection || parsing || !!activeTask} onClick={() => void download()}>{activeTask ? '已有下载进行中' : '下载所选媒体'}</Button></div>
    </section> : !parsing && <div className="parse-empty"><LinkOutlined /><h2>从一条链接开始</h2><p>视频、图集与附属音轨，都在这里选择。</p></div>}
    {task && <section className="inline-tasks"><div className="row-between"><h2>下载进度</h2><Button type="text" size="small" onClick={() => void shell.open(settings.outDir).catch(error => message.error(errorText(error)))}>打开保存目录</Button></div><TaskCard key={task.id} task={task} /></section>}
  </div>;
}
