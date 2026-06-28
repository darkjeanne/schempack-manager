package schempack;

import arc.util.Log;

public class SchemSyncController {
    private final SchemPackConfig config;
    private final GitRepoManager gitRepoManager;
    private volatile boolean syncInProgress;
    private volatile boolean autoSyncQueued;
    private volatile boolean startupCheckQueued;

    public SchemSyncController(SchemPackConfig config, GitRepoManager gitRepoManager) {
        this.config = config;
        this.gitRepoManager = gitRepoManager;
    }

    public void startWatching() {
        requestStatusRefresh();
        requestImmediateAutoSync();
    }

    public void stopWatching() {
        gitRepoManager.setStatus("Auto push disabled");
    }

    public void requestSync() {
        runGitOperation("Sync in progress", () -> {
            if (!config.isAutoSyncEnabled()) {
                gitRepoManager.commitAndPush("Manual sync");
            } else if (config.isAutoPullEnabled()) {
                gitRepoManager.autoPullAndPush();
            } else {
                gitRepoManager.autoBackupLocal();
            }
        });
    }

    public void requestAutoBackup() {
        if (blockIfDisallowed(SchemSyncPolicy.Action.BACKUP_LOCAL)) return;
        runGitOperation("Auto backup in progress", gitRepoManager::autoBackupLocal);
    }

    public void requestCompare() {
        runGitOperation("Compare in progress", gitRepoManager::compareLocalAndRemote);
    }

    public void requestStatusRefresh() {
        runGitOperation("Comparing local and GitHub", gitRepoManager::compareLocalAndRemote, true, true);
    }

    public void requestStartupCheck() {
        runGitOperation("Checking local and GitHub", this::runStartupCheck, true, true);
    }

    public void requestPull() {
        if (blockIfDisallowed(SchemSyncPolicy.Action.PULL_GITHUB)) return;
        runGitOperation("Pull in progress", gitRepoManager::pullRemote);
    }

    public void requestPush() {
        if (blockIfDisallowed(SchemSyncPolicy.Action.PUSH_LOCAL)) return;
        runGitOperation("Push in progress", gitRepoManager::pushLocal);
    }

    public void requestForcePush() {
        if (blockIfDisallowed(SchemSyncPolicy.Action.FORCE_PUSH_LOCAL)) return;
        runGitOperation("Force push in progress", gitRepoManager::forcePushLocal);
    }

    public void requestImmediateAutoSync() {
        if (!config.isAutoSyncEnabled()) {
            gitRepoManager.setStatus("Auto push is off");
            return;
        }
        runGitOperation("Auto sync in progress", this::runAutoSync, true);
    }

    public boolean isBusy() {
        return syncInProgress;
    }

    private SchemSyncPolicy.Mode currentMode() {
        return SchemSyncPolicy.modeFor(config.isAutoSyncEnabled(), config.isAutoPullEnabled());
    }

    private boolean blockIfDisallowed(SchemSyncPolicy.Action action) {
        SchemSyncPolicy.Mode mode = currentMode();
        if (SchemSyncPolicy.isAllowed(mode, action)) return false;
        gitRepoManager.setStatus(SchemSyncPolicy.blockedReason(mode, action));
        return true;
    }

    private void runGitOperation(String status, Runnable operation) {
        runGitOperation(status, operation, false, false);
    }

    private void runGitOperation(String status, Runnable operation, boolean queueIfBusy) {
        runGitOperation(status, operation, queueIfBusy, false);
    }

    private void runGitOperation(String status, Runnable operation, boolean queueIfBusy, boolean queueStartupCheck) {
        if (syncInProgress) {
            if (queueStartupCheck) {
                startupCheckQueued = true;
                gitRepoManager.setStatus("Refresh queued");
            } else if (queueIfBusy) {
                autoSyncQueued = true;
                gitRepoManager.setStatus("Auto push queued");
            } else {
                gitRepoManager.setStatus("Sync already in progress");
            }
            return;
        }

        syncInProgress = true;
        gitRepoManager.setStatus(status);
        Thread worker = new Thread(() -> {
            try {
                operation.run();
            } catch (Exception e) {
                gitRepoManager.setStatus("Operation failed: " + e.getMessage());
                Log.err(e);
            } finally {
                syncInProgress = false;
                runQueuedOperation();
            }
        }, "SchemPack Git Sync");
        worker.setDaemon(true);
        worker.start();
    }

    private void runAutoSync() {
        if (!config.isAutoSyncEnabled()) {
            gitRepoManager.setStatus("Auto push disabled");
        } else if (config.isAutoPullEnabled()) {
            gitRepoManager.autoPullAndPush();
        } else {
            gitRepoManager.autoBackupLocal();
        }
    }

    private void runStartupCheck() {
        gitRepoManager.compareLocalAndRemote();
    }

    private void runQueuedOperation() {
        if (syncInProgress) return;
        if (startupCheckQueued) {
            startupCheckQueued = false;
            runGitOperation("Checking local and GitHub", this::runStartupCheck, true, true);
            return;
        }
        if (!autoSyncQueued) return;
        autoSyncQueued = false;
        if (config.isAutoSyncEnabled()) {
            runGitOperation("Auto sync in progress", this::runAutoSync, true);
        }
    }
}
