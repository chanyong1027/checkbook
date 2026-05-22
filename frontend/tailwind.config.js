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
          aladin: '#e8400c',
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
