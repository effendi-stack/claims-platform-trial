import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { debounceTime } from 'rxjs';
import { Api as ClaimsApi } from '../../api/claims/api';
import { Api as InsightsApi } from '../../api/insights/api';
import { list, List$Params } from '../../api/claims/fn/claims/list';
import { workload } from '../../api/insights/fn/insights/workload';
import { ClaimSummary } from '../../api/claims/models';
import { Workload } from '../../api/insights/models';
import { AuthService } from '../../core/auth.service';
import { LiveUpdatesService } from '../../core/live-updates.service';
import { dateTime, errorMessage, label, money } from '../../core/format';
import { StatusBadge } from '../shared/status-badge';

type Tab = 'mine' | 'unassigned' | 'open' | 'closed';
const OPEN: List$Params['status'] = ['SUBMITTED', 'IN_REVIEW', 'AWAITING_INFO', 'APPROVED'];
const CLOSED: List$Params['status'] = ['SETTLED', 'REJECTED', 'WITHDRAWN'];

/**
 * A handler's working view. Live data comes from claims-service; the team summary strip comes
 * from insights-service and may lag by a moment.
 */
@Component({
  selector: 'app-queue',
  imports: [RouterLink, StatusBadge],
  template: `
    <div class="page-head"><h1>{{ isManager() ? 'All claims' : 'Claims queue' }}</h1></div>

    @if (load(); as w) {
      <div class="kpis">
        <div class="kpi"><span>{{ w.openClaims }}</span>open claims</div>
        <div class="kpi" [class.warn]="(w.unassigned ?? 0) > 0"><span>{{ w.unassigned }}</span>waiting to be picked up</div>
        <div class="kpi" [class.bad]="(w.slaBreached ?? 0) > 0"><span>{{ w.slaBreached }}</span>past decision SLA</div>
        <div class="kpi"><span>{{ w.ageing?.over30 }}</span>open over 30 days</div>
      </div>
    }

    <div class="tabs">
      @if (!isManager()) {
        <button [class.active]="tab() === 'mine'" (click)="select('mine')">My claims</button>
      }
      <button [class.active]="tab() === 'unassigned'" (click)="select('unassigned')">Unassigned</button>
      <button [class.active]="tab() === 'open'" (click)="select('open')">All open</button>
      <button [class.active]="tab() === 'closed'" (click)="select('closed')">Closed</button>
    </div>

    @if (error()) { <p class="error">{{ error() }}</p> }
    <table class="card">
      <thead>
        <tr>
          <th>Claim</th><th>Status</th><th>Line</th><th>Claimant</th><th>Handler</th>
          <th class="num">Claimed</th><th class="num">Reserve</th><th>Decision due</th>
        </tr>
      </thead>
      <tbody>
        @for (c of claims(); track c.id) {
          <tr [routerLink]="['/claims', c.id]" class="clickable" [class.breach-row]="c.slaBreached">
            <td>
              <strong>{{ c.claimNumber }}</strong>
              @if (c.priority === 'HIGH') { <span class="badge high">High</span> }
            </td>
            <td><app-status [status]="c.status" /></td>
            <td>{{ label(c.productLine) }} · {{ c.marketCode }}</td>
            <td>{{ c.claimantName }}</td>
            <td>{{ c.assignedHandlerName ?? '—' }}</td>
            <td class="num">{{ money(c.claimedAmount, c.currency) }}</td>
            <td class="num">{{ money(c.currentReserve, c.currency) }}</td>
            <td [class.breach-text]="c.slaBreached">{{ isOpen(c) ? dateTime(c.slaDueAt) : '—' }}</td>
          </tr>
        } @empty {
          <tr><td colspan="8" class="muted">Nothing here.</td></tr>
        }
      </tbody>
    </table>
  `,
})
export class QueuePage implements OnInit {
  private readonly claimsApi = inject(ClaimsApi);
  private readonly insightsApi = inject(InsightsApi);
  private readonly auth = inject(AuthService);
  private readonly live = inject(LiveUpdatesService);

  protected readonly isManager = computed(() => this.auth.role() === 'MANAGER');
  protected readonly tab = signal<Tab>(this.auth.role() === 'MANAGER' ? 'unassigned' : 'mine');
  protected readonly claims = signal<ClaimSummary[]>([]);
  protected readonly load = signal<Workload | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly money = money;
  protected readonly dateTime = dateTime;
  protected readonly label = label;

  constructor() {
    // A burst of events (e.g. approve + settle) triggers one refresh, not several.
    this.live.changes.pipe(debounceTime(400), takeUntilDestroyed(inject(DestroyRef))).subscribe(() => this.refresh());
  }

  ngOnInit() {
    this.refresh();
  }

  protected select(tab: Tab) {
    this.tab.set(tab);
    this.refresh();
  }

  protected isOpen(c: ClaimSummary) {
    return c.status === 'SUBMITTED' || c.status === 'IN_REVIEW' || c.status === 'AWAITING_INFO';
  }

  private async refresh() {
    const params: List$Params = { size: 50 };
    switch (this.tab()) {
      case 'mine': Object.assign(params, { assignedTo: 'me', status: OPEN }); break;
      case 'unassigned': Object.assign(params, { assignedTo: 'unassigned', status: ['SUBMITTED'] }); break;
      case 'open': params.status = OPEN; break;
      case 'closed': Object.assign(params, { status: CLOSED, sort: ['updatedAt,desc'] }); break;
    }
    try {
      const page = await this.claimsApi.invoke(list, params);
      this.claims.set(page.content ?? []);
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e));
    }
    try {
      // handlers get their own team; the manager gets all teams
      this.load.set(await this.insightsApi.invoke(workload, {}));
    } catch {
      this.load.set(null); // insights is optional for this screen: the queue works without it
    }
  }
}
