export function Spinner({ size = 20 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      className="animate-spin"
      aria-label="로딩 중"
    >
      <circle cx="12" cy="12" r="10" stroke="#e2e8f0" strokeWidth="3" />
      <path
        d="M12 2a10 10 0 0 1 10 10"
        stroke="#2563EB"
        strokeWidth="3"
        strokeLinecap="round"
      />
    </svg>
  )
}
