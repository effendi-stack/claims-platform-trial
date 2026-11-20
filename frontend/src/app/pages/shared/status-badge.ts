import { Component, computed, input } from '@angular/core';
import { label } from '../../core/format';

/** Claimants see plain-language statuses; staff see the workflow names. */
const CLAIMANT_LABELS: Record<string, string> = {
  SUBMITTED: 'Received',
  IN_REVIEW: 'Being reviewed',
  AWAITING_INFO: 'Action needed',
  APPROVED: 'Approved',
  SETTLED: 'Paid',
  REJECTED: 'Not approved',
  WITHDRAWN: 'Withdrawn',
};

@Component({
  selector: 'app-status',
  template: `<span class="badge" [class]="'badge s-' + status()">{{ text() }}</span>`,
})
export class StatusBadge {
  readonly status = input.required<string | undefined>();
  readonly claimantView = input(false);
  protected readonly text = computed(() =>
    this.claimantView() ? (CLAIMANT_LABELS[this.status() ?? ''] ?? label(this.status())) : label(this.status()),
  );
}
