import { Injectable, computed, inject, signal } from '@angular/core';
import { Api as ClaimsApi } from '../api/claims/api';
import { login } from '../api/claims/fn/auth-dev-only/login';
import { UserView } from '../api/claims/models';

export type Role = 'CLAIMANT' | 'HANDLER' | 'MANAGER';

export interface Session {
  token: string;
  user: UserView;
}

const KEY = 'claims.session';

/**
 * Holds the signed-in user. The session lives in sessionStorage, which is per browser tab, so
 * a demo can run the claimant in one tab and a handler in another.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(ClaimsApi);

  readonly session = signal<Session | null>(restore());
  readonly user = computed(() => this.session()?.user ?? null);
  readonly role = computed(() => (this.user()?.role ?? null) as Role | null);
  readonly token = computed(() => this.session()?.token ?? null);

  async login(email: string): Promise<void> {
    const res = await this.api.invoke(login, { body: { email } });
    const session: Session = { token: res.token!, user: res.user! };
    this.session.set(session);
    persist(session);
  }

  logout(): void {
    this.session.set(null);
    persist(null);
  }

  /** Where each role lands after signing in. */
  home(): string {
    switch (this.role()) {
      case 'CLAIMANT': return '/my-claims';
      case 'HANDLER': return '/queue';
      case 'MANAGER': return '/dashboard';
      default: return '/login';
    }
  }
}

function restore(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

function persist(session: Session | null): void {
  try {
    if (session) {
      sessionStorage.setItem(KEY, JSON.stringify(session));
    } else {
      sessionStorage.removeItem(KEY);
    }
  } catch {
    // storage unavailable (private mode etc.): session just won't survive a reload
  }
}
