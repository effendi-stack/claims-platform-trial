import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';
import { ApiConfiguration as ClaimsApiConfiguration } from './api/claims/api-configuration';
import { ApiConfiguration as InsightsApiConfiguration } from './api/insights/api-configuration';

/**
 * The generated clients default to http://localhost:808x. Both are pointed at the app's own
 * origin instead: the dev proxy (proxy.conf.json), or an API gateway in production, routes
 * /api/insights/** to insights-service and everything else to claims-service.
 */
function sameOrigin<T extends { rootUrl: string }>(type: new () => T) {
  const config = new type();
  config.rootUrl = '';
  return { provide: type, useValue: config };
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([authInterceptor])),
    sameOrigin(ClaimsApiConfiguration),
    sameOrigin(InsightsApiConfiguration),
  ],
};
