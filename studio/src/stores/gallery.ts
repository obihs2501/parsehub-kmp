import { canonicalPath } from '../utils/gallery-media';
import { invoke } from '@tauri-apps/api/tauri';
export interface GalleryFolder { name: string; path: string; modifiedAt?: number }
export interface GalleryMedia { path: string; name: string; isVideo: boolean; modifiedAt?: number; size?: number }
export interface DirEntryInfo { name: string; path: string; isDir: boolean; modifiedAt?: number; size?: number }
// Deliberately scan a single level; enter a child folder to scan that level.
export function readDirectory(path: string) { return invoke<DirEntryInfo[]>('read_dir_with_metadata', { path }); }

interface CachedDirectory { entries: DirEntryInfo[]; modifiedAt?: number }
const directories = new Map<string, CachedDirectory>();
const CACHE_LIMIT = 100;
export async function cachedDirectory(path: string, force = false): Promise<DirEntryInfo[]> {
  const key = canonicalPath(path);
  const hit = directories.get(key);
  const [metadata] = await invoke<{ modifiedAt?: number }[]>('filesystem_metadata', { paths: [path] });
  if (!force && hit && metadata?.modifiedAt != null && metadata.modifiedAt === hit.modifiedAt) {
    directories.delete(key); directories.set(key, hit);
    return hit.entries;
  }
  const entries = await readDirectory(path);
  directories.delete(key);
  directories.set(key, { entries, modifiedAt: metadata?.modifiedAt });
  if (directories.size > CACHE_LIMIT) directories.delete(directories.keys().next().value!);
  return entries;
}
