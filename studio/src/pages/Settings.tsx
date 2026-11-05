import { useState } from 'react';
import { App, Alert, Button, Checkbox, Input, InputNumber, Modal, Select, Switch, Tag } from 'antd';
import { ArrowLeftOutlined, DeleteOutlined, FolderOpenOutlined, PlusOutlined, SaveOutlined } from '@ant-design/icons';
import { useStudio } from '../context';
import { AppConfig, SettingsData } from '../types';
import { errorText } from '../api';
import { canonicalPath, uniquePaths } from '../utils/gallery-media';
import { chooseDirectory, openLocal } from '../native';

export function Settings() {
  const { settings, saveSettings, platforms, back } = useStudio();
  const { message } = App.useApp();
  const [draft, setDraft] = useState<SettingsData>(() => structuredClone(settings));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [dirty, setDirty] = useState(false);
  const update = (patch: Partial<SettingsData>) => { setDraft(value => ({ ...value, ...patch })); setDirty(true); };
  const config = (patch: Partial<AppConfig>) => { setDraft(value => ({ ...value, config: { ...value.config, ...patch } })); setDirty(true); };
  const save = async () => {
    if (saving) return;
    setSaving(true); setError('');
    try {
      if (!draft.outDir.trim()) throw new Error('请选择保存目录');
      if (!draft.config.folderTemplate.trim() || !draft.config.fileTemplate.trim()) throw new Error('归档模板不能为空');
      if (draft.config.connections < 1 || draft.config.connections > 16 || draft.config.timeoutSec < 10 || draft.config.timeoutSec > 3600) throw new Error('连接数范围 1–16，超时范围 10–3600 秒');
      if (draft.config.cookies.some(entry => !entry.platform || !entry.cookie.trim())) throw new Error('Cookie 条目需要选择平台并填写 Cookie，或移除空条目');
      if (draft.config.aliases.some(alias => !alias.platform || !alias.account.trim() || !alias.creator.trim())) throw new Error('请补全作者映射的平台、匹配值与归档作者');
      await saveSettings({ ...draft, galleryRoots: uniquePaths(draft.galleryRoots).filter(root => canonicalPath(root) !== canonicalPath(draft.outDir)) }); setDirty(false); message.success('设置已保存');
    } catch (error) { setError(errorText(error)); }
    finally { setSaving(false); }
  };
  const selectOutput = async () => {
    try { const selected = await chooseDirectory(draft.outDir); if (selected) update({ outDir: selected }); }
    catch (error) { message.error(errorText(error)); }
  };
  const addRoot = async () => {
    try { const selected = await chooseDirectory(draft.outDir); if (selected && !([draft.outDir, ...draft.galleryRoots].some(root => canonicalPath(root) === canonicalPath(selected)))) update({ galleryRoots: [...draft.galleryRoots, selected] }); }
    catch (error) { message.error(errorText(error)); }
  };
  return <div className="page settings-page">
    <header className="page-heading"><div><Button type="text" icon={<ArrowLeftOutlined />} onClick={back}>返回 · Esc</Button><h1>设置</h1><p className="muted">解析偏好、归档方式与本地图库。</p></div><Button type="primary" icon={<SaveOutlined />} loading={saving} disabled={!dirty} onClick={() => void save()}>保存设置</Button></header>
    {error && <Alert type="error" showIcon message="保存失败" description={error} />}
    <section className="paper-card settings-section"><h2>外观与解析</h2>
      <label className="setting-row"><span><strong>界面主题</strong><small>跟随系统会随系统外观自动切换</small></span><Select value={draft.config.theme} onChange={theme => config({ theme })} options={[{ value: 'system', label: '跟随系统' }, { value: 'light', label: '浅色' }, { value: 'dark', label: '深色' }]} /></label>
      <label className="setting-row"><span><strong>默认视频档位</strong><small>解析后自动选择，可在解析页更改</small></span><Select value={draft.config.defaultTier} onChange={defaultTier => config({ defaultTier })} options={[{ value: 'origin', label: '原画优先' }, { value: 'h264', label: 'H.264 优先' }, { value: 'small', label: '最小文件' }, { value: 'none', label: '不自动选择' }]} /></label>
      <label className="setting-row"><span><strong>默认下载图集</strong><small>包含实况照片的动态视频</small></span><Switch checked={draft.config.imagesByDefault} onChange={imagesByDefault => config({ imagesByDefault })} /></label>
    </section>
    <section className="paper-card settings-section"><h2>保存与归档</h2>
      <label className="field-label">保存目录<div className="path-input"><Input value={draft.outDir} onChange={event => update({ outDir: event.target.value })} /><Button icon={<FolderOpenOutlined />} onClick={() => void selectOutput()}>选择目录</Button></div></label>
      <label className="field-label">文件夹模板<Input value={draft.config.folderTemplate} onChange={event => config({ folderTemplate: event.target.value })} /></label>
      <label className="field-label">文件名模板<Input value={draft.config.fileTemplate} onChange={event => config({ fileTemplate: event.target.value })} /></label>
      <p className="muted text-sm">支持 {'{platform} {author} {creator} {title} {id} {publish_date} {date} {time} {index} {type}'}；序号可写成 {'{index:03d}'}。模板由内核校验，保存后生效。</p>
      <label className="setting-row"><span><strong>合并同作者目录</strong><small>同一作者归档到同一文件夹</small></span><Switch checked={draft.config.mergeAuthorFolders} onChange={mergeAuthorFolders => config({ mergeAuthorFolders })} /></label>
      <label className="setting-row"><span><strong>保存作品元数据 JSON</strong></span><Switch checked={draft.config.saveJson} onChange={saveJson => config({ saveJson })} /></label>
      <label className="setting-row"><span><strong>下载连接数</strong></span><InputNumber min={1} max={16} precision={0} value={draft.config.connections} onChange={value => config({ connections: value ?? 4 })} /></label>
      <label className="setting-row"><span><strong>下载超时（秒）</strong></span><InputNumber min={10} max={3600} precision={0} value={draft.config.timeoutSec} onChange={value => config({ timeoutSec: value ?? 180 })} /></label>
    </section>
    <section className="paper-card settings-section"><div className="row-between"><h2>图库目录</h2><Button icon={<PlusOutlined />} onClick={() => void addRoot()}>添加目录</Button></div><p className="muted">保存目录始终显示在图库中。额外目录仅用于浏览，移除不会删除文件。</p>
      <div className="root-setting"><Tag>保存目录</Tag><span className="truncate" title={draft.outDir}>{draft.outDir}</span></div>
      {draft.galleryRoots.map((root, index) => <div className="root-setting" key={`${root}-${index}`}><FolderOpenOutlined /><span className="truncate" title={root}>{root}</span><Button type="text" danger aria-label={`移除图库目录 ${root}`} icon={<DeleteOutlined />} onClick={() => update({ galleryRoots: draft.galleryRoots.filter((_, i) => i !== index) })} /></div>)}
    </section>
    <section className="paper-card settings-section"><div className="row-between"><h2>平台 Cookie 池</h2><Button icon={<PlusOutlined />} onClick={() => config({ cookies: [...draft.config.cookies, { platform: platforms[0]?.key || '', label: '', cookie: '', enabled: true }] })}>添加 Cookie</Button></div><p className="muted">可选。仅供解析内核按平台轮换使用，请勿共享包含 Cookie 的设置文件。</p>
      {draft.config.cookies.map((entry, index) => <div className="settings-entry" key={index}><div className="row-wrap"><Select aria-label="Cookie 平台" value={entry.platform} onChange={platform => config({ cookies: draft.config.cookies.map((item, i) => i === index ? { ...item, platform } : item) })} options={platforms.map(platform => ({ value: platform.key, label: platform.label }))} /><Input aria-label="Cookie 标签" placeholder="标签（可选）" value={entry.label} onChange={event => config({ cookies: draft.config.cookies.map((item, i) => i === index ? { ...item, label: event.target.value } : item) })} /><Checkbox checked={entry.enabled} onChange={event => config({ cookies: draft.config.cookies.map((item, i) => i === index ? { ...item, enabled: event.target.checked } : item) })}>启用</Checkbox><Button type="text" danger aria-label="删除 Cookie 条目" icon={<DeleteOutlined />} onClick={() => config({ cookies: draft.config.cookies.filter((_, i) => i !== index) })} /></div><Input.Password autoComplete="off" placeholder="粘贴该平台 Cookie" value={entry.cookie} onChange={event => config({ cookies: draft.config.cookies.map((item, i) => i === index ? { ...item, cookie: event.target.value } : item) })} /></div>)}
    </section>
    <section className="paper-card settings-section"><div className="row-between"><h2>作者映射</h2><Button icon={<PlusOutlined />} onClick={() => config({ aliases: [...draft.config.aliases, { platform: platforms[0]?.key || '', account: '', creator: '', matchBy: 'id' }] })}>添加映射</Button></div><p className="muted">将不同平台的同一作者归档到统一名称，优先按账号 ID 匹配。</p>
      {draft.config.aliases.map((alias, index) => <div className="alias-entry" key={index}><Select aria-label="映射平台" value={alias.platform} options={platforms.map(platform => ({ value: platform.key, label: platform.label }))} onChange={platform => config({ aliases: draft.config.aliases.map((item, i) => i === index ? { ...item, platform } : item) })} /><Select aria-label="匹配方式" value={alias.matchBy} options={[{ value: 'id', label: '账号 ID' }, { value: 'name', label: '作者昵称' }]} onChange={matchBy => config({ aliases: draft.config.aliases.map((item, i) => i === index ? { ...item, matchBy } : item) })} /><Input aria-label="作者匹配值" placeholder="匹配值" value={alias.account} onChange={event => config({ aliases: draft.config.aliases.map((item, i) => i === index ? { ...item, account: event.target.value } : item) })} /><Input aria-label="归档作者" placeholder="归档作者" value={alias.creator} onChange={event => config({ aliases: draft.config.aliases.map((item, i) => i === index ? { ...item, creator: event.target.value } : item) })} /><Button type="text" danger aria-label="删除作者映射" icon={<DeleteOutlined />} onClick={() => config({ aliases: draft.config.aliases.filter((_, i) => i !== index) })} /></div>)}
    </section>
    <section className="paper-card settings-section"><h2>关于 ParseHub Studio</h2><p>版本 {PACKAGE_JSON_VERSION} · {PACKAGE_JSON_LICENSE}</p><p className="muted">界面与图库参考 X-Spider，保留媒体卡片、视频缩略图与灯箱交互。解析与下载由 ParseHub 多平台内核提供。</p><div className="row-wrap"><Button onClick={() => void openLocal('https://github.com/obihs2501/parsehub-kmp').catch(error => message.error(errorText(error)))}>项目主页</Button><Button onClick={() => void openLocal('https://github.com/obihs2501/x-spider-mod-2026').catch(error => message.error(errorText(error)))}>X-Spider 参考项目</Button><Button onClick={() => Modal.info({ title: '开源许可', content: <p>ParseHub Studio 使用 GPL-3.0-only 许可。图库部分由 X-Spider 参考源码改编，来源与改编范围记录在 src/REFERENCE.txt。</p> })}>开源声明</Button></div></section>
    <div className="settings-save-bar"><span className="muted">{dirty ? '有未保存的更改，离开设置将放弃这些更改' : '所有更改已保存'}</span><Button type="primary" loading={saving} disabled={!dirty} onClick={() => void save()}>保存设置</Button></div>
  </div>;
}
