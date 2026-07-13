// 외부 데이터로 받은 URL을 <a href>에 넣기 전 http/https만 허용하는 가드
// (javascript: 등 위험 스킴 차단 — 렌더 측 공통 사용)
export function isSafeUrl(url: string | null | undefined): url is string {
  if (!url) return false
  try {
    const { protocol } = new URL(url)
    return protocol === 'http:' || protocol === 'https:'
  } catch {
    return false
  }
}
