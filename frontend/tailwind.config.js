/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        primary: '#F97316',
        'primary-dark': '#EA580C',
        surface: '#FFF8F3',
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
        sans: ['Pretendard', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'monospace'],
      },
    },
  },
  plugins: [],
}
