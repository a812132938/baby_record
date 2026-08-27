type ChoiceRowProps = {
  title: string;
  values: string[];
  value: string;
  onChange: (v: string) => void;
};

export function ChoiceRow({ title, values, value, onChange }: ChoiceRowProps) {
  return <div className="choice-block">
    <span>{title}</span>
    <div className="choice-row">{values.map(v => <button key={v} className={value === v ? 'active' : ''} onClick={() => onChange(v)}>{v}</button>)}</div>
  </div>;
}

export const POOP_COLORS = ['黄色', '黄绿色', '绿色', '棕色'];
export const POOP_TEXTURES = ['奶瓣', '糊状', '稀', '水样'];
export const POOP_AMOUNTS = ['少', '中', '多'];
