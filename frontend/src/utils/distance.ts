// 서버 거리는 소수 한 자리 km. 0.0은 '0km'로 보이면 이상하니 100m 이내로
export function formatDistance(km: number): string {
  return km < 0.1 ? '100m 이내' : `${km}km`
}
