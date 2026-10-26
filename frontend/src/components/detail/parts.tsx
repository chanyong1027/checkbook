// 상세 화면 조각: 섹션 틀, 묶음 제목, 상태 칩, 바깥 링크 버튼(재디자인 결정 카드 2026-10-26).
// 모바일은 섹션을 화면 끝까지 펴고(사이 8px 회색 띠), md 이상은 둥근 흰 카드로 띄운다
import type { ReactNode } from 'react'
import { AlertIcon, CheckIcon, CloseIcon, ExternalIcon, MinusIcon } from '../shared/icons'

export function DetailSection({
  id,
  icon,
  title,
  aside,
  children,
}: {
  id?: string
  icon: ReactNode
  title: string
  aside?: ReactNode
  children: ReactNode
}) {
  return (
    <section
      id={id}
      aria-labelledby={id ? `${id}-title` : undefined}
      className="scroll-mt-20 bg-white px-4 py-5 md:p-6 md:rounded-2xl md:border md:border-ink-100"
    >
      <div className="flex items-center gap-2 mb-4">
        <span className="text-ink-600 shrink-0">{icon}</span>
        {/* 한눈 요약에서 이 칸으로 옮겨 올 때 포커스를 받는다(tabIndex -1: 탭 순서에는 안 들어감) */}
        <h3 id={id ? `${id}-title` : undefined} tabIndex={-1} className="text-body-lg font-bold text-ink-900 focus:outline-none">
          {title}
        </h3>
        {aside && <div className="ml-auto flex items-center gap-2 min-w-0">{aside}</div>}
      </div>
      {children}
    </section>
  )
}

// 빌리기·읽기·사기 묶음 제목. 모바일은 섹션이 화면 끝까지라 제목에만 좌우 여백
export function GroupHeading({ title, desc }: { title: string; desc: string }) {
  return (
    <div className="flex items-baseline gap-2 px-4 md:px-0 pt-5 pb-1 md:pt-4 md:pb-0">
      <h2 className="text-title font-bold text-ink-900">{title}</h2>
      <span className="text-meta text-ink-600">{desc}</span>
    </div>
  )
}

export type Tone = 'ok' | 'off' | 'unknown' | 'danger'

const TONE_CLASS: Record<Tone, string> = {
  ok: 'bg-status-ok-soft text-status-ok',
  off: 'bg-status-off-soft text-status-off',
  unknown: 'bg-white text-ink-600 border border-dashed border-ink-300',
  danger: 'bg-status-danger-soft text-status-danger',
}

const TONE_ICON: Record<Tone, ReactNode> = {
  ok: <CheckIcon size={14} strokeWidth={2.5} />,
  off: <CloseIcon size={14} strokeWidth={2.5} />,
  unknown: <MinusIcon size={14} strokeWidth={2.5} />,
  danger: <AlertIcon size={14} strokeWidth={2.2} />,
}

// 상태는 색만으로 구분하지 않는다 — 기호와 글자를 함께(WCAG 1.4.1)
export function StatusChip({ tone, children }: { tone: Tone; children: ReactNode }) {
  return (
    <span
      className={`inline-flex items-center gap-1 h-7 px-2.5 rounded-full text-label font-semibold whitespace-nowrap ${TONE_CLASS[tone]}`}
    >
      {TONE_ICON[tone]}
      {children}
    </span>
  )
}

// 바깥 사이트로 나가는 버튼. primary는 칸에서 가장 중요한 행동 하나에만
export function ExternalButton({
  href,
  label,
  children,
  primary = false,
  className = '',
}: {
  href: string
  label?: string
  children: ReactNode
  primary?: boolean
  className?: string
}) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      aria-label={label}
      className={
        'inline-flex items-center justify-center gap-1.5 min-h-11 px-4 rounded-xl text-meta font-semibold transition ' +
        (primary
          ? 'bg-primary text-white hover:bg-primary-dark '
          : 'bg-white border border-ink-200 text-ink-900 hover:border-ink-400 ') +
        className
      }
    >
      {children}
      <ExternalIcon size={14} strokeWidth={2} />
    </a>
  )
}

// id를 칸과 같게 주면 불러오는 중에도 한눈 요약에서 그 자리로 옮겨 갈 수 있다
export function SectionSkeleton({ id, rows = 3 }: { id?: string; rows?: number }) {
  return (
    <div id={id} className="scroll-mt-20 bg-white px-4 py-5 md:p-6 md:rounded-2xl md:border md:border-ink-100 animate-pulse" aria-hidden="true">
      <div className="h-5 w-32 bg-ink-100 rounded mb-5" />
      <div className="space-y-4">
        {Array.from({ length: rows }, (_, i) => (
          <div key={i} className="flex items-center justify-between gap-4">
            <div className="h-4 bg-ink-100 rounded w-2/5" />
            <div className="h-7 w-20 bg-ink-100 rounded-full" />
          </div>
        ))}
      </div>
    </div>
  )
}

// 칸 안의 안내 문구(빈 결과·확인 불가 등)
export function SectionNotice({ children }: { children: ReactNode }) {
  return <p className="text-body text-ink-600 py-1">{children}</p>
}
