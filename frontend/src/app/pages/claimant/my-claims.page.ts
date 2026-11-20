import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Api as ClaimsApi } from '../../api/claims/api';
import { list } from '../../api/claims/fn/claims/list';
import { ClaimSummary } from '../../api/claims/models';
import { LiveUpdatesService } from '../../core/live-updates.service';
import { date, errorMessage, label, money } from '../../core/format';
import { StatusBadge } from '../shared/status-badge';

@Component({
  selector: 'app-my-claims',
  imports: [RouterLink, StatusBadge],
  template: `
    <div class="page-head">
      <h1>My claims</h1>
      <a class="btn primary" routerLink="/my-claims/new">Report a claim</a>
    </div>
    @if (error()) { <p class="error">{{ error() }}</p> }
    @if (claims().length === 0 && !loading()) {
      <div class="card empty">You have no claims yet.</div>
    }
    <div class="claim-cards">
      @for (c of claims(); track c.id) {
        <a class="card claim-card" [routerLink]="['/claims', c.id]">
          <div class="row">
            <strong>{{ c.claimNumber }}</strong>
            <app-status [status]="c.status" [claimantView]="true" />
          </div>
          <div class="muted">{{ label(c.productLine) }} · reported {{ date(c.reportedAt) }}</div>
          <div>Claimed {{ money(c.claimedAmount, c.currency) }}</div>
          @if (c.status === 'AWAITING_INFO') {
            <div class="callout">We need more information from you. Open the claim to respond.</div>
          }
        </a>
      }
    </div>
  `,
})
export class MyClaimsPage implements OnInit {
  private readonly api = inject(ClaimsApi);
  private readonly live = inject(LiveUpdatesService);

  protected readonly claims = signal<ClaimSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly money = money;
  protected readonly date = date;
  protected readonly label = label;

  constructor() {
    this.live.changes.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe(() => this.load());
  }

  ngOnInit() {
    this.load();
  }

  private async load() {
    try {
      const page = await this.api.invoke(list, { size: 50 });
      this.claims.set(page.content ?? []);
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.loading.set(false);
    }
  }
}
