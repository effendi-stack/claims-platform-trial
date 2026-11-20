import { Injectable, signal } from '@angular/core';
import { Subject } from 'rxjs';

export interface ClaimChanged {
  claimId: string;
  claimNumber: string;
  eventType: string;
  status: string;
}

/**
 * One Server-Sent Events stream per signed-in tab. A message is only a hint that a claim
 * changed: screens react by refetching over REST, which stays the source of truth.
 * EventSource reconnects by itself if the connection drops.
 */
@Injectable({ providedIn: 'root' })
export class LiveUpdatesService {
  private source?: EventSource;

  readonly changes = new Subject<ClaimChanged>();
  readonly connected = signal(false);

  connect(token: string): void {
    this.disconnect();
    // EventSource can't send headers, so the backend accepts the token as a query parameter on this endpoint only.
    const es = new EventSource(`/api/events/stream?access_token=${encodeURIComponent(token)}`);
    es.addEventListener('connected', () => this.connected.set(true));
    es.addEventListener('claim-changed', (e) => this.changes.next(JSON.parse((e as MessageEvent).data)));
    es.onerror = () => this.connected.set(false);
    this.source = es;
  }

  disconnect(): void {
    this.source?.close();
    this.source = undefined;
    this.connected.set(false);
  }
}
