/** 서버 InputNormalizer.isValidIsbn13과 같은 규칙: 13자리 숫자, 978/979 접두, 체크섬. 아니면 도서관 조회를 하지 않는다(F1) */
export function isValidIsbn13(value: string): boolean {
  if (!/^\d{13}$/.test(value)) return false
  if (!value.startsWith('978') && !value.startsWith('979')) return false
  let sum = 0
  for (let i = 0; i < 12; i++) sum += Number(value[i]) * (i % 2 === 0 ? 1 : 3)
  return (10 - (sum % 10)) % 10 === Number(value[12])
}
