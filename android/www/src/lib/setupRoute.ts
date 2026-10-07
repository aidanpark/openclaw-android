/**
 * Which screen the app opens on, from native's install state. Pure: no bridge, no React — the
 * truth table is pinned by tests.
 */

// getSetupStatus() (BootstrapManager.SetupStatus). platformInstalled is the marker
// `.post-setup-done`: the whole first install (bootstrap and post-setup) finished.
export interface SetupStatus {
  bootstrapInstalled?: boolean
  platformInstalled?: boolean
}

// getSetupState(): only `running` (a bootstrap install is going on) is read here
export interface BootstrapState {
  running?: boolean
}

// main: the dashboard and the other tabs. setup: the first-install screen (platform selection, or a
// bootstrap that is running). resume: the bootstrap is there but the post-setup never finished —
// the page offers to continue it instead of starting the first install over.
export type SetupRoute = 'main' | 'setup' | 'resume'

export function routeFor(status: SetupStatus | null, setupState: BootstrapState | null): SetupRoute {
  // Bridge not available (dev mode): assume setup done
  if (!status) return 'main'
  // An install is running (the page was recreated mid-install): show it, whatever the files say
  if (setupState?.running) return 'setup'
  if (status.bootstrapInstalled && status.platformInstalled) return 'main'
  // The marker decides only once the bootstrap is there: without it the first install starts over
  if (status.bootstrapInstalled) return 'resume'
  return 'setup'
}
