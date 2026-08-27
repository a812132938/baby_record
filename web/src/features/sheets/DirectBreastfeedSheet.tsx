import { BottomSheet, SheetHeader } from '../../components/BottomSheet';
import { Icon } from '../../components/Icon';
import { clockDuration } from '../../domain/format';
import type { BreastSide } from '../../domain/feeding';

type DirectBreastfeedSheetProps = {
  open: boolean;
  onClose: () => void;
  running: boolean;
  activeSide: BreastSide | null;
  leftSeconds: number;
  rightSeconds: number;
  onStartSide: (side: BreastSide) => void;
  onPause: () => void;
  onResume: () => void;
  onFinish: () => void;
  onDiscard: () => void;
};

export function DirectBreastfeedSheet(p: DirectBreastfeedSheetProps) {
  const sideButton = (side: BreastSide, label: string, seconds: number) =>
    <button className={p.activeSide === side ? 'active' : ''} onClick={() => p.onStartSide(side)}>
      <small>{label}</small>
      <strong>{clockDuration(seconds)}</strong>
      <span>{p.activeSide === side ? '计时中' : '点击开始 / 切换'}</span>
    </button>;

  return <BottomSheet open={p.open} onClose={p.onClose} tall>
    <SheetHeader eyebrow="母乳亲喂" title={p.running ? '正在记录亲喂' : '选择一侧开始'} onClose={p.onClose}/>
    <div className="breast-timer" aria-live="polite">
      {sideButton('LEFT', '左侧', p.leftSeconds)}
      {sideButton('RIGHT', '右侧', p.rightSeconds)}
    </div>
    {p.running && <div className="breast-total"><span>本次累计</span><strong>{clockDuration(p.leftSeconds + p.rightSeconds)}</strong></div>}
    <div className="breast-controls">
      {p.activeSide
        ? <button onClick={p.onPause}><Icon type="clock" size={18}/>暂停</button>
        : p.running ? <button onClick={p.onResume}><Icon type="plus" size={18}/>继续</button> : null}
      <button className="finish" disabled={!p.running} onClick={p.onFinish}><Icon type="check" size={18}/>结束并记录</button>
    </div>
    {p.running && <button className="timer-discard" onClick={p.onDiscard}>放弃本次计时</button>}
  </BottomSheet>;
}
