/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      // 재디자인 토큰(결정 카드 2026-10-26). 대비는 흰 바탕 기준 계산값 — 바탕이 회색이면 한 단계 진한 글자색을 쓴다
      colors: {
        // 버튼·링크 주황: 흰 글자 대비 4.5:1을 넘는 가장 선명한 주황(4.52). 선명한 #FF6B00은 흰 글자를 못 올려 accent로 따로 둔다
        primary: '#DC3A00',
        'primary-dark': '#B93000',
        // 브랜드 주황: 로고·선택 표시·강조 점·연한 배경에만(흰 글자 금지 — 대비 2.9)
        accent: {
          DEFAULT: '#FF6B00',
          soft: '#FFF4EC',
          // 연한 주황 바탕 위의 글자(5.6:1)
          strong: '#B93000',
        },
        // 차가운 회색 글자·선. 500은 흰 바탕에서만(4.6), 회색 바탕 위 작은 글자는 600 이상(6.5+). 400은 장식용(3.0)
        ink: {
          900: '#191F28',
          700: '#333D4B',
          600: '#4E5968',
          500: '#6B7684',
          400: '#8B95A1',
          300: '#B0B8C1',
          200: '#D1D6DB',
          100: '#E5E8EB',
          50: '#F2F4F6',
        },
        // 페이지 바탕(PC). 카드는 흰색, 모바일 섹션 구분 띠는 ink-50
        surface: '#F7F8FA',
        // 대출 상태(색만으로 구분하지 않는다 — 기호와 글자를 함께)
        status: {
          ok: '#146C2E',
          'ok-soft': '#E7F5EC',
          off: '#4E5968',
          'off-soft': '#F2F4F6',
          danger: '#B42318',
          'danger-soft': '#FDECEA',
        },
        brand: {
          // 버튼은 '배경 = 브랜드 색 틴트, 글자 = 진한 색'으로 쓴다. 브랜드 색을 그대로 글자로 쓰면
          // 작은 글자 대비 기준(WCAG AA 4.5:1)에 못 미친다 — -dark는 공식 색이 아니라 대비용으로 진하게 만든 색
          aladin: '#e8400c',
          'aladin-dark': '#ae3009',
          // YES24 공식 로고(logoN4.svg)의 파랑
          yes24: '#0080ff',
          'yes24-dark': '#0060bf',
          // 밀리 홈페이지의 강조 노랑 — 글자로는 거의 안 보여서 글자는 slate-800을 쓴다
          millie: '#ffc004',
          kakao: '#FEE500',
          'kakao-hover': '#FDD835',
          // 카카오 다크 — 텍스트/배경 양쪽으로 쓰이는 공식 갈색
          'kakao-dark': '#3A1D1D',
        },
      },
      fontFamily: {
        sans: ['"Pretendard Variable"', 'Pretendard', 'system-ui', 'sans-serif'],
        // 웹 글꼴을 따로 받지 않는다(ISBN 정도라 시스템 고정폭이면 충분)
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
      },
      // 글자 단계(rem, 행간). 본문·목록·입력은 body(16) — 입력칸이 16px 미만이면 iOS가 확대한다.
      // caption(12)은 출처 표기 같은 '안 읽어도 되는' 글자에만
      fontSize: {
        caption: ['0.75rem', { lineHeight: '1rem' }],
        label: ['0.8125rem', { lineHeight: '1.125rem' }],
        meta: ['0.875rem', { lineHeight: '1.25rem' }],
        body: ['1rem', { lineHeight: '1.5rem' }],
        'body-lg': ['1.0625rem', { lineHeight: '1.625rem' }],
        title: ['1.25rem', { lineHeight: '1.75rem', letterSpacing: '-0.01em' }],
        heading: ['1.5rem', { lineHeight: '2rem', letterSpacing: '-0.02em' }],
        'heading-lg': ['1.75rem', { lineHeight: '2.25rem', letterSpacing: '-0.02em' }],
        display: ['2rem', { lineHeight: '2.5rem', letterSpacing: '-0.02em' }],
        'display-lg': ['3rem', { lineHeight: '3.625rem', letterSpacing: '-0.03em' }],
      },
      maxWidth: {
        // 콘텐츠 최대 폭(PC). 그보다 넓으면 양옆 여백
        content: '1080px',
      },
    },
  },
  plugins: [],
}
