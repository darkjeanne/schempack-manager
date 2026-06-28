# SchemPack Manager Mod Plan

## Goal
Create a Mindustry mod named SchemPack Manager that integrates with the schematic interface and provides GitHub-backed synchronization for a user's schematics folder.

## Core behavior
- Add a button in the schematics interface near the existing navigation controls.
- The button opens a UI for managing a GitHub-backed schematic repository.
- The mod watches the user's schematic folder for changes.
- Every change is recorded as a Git commit and pushed automatically when auto-sync is enabled.
- The mod supports syncing both directions:
  - local changes -> GitHub
  - GitHub changes -> local
- The mod should handle conflicts and edge cases gracefully.

## Requirements

### 1. Repository integration
- Allow the user to provide a GitHub repository URL or clone target.
- Support authentication/accessibility for GitHub repo operations.
- Use Git commands via Java process execution or a library.
- Handle cases where:
  - local files changed but GitHub has not been synced yet
  - GitHub changed but local repo is outdated
  - files were deleted locally or remotely
  - conflicting or partial updates happen

### 2. Local storage strategy
- Decide whether to use:
  - a local Git repository inside the schematics folder, or
  - a separate repository directory that mirrors the schematic folder
- Prefer a separate managed repo directory unless there is a strong reason to place Git metadata inside the game's schematic folder.
- The mod should keep schematics accessible to the user in the normal folder.

### 3. Change tracking
- Detect and log changes such as:
  - add schematic
  - modify schematic
  - delete schematic
  - rename schematic
- Each detected change should produce a commit message such as:
  - `Add schem1.msch`
  - `Update schem1.msch`
  - `Remove schem1.msch`

### 4. Filter feature
- Allow users to filter a schematic from repository sync.
- Example behavior:
  - `filter schem1` -> schematic stays in the local schematics folder but is ignored by GitHub sync
  - `unfilter schem1` -> the schematic becomes part of sync again
- These filters should be stored in a local config file.

### 5. Sync controls
- Provide controls to:
  - enable or disable auto-sync
  - manually trigger a sync
  - sync from local to GitHub
  - sync from GitHub to local

### 6. Failure and external-change handling
- If the GitHub repository becomes unavailable, the mod should not crash or corrupt local files.
- The mod should detect:
  - network problems
  - authentication failures
  - missing permissions
  - repository deletion or invalid remote configuration
- If the user edits, adds, removes, or renames schematics outside the game, the mod should detect those changes and sync them appropriately.
- This should work whether the game is open or closed.
- When the game is open, the mod should follow normal in-game behavior by avoiding destructive writes while files are actively in use and by retrying on the next safe pass.
- If the user or another process commits or pushes directly to the Git repo outside the mod, the mod should detect remote changes on the next sync.
- The mod should reconcile local and remote changes conservatively:
  - pull remote changes when safe
  - preserve local changes when possible
  - create a conflict report or backup if a true conflict occurs
  - avoid silently overwriting user work
- The system should keep a small status log so the user can see whether the repo is offline, syncing, conflicted, or healthy.

## Implementation steps

### Phase 1: Project setup
- [ ] Rename the example mod package and metadata to match the new mod.
- [ ] Create a basic UI entry point in the schematics screen.
- [ ] Add configuration storage for repo path, GitHub repo info, auto-sync toggle, and filters.

### Phase 2: Git integration
- [ ] Detect whether a Git repository exists for the target folder.
- [ ] Initialize a repository if needed.
- [ ] Add a remote GitHub repository.
- [ ] Configure Git user identity for commits.
- [ ] Implement commit and push operations.

### Phase 3: File monitoring
- [ ] Watch the schematic folder for create/update/delete events.
- [ ] Queue changes and process them in a controlled way.
- [ ] Avoid spamming commits for every tiny event; batch changes when practical.

### Phase 4: Sync logic
- [ ] Implement local -> GitHub sync.
- [ ] Implement GitHub -> local sync.
- [ ] Handle merge conflicts and divergence safely.
- [ ] Use fetch/pull/rebase or reset strategy carefully.
- [ ] Detect and recover from GitHub access failures and remote repository changes.
- [ ] Detect external schematic edits made outside the game and queue them for sync.
- [ ] Handle direct Git commits/pushes from outside the mod without losing local state.

### Phase 5: UI and controls
- [ ] Add UI buttons for:
  - open manager
  - enable/disable auto-sync
  - sync now
  - pull from remote
  - push to remote
- [ ] Add a list of filtered schematics.
- [ ] Add a way to add/remove filters.

### Phase 6: Reliability and polish
- [ ] Show status messages in-game.
- [ ] Log errors for failed Git operations.
- [ ] Add warning dialogs for destructive sync actions.
- [ ] Make the feature compatible with other UI mods where possible.

## Suggested architecture
- Use a `SchemPackManager` controller class for high-level behavior.
- Use a `GitRepoManager` class for Git commands and repository state.
- Use a `SchemSyncConfig` class for persistent settings.
- Use a `SchemSyncDialog` or `SchemPackDialog` for in-game UI.
- Use a `FileWatcher` or polling approach to detect schematic changes.

## Risks and considerations
- GitHub authentication is the biggest challenge.
- A full automatic sync system can cause accidental destructive behavior.
- The mod should be conservative and confirm destructive actions.
- The Git implementation must be robust to local/remote differences.

## Recommended initial milestone
Build the first version with:
- a button in the schematics UI
- a basic config screen
- a local Git repository integration
- manual push/pull controls
- auto-sync toggle

This milestone is enough to validate the feature without trying to solve every edge case at once.
