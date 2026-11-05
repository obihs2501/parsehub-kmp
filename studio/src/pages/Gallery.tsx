import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { App, Alert, Button, Empty, Input, Select, Slider, Spin, Switch, Popover } from 'antd';
import { AppstoreOutlined, ArrowLeftOutlined, FolderFilled, FolderOpenOutlined, MenuFoldOutlined, MenuUnfoldOutlined, PlusOutlined, ReloadOutlined, SettingOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { convertFileSrc } from '@tauri-apps/api/tauri';
import { MediaLightbox } from '../components/gallery/MediaLightbox';
import { MediaTile, MediaRow } from '../components/gallery/MediaTile';
import { GalleryFolder, GalleryMedia, cachedDirectory } from '../stores/gallery';
import { classifyFile, compareNames, isPathUnder, canonicalPath, uniquePaths, mediaVersion } from '../utils/gallery-media';
import { useStudio } from '../context';
import { errorText } from '../api';
import { chooseDirectory, openLocal, revealFile } from '../native';

interface FolderContent { medias: GalleryMedia[]; folders: GalleryFolder[] }
interface Preferences { columns: number; view: 'thumbnail' | 'list'; fit: 'cover' | 'contain'; thumbs: boolean; filter: string; sort: string; sidebar: boolean }
function getPreferences(): Preferences {
  const fallback: Preferences = { columns: 5, view: 'thumbnail', fit: 'cover', thumbs: true, filter: 'all', sort: 'newest', sidebar: true };
  try {
    const stored = JSON.parse(localStorage.getItem('parsehub-gallery-view') || '{}') as Partial<Preferences>;
    return {
      columns: typeof stored.columns === 'number' && Number.isFinite(stored.columns) ? Math.max(3, Math.min(10, Math.round(stored.columns))) : fallback.columns,
      view: stored.view === 'list' ? 'list' : 'thumbnail', fit: stored.fit === 'contain' ? 'contain' : 'cover',
      thumbs: typeof stored.thumbs === 'boolean' ? stored.thumbs : true,
      filter: ['all', 'image', 'video'].includes(stored.filter || '') ? stored.filter! : 'all',
      sort: ['newest', 'oldest', 'name', 'size'].includes(stored.sort || '') ? stored.sort! : 'newest',
      sidebar: typeof stored.sidebar === 'boolean' ? stored.sidebar : true,
    };
  } catch { return fallback; }
}
export function Gallery() {
  const { settings, saveSettings, galleryTarget, consumeGalleryTarget, historyVersion } = useStudio();
  const { message } = App.useApp();
  const [preferences, setPreferences] = useState(getPreferences);
  const [rootFolders, setRootFolders] = useState<Record<string, GalleryFolder[]>>({});
  const [rootErrors, setRootErrors] = useState<Record<string, string>>({});
  const [stack, setStack] = useState<GalleryFolder[]>([]);
  const [content, setContent] = useState<FolderContent>({ medias: [], folders: [] });
  const [loading, setLoading] = useState(false);
  const [rootsLoading, setRootsLoading] = useState(false);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [folderSearch, setFolderSearch] = useState('');
  const [visible, setVisible] = useState(120);
  const [index, setIndex] = useState<number | null>(null);
  const requestToken = useRef(0);
  const rootToken = useRef(0);
  const currentRef = useRef<GalleryFolder[]>([]);
  const pendingRefresh = useRef(false);
  const lastHistoryVersion = useRef(historyVersion);
  const scroll = useRef<HTMLDivElement>(null);
  const sentinel = useRef<HTMLDivElement>(null);
  const rootsKey = JSON.stringify(uniquePaths([settings.outDir, ...settings.galleryRoots]));
  const roots = useMemo<string[]>(() => JSON.parse(rootsKey), [rootsKey]);
  const current = stack[stack.length - 1];
  useEffect(() => { try { localStorage.setItem('parsehub-gallery-view', JSON.stringify(preferences)); } catch { /* optional convenience */ } }, [preferences]);
  const change = (patch: Partial<Preferences>) => { setPreferences(value => ({ ...value, ...patch })); setIndex(null); setVisible(120); };
  const enter = useCallback(async (nextStack: GalleryFolder[], force = false) => {
    const token = ++requestToken.current;
    currentRef.current = nextStack; setStack(nextStack); setIndex(null); setVisible(120); setSearch(''); setError('');
    scroll.current?.scrollTo({ top: 0 });
    const folder = nextStack[nextStack.length - 1];
    if (!folder) { setContent({ medias: [], folders: [] }); setLoading(false); return; }
    setContent({ medias: [], folders: [] });
    setLoading(true);
    try {
      const entries = await cachedDirectory(folder.path, force);
      if (token !== requestToken.current) return;
      const result: FolderContent = { medias: [], folders: [] };
      entries.forEach(entry => {
        if (entry.isDir) result.folders.push({ ...entry });
        else { const kind = classifyFile(entry.name); if (kind) result.medias.push({ ...entry, isVideo: kind === 'video' }); }
      });
      setContent(result);
    } catch (error) { if (token === requestToken.current) setError(errorText(error)); }
    finally { if (token === requestToken.current) setLoading(false); }
  }, []);
  const loadRoots = useCallback(async (force = false) => {
    const token = ++rootToken.current;
    setRootsLoading(true);
    const results = await Promise.allSettled(roots.map(root => cachedDirectory(root, force)));
    if (token !== rootToken.current) return;
    const folders: Record<string, GalleryFolder[]> = {};
    const errors: Record<string, string> = {};
    results.forEach((result, i) => {
      if (result.status === 'fulfilled') folders[roots[i]] = result.value.filter(entry => entry.isDir);
      else { folders[roots[i]] = []; errors[roots[i]] = errorText(result.reason); }
    });
    setRootFolders(folders); setRootErrors(errors); setRootsLoading(false);
  }, [rootsKey]);
  useEffect(() => {
    void loadRoots();
    const previous = currentRef.current;
    if (previous.length && !roots.some(root => canonicalPath(root) === canonicalPath(previous[0].path))) void enter([]);
    return () => { rootToken.current++; };
  }, [loadRoots, enter]);
  useEffect(() => {
    if (!galleryTarget) return;
    const target = galleryTarget.replace(/[\\/]+$/, '');
    consumeGalleryTarget();
    const root = [...roots].sort((a, b) => b.length - a.length).find(path => isPathUnder(target, path));
    if (!root) { setError('该记录的目录不在图库根目录中，请在设置中添加它所在的根目录。'); return; }
    const next: GalleryFolder[] = [{ name: root.split(/[\\/]/).pop() || root, path: root }];
    let path = root;
    const separator = root.includes('\\') ? '\\' : '/';
    target.slice(root.replace(/[\\/]+$/, '').length).split(/[\\/]/).filter(Boolean).forEach(name => { path = `${path.replace(/[\\/]$/, '')}${separator}${name}`; next.push({ name, path }); });
    void enter(next, true);
  }, [galleryTarget, consumeGalleryTarget, rootsKey, enter]);
  useEffect(() => {
    const refresh = () => {
      void loadRoots(true);
      if (document.querySelector('[aria-label^="媒体预览："]')) { pendingRefresh.current = true; return; }
      if (currentRef.current.length) void enter(currentRef.current, true);
    };
    window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, [loadRoots, enter]);
  useEffect(() => {
    if (lastHistoryVersion.current === historyVersion) return;
    lastHistoryVersion.current = historyVersion;
    void loadRoots(true);
    if (!currentRef.current.length) return;
    if (document.querySelector('[aria-label^="媒体预览："]')) { pendingRefresh.current = true; return; }
    void enter(currentRef.current, true);
  }, [historyVersion, enter, loadRoots]);
  useEffect(() => () => { requestToken.current++; rootToken.current++; }, []);
  const media = useMemo(() => content.medias.filter(item => (!search || item.name.toLowerCase().includes(search.toLowerCase())) && (preferences.filter === 'all' || (preferences.filter === 'video') === item.isVideo)).sort((a, b) => preferences.sort === 'name' ? compareNames(a.name, b.name) : preferences.sort === 'size' ? (b.size || 0) - (a.size || 0) : preferences.sort === 'oldest' ? (a.modifiedAt || 0) - (b.modifiedAt || 0) : (b.modifiedAt || 0) - (a.modifiedAt || 0)), [content, search, preferences.filter, preferences.sort]);
  const folders = useMemo(() => content.folders.filter(folder => folder.name.toLowerCase().includes(search.toLowerCase())).sort((a, b) => compareNames(a.name, b.name)), [content, search]);
  useEffect(() => {
    const element = sentinel.current;
    if (!element || visible >= media.length) return;
    const observer = new IntersectionObserver(entries => { if (entries.some(entry => entry.isIntersecting)) setVisible(value => value + 120); }, { root: scroll.current, rootMargin: '600px' });
    observer.observe(element); return () => observer.disconnect();
  }, [visible, media.length]);
  const report = (promise: Promise<unknown>) => { void promise.catch(error => message.error(errorText(error))); };
  const addRoot = async () => {
    try { const selected = await chooseDirectory(settings.outDir); if (selected && !roots.some(root => canonicalPath(root) === canonicalPath(selected))) await saveSettings({ ...settings, galleryRoots: [...settings.galleryRoots, selected] }); }
    catch (error) { message.error(errorText(error)); }
  };
  const closeLightbox = () => {
    if (index !== null) setVisible(value => Math.max(value, index + 1));
    setIndex(null);
    if (pendingRefresh.current) { pendingRefresh.current = false; void enter(currentRef.current, true); }
  };
  return <div className="page gallery-page">
    <header className="page-heading"><div><p className="eyebrow">LOCAL COLLECTION</p><h1>图库</h1><p className="muted">按文件夹浏览本地图片与视频。</p></div><div className="row-wrap"><Button icon={<PlusOutlined />} onClick={() => void addRoot()}>添加目录</Button><Button icon={<ReloadOutlined spin={loading || rootsLoading} />} onClick={() => { void loadRoots(true); if (stack.length) void enter(stack, true); }}>刷新</Button></div></header>
    <div className="gallery-layout">
      <aside className={`gallery-folders ${preferences.sidebar ? '' : 'collapsed'}`}>
        <div className="row-between"><strong>{preferences.sidebar ? '文件夹' : ''}</strong><Button type="text" aria-label={preferences.sidebar ? '收起文件夹' : '展开文件夹'} icon={preferences.sidebar ? <MenuFoldOutlined /> : <MenuUnfoldOutlined />} onClick={() => change({ sidebar: !preferences.sidebar })} /></div>
        {preferences.sidebar && <><Input allowClear placeholder="搜索文件夹" value={folderSearch} onChange={event => setFolderSearch(event.target.value)} /><div className="root-list">{roots.map(root => <div key={root} className="root-group"><button className={`root-title ${current?.path === root ? 'selected' : ''}`} title={root} onClick={() => void enter([{ path: root, name: root.split(/[\\/]/).pop() || root }])}><FolderOpenOutlined /><span>{root.split(/[\\/]/).pop() || root}</span></button><p className="root-path" title={root}>{root}</p>{rootErrors[root] && <p className="error-text text-sm">{rootErrors[root]}</p>}{(rootFolders[root] || []).filter(folder => folder.name.toLowerCase().includes(folderSearch.toLowerCase())).sort((a, b) => compareNames(a.name, b.name)).map(folder => <button className={`folder-row ${current?.path === folder.path ? 'selected' : ''}`} key={folder.path} title={folder.name} onClick={() => void enter([{ path: root, name: root.split(/[\\/]/).pop() || root }, folder])}><FolderFilled /><span>{folder.name}</span></button>)}</div>)}</div></>}
      </aside>
      <section className="gallery-content">
        <div className="gallery-toolbar"><div className="breadcrumbs"><Button type="text" size="small" icon={<ArrowLeftOutlined />} disabled={!stack.length} aria-label="上一级文件夹" onClick={() => void enter(stack.slice(0, -1))} />{stack.length ? stack.map((folder, i) => <button title={folder.path} key={folder.path} onClick={() => void enter(stack.slice(0, i + 1))}>{folder.name}{i < stack.length - 1 && ' /'}</button>) : <span className="muted">选择左侧文件夹</span>}</div>{current && <Button type="text" aria-label="打开当前文件夹" icon={<FolderOpenOutlined />} onClick={() => report(openLocal(current.path))} />}</div>
        <div className="gallery-controls"><Input allowClear placeholder="搜索当前文件夹" value={search} onChange={event => { setSearch(event.target.value); setIndex(null); setVisible(120); }} /><Select aria-label="媒体类型" value={preferences.filter} onChange={filter => change({ filter })} options={[{ value: 'all', label: '全部媒体' }, { value: 'image', label: '图片' }, { value: 'video', label: '视频' }]} /><Select aria-label="排序" value={preferences.sort} onChange={sort => change({ sort })} options={[{ value: 'newest', label: '最近修改' }, { value: 'oldest', label: '最早修改' }, { value: 'name', label: '文件名称' }, { value: 'size', label: '文件大小' }]} /><Button aria-label="切换网格或列表" icon={preferences.view === 'thumbnail' ? <UnorderedListOutlined /> : <AppstoreOutlined />} onClick={() => change({ view: preferences.view === 'thumbnail' ? 'list' : 'thumbnail' })} /><Popover trigger="click" content={<div className="gallery-display-settings"><label>每行列数：{preferences.columns}<Slider min={3} max={10} value={preferences.columns} onChange={columns => change({ columns })} /></label><label className="row-between">完整显示图片<Switch checked={preferences.fit === 'contain'} onChange={checked => change({ fit: checked ? 'contain' : 'cover' })} /></label><label className="row-between">视频首帧缩略图<Switch checked={preferences.thumbs} onChange={thumbs => change({ thumbs })} /></label></div>}><Button aria-label="显示设置" icon={<SettingOutlined />} /></Popover></div>
        {error && <Alert type="error" showIcon message="无法读取文件夹" description={error} />}
        <div className="gallery-scroll" ref={scroll}>
          {loading && <div className="gallery-loading"><Spin /></div>}
          {!current ? <Empty description="选择一个根目录或文件夹开始浏览" image={Empty.PRESENTED_IMAGE_SIMPLE} /> : <>
            {folders.length > 0 && <div className="subfolder-grid">{folders.map(folder => <button key={folder.path} title={folder.name} onClick={() => void enter([...stack, folder])}><FolderFilled /><span>{folder.name}</span></button>)}</div>}
            <div className={preferences.view === 'thumbnail' ? 'media-grid' : 'media-list'} style={preferences.view === 'thumbnail' ? { gridTemplateColumns: `repeat(${preferences.columns}, minmax(0, 1fr))` } : undefined}>
              {media.slice(0, visible).map((item, i) => { const Tile = preferences.view === 'thumbnail' ? MediaTile : MediaRow; return <Tile key={mediaVersion(item)} media={item} src={convertFileSrc(item.path)} fitMode={preferences.fit} videoThumbs={preferences.thumbs} onOpen={() => setIndex(i)} onOpenExternal={() => report(openLocal(item.path))} onShowInFolder={() => report(revealFile(item.path))} />; })}
            </div>
            {!loading && !media.length && !folders.length && <Empty description={search || preferences.filter !== 'all' ? '没有匹配的媒体' : '此文件夹没有可预览的媒体'} image={Empty.PRESENTED_IMAGE_SIMPLE} />}
            <div ref={sentinel} className="gallery-sentinel">{visible < media.length ? <Button onClick={() => setVisible(value => value + 120)}>加载更多</Button> : media.length > 0 && <span className="muted">{media.length} 个媒体 · 当前文件夹</span>}</div>
          </>}
        </div>
      </section>
    </div>
    <MediaLightbox items={media} index={index} onClose={closeLightbox} onNavigate={setIndex} onOpenExternal={item => report(openLocal(item.path))} onShowInFolder={item => report(revealFile(item.path))} />
  </div>;
}
