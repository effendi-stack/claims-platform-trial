import { HttpErrorResponse } from '@angular/common/http';

/** "SGD 3,700.00". Amounts are always shown with their own currency, never converted. */
export function money(amount: number | null | undefined, currency: string | null | undefined): string {
  if (amount == null || !currency) {
    return '—';
  }
  return `${currency} ${new Intl.NumberFormat('en', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount)}`;
}

export function date(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' }) : '—';
}

export function dateTime(value: string | null | undefined): string {
  return value
    ? new Date(value).toLocaleString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' })
    : '—';
}

/** Human labels for API enum values, e.g. AWAITING_INFO -> "Awaiting info". */
export function label(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const s = value.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Problem+json "detail" from the backend, or a generic message. */
export function errorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const body = err.error as { detail?: string; errors?: { field: string; message: string }[] } | null;
    if (body?.errors?.length) {
      return body.errors.map((e) => `${e.field}: ${e.message}`).join('; ');
    }
    return body?.detail ?? `Request failed (${err.status})`;
  }
  return 'Something went wrong';
}
