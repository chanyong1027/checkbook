import type { PublicLibraryBatchItem } from '../../types'

export type PointTone = 'ok' | 'off' | 'unknown'

export interface MapPoint {
  code: string
  name: string
  lat: number
  lng: number
  /** 목록 순서 번호(1부터). 마커와 목록 행을 이어 준다 */
  label: string
  tone: PointTone
  stateLabel: string
}

const STATE_LABEL: Record<string, string> = {
  AVAILABLE: '대출가능',
  ON_LOAN: '대출중',
  NOT_HELD: '미소장',
}

/** 목록에 보이는 기관을 같은 순서·번호로 마커로 바꾼다. 좌표가 이상한 기관은 지도에서만 뺀다(목록엔 남음) */
export function toMapPoints(items: PublicLibraryBatchItem[]): MapPoint[] {
  return items
    .map((item, index) => ({ item, index }))
    .filter(({ item }) =>
      Number.isFinite(item.latitude) && Number.isFinite(item.longitude)
      && Math.abs(item.latitude) <= 90 && Math.abs(item.longitude) <= 180
      && !(item.latitude === 0 && item.longitude === 0))
    .map(({ item, index }) => ({
      code: item.libCode,
      name: item.name,
      lat: item.latitude,
      lng: item.longitude,
      label: String(index + 1),
      tone: item.state === 'AVAILABLE' ? 'ok' : item.state === 'UNKNOWN' ? 'unknown' : 'off',
      stateLabel: STATE_LABEL[item.state] ?? '확인 못 함',
    }))
}
