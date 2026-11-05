/* eslint-disable react/prop-types */
import {
  FolderFilled,
  FolderOpenOutlined,
  PlayCircleFilled,
  VideoCameraOutlined,
} from '@ant-design/icons';
import { Tooltip } from 'antd';
import clsx from 'clsx';
import React, { useEffect, useRef, useState } from 'react';
import { GalleryMedia } from '../../stores/gallery';
import {
  mediaVersion,
  formatBytes,
  formatDateTime,
  formatDuration,
} from '../../utils/gallery-media';
import {
  VideoThumb as VideoThumbData,
  cancelVideoThumb,
  getCachedVideoThumb,
  requestVideoThumb,
} from '../../utils/video-thumbnail';

/** 视频首帧缩略图：进入视口后排队生成，离开视口取消排队 */
const VideoThumb: React.FC<{
  path: string;
  src: string;
  fitMode: 'cover' | 'contain';
  enabled: boolean;
  className?: string;
  onDuration?: (d: number | null) => void;
}> = ({ path, src, fitMode, enabled, className, onDuration }) => {
  const [thumb, setThumb] = useState<VideoThumbData | undefined>(() =>
    getCachedVideoThumb(path),
  );
  const ref = useRef<HTMLDivElement>(null);
  const onDurationRef = useRef(onDuration);
  onDurationRef.current = onDuration;

  useEffect(() => {
    if (thumb) onDurationRef.current?.(thumb.duration);
  }, [thumb]);

  useEffect(() => {
    if (!enabled || thumb) return;
    const el = ref.current;
    if (!el) return;
    let mounted = true;
    let requested = false;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          if (requested) return;
          requested = true;
          requestVideoThumb(path, src).then((result) => {
            if (!mounted) return;
            if (result) setThumb(result);
            else requested = false; // 被取消：下次进入视口重新请求
          });
        } else if (requested) {
          cancelVideoThumb(path);
        }
      },
      { rootMargin: '240px' },
    );
    observer.observe(el);
    return () => {
      mounted = false;
      observer.disconnect();
      cancelVideoThumb(path);
    };
  }, [enabled, path, src, thumb]);

  return (
    <div
      ref={ref}
      className={clsx(
        'w-full h-full flex items-center justify-center bg-[#3D3929]',
        className,
      )}
    >
      {thumb?.dataUrl ? (
        <img
          src={thumb.dataUrl}
          alt=""
          draggable={false}
          className="w-full h-full"
          style={{ objectFit: fitMode }}
        />
      ) : (
        <VideoCameraOutlined className="text-3xl text-white/60" />
      )}
    </div>
  );
};

export interface MediaTileProps {
  media: GalleryMedia;
  src: string;
  fitMode: 'cover' | 'contain';
  videoThumbs: boolean;
  onOpen: () => void;
  onOpenExternal: () => void;
  onShowInFolder: () => void;
}

const TileActions: React.FC<{
  onOpenExternal: () => void;
  onShowInFolder: () => void;
  className?: string;
}> = ({ onOpenExternal, onShowInFolder, className }) => (
  <span className={clsx('flex gap-1', className)}>
    <Tooltip title="用默认程序打开">
      <button
        className="w-7 h-7 rounded-md bg-black/60 hover:bg-black/85 text-white flex items-center justify-center transition-colors"
        onClick={(e) => {
          e.stopPropagation();
          onOpenExternal();
        }}
      >
        <FolderOpenOutlined />
      </button>
    </Tooltip>
    <Tooltip title="在资源管理器中显示">
      <button
        className="w-7 h-7 rounded-md bg-black/60 hover:bg-black/85 text-white flex items-center justify-center transition-colors"
        onClick={(e) => {
          e.stopPropagation();
          onShowInFolder();
        }}
      >
        <FolderFilled />
      </button>
    </Tooltip>
  </span>
);

/** 网格视图中的媒体卡片 */
export const MediaTile = React.memo(function MediaTile({
  media,
  src,
  fitMode,
  videoThumbs,
  onOpen,
  onOpenExternal,
  onShowInFolder,
}: MediaTileProps) {
  const [duration, setDuration] = useState<number | null>(null);
  return (
    <div
      role="button"
      tabIndex={0}
      className="group relative aspect-square rounded-lg overflow-hidden bg-ant-color-fill-tertiary cursor-pointer outline-none focus-visible:ring-2 focus-visible:ring-ant-color-primary"
      style={
        {
          contentVisibility: 'auto',
          containIntrinsicSize: '200px',
        } as React.CSSProperties
      }
      title={media.name}
      onClick={onOpen}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onOpen();
        }
      }}
    >
      {media.isVideo ? (
        <>
          <VideoThumb
            key={mediaVersion(media)}
            path={mediaVersion(media)}
            src={src}
            fitMode={fitMode}
            enabled={videoThumbs}
            onDuration={setDuration}
          />
          <PlayCircleFilled className="absolute inset-0 m-auto w-10 h-10 text-4xl text-white/85 drop-shadow pointer-events-none" />
          <span className="absolute right-1.5 bottom-1.5 px-1.5 py-0.5 rounded bg-black/65 text-white text-[10px] leading-4 pointer-events-none">
            {duration ? formatDuration(duration) : '视频'}
          </span>
        </>
      ) : (
        <img
          src={src}
          alt={media.name}
          loading="lazy"
          decoding="async"
          draggable={false}
          className="w-full h-full transition-transform duration-300 group-hover:scale-[1.03]"
          style={{ objectFit: fitMode }}
        />
      )}
      <TileActions
        onOpenExternal={onOpenExternal}
        onShowInFolder={onShowInFolder}
        className="absolute right-1.5 top-1.5 opacity-0 group-hover:opacity-100 group-focus-within:opacity-100 transition-opacity"
      />
      <span className="absolute left-0 right-0 bottom-0 px-2 pt-6 pb-1.5 bg-gradient-to-t from-black/70 to-transparent text-white text-[11px] leading-4 truncate opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none">
        {media.name}
      </span>
    </div>
  );
});

/** 列表视图中的媒体行 */
export const MediaRow = React.memo(function MediaRow({
  media,
  src,
  fitMode,
  videoThumbs,
  onOpen,
  onOpenExternal,
  onShowInFolder,
}: MediaTileProps) {
  return (
    <div
      role="button"
      tabIndex={0}
      className="group flex items-center gap-3 p-2 rounded-lg bg-ant-color-bg-container border border-ant-color-border-secondary hover:border-ant-color-primary cursor-pointer transition-colors"
      onClick={onOpen}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onOpen();
        }
      }}
    >
      <div className="w-14 h-14 rounded-md overflow-hidden bg-ant-color-fill-tertiary shrink-0 relative">
        {media.isVideo ? (
          <>
            <VideoThumb
              key={mediaVersion(media)}
            path={mediaVersion(media)}
              src={src}
              fitMode={fitMode}
              enabled={videoThumbs}
            />
            <PlayCircleFilled className="absolute inset-0 m-auto w-5 h-5 text-xl text-white/85 pointer-events-none" />
          </>
        ) : (
          <img
            src={src}
            alt={media.name}
            loading="lazy"
            decoding="async"
            draggable={false}
            className="w-full h-full"
            style={{ objectFit: fitMode }}
          />
        )}
      </div>
      <span className="grow min-w-0">
        <span className="block truncate select-text text-sm">
          {media.name}
        </span>
        <span className="block text-xs text-ant-color-text-tertiary mt-0.5">
          {[
            media.isVideo ? '视频' : '图片',
            formatBytes(media.size),
            formatDateTime(media.modifiedAt),
          ]
            .filter(Boolean)
            .join(' · ')}
        </span>
      </span>
      <TileActions
        onOpenExternal={onOpenExternal}
        onShowInFolder={onShowInFolder}
        className="shrink-0 opacity-60 group-hover:opacity-100 transition-opacity"
      />
    </div>
  );
});
