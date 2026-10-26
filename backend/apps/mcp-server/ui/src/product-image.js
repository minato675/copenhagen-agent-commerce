import { safeImage } from './catalog.js';

// Keep layout stable while an allowed merchant image loads or fails.
export function productImage(url) {
  const holder = document.createElement('div'); holder.className = 'picture';
  const placeholder = document.createElement('div'); placeholder.className = 'image-placeholder';
  placeholder.innerHTML = '<svg viewBox="0 0 32 32" fill="none" aria-hidden="true"><rect x="4" y="4" width="24" height="24" rx="5" stroke="currentColor" stroke-width="1.5"/><circle cx="12" cy="12" r="2" stroke="currentColor" stroke-width="1.5"/><path d="m5 23 8-7 5 4 4-4 5 6" stroke="currentColor" stroke-width="1.5"/></svg>';
  const label = document.createElement('span'); placeholder.append(label); holder.append(placeholder);
  const safe = safeImage(url);
  holder.dataset.state = safe ? 'loading' : 'missing';
  label.textContent = safe ? '이미지 불러오는 중' : '등록된 이미지가 없어요';
  if (safe) {
    const image = document.createElement('img'); image.alt = ''; image.loading = 'lazy'; image.referrerPolicy = 'no-referrer';
    image.onload = () => { holder.dataset.state = 'loaded'; };
    image.onerror = () => { holder.dataset.state = 'failed'; label.textContent = '이미지를 불러오지 못했어요'; image.remove(); };
    image.src = safe; holder.append(image);
  }
  return holder;
}
