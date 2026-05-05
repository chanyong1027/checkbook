const DEFAULT_API_ERROR_MESSAGE = '요청 처리 중 오류가 발생했습니다.'
const NETWORK_ERROR_MESSAGE = '네트워크 연결을 확인해주세요.'

export class ApiError extends Error {
  readonly status: number
  readonly code?: string

  constructor(message: string, status: number, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export async function toApiError(res: Response): Promise<ApiError> {
  let message = DEFAULT_API_ERROR_MESSAGE
  let code: string | undefined

  try {
    const body: unknown = await res.json()

    if (body && typeof body === 'object') {
      if ('message' in body && typeof body.message === 'string' && body.message.trim()) {
        message = body.message
      }
      if ('code' in body && typeof body.code === 'string' && body.code.trim()) {
        code = body.code
      }
    }
  } catch {
    // non-JSON 응답이면 generic message 유지
  }

  return new ApiError(message, res.status, code)
}

export function toUserMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError) return err.message
  if (err instanceof Error && err.message === 'Failed to fetch') {
    return NETWORK_ERROR_MESSAGE
  }
  return fallback
}
