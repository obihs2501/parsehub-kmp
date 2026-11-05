import { createContext, useContext } from 'react';
import { Platform, SettingsData, TaskState } from './types';
export type Tab = 'parse' | 'gallery' | 'history' | 'settings';
export interface StudioContextValue {
  settings: SettingsData; platforms: Platform[]; tasks: TaskState[];
  saveSettings: (settings: SettingsData) => Promise<void>;
  refreshTasks: () => Promise<void>; historyVersion: number;
  navigate: (tab: Tab) => void; back: () => void;
  openGallery: (path: string) => void; galleryTarget: string; consumeGalleryTarget: () => void;
}
export const StudioContext = createContext<StudioContextValue | null>(null);
export function useStudio() {
  const context = useContext(StudioContext);
  if (!context) throw new Error('Studio context unavailable');
  return context;
}
