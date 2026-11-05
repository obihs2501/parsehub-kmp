/**
 * 视频首帧缩略图：用隐藏的 <video> 元素定位到片头后绘制到 canvas，
 * 结果以小尺寸 JPEG dataURL 缓存在内存中（有上限），
 * 通过并发队列串行处理，避免同时解码大量视频占用内存。
 */

export interface VideoThumb {
  /** 缩略图 dataURL；null 表示生成失败（不再重试） */
  dataUrl: string | null;
  duration: number | null;
}

const CACHE_LIMIT = 800;
const CONCURRENCY = 2;
const MAX_EDGE = 360;
const TIMEOUT_MS = 12000;

const cache = new Map<string, VideoThumb>();

interface Job {
  path: string;
  src: string;
  cancelled: boolean;
  resolvers: ((value: VideoThumb | undefined) => void)[];
}

const queue: Job[] = [];
const jobs = new Map<string, Job>();
let running = 0;

function remember(path: string, value: VideoThumb) {
  if (cache.size >= CACHE_LIMIT) {
    const oldest = cache.keys().next().value;
    if (oldest !== undefined) cache.delete(oldest);
  }
  cache.set(path, value);
}

export function getCachedVideoThumb(path: string): VideoThumb | undefined {
  return cache.get(path);
}

/**
 * 请求缩略图。缓存命中立即返回；否则排队生成。
 * 返回 undefined 表示任务在开始前被取消（未缓存，可再次请求）。
 */
export function requestVideoThumb(
  path: string,
  src: string,
): Promise<VideoThumb | undefined> {
  const hit = cache.get(path);
  if (hit) return Promise.resolve(hit);
  return new Promise((resolve) => {
    let job = jobs.get(path);
    if (job) {
      job.cancelled = false;
      job.resolvers.push(resolve);
      return;
    }
    job = { path, src, cancelled: false, resolvers: [resolve] };
    jobs.set(path, job);
    queue.push(job);
    pump();
  });
}

/** 取消尚未开始的生成任务（滚出可视区域时调用） */
export function cancelVideoThumb(path: string) {
  const job = jobs.get(path);
  if (job) job.cancelled = true;
}

function pump() {
  while (running < CONCURRENCY && queue.length > 0) {
    // 后进先出：优先处理最近进入视口的卡片
    const job = queue.pop()!;
    if (job.cancelled) {
      jobs.delete(job.path);
      job.resolvers.forEach((r) => r(undefined));
      continue;
    }
    running++;
    capture(job.src)
      .then((value) => {
        remember(job.path, value);
        job.resolvers.forEach((r) => r(value));
      })
      .catch(() => {
        const failed = { dataUrl: null, duration: null };
        remember(job.path, failed);
        job.resolvers.forEach((r) => r(failed));
      })
      .finally(() => {
        jobs.delete(job.path);
        running--;
        pump();
      });
  }
}

function capture(src: string): Promise<VideoThumb> {
  return new Promise((resolve) => {
    const video = document.createElement('video');
    video.muted = true;
    video.preload = 'metadata';
    video.playsInline = true;
    // asset 协议带 CORS 头，声明匿名跨域后 canvas 才不会被污染
    video.crossOrigin = 'anonymous';
    let done = false;
    let fallbackTimer: ReturnType<typeof setTimeout> | undefined;

    const cleanup = () => {
      clearTimeout(timer);
      if (fallbackTimer) clearTimeout(fallbackTimer);
      video.onloadedmetadata = null;
      video.onloadeddata = null;
      video.onseeked = null;
      video.onerror = null;
      video.removeAttribute('src');
      try {
        video.load();
      } catch {
        // ignore
      }
    };
    const finish = (value: VideoThumb) => {
      if (done) return;
      done = true;
      cleanup();
      resolve(value);
    };
    const draw = () => {
      if (done) return;
      const w = video.videoWidth;
      const h = video.videoHeight;
      if (!w || !h) {
        finish({ dataUrl: null, duration: null });
        return;
      }
      const scale = Math.min(1, MAX_EDGE / Math.max(w, h));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(w * scale));
      canvas.height = Math.max(1, Math.round(h * scale));
      try {
        const ctx = canvas.getContext('2d');
        if (!ctx) throw new Error('no canvas context');
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        finish({
          dataUrl: canvas.toDataURL('image/jpeg', 0.75),
          duration: Number.isFinite(video.duration) ? video.duration : null,
        });
      } catch {
        finish({
          dataUrl: null,
          duration: Number.isFinite(video.duration) ? video.duration : null,
        });
      }
    };

    const timer = setTimeout(
      () => finish({ dataUrl: null, duration: null }),
      TIMEOUT_MS,
    );
    video.onloadedmetadata = () => {
      const duration = video.duration;
      const target =
        Number.isFinite(duration) && duration > 0
          ? Math.min(1, duration / 2)
          : 0;
      try {
        video.currentTime = target;
      } catch {
        // 某些格式不支持定位，等待 loadeddata 兜底
      }
    };
    video.onseeked = draw;
    video.onloadeddata = () => {
      // 定位事件未触发时（例如无法 seek），稍后直接绘制当前帧
      fallbackTimer = setTimeout(draw, 1500);
    };
    video.onerror = () => finish({ dataUrl: null, duration: null });
    video.src = src;
    video.load();
  });
}
