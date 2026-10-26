// 아이콘 모음(결정 카드 2026-10-26 카드 2: 라이브러리 대신 직접 그린 SVG를 한곳에).
// 선 굵기·끝 모양을 통일한다. 글자 색을 따라가도록 stroke는 currentColor. 장식이라 기본으로 스크린리더에서 숨긴다 —
// 아이콘만 있는 버튼은 버튼 쪽에 aria-label을 단다
import type { ReactNode, SVGProps } from 'react'

interface IconProps extends Omit<SVGProps<SVGSVGElement>, 'children'> {
  size?: number
  strokeWidth?: number
}

function Svg({ size = 20, strokeWidth = 1.75, children, ...rest }: IconProps & { children: ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      {children}
    </svg>
  )
}

export const BookIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5z" />
    <path d="M4 19.5A2.5 2.5 0 0 0 6.5 22H20v-5" />
  </Svg>
)

export const SearchIcon = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="11" cy="11" r="7" />
    <path d="m20 20-3.5-3.5" />
  </Svg>
)

export const CloseIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M18 6 6 18M6 6l12 12" />
  </Svg>
)

export const ChevronLeftIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="m15 18-6-6 6-6" />
  </Svg>
)

export const ChevronRightIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="m9 18 6-6-6-6" />
  </Svg>
)

export const SpinnerIcon = ({ className = '', ...p }: IconProps) => (
  <Svg {...p} className={`animate-spin ${className}`}>
    <path d="M12 3a9 9 0 1 0 9 9" />
  </Svg>
)

// 공공도서관
export const LibraryIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3 21h18M5 21V10l7-5 7 5v11M9 21v-6h6v6" />
  </Svg>
)

// 전자도서관(태블릿)
export const TabletIcon = (p: IconProps) => (
  <Svg {...p}>
    <rect x="5" y="2.5" width="14" height="19" rx="2" />
    <path d="M10 18h4" />
  </Svg>
)

// 구독(오디오북·전자책)
export const HeadphonesIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 18v-6a8 8 0 0 1 16 0v6" />
    <rect x="3" y="15" width="4" height="6" rx="1.5" />
    <rect x="17" y="15" width="4" height="6" rx="1.5" />
  </Svg>
)

// 새 책·중고·매장
export const StoreIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3 9l1.5-5h15L21 9M4 9v11h16V9M9 20v-6h6v6" />
  </Svg>
)

export const HistoryIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3 12a9 9 0 1 0 3-6.7L3 8" />
    <path d="M3 3v5h5M12 7v5l3 2" />
  </Svg>
)
