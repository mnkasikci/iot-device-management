import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Command, DeviceDetail, DeviceSummary, Page } from './models';

export interface DeviceFilter {
  online: boolean | null;
  type: string | null;
  search: string;
}

/** All REST calls of the portal. Live changes come from {@link LiveStream} instead. */
@Injectable({ providedIn: 'root' })
export class DeviceApi {
  private readonly http = inject(HttpClient);

  list(filter: DeviceFilter, page: number, size: number): Observable<Page<DeviceSummary>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (filter.online !== null) params = params.set('online', filter.online);
    if (filter.type) params = params.set('type', filter.type);
    if (filter.search.trim()) params = params.set('q', filter.search.trim());
    return this.http.get<Page<DeviceSummary>>('/api/devices', { params });
  }

  get(id: string): Observable<DeviceDetail> {
    return this.http.get<DeviceDetail>(`/api/devices/${encodeURIComponent(id)}`);
  }

  commands(id: string): Observable<Command[]> {
    return this.http.get<Command[]>(`/api/devices/${encodeURIComponent(id)}/commands`);
  }

  sendCommand(id: string, name: string, params: Record<string, unknown>): Observable<Command> {
    return this.http.post<Command>(`/api/devices/${encodeURIComponent(id)}/commands`, {
      name,
      params,
    });
  }
}
