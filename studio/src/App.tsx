import { useCallback, useEffect, useRef, useState } from 'react';
import { App as AntApp, Alert, Button, ConfigProvider, Spin } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { HistoryOutlined, LinkOutlined, PictureOutlined, SettingOutlined } from '@ant-design/icons';
import { getAntdTheme } from './constants/antd-theme';
import { request, errorText } from './api';
import { Platform, SettingsData, TaskState } from './types';
import { StudioContext, Tab } from './context';
import { Homepage } from './pages/Homepage';
import { Gallery } from './pages/Gallery';
import { History } from './pages/History';
import { Settings } from './pages/Settings';

const activeTasks = (states: TaskState[]) => states.some(task => task.status === 'running' || task.status === 'cancelling');
const tabs = [
  { id: 'parse' as const, label: '解析', icon: <LinkOutlined /> },
  { id: 'gallery' as const, label: '图库', icon: <PictureOutlined /> },
  { id: 'history' as const, label: '记录', icon: <HistoryOutlined /> },
  { id: 'settings' as const, label: '设置', icon: <SettingOutlined /> },
];
function useSystemDark() {
  const [dark, setDark] = useState(() => matchMedia('(prefers-color-scheme: dark)').matches);
  useEffect(() => {
    const query = matchMedia('(prefers-color-scheme: dark)');
    const listener = () => setDark(query.matches);
    query.addEventListener('change', listener);
    return () => query.removeEventListener('change', listener);
  }, []);
  return dark;
}
export function App() {
  const [settings, setSettings] = useState<SettingsData | null>(null);
  const [platforms, setPlatforms] = useState<Platform[]>([]);
  const [tasks, setTasks] = useState<TaskState[]>([]);
  const [tab, setTab] = useState<Tab>('parse');
  const [bootError, setBootError] = useState('');
  const [connectionError, setConnectionError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [historyVersion, setHistoryVersion] = useState(0);
  const [pollRevision, setPollRevision] = useState(0);
  const pollRequested = useRef(false);
  const taskSignature = useRef('');
  const [galleryTarget, setGalleryTarget] = useState('');
  const previousTab = useRef<Tab>('parse');
  const alive = useRef(true);
  const systemDark = useSystemDark();
  const dark = settings?.config.theme === 'dark' || ((!settings || settings.config.theme === 'system') && systemDark);
  useEffect(() => {
    document.documentElement.classList.toggle('theme-dark', dark);
    document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
  }, [dark]);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setBootError('');
    const boot = async () => {
      let lastError: unknown;
      for (let i = 0; i < 15 && !cancelled; i++) {
        try {
          await request('/health');
          const [config, supported, states] = await Promise.all([
            request<SettingsData>('/settings'), request<Platform[]>('/platforms'), request<TaskState[]>('/tasks'),
          ]);
          if (cancelled) return;
          setSettings(config); setPlatforms(supported); setTasks(states); taskSignature.current = states.map(t => `${t.id}:${t.status}`).join('|'); return;
        } catch (error) {
          lastError = error;
          if (i < 14) await new Promise<void>(resolve => { timer = setTimeout(resolve, Math.min(1500, 400 + i * 100)); });
        }
      }
      if (!cancelled) setBootError(errorText(lastError));
    };
    void boot();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [attempt]);
  const refreshTasks = useCallback(async () => {
    try {
      const states = await request<TaskState[]>('/tasks');
      if (alive.current) {
        setTasks(states); setConnectionError('');
        taskSignature.current = states.map(t => `${t.id}:${t.status}`).join('|');
        setHistoryVersion(v => v + 1);
      }
    } finally {
      // Restart even if the immediate refresh fails after a successful start.
      if (alive.current) { pollRequested.current = true; setPollRevision(v => v + 1); }
    }
  }, []);
  useEffect(() => {
    if (!settings || (!pollRequested.current && !activeTasks(tasks))) return;
    pollRequested.current = false;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let failures = 0;
    const poll = async () => {
      try {
        const states = await request<TaskState[]>('/tasks');
        if (cancelled) return;
        setTasks(states); setConnectionError(''); failures = 0;
        const signature = states.map(t => `${t.id}:${t.status}`).join('|');
        if (signature !== taskSignature.current) { setHistoryVersion(v => v + 1); taskSignature.current = signature; }
        if (!activeTasks(states)) return;
      } catch (error) {
        if (cancelled) return;
        failures++;
        setConnectionError(errorText(error));
      }
      if (!cancelled) timer = setTimeout(poll, failures ? Math.min(10000, 1500 * failures) : 500);
    };
    void poll();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [!!settings, pollRevision]);
  const navigate = useCallback((next: Tab) => {
    setTab(current => { if (next === 'settings' && current !== 'settings') previousTab.current = current; return next; });
  }, []);
  const back = useCallback(() => setTab(previousTab.current), []);
  useEffect(() => {
    if (tab !== 'settings') return;
    const listener = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented && !document.querySelector('.ant-modal-wrap, .ant-select-dropdown:not(.ant-select-dropdown-hidden)')) {
        event.preventDefault(); back();
      }
    };
    window.addEventListener('keydown', listener);
    return () => window.removeEventListener('keydown', listener);
  }, [tab, back]);
  const saveSettings = useCallback(async (next: SettingsData) => {
    const saved = await request<SettingsData>('/settings', 'PUT', next);
    if (alive.current) setSettings(saved);
  }, []);
  const openGallery = useCallback((path: string) => { setGalleryTarget(path); setTab('gallery'); }, []);
  const consumeGalleryTarget = useCallback(() => setGalleryTarget(''), []);
  return <ConfigProvider locale={zhCN} theme={getAntdTheme(dark ? 'dark' : 'light')} autoInsertSpaceInButton={false}><AntApp>
    {!settings ? <div className="boot-screen">
      <div className="brand-mark">P</div><h1>ParseHub Studio</h1>
      {bootError ? <><Alert type="error" showIcon message="无法连接解析服务" description={bootError} /><Button type="primary" onClick={() => setAttempt(v => v + 1)}>重新连接</Button></> : <><Spin size="large" /><p>正在启动解析服务…</p></>}
    </div> : <StudioContext.Provider value={{ settings, platforms, tasks, saveSettings, refreshTasks, historyVersion, navigate, back, openGallery, galleryTarget, consumeGalleryTarget }}>
      <div className="studio-shell css-var-r0">
        <aside className="studio-sidebar"><div className="studio-brand"><div className="brand-mark">P</div><strong>ParseHub<span>Studio</span></strong></div>
          <nav aria-label="页面导航">{tabs.map(item => <button key={item.id} aria-current={tab === item.id ? 'page' : undefined} className={tab === item.id ? 'active' : ''} onClick={() => navigate(item.id)}>{item.icon}<span>{item.label}</span></button>)}</nav>
          <div className="sidebar-footer"><span className={`service-dot ${connectionError ? 'offline' : ''}`} />{connectionError ? '服务连接中断' : '本地解析服务'}<small>v{PACKAGE_JSON_VERSION}</small></div>
        </aside>
        <main className="studio-main">
          {connectionError && <Alert banner type="warning" message="解析服务连接中断，正在自动重连" description={connectionError} />}
          <div hidden={tab !== 'parse'}><Homepage /></div>
          {tab === 'gallery' && <Gallery />}
          {tab === 'history' && <History />}
          {tab === 'settings' && <Settings />}
        </main>
      </div>
    </StudioContext.Provider>}
  </AntApp></ConfigProvider>;
}
