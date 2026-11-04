import type { PublicLibraryBatchItem } from '../../types'
import { isSafeUrl } from '../../utils/url.ts'

/**
 * 도서관 행의 사이트 링크 한 자리(2026-11-04 딥링크 결정).
 * - 제목 검색 결과(GET): "사이트에서 검색" 링크
 * - 제목 검색 결과(POST, 주소에 검색어가 안 남는 사이트): 같은 문구의 버튼형 폼(새 탭 제출)
 * - 검색 화면만 되는 곳: "도서관 검색"
 * - 형식이 없으면 지금처럼 "홈페이지"
 */
export type SiteLink =
  | { kind: 'get'; href: string; label: string }
  | { kind: 'post'; action: string; fields: [string, string][]; label: string }

export function siteLinkOf(item: PublicLibraryBatchItem): SiteLink | null {
  const link = item.searchLink
  if (link && isSafeUrl(link.url)) {
    if (link.kind === 'SEARCH_PAGE') return { kind: 'get', href: link.url, label: '도서관 검색' }
    if (link.method === 'POST' && link.fields) {
      return { kind: 'post', action: link.url, fields: Object.entries(link.fields), label: '사이트에서 검색' }
    }
    if (link.method === 'GET') return { kind: 'get', href: link.url, label: '사이트에서 검색' }
  }
  return isSafeUrl(item.homepage) ? { kind: 'get', href: item.homepage, label: '홈페이지' } : null
}
