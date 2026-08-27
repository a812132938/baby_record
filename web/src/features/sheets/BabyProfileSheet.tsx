import { BottomSheet, SheetHeader } from '../../components/BottomSheet';
import type { BabyGender } from '../../domain/model';
import { birthWeightGrams, birthWeightLabel, dateKey, daysOld, genderLabel } from '../../domain/format';
import { BirthWeightInput, GenderSegments } from './FamilySheet';

export type BabyProfileForm = {
  nickname: string;
  birthday: string;
  gender: BabyGender | '';
  birthWeightKg: string;
};

type BabyProfileSheetProps = {
  open: boolean;
  onClose: () => void;
  form: BabyProfileForm;
  onChange: (patch: Partial<BabyProfileForm>) => void;
  saving: boolean;
  onSave: () => void;
};

export function babyProfileIncomplete(form: BabyProfileForm) {
  return !form.nickname.trim() || !form.birthday || daysOld(form.birthday) === null
    || !form.gender || birthWeightGrams(form.birthWeightKg) === null;
}

export function BabyProfileSheet(p: BabyProfileSheetProps) {
  const f = p.form;
  const ageDays = daysOld(f.birthday || null);
  return <BottomSheet open={p.open} onClose={p.onClose} tall>
    <SheetHeader eyebrow="宝宝资料" title="基本信息" onClose={p.onClose}/>
    <label className="field"><span>宝宝称呼</span><input value={f.nickname} placeholder="请输入宝宝称呼" onChange={e => p.onChange({ nickname: e.target.value })}/></label>
    <label className="field"><span>出生年月日</span><input type="date" required value={f.birthday} max={dateKey(Date.now())} onChange={e => p.onChange({ birthday: e.target.value })}/></label>
    <div className="form-group field"><span>性别</span><GenderSegments value={f.gender} onChange={gender => p.onChange({ gender })}/></div>
    <label className="field"><span>出生体重</span><BirthWeightInput value={f.birthWeightKg} onChange={birthWeightKg => p.onChange({ birthWeightKg })}/></label>
    <div className="profile-preview">
      <span>首页将显示</span>
      <strong>{f.nickname || '宝宝'}{ageDays !== null ? ` · 出生第 ${ageDays} 天` : ' · 出生年月日未设置'} · {genderLabel(f.gender || null)} · {birthWeightLabel(birthWeightGrams(f.birthWeightKg))}</strong>
    </div>
    <button className="primary" disabled={babyProfileIncomplete(f) || p.saving} onClick={p.onSave}>{p.saving ? '正在保存…' : '保存宝宝资料'}</button>
  </BottomSheet>;
}
