import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api as ClaimsApi } from '../../api/claims/api';
import { myPolicies } from '../../api/claims/fn/reference-data/my-policies';
import { submit } from '../../api/claims/fn/claims/submit';
import { PolicyView, SubmitClaim } from '../../api/claims/models';
import { errorMessage, label, money } from '../../core/format';

const PERILS = ['FIRE', 'FLOOD', 'STORM', 'THEFT', 'ESCAPE_OF_WATER', 'ACCIDENTAL_DAMAGE', 'OTHER'] as const;

/** First notice of loss. The form switches between motor and property details by policy line. */
@Component({
  selector: 'app-new-claim',
  imports: [FormsModule],
  template: `
    <h1>Report a claim</h1>
    <form class="card form" (ngSubmit)="submitClaim()" #f="ngForm">
      <label>Policy
        <select name="policy" [(ngModel)]="policyId" required>
          <option value="" disabled>Select a policy</option>
          @for (p of policies(); track p.id) {
            <option [value]="p.id">{{ p.policyNumber }} — {{ label(p.productLine) }} ({{ p.marketCode }}, cover {{ money(p.sumInsured, p.currency) }})</option>
          }
        </select>
      </label>

      @if (policy(); as p) {
        <div class="grid2">
          <label>Date of incident
            <input type="date" name="incidentDate" [(ngModel)]="incidentDate" [max]="today" required />
          </label>
          <label>Amount claimed ({{ p.currency }})
            <input type="number" name="amount" [(ngModel)]="claimedAmount" min="0" step="0.01" required />
          </label>
        </div>
        <label>What happened?
          <textarea name="description" [(ngModel)]="description" rows="4" maxlength="4000" required></textarea>
        </label>

        @if (p.productLine === 'MOTOR') {
          <fieldset>
            <legend>Vehicle incident</legend>
            <div class="grid2">
              <label>Vehicle registration <input name="reg" [(ngModel)]="motor.vehicleRegistration" required /></label>
              <label>Location <input name="location" [(ngModel)]="motor.location" required /></label>
              <label>Police report number (optional) <input name="police" [(ngModel)]="motor.policeReportNumber" /></label>
            </div>
            <label class="check"><input type="checkbox" name="tp" [(ngModel)]="motor.thirdPartyInvolved" /> Another party was involved</label>
            <label class="check"><input type="checkbox" name="inj" [(ngModel)]="motor.injuries" /> Someone was injured</label>
          </fieldset>
        } @else {
          <fieldset>
            <legend>Property damage</legend>
            <label>Property address <input name="address" [(ngModel)]="property.propertyAddress" required /></label>
            <label>Cause
              <select name="peril" [(ngModel)]="property.peril" required>
                @for (x of perils; track x) { <option [value]="x">{{ label(x) }}</option> }
              </select>
            </label>
            <label class="check"><input type="checkbox" name="hab" [(ngModel)]="property.habitable" /> The property is still habitable</label>
          </fieldset>
        }
      }

      @if (error()) { <p class="error">{{ error() }}</p> }
      <div class="actions">
        <button class="btn primary" type="submit" [disabled]="!f.valid || busy()">Submit claim</button>
      </div>
    </form>
  `,
})
export class NewClaimPage implements OnInit {
  private readonly api = inject(ClaimsApi);
  private readonly router = inject(Router);

  protected readonly policies = signal<PolicyView[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly perils = PERILS;
  protected readonly money = money;
  protected readonly label = label;
  protected readonly today = new Date().toISOString().slice(0, 10);

  private readonly selectedPolicyId = signal('');
  get policyId() { return this.selectedPolicyId(); }
  set policyId(v: string) { this.selectedPolicyId.set(v); }
  protected readonly policy = computed(() => this.policies().find((p) => p.id === this.selectedPolicyId()));

  protected incidentDate = this.today;
  protected claimedAmount: number | null = null;
  protected description = '';
  protected motor = { vehicleRegistration: '', location: '', policeReportNumber: '', thirdPartyInvolved: false, injuries: false };
  protected property: { propertyAddress: string; peril: (typeof PERILS)[number]; habitable: boolean } =
    { propertyAddress: '', peril: 'STORM', habitable: true };

  /** One key per form instance: a double-click or a retry after a network blip can't create two claims. */
  private readonly idempotencyKey = crypto.randomUUID();

  async ngOnInit() {
    this.policies.set(await this.api.invoke(myPolicies));
  }

  protected async submitClaim() {
    const p = this.policy();
    if (!p) {
      return;
    }
    const body: SubmitClaim = {
      policyId: p.id!,
      incidentDate: this.incidentDate,
      description: this.description,
      claimedAmount: Number(this.claimedAmount),
      ...(p.productLine === 'MOTOR'
        ? { motor: { ...this.motor, policeReportNumber: this.motor.policeReportNumber || undefined } }
        : { property: this.property }),
    };
    this.busy.set(true);
    this.error.set(null);
    try {
      const claim = await this.api.invoke(submit, { body, 'Idempotency-Key': this.idempotencyKey });
      await this.router.navigate(['/claims', claim.summary?.id]);
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.busy.set(false);
    }
  }
}
