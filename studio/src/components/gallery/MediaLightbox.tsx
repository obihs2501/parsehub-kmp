/* eslint-disable react/prop-types */
import {
  CloseOutlined,
  FolderFilled,
  FolderOpenOutlined,
  LeftOutlined,
  OneToOneOutlined,
  RightOutlined,
  ZoomInOutlined,
  ZoomOutOutlined,
} from '@ant-design/icons';
import { tauri } from '@tauri-apps/api';
import clsx from 'clsx';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { GalleryMedia } from '../../stores/gallery';
import { formatBytes, formatDateTime } from '../../utils/gallery-media';

const MIN_SCALE = 0.2;
const MAX_SCALE = 8;

/** 视频音量 / 静音在预览会话内记忆，切换视频时沿用 */
const videoAudioState = { volume: 1, muted: false };

function clampScale(v: number) {
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, v));
}

/** 可滚轮缩放、拖拽平移、双击复位的图片 */
const ZoomableImage: React.FC<{
  src: string;
  alt: string;
  scale: number;
  onScaleChange: (updater: (s: number) => number) => void;
}> = ({ src, alt, scale, onScaleChange }) => {
  const [offset, setOffset] = useState({ x: 0, y: 0 });
  const zoomRef = useRef({ scale, offset });
  zoomRef.current = { scale, offset };
  const wheelZoom = useRef(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<{
    startX: number;
    startY: number;
    originX: number;
    originY: number;
    moved: boolean;
  } | null>(null);
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    setOffset({ x: 0, y: 0 });
  }, [src]);

  useEffect(() => {
    if (!wheelZoom.current && scale <= 1) setOffset({ x: 0, y: 0 });
    wheelZoom.current = false;
  }, [scale]);

  // React 的 onWheel 是被动监听，无法阻止默认行为，这里用原生监听
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      if (!e.deltaY) return;
      const current = zoomRef.current;
      const nextScale = clampScale(current.scale * (e.deltaY < 0 ? 1.15 : 1 / 1.15));
      if (nextScale === current.scale) return;
      const bounds = el.getBoundingClientRect();
      const x = e.clientX - bounds.left - bounds.width / 2;
      const y = e.clientY - bounds.top - bounds.height / 2;
      const ratio = nextScale / current.scale;
      // Keep the image point under the cursor fixed as its scale changes.
      const nextOffset = {
        x: x - (x - current.offset.x) * ratio,
        y: y - (y - current.offset.y) * ratio,
      };
      wheelZoom.current = true;
      zoomRef.current = { scale: nextScale, offset: nextOffset };
      setOffset(nextOffset);
      onScaleChange(() => nextScale);
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [onScaleChange]);

  return (
    <div
      ref={containerRef}
      className="w-full h-full flex items-center justify-center overflow-hidden"
      style={{
        cursor: scale > 1 ? (dragging ? 'grabbing' : 'grab') : 'default',
      }}
      onMouseDown={(e) => {
        if (e.button !== 0) return;
        dragRef.current = {
          startX: e.clientX,
          startY: e.clientY,
          originX: offset.x,
          originY: offset.y,
          moved: false,
        };
      }}
      onMouseMove={(e) => {
        const drag = dragRef.current;
        if (!drag || scale <= 1) return;
        const dx = e.clientX - drag.startX;
        const dy = e.clientY - drag.startY;
        if (Math.abs(dx) + Math.abs(dy) > 3) {
          drag.moved = true;
          if (!dragging) setDragging(true);
        }
        setOffset({ x: drag.originX + dx, y: drag.originY + dy });
      }}
      onMouseUp={() => {
        dragRef.current = null;
        setDragging(false);
      }}
      onMouseLeave={() => {
        dragRef.current = null;
        setDragging(false);
      }}
      onClick={(e) => {
        // 图片区域：点击不再放大（避免与拖拽冲突），缩放交给滚轮 / 工具栏；
        // 点击图片本身不冒泡关闭，点击空白处由外层关闭
        if ((e.target as HTMLElement).tagName === 'IMG') e.stopPropagation();
      }}
      onDoubleClick={(e) => e.stopPropagation()}
    >
      <img
        src={src}
        alt={alt}
        draggable={false}
        className={clsx(
          'max-w-full max-h-full object-contain select-none will-change-transform',
          // 拖拽期间关闭过渡动画，否则每帧都在补间，拖动会卡顿
          !dragging && 'transition-transform duration-100',
        )}
        style={{
          transform: `translate(${offset.x}px, ${offset.y}px) scale(${scale})`,
        }}
      />
    </div>
  );
};

export interface MediaLightboxProps {
  items: GalleryMedia[];
  index: number | null;
  onClose: () => void;
  onNavigate: (index: number) => void;
  onOpenExternal: (media: GalleryMedia) => void;
  onShowInFolder: (media: GalleryMedia) => void;
}

const ToolbarButton: React.FC<{
  title: string;
  icon: React.ReactNode;
  onClick: () => void;
  disabled?: boolean;
}> = ({ title, icon, onClick, disabled }) => (
  // 黑底遮罩上 antd 气泡不可靠，改用原生 title 由系统绘制提示
  <button
    title={title}
    aria-label={title}
    className="w-9 h-9 rounded-lg !bg-transparent border-0 !text-[#C9C6BA] hover:!text-white hover:!bg-white/10 disabled:opacity-30 disabled:hover:!bg-transparent flex items-center justify-center transition-colors text-base cursor-pointer"
    onClick={(e) => {
      e.stopPropagation();
      onClick();
    }}
    disabled={disabled}
  >
    {icon}
  </button>
);

/** 图片 / 视频统一的全屏预览层，支持键盘左右切换、Esc 关闭、滚轮缩放 */
export const MediaLightbox: React.FC<MediaLightboxProps> = ({
  items,
  index,
  onClose,
  onNavigate,
  onOpenExternal,
  onShowInFolder,
}) => {
  const open = index !== null && index >= 0 && index < items.length;
  const media = open ? items[index as number] : null;
  const [scale, setScale] = useState(1);
  const dialogRef = useRef<HTMLDivElement>(null);
  const total = items.length;
  const hasPrev = open && (index as number) > 0;
  const hasNext = open && (index as number) < total - 1;

  const goPrev = useCallback(() => {
    if (index !== null && index > 0) onNavigate(index - 1);
  }, [index, onNavigate]);
  const goNext = useCallback(() => {
    if (index !== null && index < total - 1) onNavigate(index + 1);
  }, [index, onNavigate, total]);

  useEffect(() => {
    setScale(1);
  }, [media?.path]);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e: KeyboardEvent) => {
      switch (e.key) {
        case 'Escape':
          e.preventDefault();
          onClose();
          break;
        case 'ArrowLeft':
          e.preventDefault();
          goPrev();
          break;
        case 'ArrowRight':
          e.preventDefault();
          goNext();
          break;
        case '+':
        case '=':
          setScale((s) => clampScale(s * 1.25));
          break;
        case '-':
          setScale((s) => clampScale(s / 1.25));
          break;
        case '0':
          setScale(1);
          break;
        default:
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open, onClose, goPrev, goNext]);

  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialogRef.current?.focus();
    const trapFocus = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return;
      const controls = [...(dialogRef.current?.querySelectorAll<HTMLElement>('button:not(:disabled), video[controls]') || [])];
      const first = controls[0];
      const last = controls[controls.length - 1];
      if (!first || !last) { event.preventDefault(); return; }
      if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogRef.current)) {
        event.preventDefault(); last.focus();
      } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === dialogRef.current)) {
        event.preventDefault(); first.focus();
      }
    };
    window.addEventListener('keydown', trapFocus);
    return () => {
      window.removeEventListener('keydown', trapFocus);
      if (previous?.isConnected) previous.focus({ preventScroll: true });
    };
  }, [open]);

  // 预加载相邻图片，切换时不留白
  useEffect(() => {
    if (!open) return;
    [index! - 1, index! + 1].forEach((i) => {
      const m = items[i];
      if (m && !m.isVideo) {
        const img = new Image();
        img.src = tauri.convertFileSrc(m.path);
      }
    });
  }, [open, index, items]);

  if (!open || !media) return null;

  const src = tauri.convertFileSrc(media.path);
  const meta = [formatBytes(media.size), formatDateTime(media.modifiedAt)]
    .filter(Boolean)
    .join(' · ');

  return createPortal(
    <div
      ref={dialogRef}
      tabIndex={-1}
      role="dialog"
      aria-modal="true"
      aria-label={`媒体预览：${media.name}`}
      className="fixed inset-0 z-[1100] flex flex-col select-none"
      style={{ backgroundColor: 'rgba(0,0,0,0.92)' }}
      onClick={onClose}
    >
      <div
        className="flex items-center gap-2 px-4 h-14 shrink-0 text-white"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium" title={media.name}>
            {media.name}
          </div>
          <div className="text-xs text-white/60 truncate">
            <span className="tabular-nums">
              {(index as number) + 1} / {total}
            </span>
            {meta && <span className="ml-2">{meta}</span>}
          </div>
        </div>
        {!media.isVideo && (
          <>
            <ToolbarButton
              title="缩小（-）"
              icon={<ZoomOutOutlined />}
              onClick={() => setScale((s) => clampScale(s / 1.25))}
            />
            <span className="text-xs text-white/50 w-12 text-center tabular-nums">
              {Math.round(scale * 100)}%
            </span>
            <ToolbarButton
              title="放大（+）"
              icon={<ZoomInOutlined />}
              onClick={() => setScale((s) => clampScale(s * 1.25))}
            />
            <ToolbarButton
              title="实际大小 / 适应窗口（0）"
              icon={<OneToOneOutlined />}
              onClick={() => setScale(1)}
            />
            <span className="w-px h-5 bg-white/20 mx-1" />
          </>
        )}
        <ToolbarButton
          title="用默认程序打开"
          icon={<FolderOpenOutlined />}
          onClick={() => onOpenExternal(media)}
        />
        <ToolbarButton
          title="在资源管理器中显示"
          icon={<FolderFilled />}
          onClick={() => onShowInFolder(media)}
        />
        <ToolbarButton
          title="关闭（Esc）"
          icon={<CloseOutlined />}
          onClick={onClose}
        />
      </div>
      <div className="relative flex-1 min-h-0 px-16 pb-6">
        {media.isVideo ? (
          <div
            className="w-full h-full flex items-center justify-center"
            onClick={(e) => e.stopPropagation()}
          >
            <video
              key={src}
              src={src}
              controls
              autoPlay
              className="max-w-full max-h-full bg-black outline-none"
              onLoadedMetadata={(e) => {
                // 沿用上一次的音量 / 静音设置
                e.currentTarget.volume = videoAudioState.volume;
                e.currentTarget.muted = videoAudioState.muted;
              }}
              onVolumeChange={(e) => {
                videoAudioState.volume = e.currentTarget.volume;
                videoAudioState.muted = e.currentTarget.muted;
              }}
            />
          </div>
        ) : (
          <ZoomableImage
            key={src}
            src={src}
            alt={media.name}
            scale={scale}
            onScaleChange={setScale}
          />
        )}
        <button
          className="absolute left-3 top-1/2 -translate-y-1/2 w-11 h-11 rounded-full border-0 !bg-white/10 hover:!bg-white/25 disabled:opacity-0 !text-white flex items-center justify-center transition-colors text-lg cursor-pointer"
          disabled={!hasPrev}
          title="上一个（←）"
          onClick={(e) => {
            e.stopPropagation();
            goPrev();
          }}
        >
          <LeftOutlined />
        </button>
        <button
          className="absolute right-3 top-1/2 -translate-y-1/2 w-11 h-11 rounded-full border-0 !bg-white/10 hover:!bg-white/25 disabled:opacity-0 !text-white flex items-center justify-center transition-colors text-lg cursor-pointer"
          disabled={!hasNext}
          title="下一个（→）"
          onClick={(e) => {
            e.stopPropagation();
            goNext();
          }}
        >
          <RightOutlined />
        </button>
      </div>
    </div>,
    document.body,
  );
};
