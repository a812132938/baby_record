import { BottomSheet, SheetHeader } from '../../components/BottomSheet';
import { Icon } from '../../components/Icon';
import { formatDateTime } from '../../domain/format';

type QuickRecordSheetProps = {
  open: boolean;
  onClose: () => void;
  draftAt: number;
  draftIsNow: boolean;
  timerRunning: boolean;
  sleeping: boolean;
  onEditTime: () => void;
  onDirectBreastfeed: () => void;
  onBottleBreastMilk: () => void;
  onFormulaFeed: () => void;
  onPumping: () => void;
  onToggleSleep: () => void;
  onPoop: () => void;
  onPee: () => void;
};

export function QuickRecordSheet(p: QuickRecordSheetProps) {
  return <BottomSheet open={p.open} onClose={p.onClose} tall>
    <SheetHeader eyebrow="快速记录" title="刚刚发生了什么？" onClose={p.onClose}/>
    <button className={`record-time-chip ${!p.draftIsNow ? 'changed' : ''}`} onClick={p.onEditTime}>
      <Icon type="clock" size={16}/><span>记录时间</span><strong>{p.draftIsNow ? '现在' : formatDateTime(p.draftAt)}</strong><Icon type="chevron" size={16}/>
    </button>
    <div className="quick-grid feeding-quick-grid">
      <button onClick={p.onDirectBreastfeed}><span><Icon type="milk" size={25}/></span><strong>母乳亲喂</strong><small>{p.timerRunning ? '计时进行中' : '左右侧计时'}</small></button>
      <button onClick={p.onBottleBreastMilk}><span><Icon type="milk" size={25}/></span><strong>母乳瓶喂</strong><small>实际喝下量</small></button>
      <button onClick={p.onFormulaFeed}><span><Icon type="milk" size={25}/></span><strong>配方奶</strong><small>实际喝下量</small></button>
      <button onClick={p.onPumping}><span><Icon type="milk" size={25}/></span><strong>泵奶</strong><small>左右侧产量</small></button>
      <button onClick={p.onToggleSleep}><span><Icon type="moon" size={25}/></span><strong>{p.sleeping ? '醒来' : '睡觉'}</strong><small>{p.sleeping ? '结束本次睡眠' : '开始计时'}</small></button>
      <button onClick={p.onPoop}><span><Icon type="poop" size={25}/></span><strong>便便</strong><small>颜色 · 性状 · 量</small></button>
      <button onClick={p.onPee}><span><Icon type="drop" size={25}/></span><strong>尿尿</strong><small>一键记录</small></button>
    </div>
  </BottomSheet>;
}
