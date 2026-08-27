import { BottomSheet, SheetHeader } from '../../components/BottomSheet';
import { Icon } from '../../components/Icon';
import { formatDateTime } from '../../domain/format';

const MAX_ML = 1000;
const STEPS = [-10, -5, 5, 10];

type BottleAmountSheetProps = {
  open: boolean;
  onClose: () => void;
  eyebrow: string;
  ariaLabel: string;
  submitLabel: string;
  draftAt: number;
  value: string;
  onChange: (value: string) => void;
  onEditTime: () => void;
  onSubmit: () => void;
};

/** Shared by 母乳瓶喂 and 配方奶 — both record a single "actually drunk" volume. */
export function BottleAmountSheet(p: BottleAmountSheetProps) {
  const clamp = (n: number) => String(Math.min(MAX_ML, Math.max(0, n)));
  return <BottomSheet open={p.open} onClose={p.onClose}>
    <SheetHeader eyebrow={p.eyebrow} title="输入实际喝下量" onClose={p.onClose}/>
    <button className="feed-hint" onClick={p.onEditTime}><Icon type="clock" size={17}/><span>{formatDateTime(p.draftAt)}</span><b>修改</b></button>
    <div className="custom-input">
      <input
        autoFocus
        aria-label={p.ariaLabel}
        inputMode="numeric"
        value={p.value}
        onChange={e => {
          const value = e.target.value.replace(/\D/g, '');
          p.onChange(value ? String(Math.min(MAX_ML, Number(value))) : '');
        }}
      />
      <span>ml</span>
    </div>
    <div className="stepper">{STEPS.map(v => <button key={v} onClick={() => p.onChange(clamp(Number(p.value || 0) + v))}>{v > 0 ? `+${v}` : v}</button>)}</div>
    <button className="primary" disabled={!Number(p.value)} onClick={p.onSubmit}>{p.submitLabel}</button>
  </BottomSheet>;
}
