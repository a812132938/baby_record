import { BottomSheet, SheetHeader } from '../../components/BottomSheet';
import { ChoiceRow, POOP_AMOUNTS, POOP_COLORS, POOP_TEXTURES } from '../../components/ChoiceRow';
import { Icon } from '../../components/Icon';
import { formatDateTime } from '../../domain/format';

export type PoopDraft = { color: string; texture: string; amount: string };

type PoopSheetProps = {
  open: boolean;
  onClose: () => void;
  draftAt: number;
  draft: PoopDraft;
  onChange: (patch: Partial<PoopDraft>) => void;
  onEditTime: () => void;
  onSubmit: () => void;
};

export function PoopChoices({ draft, onChange, compact = false }: { draft: PoopDraft; onChange: (patch: Partial<PoopDraft>) => void; compact?: boolean }) {
  return <div className={`poop-editor${compact ? ' compact' : ''}`}>
    <ChoiceRow title="颜色" values={POOP_COLORS} value={draft.color} onChange={(v: string) => onChange({ color: v })}/>
    <ChoiceRow title="性状" values={POOP_TEXTURES} value={draft.texture} onChange={(v: string) => onChange({ texture: v })}/>
    <ChoiceRow title="量" values={POOP_AMOUNTS} value={draft.amount} onChange={(v: string) => onChange({ amount: v })}/>
  </div>;
}

export function PoopSheet(p: PoopSheetProps) {
  return <BottomSheet open={p.open} onClose={p.onClose} tall>
    <SheetHeader eyebrow="记录便便" title="快速选一下状态" onClose={p.onClose}/>
    <button className="feed-hint" onClick={p.onEditTime}><Icon type="clock" size={17}/><span>{formatDateTime(p.draftAt)}</span><b>修改</b></button>
    <PoopChoices draft={p.draft} onChange={p.onChange}/>
    <button className="primary" onClick={p.onSubmit}>记录便便</button>
  </BottomSheet>;
}
