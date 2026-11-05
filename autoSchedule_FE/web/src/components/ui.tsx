// Figma Design System 컴포넌트(Button, Badge, Avatar, SegmentItem, ShiftBlock, StatStrip, Modal)와 대응하는 공통 UI
import { useEffect, type ButtonHTMLAttributes, type ReactNode } from 'react';
import Icon from './Icon';

const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ');
export { cx };

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

export function Button({
  variant = 'secondary',
  size = 'md',
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: 'sm' | 'md' }) {
  return (
    <button
      className={cx(
        'inline-flex items-center justify-center gap-1.5 rounded-full font-medium whitespace-nowrap transition-colors disabled:cursor-not-allowed disabled:opacity-40',
        size === 'md' ? 'h-10 px-[18px] text-sm' : 'h-8 px-3 text-xs',
        // 검정(primary)은 화면당 하나
        variant === 'primary' && 'bg-ink text-white hover:bg-ink/85',
        variant === 'secondary' && 'border border-line-strong bg-surface hover:bg-bg',
        variant === 'ghost' && 'text-ink hover:bg-bg',
        variant === 'danger' && 'border border-danger bg-surface text-danger hover:bg-danger-subtle',
        className,
      )}
      {...props}
    />
  );
}

export type Tone = 'neutral' | 'brand' | 'danger' | 'warning' | 'success' | 'staff' | 'open' | 'middle' | 'close';

const toneClass: Record<Tone, string> = {
  neutral: 'bg-brand-subtle text-muted',
  brand: 'bg-brand-subtle text-ink',
  open: 'bg-open-bg text-open',
  middle: 'bg-middle-bg text-middle',
  close: 'bg-close-bg text-close',
  danger: 'bg-danger-subtle text-danger',
  warning: 'bg-warning-subtle text-warning',
  success: 'bg-success-subtle text-success',
  staff: 'bg-staff-bg text-staff',
};

export function Badge({ tone = 'neutral', children, className }: { tone?: Tone; children: ReactNode; className?: string }) {
  return (
    <span className={cx('inline-flex h-[22px] items-center rounded-md px-2 text-xs font-medium whitespace-nowrap', toneClass[tone], className)}>
      {children}
    </span>
  );
}

export function Card({ children, className }: { children: ReactNode; className?: string }) {
  return <section className={cx('rounded-[20px] border border-line bg-surface', className)}>{children}</section>;
}

// 사람마다 고정된 색(신원 표시용, 데이터 색 아님). id 순으로 돌려 쓴다
const TINTS = ['bg-open-bg text-open', 'bg-middle-bg text-middle', 'bg-close-bg text-close', 'bg-staff-bg text-staff', 'bg-brand-subtle text-ink'];

export function Avatar({ name, id = 0, size = 'md' }: { name: string; id?: number; size?: 'md' | 'lg' }) {
  return (
    <span className={cx('grid shrink-0 place-items-center rounded-full font-medium', size === 'md' ? 'size-8 text-xs' : 'size-10 text-sm', TINTS[(Math.max(id, 1) - 1) % TINTS.length])}>
      {name[0]}
    </span>
  );
}

export function Segmented<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T;
  options: { value: T; label: string }[];
  onChange: (v: T) => void;
}) {
  return (
    <div className="inline-flex max-w-full gap-0.5 overflow-x-auto rounded-full bg-brand-subtle p-1">
      {options.map((o) => (
        <button
          key={o.value}
          onClick={() => onChange(o.value)}
          className={cx(
            'h-8 shrink-0 rounded-full px-3.5 text-[13px] whitespace-nowrap transition-colors',
            o.value === value ? 'border border-line bg-surface font-medium' : 'text-muted hover:text-ink',
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export type BlockKind = 'open' | 'middle' | 'close' | 'staff' | 'tentative' | 'leave';

const blockClass: Record<BlockKind, string> = {
  open: 'bg-open-bg text-open',
  middle: 'bg-middle-bg text-middle',
  close: 'bg-close-bg text-close',
  staff: 'bg-staff-bg text-staff',
  tentative: 'bg-surface text-staff border-[1.5px] border-dashed border-staff',
  leave: 'bg-brand-subtle text-muted',
};

export function ShiftBlock({
  kind,
  title,
  sub,
  onClick,
  className,
}: {
  kind: BlockKind;
  title: ReactNode;
  sub?: ReactNode;
  onClick?: () => void;
  className?: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cx('block w-full rounded-md px-2.5 py-2 text-left leading-tight hover:brightness-[0.97]', blockClass[kind], className)}
    >
      <span className="block truncate text-xs font-medium">{title}</span>
      {sub && <span className="mt-0.5 block truncate text-xs">{sub}</span>}
    </button>
  );
}

export function Modal({ title, onClose, children, footer }: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-ink/30 p-4" onMouseDown={onClose}>
      <div
        role="dialog"
        aria-label={title}
        className="max-h-[90vh] w-full max-w-[440px] overflow-y-auto rounded-[20px] bg-surface shadow-[0_12px_32px_-4px_rgb(27_29_31/0.12),0_2px_6px_rgb(27_29_31/0.06)]"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <header className="flex items-center justify-between px-6 pt-5 pb-2">
          <h2 className="font-bold">{title}</h2>
          <button onClick={onClose} className="text-muted hover:text-ink" aria-label="닫기">
            <Icon name="x" size={18} />
          </button>
        </header>
        <div className="space-y-4 px-6 py-3">{children}</div>
        {footer && <footer className="flex justify-end gap-2 px-6 pt-4 pb-5">{footer}</footer>}
      </div>
    </div>
  );
}

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs font-medium">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-xs text-muted">{hint}</span>}
    </label>
  );
}

/** 너비 없는 입력칸 모양. 너비를 따로 줄 때 쓴다 (inputClass의 w-full이 다른 너비를 이기므로) */
export const inputBase =
  'h-10 rounded-[10px] border border-line-strong bg-surface px-3 text-sm outline-none focus:border-ink focus:ring-2 focus:ring-ink/10';
export const inputClass = `${inputBase} w-full`;

export function Stat({ label, value, sub, tone }: { label: string; value: ReactNode; sub?: ReactNode; tone?: 'danger' | 'warning' }) {
  return (
    <Card className="p-5">
      <div className="text-[13px] text-muted">{label}</div>
      <div className={cx('mt-1.5 text-2xl font-bold', tone === 'danger' && 'text-danger', tone === 'warning' && 'text-warning')}>{value}</div>
      {sub && <div className="mt-1 text-xs text-muted">{sub}</div>}
    </Card>
  );
}

/** 통계 띠: 카드 하나를 세로선으로 나눈다. 아이콘·색 없음. lead는 답이 되는 수치 하나 */
export function StatStrip({ items }: { items: { label: string; value: ReactNode; sub?: ReactNode; lead?: boolean }[] }) {
  return (
    <Card className="grid divide-y divide-line sm:grid-cols-3 sm:divide-x sm:divide-y-0">
      {items.map((s) => (
        <div key={s.label} className="px-6 py-5">
          <div className="text-sm text-muted">{s.label}</div>
          <div className={cx('mt-1 tabular-nums', s.lead ? 'text-[30px] leading-[38px] font-bold' : 'text-xl leading-7 font-medium')}>{s.value}</div>
          {s.sub && <div className="mt-1 text-xs text-muted">{s.sub}</div>}
        </div>
      ))}
    </Card>
  );
}

export function Loading() {
  return <div className="p-10 text-sm text-muted">불러오는 중…</div>;
}
