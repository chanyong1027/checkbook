// 한눈 요약의 결론 계산(도서관·전자도서관). 화면과 떼어 두어 테스트로 묶는다.
// 응답하지 않은 곳이 섞이면 '모두 대출중'·'없음' 같은 부정적인 결론을 단정하지 않는다
import type { ELibraryResult, ELibrarySearchResponse, PublicLibraryBatchItem } from '../../types'
import type { SummaryTone } from './BookPanel'
import type { Tone } from './parts'

export interface SummaryValue {
  value: string
  tone: SummaryTone
}

const PARTIAL = '일부 확인 못 함'

// 전자도서관 사이트 하나의 결과를 상태 칩으로. 사이트가 실패하면 '없음'과 구분해 보인다
export function elibSiteStatus(result: ELibraryResult): { tone: Tone; label: string } {
  switch (result.status) {
    case 'FAILED': return { tone: 'danger', label: '접속 실패' }
    case 'TIMEOUT': return { tone: 'unknown', label: '응답 없음' }
    // 이 도서관 사이트의 하루 조회 상한(작은 사이트 보호) — 자정에 풀린다
    case 'DAILY_LIMIT': return { tone: 'unknown', label: '오늘 조회 마감' }
  }
  if (result.books.some(b => b.available)) return { tone: 'ok', label: '대출가능' }
  if (result.books.length > 0) return { tone: 'off', label: '대출중' }
  return { tone: 'off', label: '소장 없음' }
}

// 공공도서관 칸의 결론. found = 소장(대출가능·대출중) 기관, unknownCount = 확인 못 한 기관 수.
// 확인 못 한 곳이 섞이면 '없음'·'모두 대출중'을 단정하지 않는다
export function summarizeLibraries(
  found: PublicLibraryBatchItem[],
  unknownCount: number,
  emptyLabel: string,
): SummaryValue {
  const available = found.filter(l => l.state === 'AVAILABLE').length
  if (available > 0) return { value: `${available}곳 대출가능`, tone: 'ok' }
  if (found.length > 0) {
    return { value: unknownCount > 0 ? `${found.length}곳 대출중 · ${PARTIAL}` : `${found.length}곳 대출중`, tone: 'off' }
  }
  return { value: unknownCount > 0 ? `소장 없음 · ${PARTIAL}` : emptyLabel, tone: 'off' }
}

// 확인 못 한 곳 = 답이 없던 사이트 + 검색 전에 빠진 곳(로그인 필요·비활성 등, metadata.failures — results와 겹치지 않음)
export function summarizeELibraries(res: ELibrarySearchResponse): SummaryValue {
  const checked = res.results.filter(r => ['ok', 'off'].includes(elibSiteStatus(r).tone))
  if (checked.length === 0) return { value: '확인 못 함', tone: 'warn' }
  const unanswered = res.results.length - checked.length + res.metadata.failures.length
  const available = checked.filter(r => elibSiteStatus(r).tone === 'ok').length
  if (available > 0) return { value: `${available}곳 대출가능`, tone: 'ok' }
  const held = checked.filter(r => r.books.length > 0).length
  // '모두 대출중'이라 하면 소장 없는 곳까지 책이 있는 것처럼 읽힌다 — 대출중인 곳 수만 말한다
  if (held > 0) return { value: unanswered > 0 ? `${held}곳 대출중 · ${PARTIAL}` : `${held}곳 대출중`, tone: 'off' }
  return { value: unanswered > 0 ? `소장 없음 · ${PARTIAL}` : '소장 없음', tone: 'off' }
}
