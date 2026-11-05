export type ThemeMode = 'system' | 'light' | 'dark';
export interface Platform { key: string; label: string }
export interface Alias { platform: string; account: string; creator: string; matchBy: string }
export interface CookieEntry { platform: string; label: string; cookie: string; enabled: boolean }
export interface AppConfig {
  defaultTier: string; theme: ThemeMode; folderTemplate: string; fileTemplate: string;
  mergeAuthorFolders: boolean; aliases: Alias[]; cookies: CookieEntry[];
  connections: number; timeoutSec: number; imagesByDefault: boolean; saveJson: boolean;
  downloadRootDir: string; treeUri: string; galleryColumns: number; gallerySort: string;
  galleryFilter: string; galleryOpenPanel: boolean; navStyle: string;
}
export interface SettingsData { config: AppConfig; outDir: string; galleryRoots: string[] }
export interface Tier { idx: number; gear: string; w: number; h: number; bps: number; size: number; h265: boolean; fps: number; fpsEst: boolean; isOrigin: boolean; urls: string[] }
export interface ParseResult {
  platform: string; id: string; title: string; author: string; authorId: string; date: string;
  durSec: number; statLine: string; width: number; height: number; cover: string | null; referer: string;
  tiers: Tier[]; images: { index: number; url: string; ext: string; live: string | null; w: number; h: number }[];
  aux: { kind: string; label: string; spec: string; ext: string; urls: string[] }[];
}
export interface Parsed { resultId: string; result: ParseResult }
export interface TaskState { id: string; status: string; name: string; done: number; total: number; speed: number; doneJobs: number; totalJobs: number; error: string; files: string[] }
export interface HistoryEntry { ts: number; platform: string; id: string; title: string; author: string; creator: string; url: string; kind: string; status: string; files: number; dir: string; error: string; fileNames: string }
