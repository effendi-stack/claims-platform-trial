import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { filter } from 'rxjs';
import { Api as ClaimsApi } from '../../api/claims/api';
import { get } from '../../api/claims/fn/claims/get';
import { assign } from '../../api/claims/fn/claims/assign';
import { requestInformation } from '../../api/claims/fn/claims/request-information';
import { respond } from '../../api/claims/fn/claims/respond';
import { setReserve } from '../../api/claims/fn/claims/set-reserve';
import { approve } from '../../api/claims/fn/claims/approve';
import { reject } from '../../api/claims/fn/claims/reject';
import { settle } from '../../api/claims/fn/claims/settle';
import { withdraw } from '../../api/claims/fn/claims/withdraw';
import { handlers as listHandlers } from '../../api/claims/fn/reference-data/handlers';
import { ClaimDetail, HandlerView, Reject } from '../../api/claims/models';
import { AuthService } from '../../core/auth.service';
import { LiveUpdatesService } from '../../core/live-updates.service';
import { date, dateTime, errorMessage, label, money } from '../../core/format';
import { StatusBadge } from './status-badge';

type Action = NonNullable<ClaimDetail['allowedActions']>[number];

/** Booleans as Yes/No, enum codes (ESCAPE_OF_WATER) as labels, free text (SGX1234A) untouched. */
function displayValue(v: unknown): string {
  if (typeof v === 'boolean') {
    return v ? 'Yes' : 'No';
  }
  const s = String(v);
  return /^[A-Z]+(_[A-Z]+)+$|^[A-Z]{4,}$/.test(s) && !/\d/.test(s) ? label(s) : s;
}

const REJECTION_REASONS: Reject['reasonCode'][] = [
  'NOT_COVERED', 'POLICY_EXCLUSION', 'INSUFFICIENT_EVIDENCE', 'BELOW_DEDUCTIBLE', 'DUPLICATE', 'FRAUD_SUSPECTED', 'OTHER',
];

/**
 * One claim, for claimants and staff. The screen doesn't decide what the user may do: it renders
 * the actions listed in allowedActions from the API, so UI rules can't drift from server rules.
 */
@Component({
  selector: 'app-claim-detail',
  imports: [FormsModule, RouterLink, StatusBadge],
  template: `
    <a class="back" [routerLink]="isClaimant() ? '/my-claims' : '/queue'">← Back</a>
    @if (claim(); as c) {
      <div class="page-head">
        <h1>{{ c.summary?.claimNumber }}</h1>
        <app-status [status]="c.summary?.status" [claimantView]="isClaimant()" />
        @if (c.summary?.slaBreached) { <span class="badge breach">SLA breached</span> }
        @if (c.summary?.priority === 'HIGH') { <span class="badge high">High priority</span> }
      </div>

      @if (flash()) { <p class="flash">{{ flash() }}</p> }
      @if (error()) { <p class="error">{{ error() }}</p> }

      <div class="detail-grid">
        <section class="card">
          <h2>Claim</h2>
          <dl>
            <dt>Policy</dt><dd>{{ c.policyNumber }}</dd>
            <dt>Line / market</dt><dd>{{ label(c.summary?.productLine) }} · {{ c.summary?.marketCode }}</dd>
            <dt>Incident date</dt><dd>{{ date(c.incidentDate) }}</dd>
            <dt>Reported</dt><dd>{{ dateTime(c.summary?.reportedAt) }}</dd>
            <dt>Claimed</dt><dd>{{ money(c.summary?.claimedAmount, c.summary?.currency) }}</dd>
            @if (!isClaimant()) {
              <dt>Claimant</dt><dd>{{ c.summary?.claimantName }}</dd>
              <dt>Handler</dt><dd>{{ c.summary?.assignedHandlerName ?? 'Unassigned' }}</dd>
              <dt>Current reserve</dt><dd><strong>{{ money(c.summary?.currentReserve, c.summary?.currency) }}</strong></dd>
              <dt>Decision due</dt><dd [class.breach-text]="c.summary?.slaBreached">{{ dateTime(c.summary?.slaDueAt) }}</dd>
            }
          </dl>
          <p class="description">{{ c.description }}</p>
          <dl class="details">
            @for (d of detailEntries(); track d[0]) {
              <dt>{{ fieldLabel(d[0]) }}</dt><dd>{{ d[1] }}</dd>
            }
          </dl>
        </section>

        <section class="card">
          <h2>{{ isClaimant() ? 'What you can do' : 'Actions' }}</h2>
          @if (actions().length === 0) { <p class="muted">No actions available for this claim right now.</p> }

          @if (can('PICK_UP')) {
            <button class="btn primary" (click)="run('Claim picked up', assign, { id: id() })">Pick up this claim</button>
          }
          @if (can('ASSIGN')) {
            <form class="inline" (ngSubmit)="run('Claim assigned', assign, { id: id(), body: { handlerId: form.handlerId } })">
              <select name="handler" [(ngModel)]="form.handlerId" required>
                <option value="" disabled>Assign to…</option>
                @for (h of handlers(); track h.id) { <option [value]="h.id">{{ h.name }}</option> }
              </select>
              <button class="btn" type="submit" [disabled]="!form.handlerId">Assign</button>
            </form>
          }
          @if (can('REQUEST_INFO')) {
            <details>
              <summary>Request information from the claimant</summary>
              <form (ngSubmit)="run('Request sent to claimant', requestInformation, { id: id(), body: { question: form.question } })">
                <textarea name="q" [(ngModel)]="form.question" rows="3" placeholder="What do you need?" required></textarea>
                <button class="btn" type="submit" [disabled]="!form.question">Send request</button>
              </form>
            </details>
          }
          @if (can('SET_RESERVE')) {
            <details>
              <summary>Revise reserve</summary>
              <form (ngSubmit)="run('Reserve updated', setReserve, { id: id(), body: { amount: form.reserve!, reason: form.reserveReason } })">
                <input type="number" name="reserve" [(ngModel)]="form.reserve" min="0" step="0.01" placeholder="Amount" required />
                <input name="rr" [(ngModel)]="form.reserveReason" placeholder="Reason" required />
                <button class="btn" type="submit" [disabled]="form.reserve == null || !form.reserveReason">Save reserve</button>
              </form>
            </details>
          }
          @if (can('APPROVE')) {
            <details>
              <summary>Approve</summary>
              <form (ngSubmit)="run('Claim approved', approve, { id: id(), body: { amount: form.approveAmount!, notes: form.notes || undefined } })">
                <input type="number" name="aa" [(ngModel)]="form.approveAmount" min="0.01" step="0.01" placeholder="Approved amount" required />
                <input name="an" [(ngModel)]="form.notes" placeholder="Notes (optional)" />
                <button class="btn primary" type="submit" [disabled]="!form.approveAmount">Approve</button>
              </form>
            </details>
          }
          @if (can('REJECT')) {
            <details>
              <summary>Reject</summary>
              <form (ngSubmit)="run('Claim rejected', reject, { id: id(), body: { reasonCode: form.rejectReason, notes: form.notes || undefined } })">
                <select name="rc" [(ngModel)]="form.rejectReason">
                  @for (r of reasons; track r) { <option [value]="r">{{ label(r) }}</option> }
                </select>
                <input name="rn" [(ngModel)]="form.notes" placeholder="Notes (shown to the claimant)" />
                <button class="btn danger" type="submit">Reject</button>
              </form>
            </details>
          }
          @if (can('SETTLE')) {
            <form class="inline" (ngSubmit)="run('Settlement recorded', settle, { id: id(), body: { paymentReference: form.paymentRef } })">
              <input name="pr" [(ngModel)]="form.paymentRef" placeholder="Payment reference" required />
              <button class="btn primary" type="submit" [disabled]="!form.paymentRef">
                Record payment of {{ money(c.decision?.approvedAmount, c.summary?.currency) }}
              </button>
            </form>
          }
          @if (can('RESPOND_TO_INFO')) {
            @for (r of openRequests(); track r.id) {
              <div class="callout">
                <strong>We asked:</strong> {{ r.question }}
                <form (ngSubmit)="run('Thank you, your response has been sent', respond, { id: id(), requestId: r.id!, body: { responseText: form.response } })">
                  <textarea name="resp" [(ngModel)]="form.response" rows="3" placeholder="Your answer" required></textarea>
                  <button class="btn primary" type="submit" [disabled]="!form.response">Send response</button>
                </form>
              </div>
            }
          }
          @if (can('WITHDRAW')) {
            <button class="btn link danger" (click)="confirmWithdraw()">Withdraw this claim</button>
          }

          @if (c.decision; as d) {
            <h3>Decision</h3>
            <p>
              <strong>{{ d.outcome === 'APPROVED' ? 'Approved' : 'Not approved' }}</strong>
              @if (d.approvedAmount) { for {{ money(d.approvedAmount, c.summary?.currency) }} }
              @if (d.outcome === 'REJECTED') { — {{ label(d.reasonCode) }} }
              <br /><small class="muted">{{ dateTime(d.decidedAt) }}</small>
            </p>
            @if (d.notes) { <p class="muted">{{ d.notes }}</p> }
          }
          @for (p of c.payments ?? []; track p.reference) {
            <p>Paid {{ money(p.amount, p.currency) }} on {{ date(p.paidAt) }} (ref {{ p.reference }})</p>
          }
        </section>

        <section class="card">
          <h2>Timeline</h2>
          <ol class="timeline">
            @for (t of c.timeline ?? []; track $index) {
              <li>
                <app-status [status]="t.toStatus" [claimantView]="isClaimant()" />
                <small class="muted">{{ dateTime(t.occurredAt) }} · {{ t.actorName }}</small>
                @if (t.reason) { <div>{{ t.reason }}</div> }
              </li>
            }
          </ol>
          @if ((c.informationRequests ?? []).length) {
            <h3>Information requests</h3>
            @for (r of c.informationRequests ?? []; track r.id) {
              <div class="ir">
                <div><strong>Q:</strong> {{ r.question }} <span class="badge">{{ label(r.status) }}</span></div>
                @if (r.responseText) { <div><strong>A:</strong> {{ r.responseText }}</div> }
              </div>
            }
          }
        </section>

        @if (c.reserveHistory; as rh) {
          <section class="card">
            <h2>Reserve history</h2>
            <table>
              <thead><tr><th>When</th><th>Amount</th><th>Reason</th><th>By</th></tr></thead>
              <tbody>
                @for (r of rh; track $index) {
                  <tr><td>{{ dateTime(r.setAt) }}</td><td>{{ money(r.amount, r.currency) }}</td><td>{{ r.reason }}</td><td>{{ r.setByName }}</td></tr>
                }
              </tbody>
            </table>
          </section>
        }
      </div>
    } @else if (error()) {
      <p class="error">{{ error() }}</p>
    } @else {
      <p class="muted">Loading…</p>
    }
  `,
})
export class ClaimDetailPage {
  /** Bound from the :id route parameter. */
  readonly id = input.required<string>();

  private readonly api = inject(ClaimsApi);
  private readonly auth = inject(AuthService);
  private readonly live = inject(LiveUpdatesService);

  protected readonly claim = signal<ClaimDetail | null>(null);
  protected readonly handlers = signal<HandlerView[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly flash = signal<string | null>(null);

  protected readonly isClaimant = computed(() => this.auth.role() === 'CLAIMANT');
  protected readonly actions = computed(() => this.claim()?.allowedActions ?? []);
  protected readonly openRequests = computed(() =>
    (this.claim()?.informationRequests ?? []).filter((r) => r.status === 'OPEN'));
  protected readonly detailEntries = computed(() =>
    Object.entries(this.claim()?.details ?? {})
      .filter(([, v]) => v !== null && v !== '')
      .map(([k, v]) => [k, displayValue(v)] as [string, string]));

  protected form = this.emptyForm();
  protected readonly reasons = REJECTION_REASONS;

  // exposed to the template
  protected readonly money = money;
  protected readonly date = date;
  protected readonly dateTime = dateTime;
  protected readonly label = label;
  protected readonly assign = assign;
  protected readonly requestInformation = requestInformation;
  protected readonly respond = respond;
  protected readonly setReserve = setReserve;
  protected readonly approve = approve;
  protected readonly reject = reject;
  protected readonly settle = settle;

  constructor() {
    effect(() => {
      this.load(this.id());
    });
    // Someone else changed this claim (e.g. the claimant answered): refresh.
    this.live.changes
      .pipe(filter((e) => e.claimId === this.id()), takeUntilDestroyed(inject(DestroyRef)))
      .subscribe(() => this.load(this.id()));
  }

  protected can(action: Action): boolean {
    return this.actions().includes(action);
  }

  /** Runs a command; every command returns the claim's new state, which replaces the screen's copy. */
  protected async run<P>(done: string, fn: (...args: any[]) => any, params: P) {
    this.error.set(null);
    this.flash.set(null);
    try {
      const updated = (await this.api.invoke(fn as any, params)) as ClaimDetail;
      this.claim.set(updated);
      this.form = this.emptyForm();
      this.flash.set(done);
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  protected confirmWithdraw() {
    if (confirm('Withdraw this claim? This cannot be undone.')) {
      this.run('Your claim has been withdrawn', withdraw, { id: this.id(), body: {} });
    }
  }

  protected fieldLabel(key: string): string {
    return label(key.replace(/([A-Z])/g, '_$1'));
  }

  private async load(id: string) {
    try {
      const c = await this.api.invoke(get, { id });
      this.claim.set(c);
      this.error.set(null);
      if (c.allowedActions?.includes('ASSIGN') && c.summary?.teamId && this.handlers().length === 0) {
        this.handlers.set(await this.api.invoke(listHandlers, { teamId: c.summary.teamId }));
      }
    } catch (e) {
      this.error.set(errorMessage(e));
    }
  }

  private emptyForm() {
    return {
      handlerId: '',
      question: '',
      reserve: null as number | null,
      reserveReason: '',
      approveAmount: null as number | null,
      notes: '',
      rejectReason: 'NOT_COVERED' as Reject['reasonCode'],
      paymentRef: '',
      response: '',
    };
  }
}
