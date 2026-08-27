import { BottomSheet, SheetHeader } from '../../components/BottomSheet';
import { Icon } from '../../components/Icon';
import type { BabyEvent } from '../../domain/model';
import { PoopChoices, type PoopDraft } from './PoopSheet';

export type EventEditDraft = {
  timeInput: string;
  amount: string;
  leftSeconds: string;
  rightSeconds: string;
  leftMl: string;
  rightMl: string;
  durationMinutes: string;
  poop: PoopDraft;
};

type EventEditorSheetProps = {
  open: boolean;
  onClose: () => void;
  event?: BabyEvent;
  draft: EventEditDraft;
  onChange: (patch: Partial<EventEditDraft>) => void;
  deleteConfirm: boolean;
  onDeleteConfirm: (confirming: boolean) => void;
  onSave: () => void;
  onDelete: () => void;
};

const TITLES: Partial<Record<BabyEvent['type'], string>> = {
  feed: '编辑历史瓶喂',
  direct_breastfeed: '编辑母乳亲喂',
  bottle_breast_milk: '编辑母乳瓶喂',
  formula_feed: '编辑配方奶',
  pumping: '编辑泵奶',
  poop: '编辑便便',
  pee: '编辑尿尿',
  sleep_start: '编辑入睡时间',
};

function digits(value: string) {
  return value.replace(/\D/g, '');
}

function NumberField({ label, unit, value, onChange }: { label: string; unit: string; value: string; onChange: (v: string) => void }) {
  return <label className="field">
    <span>{label}</span>
    <div className="field-unit"><input inputMode="numeric" value={value} onChange={e => onChange(digits(e.target.value))}/><b>{unit}</b></div>
  </label>;
}

export function EventEditorSheet(p: EventEditorSheetProps) {
  const event = p.event;
  return <BottomSheet open={p.open} onClose={p.onClose} tall>
    {!event ? null : <div className="sheet-stack">
      <SheetHeader eyebrow="修改记录" title={TITLES[event.type] || '编辑醒来时间'} onClose={p.onClose}/>
      <label className="field"><span>记录时间</span><input type="datetime-local" value={p.draft.timeInput} onChange={e => p.onChange({ timeInput: e.target.value })}/></label>

      {(event.type === 'feed' || event.type === 'bottle_breast_milk' || event.type === 'formula_feed') &&
        <NumberField label="实际喝下量" unit="ml" value={p.draft.amount} onChange={amount => p.onChange({ amount })}/>}

      {event.type === 'direct_breastfeed' && <div className="feeding-edit-grid">
        <NumberField label="左侧时长" unit="秒" value={p.draft.leftSeconds} onChange={leftSeconds => p.onChange({ leftSeconds })}/>
        <NumberField label="右侧时长" unit="秒" value={p.draft.rightSeconds} onChange={rightSeconds => p.onChange({ rightSeconds })}/>
      </div>}

      {event.type === 'pumping' && <>
        <div className="feeding-edit-grid">
          <NumberField label="左侧泵出" unit="ml" value={p.draft.leftMl} onChange={leftMl => p.onChange({ leftMl })}/>
          <NumberField label="右侧泵出" unit="ml" value={p.draft.rightMl} onChange={rightMl => p.onChange({ rightMl })}/>
        </div>
        <NumberField label="泵奶时长（选填）" unit="分钟" value={p.draft.durationMinutes} onChange={durationMinutes => p.onChange({ durationMinutes })}/>
      </>}

      {event.type === 'poop' && <PoopChoices compact draft={p.draft.poop} onChange={patch => p.onChange({ poop: { ...p.draft.poop, ...patch } })}/>}

      <button className="primary" onClick={p.onSave}>保存修改</button>
      {!p.deleteConfirm
        ? <button className="danger-ghost" onClick={() => p.onDeleteConfirm(true)}><Icon type="trash" size={17}/> 删除这条记录</button>
        : <div className="danger-confirm">
            <span>确定删除？删除后会同步到家人的设备。</span>
            <div><button onClick={() => p.onDeleteConfirm(false)}>取消</button><button onClick={p.onDelete}>确定删除</button></div>
          </div>}
    </div>}
  </BottomSheet>;
}
