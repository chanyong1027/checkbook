export function SectionCard({ icon, title, children }: { icon: React.ReactNode; title: string; children: React.ReactNode }) {
  return (
    <div className="bg-white rounded-2xl border border-orange-50 p-4 shadow-sm shadow-orange-50">
      <h3 className="text-[13px] font-semibold text-slate-500 flex items-center gap-1.5 mb-3">
        {icon}
        {title}
      </h3>
      {children}
    </div>
  )
}
