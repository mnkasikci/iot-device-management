import { Routes } from '@angular/router';
import { DeviceDetail } from './devices/device-detail';
import { DeviceList } from './devices/device-list';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'devices' },
  { path: 'devices', component: DeviceList, title: 'Devices' },
  { path: 'devices/:id', component: DeviceDetail, title: 'Device' },
  { path: '**', redirectTo: 'devices' },
];
