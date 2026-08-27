import { BottomSheet, SheetHeader } from '../../components/BottomSheet';
import { Icon } from '../../components/Icon';
import { formatDateTime } from '../../domain/format';

type PumpingSheetProps = {
  open: boolean;
  onClose: () => void;
  draftAt: number;
  leftMl: string;
  rightMl: string;
  durationMinutes: string;
  onLeftMl: (value: string) => void;
  onRightMl: (value: string) => void;
  onDurationMinutes: (value: string) => void;
  onEditTime: () => void;
  onSubmit: () => void;
};

export function PumpingSheet(p: PumpingSheetProps) {
  const total = Number(p.leftMl || 0) + Number(p.rightMl || 0);
  return <BottomSheet open={p.open} onClose={p.onClose}>
    <SheetHeader eyebrow="泵奶" title="分别记录两侧产量" onClose={p.onClose}/>
    <button className="feed-hint" onClick={p.onEditTime}><Icon type="clock" size={17}/><span>{formatDateTime(p.draftAt)}</span><b>修改</b></button>
    <div className="pump-grid">
      <label><span>左侧</span><div><input autoFocus aria-label="左侧泵奶量" inputMode="numeric" value={p.leftMl} onChange={e => p.onLeftMl(e.target.value.replace(/\D/g,'').slice(0,4))}/><b>ml</b></div></label>
      <label><span>右侧</span><div><input aria-label="右侧泵奶量" inputMode="numeric" value={p.rightMl} onChange={e => p.onRightMl(e.target.value.replace(/\D/g,'').slice(0,4))}/><b>ml</b></div></label>
    </div>
    <label className="field"><span>泵奶时长（选填）</span><div className="field-unit"><input aria-label="泵奶时长" inputMode="numeric" value={p.durationMinutes} onChange={e => p.onDurationMinutes(e.target.value.replace(/\D/g,'').slice(0,3))}/><b>分钟</b></div></label>
    <div className="pump-total"><span>总产量</span><strong>{total} ml</strong></div>
    <button className="primary" disabled={total < 1} onClick={p.onSubmit}>记录泵奶</button>
  </BottomSheet>;
}
