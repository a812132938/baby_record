import { BottomSheet, SheetHeader } from '../../components/BottomSheet';
import { Icon } from '../../components/Icon';
import type { FamilyDevice, Me } from '../../domain/model';

type DevicesSheetProps = {
  open: boolean;
  onClose: () => void;
  devices: FamilyDevice[];
  loading: boolean;
  me: Me | null;
  onRevoke: (device: FamilyDevice) => void;
};

function lastActive(value: string) {
  return new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export function DevicesSheet(p: DevicesSheetProps) {
  const canRevoke = (device: FamilyDevice) => !device.revoked && device.id !== p.me?.deviceId && p.me?.role === 'ADMIN';
  return <BottomSheet open={p.open} onClose={p.onClose} tall>
    <SheetHeader eyebrow="家庭设备" title="谁可以记录宝宝" onClose={p.onClose}/>
    {p.loading ? <div className="loading-card">正在读取设备…</div> : <div className="device-list">
      {p.devices.map(device => <div className={`device-card ${device.revoked ? 'revoked' : ''}`} key={device.id}>
        <div className="device-mark"><Icon type="devices" size={18}/></div>
        <div className="device-copy">
          <strong>{device.nickname}{device.id === p.me?.deviceId ? ' · 当前设备' : ''}</strong>
          <span>{device.deviceName}</span>
          <small>{device.revoked ? '已移除' : `最近活动 ${lastActive(device.lastActiveAt)}`}</small>
        </div>
        {canRevoke(device) && <button className="device-remove" onClick={() => p.onRevoke(device)}>移除</button>}
      </div>)}
      {!p.devices.length && <div className="loading-card">暂无设备数据</div>}
    </div>}
    <p className="sheet-note">移除后，该设备下次请求会失去授权；当前设备请使用“退出本设备”。</p>
  </BottomSheet>;
}
