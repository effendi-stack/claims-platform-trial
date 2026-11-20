import { Component, DestroyRef, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Api as ClaimsApi } from './api/claims/api';
import { mine } from './api/claims/fn/notifications-live-updates/mine';
import { markRead } from './api/claims/fn/notifications-live-updates/mark-read';
import { NotificationView } from './api/claims/models';
import { AuthService } from './core/auth.service';
import { LiveUpdatesService } from './core/live-updates.service';
import { dateTime } from './core/format';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    @if (auth.user(); as user) {
      <header class="topbar">
        <a class="brand" [routerLink]="auth.home()">Claims</a>
        <nav>
          @switch (user.role) {
            @case ('CLAIMANT') {
              <a routerLink="/my-claims" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">My claims</a>
              <a routerLink="/my-claims/new" routerLinkActive="active">Report a claim</a>
            }
            @case ('HANDLER') {
              <a routerLink="/queue" routerLinkActive="active">Queue</a>
            }
            @case ('MANAGER') {
              <a routerLink="/dashboard" routerLinkActive="active">Dashboard</a>
              <a routerLink="/queue" routerLinkActive="active">All claims</a>
            }
          }
        </nav>
        <div class="spacer"></div>
        <span class="live" [class.on]="live.connected()" [title]="live.connected() ? 'Live updates on' : 'Live updates off'">●</span>
        <div class="bell">
          <button class="link" (click)="toggleInbox()">
            Notifications @if (unread() > 0) { <span class="count">{{ unread() }}</span> }
          </button>
          @if (inboxOpen()) {
            <div class="inbox card">
              @for (n of notifications(); track n.id) {
                <a class="inbox-item" [class.unread]="!n.readAt" (click)="open(n)">
                  <div>{{ n.message }}</div>
                  <small>{{ dateTime(n.createdAt) }}</small>
                </a>
              } @empty {
                <p class="muted">No notifications yet.</p>
              }
            </div>
          }
        </div>
        <span class="who">{{ user.name }} <small>· {{ user.role?.toLowerCase() }}</small></span>
        <button class="link" (click)="logout()">Sign out</button>
      </header>
    }
    <main><router-outlet /></main>
  `,
})
export class App {
  protected readonly auth = inject(AuthService);
  protected readonly live = inject(LiveUpdatesService);
  private readonly api = inject(ClaimsApi);
  private readonly router = inject(Router);

  protected readonly notifications = signal<NotificationView[]>([]);
  protected readonly unread = signal(0);
  protected readonly inboxOpen = signal(false);
  protected readonly dateTime = dateTime;

  constructor() {
    // Open the live stream and load the inbox whenever someone signs in.
    effect(() => {
      const token = this.auth.token();
      if (token) {
        this.live.connect(token);
        this.loadNotifications();
      } else {
        this.live.disconnect();
        this.notifications.set([]);
        this.unread.set(0);
      }
    });
    // New events may have produced notifications for us.
    this.live.changes.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe(() => this.loadNotifications());
  }

  protected toggleInbox() {
    this.inboxOpen.update((v) => !v);
  }

  protected async open(n: NotificationView) {
    this.inboxOpen.set(false);
    if (!n.readAt) {
      await this.api.invoke(markRead, { id: n.id! });
      this.loadNotifications();
    }
    this.router.navigate(['/claims', n.claimId]);
  }

  protected logout() {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }

  private async loadNotifications() {
    // The consumer runs just after the event; a short delay avoids reading before it has written.
    await new Promise((r) => setTimeout(r, 300));
    if (!this.auth.token()) {
      return;
    }
    const list = await this.api.invoke(mine);
    this.notifications.set(list);
    this.unread.set(list.filter((n) => !n.readAt).length);
  }
}
