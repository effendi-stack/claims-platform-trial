import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService, Role } from './auth.service';

/**
 * UX only: keeps users out of screens they can't use. The real authorisation is enforced by
 * the backend on every request.
 */
export function roleGuard(...roles: Role[]): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    const role = auth.role();
    if (!role) {
      return router.parseUrl('/login');
    }
    return roles.length === 0 || roles.includes(role) ? true : router.parseUrl(auth.home());
  };
}
