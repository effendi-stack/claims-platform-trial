import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Api as ClaimsApi } from '../api/claims/api';
import { demoUsers } from '../api/claims/fn/auth-dev-only/demo-users';
import { UserView } from '../api/claims/models';
import { AuthService } from '../core/auth.service';
import { errorMessage } from '../core/format';

/** Dev-only sign-in: pick a seeded user. Production would redirect to the corporate IdP. */
@Component({
  selector: 'app-login',
  template: `
    <div class="login">
      <h1>Claims platform</h1>
      <p class="muted">Demo sign-in: choose a seeded user. Open another tab to act as a second user at the same time.</p>
      @if (error()) { <p class="error">{{ error() }}</p> }
      @for (group of groups(); track group.role) {
        <h3>{{ group.title }}</h3>
        <div class="user-grid">
          @for (u of group.users; track u.id) {
            <button class="card user" (click)="signIn(u)" [disabled]="busy()">
              <strong>{{ u.name }}</strong>
              <small>{{ u.email }}</small>
            </button>
          }
        </div>
      }
    </div>
  `,
})
export class LoginPage implements OnInit {
  private readonly api = inject(ClaimsApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  private readonly users = signal<UserView[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly groups = computed(() => [
    { role: 'CLAIMANT', title: 'Claimants', users: this.users().filter((u) => u.role === 'CLAIMANT') },
    { role: 'HANDLER', title: 'Claims handlers', users: this.users().filter((u) => u.role === 'HANDLER') },
    { role: 'MANAGER', title: 'Claims manager', users: this.users().filter((u) => u.role === 'MANAGER') },
  ]);

  async ngOnInit() {
    try {
      this.users.set(await this.api.invoke(demoUsers));
    } catch (e) {
      this.error.set('Cannot reach claims-service. Is it running on :8081? ' + errorMessage(e));
    }
  }

  protected async signIn(u: UserView) {
    this.busy.set(true);
    try {
      await this.auth.login(u.email!);
      await this.router.navigateByUrl(this.auth.home());
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.busy.set(false);
    }
  }
}
