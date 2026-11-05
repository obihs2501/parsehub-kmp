import { invoke } from '@tauri-apps/api/tauri';

export function errorText(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === 'string') return error;
  return '操作失败，请重试';
}
// Rust owns the sidecar session and authentication. Never expose either to WebView.
export async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const result = await invoke<T>('backend_request', { method, path: `/api${path}`, body: body ?? null });
  if (result && typeof result === 'object' && 'error' in result) {
    throw new Error(String((result as { error: unknown }).error));
  }
  return result;
}
export function safeRemoteImage(url: string | null | undefined): string | undefined {
  if (!url) return undefined;
  try { const parsed = new URL(url); return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? url : undefined; }
  catch { return undefined; }
}
