import { BottomSheet, SheetHeader } from '../../components/BottomSheet';
import { Icon } from '../../components/Icon';
import type { BabyGender, LocalBabyProfile, Me, OnboardingMode } from '../../domain/model';
import { birthWeightGrams, birthWeightLabel, dateKey, daysOld, genderLabel } from '../../domain/format';

export type CreateFamilyForm = {
  familyName: string;
  nickname: string;
  babyNickname: string;
  birthDate: string;
  gender: BabyGender | '';
  birthWeightKg: string;
};

export type JoinFamilyForm = { code: string; nickname: string };

export type RecoveryNotice = {
  legacyDetected: boolean;
  invalidMessage: string;
  onDiscardLegacy: () => void;
  onDiscardInvalid: () => void;
};

type FamilySheetProps = {
  open: boolean;
  onClose: () => void;
  me: Me | null;
  baby: LocalBabyProfile;
  realtime: boolean;
  installed: boolean;
  recovery: RecoveryNotice;
  invite: { code: string; loading: boolean; error: boolean; onRetry: () => void; onCopy: () => void };
  onOpenBabyProfile: () => void;
  onOpenDevices: () => void;
  onInstallPwa: () => void;
  onLogout: () => void;
  onboardingMode: OnboardingMode;
  onOnboardingMode: (mode: OnboardingMode) => void;
  createForm: CreateFamilyForm;
  onCreateForm: (patch: Partial<CreateFamilyForm>) => void;
  creating: boolean;
  onCreate: () => void;
  joinForm: JoinFamilyForm;
  onJoinForm: (patch: Partial<JoinFamilyForm>) => void;
  joining: boolean;
  onJoin: () => void;
};

const LEGACY_RECOVERY_MESSAGE = '检测到旧版创建恢复记录，因无法确认所属服务，系统不会自动发送。';

export function GenderSegments({ value, onChange }: { value: BabyGender | ''; onChange: (gender: BabyGender) => void }) {
  return <div className="gender-segments" role="radiogroup" aria-label="宝宝性别">
    <button role="radio" aria-checked={value === 'BOY'} className={value === 'BOY' ? 'active' : ''} onClick={() => onChange('BOY')}>男孩</button>
    <button role="radio" aria-checked={value === 'GIRL'} className={value === 'GIRL' ? 'active' : ''} onClick={() => onChange('GIRL')}>女孩</button>
  </div>;
}

export function BirthWeightInput({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return <div className="weight-input">
    <input type="number" required inputMode="decimal" min="0.10" max="15.00" step="0.01" value={value} placeholder="例如：3.25" onChange={e => onChange(e.target.value)}/>
    <b>kg</b>
  </div>;
}

function RecoveryWarning({ message, actionLabel, onDiscard }: { message: string; actionLabel: string; onDiscard: () => void }) {
  return <div className="recovery-warning"><span>{message}</span><button onClick={onDiscard}>{actionLabel}</button></div>;
}

/** The create button stays disabled until every required profile field is valid. */
export function createFormIncomplete(form: CreateFamilyForm) {
  return !form.familyName.trim() || !form.nickname.trim() || !form.babyNickname.trim()
    || !form.birthDate || daysOld(form.birthDate) === null
    || !form.gender || birthWeightGrams(form.birthWeightKg) === null;
}

function CreateFamilyPanel(p: FamilySheetProps) {
  const f = p.createForm;
  return <div className="family-onboarding-panel">
    <div className="family-onboarding-scroll">
      <div className="join-form">
        <label><span>家庭名称</span><input required maxLength={64} value={f.familyName} placeholder="例如：小满的家" onChange={e => p.onCreateForm({ familyName: e.target.value })}/></label>
        <label><span>你的称呼</span><input required maxLength={64} value={f.nickname} placeholder="例如：妈妈" onChange={e => p.onCreateForm({ nickname: e.target.value })}/></label>
        <label><span>宝宝称呼</span><input required maxLength={64} value={f.babyNickname} placeholder="请输入宝宝称呼" onChange={e => p.onCreateForm({ babyNickname: e.target.value })}/></label>
        <label><span>出生年月日</span><input type="date" required max={dateKey(Date.now())} value={f.birthDate} onChange={e => p.onCreateForm({ birthDate: e.target.value })}/></label>
        <div className="form-group"><span>性别</span><GenderSegments value={f.gender} onChange={gender => p.onCreateForm({ gender })}/></div>
        <label><span>出生体重</span><BirthWeightInput value={f.birthWeightKg} onChange={birthWeightKg => p.onCreateForm({ birthWeightKg })}/></label>
      </div>
      {p.recovery.legacyDetected && <RecoveryWarning message={LEGACY_RECOVERY_MESSAGE} actionLabel="清除旧版记录后重新创建" onDiscard={p.recovery.onDiscardLegacy}/>}
      {p.recovery.invalidMessage && <RecoveryWarning message={p.recovery.invalidMessage} actionLabel="放弃失效恢复并重新创建" onDiscard={p.recovery.onDiscardInvalid}/>}
      <div className="privacy-note"><strong>创建后</strong><span>你会成为家庭管理员，可从家庭空间复制邀请码给家人。</span></div>
    </div>
    <div className="family-onboarding-action"><button className="primary" disabled={createFormIncomplete(f) || p.creating} onClick={p.onCreate}>{p.creating ? '正在创建…' : '创建宝宝家庭'}</button></div>
  </div>;
}

function JoinFamilyPanel(p: FamilySheetProps) {
  const f = p.joinForm;
  return <div className="family-onboarding-panel">
    <div className="family-onboarding-scroll">
      <div className="join-form">
        <label><span>家庭邀请码</span><input autoCapitalize="characters" value={f.code} placeholder="请输入邀请码" onChange={e => p.onJoinForm({ code: e.target.value.toUpperCase() })}/></label>
        <label><span>你的称呼</span><input value={f.nickname} placeholder="请输入你的称呼" onChange={e => p.onJoinForm({ nickname: e.target.value })}/></label>
      </div>
      <div className="privacy-note"><strong>只需一次</strong><span>绑定后使用长期 HttpOnly 设备凭证，日常打开不会再出现登录页。</span></div>
    </div>
    <div className="family-onboarding-action"><button className="primary" disabled={!f.code.trim() || !f.nickname.trim() || p.joining} onClick={p.onJoin}>{p.joining ? '正在绑定…' : '绑定这台设备'}</button></div>
  </div>;
}

function FamilySpace(p: FamilySheetProps) {
  const me = p.me!;
  const invite = p.invite;
  return <div className="sheet-stack family-sheet-scroll">
    <div className="family-connected">
      <div className="family-avatar">{me.nickname.slice(0, 1)}</div>
      <div><strong>{me.nickname}</strong><span>设备长期授权 · 无需重复登录</span></div>
      <i className={p.realtime ? 'online' : ''}></i>
    </div>
    {p.recovery.legacyDetected && <RecoveryWarning message={LEGACY_RECOVERY_MESSAGE} actionLabel="清除旧版恢复记录" onDiscard={p.recovery.onDiscardLegacy}/>}
    {p.recovery.invalidMessage && <RecoveryWarning message={p.recovery.invalidMessage} actionLabel="清除失效恢复记录" onDiscard={p.recovery.onDiscardInvalid}/>}
    <button className="settings-row" onClick={p.onOpenBabyProfile}>
      <span className="settings-icon"><Icon type="baby" size={19}/></span>
      <div><strong>宝宝资料</strong><small>{p.baby.nickname} · {p.baby.birthday || '出生年月日未设置'} · {genderLabel(p.baby.gender)} · {birthWeightLabel(p.baby.birthWeightGrams)}</small></div>
      <Icon type="chevron" size={17}/>
    </button>
    <button className="settings-row" onClick={p.onOpenDevices}>
      <span className="settings-icon"><Icon type="devices" size={19}/></span>
      <div><strong>家庭设备</strong><small>查看爸爸、妈妈等已授权设备</small></div>
      <Icon type="chevron" size={17}/>
    </button>
    <button className="settings-row" onClick={p.onInstallPwa}>
      <span className="settings-icon"><Icon type="plus" size={19}/></span>
      <div><strong>{p.installed ? '已添加到桌面' : '添加到手机桌面'}</strong><small>{p.installed ? '像普通 App 一样直接打开' : '减少打开步骤，带娃时更方便'}</small></div>
      <Icon type="chevron" size={17}/>
    </button>
    <div className="family-feature">
      <Icon type="cloud" size={19}/>
      <div><strong>{p.realtime ? '爸爸妈妈实时同步' : '已连接云端'}</strong><span>另一台设备产生记录后，首页会自动刷新</span></div>
    </div>
    <div className={`invite-card ${invite.error ? 'error' : ''}`}>
      <span>家庭邀请码</span>
      <strong>{invite.loading ? '正在获取…' : invite.error ? '加载失败' : invite.code || '暂无邀请码'}</strong>
      {invite.error ? <button onClick={invite.onRetry}>重试</button> : <button disabled={invite.loading || !invite.code} onClick={invite.onCopy}><Icon type="copy" size={16}/>复制给家人</button>}
    </div>
    <button className="text-danger" onClick={p.onLogout}>退出本设备</button>
  </div>;
}

export function FamilySheet(p: FamilySheetProps) {
  const title = p.me ? '家庭空间' : p.onboardingMode === 'create' ? '创建宝宝家庭' : '加入宝宝家庭';
  return <BottomSheet open={p.open} onClose={p.onClose} tall className="family-sheet">
    <SheetHeader eyebrow="家庭与设备" title={title} onClose={p.onClose}/>
    {p.me ? <FamilySpace {...p}/> : <div className="sheet-stack family-onboarding">
      <div className="onboarding-segments" role="tablist" aria-label="家庭设置方式">
        <button role="tab" aria-selected={p.onboardingMode === 'create'} className={p.onboardingMode === 'create' ? 'active' : ''} onClick={() => p.onOnboardingMode('create')}>创建宝宝家庭</button>
        <button role="tab" aria-selected={p.onboardingMode === 'join'} className={p.onboardingMode === 'join' ? 'active' : ''} onClick={() => p.onOnboardingMode('join')}>加入家庭</button>
      </div>
      {p.onboardingMode === 'create' ? <CreateFamilyPanel {...p}/> : <JoinFamilyPanel {...p}/>}
    </div>}
  </BottomSheet>;
}
