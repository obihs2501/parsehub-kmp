import dayjs from 'dayjs';

export const IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp', 'avif'];
export const VIDEO_EXTS = ['mp4', 'mov', 'webm', 'mkv', 'm4v'];

export type MediaKind = 'image' | 'video';

export function classifyFile(name: string): MediaKind | null {
  const ext = name.split('.').pop()?.toLowerCase() || '';
  if (IMAGE_EXTS.includes(ext)) return 'image';
  if (VIDEO_EXTS.includes(ext)) return 'video';
  return null;
}

export function formatBytes(size?: number): string {
  if (!size || size <= 0) return '';
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = size;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value < 10 && unit > 0 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}

export function formatDateTime(ts?: number): string {
  if (!ts) return '';
  return dayjs(ts).format('YYYY-MM-DD HH:mm');
}

/** 相对时间：今天 → HH:mm，一年内 → MM-DD，否则 YYYY-MM-DD */
export function formatRelativeDate(ts?: number): string {
  if (!ts) return '';
  const d = dayjs(ts);
  const now = dayjs();
  if (d.isSame(now, 'day')) return d.format('HH:mm');
  if (d.isSame(now, 'year')) return d.format('MM-DD');
  return d.format('YYYY-MM-DD');
}

export function formatDuration(seconds?: number | null): string {
  if (!seconds || !Number.isFinite(seconds)) return '';
  const total = Math.round(seconds);
  const m = Math.floor(total / 60);
  const s = total % 60;
  const h = Math.floor(m / 60);
  const mm = h > 0 ? String(m % 60).padStart(2, '0') : String(m);
  return `${h > 0 ? `${h}:` : ''}${mm}:${String(s).padStart(2, '0')}`;
}

/** 自然排序（数字按数值比较，忽略大小写） */
export function compareNames(a: string, b: string): number {
  return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
}

/** 判断 child 是否位于 parent 目录之下（含自身） */
export function isPathUnder(child: string, parent: string): boolean {
  const normalizedChild = canonicalPath(child);
  const normalizedParent = canonicalPath(parent);
  return normalizedChild === normalizedParent || normalizedChild.startsWith(normalizedParent === '/' ? '/' : normalizedParent + '/');
}

/** Canonical lexical key; Windows paths are case-insensitive. */
export function canonicalPath(path: string): string {
  const value = path.replace(/\\/g, '/').replace(/\/{2,}/g, '/').replace(/\/+$/, '') || (path.startsWith('/') ? '/' : '');
  return /^[A-Za-z]:/.test(value) || path.startsWith('\\\\') ? value.toLowerCase() : value;
}
export function uniquePaths(paths: string[]): string[] {
  const seen = new Set<string>();
  return paths.filter(path => { const key = canonicalPath(path); if (!key || seen.has(key)) return false; seen.add(key); return true; });
}

export function mediaVersion(media: { path: string; modifiedAt?: number; size?: number }): string {
  return JSON.stringify([canonicalPath(media.path), media.modifiedAt ?? null, media.size ?? null]);
}
