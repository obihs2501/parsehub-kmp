import { useEffect, useMemo, useRef, useState } from 'react';
import { App, Alert, Button, Collapse, Dropdown, Empty, Input, Modal, Select, Spin, Tag } from 'antd';
import { DeleteOutlined, DownloadOutlined, FolderOpenOutlined, ReloadOutlined } from '@ant-design/icons';
import { save } from '@tauri-apps/api/dialog';
import { writeTextFile } from '@tauri-apps/api/fs';
import { useStudio } from '../context';
import { errorText, request } from '../api';
import { HistoryEntry } from '../types';
import { openLocal } from '../native';
import { formatDateTime } from '../utils/gallery-media';

function recordedFiles(entry: HistoryEntry): { name: string; path: string }[] {
  if (!entry.dir) return [];
  return entry.fileNames.split('\n').map(name => name.trim()).filter(name => name && name !== '.' && name !== '..' && !/[\\/:\0]/.test(name)).map(name => ({ name, path: `${entry.dir.replace(/[\\/]+$/, '')}/${name}` }));
}

export function History() {
  const { platforms, historyVersion, openGallery } = useStudio();
  const { message } = App.useApp();
  const [entries, setEntries] = useState<HistoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [kind, setKind] = useState('all');
  const [revision, setRevision] = useState(0);
  const [exporting, setExporting] = useState(false);
  const sequence = useRef(0);
  useEffect(() => {
    const token = ++sequence.current;
    let cancelled = false;
    setLoading(true);
    void request<HistoryEntry[]>('/history').then(data => {
      if (!cancelled && token === sequence.current) { setEntries(data); setError(''); }
    }).catch(error => { if (!cancelled) setError(errorText(error)); }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [historyVersion, revision]);
  const filtered = useMemo(() => entries.filter(entry => (kind === 'all' || entry.kind === kind) && [entry.title, entry.author, entry.creator, entry.url, entry.id].some(value => value.toLowerCase().includes(search.toLowerCase()))), [entries, search, kind]);
  const grouped = useMemo(() => {
    const groups = new Map<string, Map<string, HistoryEntry[]>>();
    filtered.forEach(entry => {
      const platform = entry.platform || 'other';
      const creator = entry.creator || entry.author || '未知作者';
      if (!groups.has(platform)) groups.set(platform, new Map());
      const authors = groups.get(platform)!;
      authors.set(creator, [...(authors.get(creator) || []), entry]);
    });
    return [...groups.entries()];
  }, [filtered]);
  const exportCsv = async () => {
    setExporting(true);
    try {
      const selected = await save({ title: '导出解析与下载记录', defaultPath: `parsehub-history-${new Date().toISOString().slice(0, 10)}.csv`, filters: [{ name: 'CSV 表格', extensions: ['csv'] }] });
      if (!selected) return;
      const { csv } = await request<{ csv: string }>('/history/export');
      // Prefix a UTF-8 BOM for desktop spreadsheet applications.
      await writeTextFile(selected, `﻿${csv}`);
      message.success('记录已导出');
    } catch (error) { message.error(errorText(error)); }
    finally { setExporting(false); }
  };
  const clear = () => Modal.confirm({
    title: '清空所有解析与下载记录？', content: '只清空记录，已下载的本地文件会保留。', okText: '清空记录', cancelText: '取消', okButtonProps: { danger: true },
    onOk: async () => { try { await request('/history', 'DELETE'); setEntries([]); message.success('记录已清空'); } catch (error) { message.error(errorText(error)); throw error; } },
  });
  return <div className="page history-page">
    <header className="page-heading"><div><p className="eyebrow">YOUR ACTIVITY</p><h1>记录</h1><p className="muted">按平台与作者归档的解析、下载记录 · 共 {entries.length} 条</p></div><div className="row-wrap"><Button icon={<ReloadOutlined />} onClick={() => setRevision(value => value + 1)}>刷新</Button><Button icon={<DownloadOutlined />} disabled={!entries.length} loading={exporting} onClick={() => void exportCsv()}>导出 CSV</Button><Button danger icon={<DeleteOutlined />} disabled={!entries.length} onClick={clear}>清空</Button></div></header>
    <div className="history-controls"><Input allowClear placeholder="搜索标题、作者、作品 ID 或链接" value={search} onChange={event => setSearch(event.target.value)} /><Select aria-label="记录类型" value={kind} options={[{ value: 'all', label: '全部记录' }, { value: 'parse', label: '解析记录' }, { value: 'download', label: '下载记录' }]} onChange={setKind} /></div>
    {error && <Alert type="error" showIcon message="读取记录失败" description={error} />}
    {loading && <div className="history-loading"><Spin /></div>}
    {!loading && !grouped.length && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={search || kind !== 'all' ? '没有匹配的记录' : '还没有记录，先去解析一条链接吧'} />}
    <Collapse className="history-groups" items={grouped.map(([platform, authors]) => ({ key: platform, label: <strong>{platforms.find(item => item.key === platform)?.label || platform}</strong>, extra: <span className="muted">{[...authors.values()].reduce((sum, list) => sum + list.length, 0)} 条</span>, children: <Collapse ghost items={[...authors.entries()].map(([author, items]) => ({ key: author, label: `${author} · ${items.length} 条`, children: <div className="history-entries">{items.map((entry, index) => <article className="history-entry" key={`${entry.ts}-${entry.id}-${entry.kind}-${index}`}><div className="row-between"><div className="row-wrap"><Tag>{entry.kind === 'download' ? '下载' : '解析'}</Tag><Tag color={entry.status === 'ok' ? 'success' : 'error'}>{entry.status === 'ok' ? '成功' : '失败'}</Tag></div><time className="muted text-sm">{formatDateTime(entry.ts)}</time></div><h3>{entry.title || '未命名作品'}</h3><p className="muted text-sm">{entry.author}{entry.creator && entry.creator !== entry.author && ` → ${entry.creator}`}{entry.id && ` · ${entry.id}`}{entry.kind === 'download' && ` · ${entry.files} 个文件`}</p>{entry.error && <p className="error-text">{entry.error}</p>}{entry.url && <p className="history-url" title={entry.url}>{entry.url}</p>}<div className="row-wrap">{entry.dir && entry.files > 0 && <><Button size="small" onClick={() => openGallery(entry.dir)}>在图库中查看</Button>{recordedFiles(entry).length > 0 && <Dropdown trigger={['click']} menu={{ items: recordedFiles(entry).map((file, i) => ({ key: String(i), label: file.name, onClick: () => { void openLocal(file.path).catch(error => message.error(errorText(error))); } })) }}><Button size="small">打开已保存文件</Button></Dropdown>}<Button size="small" icon={<FolderOpenOutlined />} onClick={() => void openLocal(entry.dir).catch(error => message.error(errorText(error)))}>打开文件夹</Button></>}</div></article>)}</div> }))} /> }))} />
  </div>;
}
