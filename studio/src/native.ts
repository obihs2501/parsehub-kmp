import { invoke } from '@tauri-apps/api/tauri';
import { open } from '@tauri-apps/api/dialog';
import { shell } from '@tauri-apps/api';
export async function chooseDirectory(defaultPath?: string): Promise<string | null> {
  const selected = await open({ directory: true, multiple: false, title: '选择文件夹', defaultPath: defaultPath || undefined });
  return typeof selected === 'string' ? selected : null;
}
export async function revealFile(path: string) {
  await invoke('show_in_folder', { path });
}
export async function openLocal(path: string) { await shell.open(path); }
