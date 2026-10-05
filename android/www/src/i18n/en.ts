export const en = {
  // App tab bar
  tab_terminal: '🖥 Terminal',
  tab_dashboard: '📊 Dashboard',
  tab_settings: '⚙ Settings',

  // Setup - steps
  step_platform: 'Platform',
  step_tools: 'Tools',
  step_setup: 'Setup',

  // Setup - platform select
  setup_choose_platform: 'Choose your platform',
  setup_more_platforms: 'More platforms available in Settings.',

  // Setup - tool select
  setup_optional_tools: 'Optional Tools',
  setup_tools_desc: 'Select tools to install alongside {platform}. You can always add more later in Settings.',
  setup_start: 'Start Setup',

  // Setup - installing
  setup_setting_up: 'Setting up...',
  setup_preparing: 'Preparing setup...',

  // Setup - done
  setup_done_title: "You're all set!",
  setup_done_desc: 'The terminal will now install runtime components and your selected tools. This takes 3–10 minutes.',
  setup_open_terminal: 'Open Terminal',

  // Setup - tips
  tip_1: 'You can install multiple AI platforms and switch between them anytime.',
  tip_2: 'Setup is a one-time process. Future launches are instant.',
  tip_3: 'Once setup is complete, your AI assistant runs at full speed — just like on a computer.',
  tip_4: 'All processing happens locally on your device. Your data never leaves your phone.',

  // Setup - tool descriptions
  tool_tmux: 'Terminal multiplexer for background sessions',
  tool_ttyd: 'Web terminal — access from a browser',
  tool_dufs: 'File server (WebDAV)',
  tool_code_server: 'VS Code in browser',
  tool_claude_code: 'Anthropic AI CLI',
  tool_gemini_cli: 'Google AI CLI',
  tool_codex_cli: 'OpenAI AI CLI',

  // Dashboard
  dash_setup_required: 'Setup Required',
  dash_setup_desc: "The runtime environment hasn't been set up yet.",
  dash_commands: 'Commands',
  dash_runtime: 'Runtime',
  dash_management: 'Management',

  // Dashboard - commands
  cmd_gateway: 'Start the gateway',
  cmd_status: 'Show gateway status',
  cmd_onboard: 'Initial setup wizard',
  cmd_logs: 'Follow live logs',
  cmd_update: 'Update OpenClaw and all components',
  cmd_install_tools: 'Add or remove optional tools',

  // Settings
  settings_title: 'Settings',
  settings_platforms: 'Platforms',
  settings_platforms_desc: 'Manage installed platforms',
  settings_updates: 'Updates',
  settings_updates_desc: 'Check for updates',
  settings_keep_alive: 'Keep Alive',
  settings_keep_alive_desc: 'Prevent background killing',
  settings_storage: 'Storage',
  settings_storage_desc: 'Manage disk usage',
  settings_about: 'About',
  settings_about_desc: 'App info & licenses',

  // Settings - Keep Alive
  ka_title: 'Keep Alive',
  ka_desc: 'Android may kill background processes after a while. Follow these steps to prevent it.',
  ka_battery: '1. Battery Optimization',
  ka_status: 'Status',
  ka_excluded: '✓ Excluded',
  ka_request: 'Request Exclusion',
  ka_developer: '2. Developer Options',
  ka_developer_desc: '• Enable Developer Options\n• Enable "Stay Awake"',
  ka_open_dev: 'Open Developer Options',
  ka_phantom: '3. Phantom Process Killer (Android 12+)',
  ka_phantom_desc: 'Connect USB and enable ADB debugging, then run this command on your PC:',
  ka_copy: 'Copy',
  ka_copied: 'Copied!',
  ka_charge: '4. Charge Limit (Optional)',
  ka_charge_desc: 'Set battery charge limit to 80% for always-on use. This can be configured in your phone\'s battery settings.',

  // Settings - Storage
  storage_title: 'Storage',
  storage_total: 'Total used: ',
  storage_bootstrap: 'Bootstrap (usr/)',
  storage_www: 'Web UI (www/)',
  storage_free: 'Free Space',
  storage_clear: 'Clear Cache',
  storage_clearing: 'Clearing...',
  storage_loading: 'Loading storage info...',

  // Settings - About
  about_title: 'About',
  about_version: 'Version',
  about_apk: 'APK',
  about_update_available: 'Update available',
  about_package: 'Package',
  about_script: 'Script',
  about_runtime: 'Runtime',
  about_license: 'License',
  about_app_info: 'App Info',
  about_made_for: 'Made for Android',

  // Settings - Updates
  updates_title: 'Updates',
  updates_checking: 'Checking for updates...',
  updates_up_to_date: 'No separate updates. The app itself is updated by new releases — see Settings → About.',
  updates_updating: 'Updating {name}...',
  updates_update: 'Update',

  // Settings - Platforms
  platforms_title: 'Platforms',
  platforms_installing: 'Installing {name}...',
  platforms_active: 'Active',
  platforms_install: 'Install & Switch',

  // Setup - failure
  setup_failed_title: 'Setup could not finish',
  setup_err_network: 'Could not reach the download servers. Check your connection and try again.',
  setup_err_missing: 'The setup file is no longer available at its download address. Update the app and try again.',
  setup_err_hash: 'The downloaded file did not match its expected checksum (it may have been tampered with or replaced). Setup was stopped.',
  setup_err_local: 'The download could not be saved (storage full or unexpectedly large). Free some space and try again.',
  setup_err_unknown: 'Something went wrong during setup. Try again.',
  setup_retry: 'Try again',

  // Unexpected UI error
  error_boundary_title: 'Something went wrong',
  error_boundary_reload: 'Reload',

  // Tools the app cannot install yet
  tool_terminal_only: "This tool can't be installed from the app yet.",

  // Tools screen (restored)
  settings_tools: "Additional Tools",
  settings_tools_desc: "Install extra tools",
  tools_title: "Additional Tools",
  tools_cat_terminal: "Terminal Tools",
  tools_cat_ai: "AI Tools",
  tools_cat_network: "Network & Access",
  tools_cat_system: "System",
  tool_desc_opencode: "AI coding assistant (TUI)",
  tool_desc_openssh: "SSH remote access",
  tool_desc_android_tools: "ADB for disabling Phantom Process Killer",
  tool_desc_chromium: "Browser automation (~400MB)",
  tool_desc_playwright: "Browser automation library (playwright-core)",
  tool_install: "Install",
  tool_reinstall: "Reinstall",
  tool_installed_broken: "Installed, but it does not run. If you reinstalled it from the terminal with npm, reinstall it here.",
  tool_broken_terminal: "Installed, but it does not run. This tool cannot be installed from the app: reinstall it in the terminal with `oa --install`.",
  tool_terminal_install_hint: "Not installable from the app yet — install it in the terminal with `oa --install`.",
  tool_experimental: "Experimental — the install may fail on this device.",
  tool_last_output: "Last output",
  tool_installed: "Installed ✓",
  tool_not_available: "Not available yet",
  tool_installing: "Installing {name}...",
  tool_cancel: "Cancel",
  tool_cancel_requested: "Cancel requested — it stops when the current step finishes",
  tool_long_running: "This is taking a long time. Some tools need several minutes.",
  tool_cancelled: "The installation was cancelled.",
  tool_uninstall_unsupported: "Removing tools from the app is not supported yet.",
  tool_err_setup_incomplete: "Setup has not finished yet. Finish the initial setup first.",
  tool_err_script_outdated: "This device's installer is too old to install tools from the app, and the app cannot update it right now. Try again after the next app update, or use the terminal.",
  tool_err_not_run: "The installation did not run. Check the connection and try again.",
  tool_err_busy: "Another installation is still running. Try again in a moment.",
  tool_err_interrupted: "The installation was interrupted. If it was force-stopped, wait a moment before trying again.",
  tool_err_index_network: "Could not download the package list. Check the connection and try again.",
  tool_err_index_verify: "Installation stopped: the package list could not be verified.",
  tool_err_install_failed: "The installation failed.",
  tool_err_verify_failed: "The tool was installed but does not run.",
  tool_err_file_mismatch: "The installer reported success but the tool is missing.",
  tool_err_env: "The installer could not create its working folder.",
  tool_err_lock: "The installer could not take its lock. Try again in a moment.",
  tool_err_unknown: "The installation did not finish. Try again.",
}
