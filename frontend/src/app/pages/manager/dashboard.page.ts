import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { debounceTime } from 'rxjs';
import { Api as InsightsApi } from '../../api/insights/api';
import { exposure } from '../../api/insights/fn/insights/exposure';
import { workload } from '../../api/insights/fn/insights/workload';
import { throughput } from '../../api/insights/fn/insights/throughput';
import { Exposure, Throughput, Workload } from '../../api/insights/models';
import { LiveUpdatesService } from '../../core/live-updates.service';
import { dateTime, errorMessage, label, money } from '../../core/format';

/** Regional manager: outstanding liability and team workload, all from insights-service. */
@Component({
  selector: 'app-dashboard',
  imports: [RouterLink],
  template: `
    <div class="page-head">
      <h1>Claims dashboard</h1>
      @if (exp(); as e) { <small class="muted">as of {{ dateTime(e.generatedAt) }}</small> }
    </div>
    @if (error()) { <p class="error">{{ error() }}</p> }

    @if (exp(); as e) {
      <div class="kpis">
        <div class="kpi"><span>{{ money(e.totalOutstandingUsd, 'USD') }}</span>outstanding liability (USD equiv.)</div>
        <div class="kpi"><span>{{ money(e.totalPaidUsd, 'USD') }}</span>paid to date (USD equiv.)</div>
        @if (wl(); as w) {
          <div class="kpi"><span>{{ w.openClaims }}</span>open claims</div>
          <div class="kpi" [class.bad]="(w.slaBreached ?? 0) > 0"><span>{{ w.slaBreached }}</span>past decision SLA</div>
        }
      </div>

      <section class="card">
        <h2>Outstanding exposure by market and line</h2>
        <table>
          <thead>
            <tr><th>Market</th><th>Line</th><th class="num" title="Open or approved-but-unpaid">Outstanding</th><th class="num">Outstanding reserve</th>
              <th class="num">Paid</th><th class="num">Incurred</th><th class="num">Outstanding (USD)</th></tr>
          </thead>
          <tbody>
            @for (s of e.segments ?? []; track $index) {
              <tr>
                <td>{{ s.marketCode }}</td><td>{{ label(s.productLine) }}</td><td class="num">{{ s.openClaims }}</td>
                <td class="num">{{ money(s.outstandingReserve, s.currency) }}</td>
                <td class="num">{{ money(s.paid, s.currency) }}</td>
                <td class="num">{{ money(s.incurred, s.currency) }}</td>
                <td class="num">{{ money(s.outstandingUsd, 'USD') }}</td>
              </tr>
            } @empty {
              <tr><td colspan="7" class="muted">No claims yet.</td></tr>
            }
          </tbody>
        </table>
        <p class="muted small">{{ e.fxNote }}</p>
      </section>

      <div class="detail-grid">
        <section class="card">
          <h2>Largest open reserves</h2>
          <table>
            <tbody>
              @for (c of e.largestOpenReserves ?? []; track c.claimId) {
                <tr [routerLink]="['/claims', c.claimId]" class="clickable">
                  <td>{{ c.claimNumber }}</td><td>{{ label(c.status) }}</td>
                  <td class="num">{{ money(c.currentReserve, c.currency) }}</td>
                  <td class="num muted">{{ money(c.reserveUsd, 'USD') }}</td>
                </tr>
              }
            </tbody>
          </table>
        </section>

        <section class="card">
          <h2>Handler workload</h2>
          <table>
            <thead><tr><th>Handler</th><th class="num">Open</th><th class="num">Awaiting info</th>
              <th class="num">SLA breached</th><th class="num">Decided (30d)</th><th class="num">Avg days to decide</th></tr></thead>
            <tbody>
              @for (h of wl()?.handlers ?? []; track h.handlerId) {
                <tr>
                  <td>{{ h.handlerName }}</td><td class="num">{{ h.open }}</td><td class="num">{{ h.awaitingInfo }}</td>
                  <td class="num" [class.breach-text]="(h.slaBreached ?? 0) > 0">{{ h.slaBreached }}</td>
                  <td class="num">{{ decided(h.handlerId) }}</td><td class="num">{{ avgDays(h.handlerId) }}</td>
                </tr>
              } @empty {
                <tr><td colspan="6" class="muted">No claims are assigned yet.</td></tr>
              }
            </tbody>
          </table>
          @if (wl(); as w) { <p class="muted small">{{ w.unassigned }} open claim(s) not yet picked up.</p> }
        </section>
      </div>
    }
  `,
})
export class DashboardPage implements OnInit {
  private readonly api = inject(InsightsApi);
  private readonly live = inject(LiveUpdatesService);

  protected readonly exp = signal<Exposure | null>(null);
  protected readonly wl = signal<Workload | null>(null);
  protected readonly tp = signal<Throughput | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly money = money;
  protected readonly dateTime = dateTime;
  protected readonly label = label;

  constructor() {
    // The projection updates just after the event, so wait briefly before re-reading it.
    this.live.changes.pipe(debounceTime(1000), takeUntilDestroyed(inject(DestroyRef))).subscribe(() => this.refresh());
  }

  ngOnInit() {
    this.refresh();
  }

  protected decided(handlerId?: string) {
    const h = this.tp()?.handlers?.find((x) => x.handlerId === handlerId);
    return h ? (h.approved ?? 0) + (h.rejected ?? 0) : 0;
  }

  protected avgDays(handlerId?: string) {
    return this.tp()?.handlers?.find((x) => x.handlerId === handlerId)?.avgDaysToDecision ?? '—';
  }

  private async refresh() {
    try {
      const [e, w, t] = await Promise.all([
        this.api.invoke(exposure),
        this.api.invoke(workload, {}),
        this.api.invoke(throughput, {}),
      ]);
      this.exp.set(e);
      this.wl.set(w);
      this.tp.set(t);
      this.error.set(null);
    } catch (err) {
      this.error.set('Insights unavailable: ' + errorMessage(err));
    }
  }
}
