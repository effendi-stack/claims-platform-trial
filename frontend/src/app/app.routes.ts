import { inject } from '@angular/core';
import { Routes } from '@angular/router';
import { AuthService } from './core/auth.service';
import { roleGuard } from './core/role.guard';

/**
 * One app, two audiences: claimant screens and staff screens are separate lazy-loaded areas.
 * Claim detail is shared; what it shows and allows comes from the API (allowedActions).
 */
export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./pages/login.page').then((m) => m.LoginPage) },

  // claimant
  {
    path: 'my-claims',
    canActivate: [roleGuard('CLAIMANT')],
    loadComponent: () => import('./pages/claimant/my-claims.page').then((m) => m.MyClaimsPage),
  },
  {
    path: 'my-claims/new',
    canActivate: [roleGuard('CLAIMANT')],
    loadComponent: () => import('./pages/claimant/new-claim.page').then((m) => m.NewClaimPage),
  },

  // staff
  {
    path: 'queue',
    canActivate: [roleGuard('HANDLER', 'MANAGER')],
    loadComponent: () => import('./pages/staff/queue.page').then((m) => m.QueuePage),
  },
  {
    path: 'dashboard',
    canActivate: [roleGuard('MANAGER')],
    loadComponent: () => import('./pages/manager/dashboard.page').then((m) => m.DashboardPage),
  },

  // shared
  {
    path: 'claims/:id',
    canActivate: [roleGuard()],
    loadComponent: () => import('./pages/shared/claim-detail.page').then((m) => m.ClaimDetailPage),
  },

  { path: '**', redirectTo: () => inject(AuthService).home() },
];
