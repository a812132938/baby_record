import React from 'react';
import { Icon } from './Icon';

type BottomSheetProps = {
  open: boolean;
  onClose: () => void;
  children: React.ReactNode;
  tall?: boolean;
  className?: string;
};

export function BottomSheet({ open, onClose, children, tall = false, className = '' }: BottomSheetProps) {
  if (!open) return null;
  return <div className="overlay" onMouseDown={onClose}>
    <section className={`sheet ${tall ? 'sheet-tall' : ''} ${className}`.trim()} onMouseDown={(e) => e.stopPropagation()}>
      <div className="grabber" />
      {children}
    </section>
  </div>;
}

/** The eyebrow + title + close button every sheet repeats. */
export function SheetHeader({ eyebrow, title, onClose }: { eyebrow: string; title: React.ReactNode; onClose: () => void }) {
  return <div className="sheet-title">
    <div><small>{eyebrow}</small><h2>{title}</h2></div>
    <button type="button" aria-label="关闭" onClick={onClose}><Icon type="close" size={20}/></button>
  </div>;
}
