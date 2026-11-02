/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  /** 카카오 지도 JavaScript 키(공개 키, 콘솔의 도메인 제한으로 보호). 없으면 지도를 숨기고 목록만 보인다 */
  readonly VITE_KAKAO_MAP_JAVASCRIPT_KEY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
