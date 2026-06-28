package schempack;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import mindustry.Vars;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class GitRepoManager {
    private final SchemPackConfig config;
    private final java.util.List<String> statusLog = new ArrayList<>();
    private String statusText = "Not configured";
    private String statusReportText = "Checking repository...";
    private String comparisonText = "Checking local and GitHub...";
    private boolean synced = false;

    public GitRepoManager(SchemPackConfig config) {
        this.config = config;
        setStatus("Ready to configure");
        refreshFromConfig();
    }

    public void refreshFromConfig() {
        if (config.getRepoPath() == null || config.getRepoPath().trim().isEmpty()) {
            setStatus("No local repo path configured");
            return;
        }

        Fi repoDir = new Fi(config.getRepoPath());
        if (!repoDir.exists()) {
            try {
                repoDir.mkdirs();
                setStatus("Created local repository directory");
            } catch (Exception e) {
                setStatus("Repository path missing");
                Log.err(e);
                return;
            }
        }

        if (!isGitRepo(repoDir)) {
            try {
                runGit(repoDir, "init");
                runGit(repoDir, "branch", "-M", "main");
                setStatus("Initialized local Git repository");
            } catch (Exception e) {
                setStatus("Failed to initialize repository");
                Log.err(e);
            }
        } else {
            setStatus("Repository ready");
        }

        if (!config.getRepoUrl().trim().isEmpty()) {
            ensureRemote(repoDir);
        }
        ensureGitIdentity(repoDir);
        ensureAuthentication(repoDir);
    }

    public void commitAndPush(String message) {
        if (config.getRepoPath() == null || config.getRepoPath().trim().isEmpty()) {
            setStatus("No local repo path configured");
            return;
        }

        Fi repoDir = new Fi(config.getRepoPath());
        if (!repoDir.exists()) {
            setStatus("Repository path missing");
            return;
        }

        if (!config.getRepoUrl().trim().isEmpty()) {
            ensureRemote(repoDir);
        }
        ensureGitIdentity(repoDir);
        ensureAuthentication(repoDir);

        try {
            if (!config.getRepoUrl().trim().isEmpty()) {
                runGit(repoDir, "fetch", "origin");
                String remoteRef = "origin/" + resolveRemoteBranch(repoDir);
                if (remoteHasCommitsNotLocal(repoDir, remoteRef)) {
                    setStatus("Sync blocked: GitHub has changes. Compare, then pull GitHub before pushing.");
                    return;
                }
            }

            if (!hasWorktreeChanges(repoDir)) {
                setStatus("No schematic changes to sync");
                return;
            }

            String effectiveMessage = buildCommitMessage(repoDir, message);
            if (!commitChanges(repoDir, effectiveMessage)) {
                setStatus("No schematic changes to sync");
                return;
            }

            if (!config.getRepoUrl().trim().isEmpty()) {
                try {
                    pushCurrentBranch(repoDir, true);
                    compareLocalAndRemote();
                    setStatus("Sync completed");
                } catch (Exception e) {
                    setStatus("Push failed: " + describeGitFailure(e.getMessage()));
                    Log.err(e);
                }
            } else {
                setStatus("Sync completed locally");
            }
        } catch (Exception e) {
            setStatus("Sync failed: " + e.getMessage());
            Log.err(e);
        }
    }

    public void autoBackupLocal() {
        if (config.getRepoPath() == null || config.getRepoPath().trim().isEmpty()) {
            setStatus("No local repo path configured");
            return;
        }

        Fi repoDir = new Fi(config.getRepoPath());
        if (!repoDir.exists()) {
            setStatus("Repository path missing");
            return;
        }

        if (config.getRepoUrl().trim().isEmpty()) {
            setStatus("No GitHub remote configured");
            return;
        }

        ensureRemote(repoDir);
        ensureGitIdentity(repoDir);
        ensureAuthentication(repoDir);

        try {
            runGit(repoDir, "fetch", "origin");
            String remoteRef = "origin/" + resolveRemoteBranch(repoDir);
            boolean hasLocalChanges = hasWorktreeChanges(repoDir);
            if (remoteHasCommitsNotLocal(repoDir, remoteRef)) {
                config.setAutoSyncEnabled(false);
                config.setAutoPullEnabled(false);
                config.save();
                compareLocalAndRemote();
                if (hasLocalChanges) {
                    setStatus("Auto Push OFF: GitHub has changes and local schematics are waiting. Turn Auto Pull on, use Pull & Merge, or Force Push Local.");
                } else {
                    setStatus("Auto Push OFF: GitHub has changes. Local files preserved.");
                }
                return;
            }

            if (hasCommit(repoDir) && localHasCommitsNotRemote(repoDir, remoteRef)) {
                pushCurrentBranch(repoDir, true);
                compareLocalAndRemote();
                setStatus("Auto pushed pending local commits");
                return;
            }

            if (!hasLocalChanges) {
                setStatus("Auto push idle: no local schematic changes");
                return;
            }

            String effectiveMessage = buildCommitMessage(repoDir, "Auto backup local schematics");
            if (!commitChanges(repoDir, effectiveMessage)) {
                setStatus("Auto push idle: no local schematic changes");
                return;
            }

            pushCurrentBranch(repoDir, true);
            compareLocalAndRemote();
            setStatus("Auto pushed local schematics");
        } catch (Exception e) {
            setStatus("Auto push failed: " + describeGitFailure(e.getMessage()));
            Log.err(e);
        }
    }

    public void autoPullAndPush() {
        if (config.getRepoPath() == null || config.getRepoPath().trim().isEmpty()) {
            setStatus("No local repo path configured");
            return;
        }

        Fi repoDir = new Fi(config.getRepoPath());
        if (!repoDir.exists()) {
            setStatus("Repository path missing");
            return;
        }

        if (config.getRepoUrl().trim().isEmpty()) {
            setStatus("No GitHub remote configured");
            return;
        }

        ensureRemote(repoDir);
        ensureGitIdentity(repoDir);
        ensureAuthentication(repoDir);

        try {
            runGit(repoDir, "fetch", "origin");
            String remoteBranch = resolveRemoteBranch(repoDir);
            String remoteRef = "origin/" + remoteBranch;
            boolean hadLocalChanges = hasWorktreeChanges(repoDir);
            boolean hadRemoteChanges = remoteHasCommitsNotLocal(repoDir, remoteRef);
            boolean committedLocal = false;

            if (hadLocalChanges) {
                committedLocal = commitChanges(repoDir, "Auto backup local schematics");
            }

            if (hadRemoteChanges) {
                try {
                    if (!hasCommit(repoDir)) {
                        runGit(repoDir, "pull", "--ff-only", "origin", remoteBranch);
                    } else {
                        runGit(repoDir, "merge", "--no-edit", remoteRef);
                    }
                    reloadSchematicsSoon();
                } catch (Exception mergeError) {
                    abortMerge(repoDir);
                    config.setAutoSyncEnabled(false);
                    config.setAutoPullEnabled(false);
                    config.save();
                    setStatus("Auto push/pull turned off: local and GitHub conflict. Local files preserved.");
                    setComparisonText("Auto pull failed because local and GitHub changed the same schematic.\n"
                            + "Auto push and auto pull were turned off.\n"
                            + "Compare, then resolve manually.");
                    Log.err(mergeError);
                    return;
                }
            }

            if (hasCommit(repoDir) && (committedLocal || localHasCommitsNotRemote(repoDir, remoteRef))) {
                pushCurrentBranch(repoDir, true);
                compareLocalAndRemote();
                setStatus(hadRemoteChanges ? "Auto pulled GitHub and pushed local merge" : "Auto pushed local schematics");
                return;
            }

            if (hadRemoteChanges) {
                compareLocalAndRemote();
                setStatus("Auto pulled GitHub changes");
            } else {
                setStatus("Auto push/pull idle: no local or GitHub changes");
            }
        } catch (Exception e) {
            setStatus("Auto push/pull failed: " + describeGitFailure(e.getMessage()));
            Log.err(e);
        }
    }

    public void pullRemote() {
        if (config.getRepoPath() == null || config.getRepoPath().trim().isEmpty()) {
            setStatus("No local repo path configured");
            return;
        }

        Fi repoDir = new Fi(config.getRepoPath());
        if (!repoDir.exists()) {
            setStatus("Repository path missing");
            return;
        }

        ensureGitIdentity(repoDir);
        ensureAuthentication(repoDir);

        try {
            if (!config.getRepoUrl().trim().isEmpty()) {
                ensureRemote(repoDir);
            } else {
                setStatus("No GitHub remote configured");
                return;
            }

            runGit(repoDir, "fetch", "origin");
            String remoteBranch = resolveRemoteBranch(repoDir);
            String remoteRef = "origin/" + remoteBranch;
            boolean committedLocal = false;

            if (hasWorktreeChanges(repoDir)) {
                committedLocal = commitChanges(repoDir, "Save local schematics before pulling GitHub");
            }

            if (!hasCommit(repoDir)) {
                runGit(repoDir, "pull", "--ff-only", "origin", remoteBranch);
                reloadSchematicsSoon();
                compareLocalAndRemote();
                setStatus("Pulled remote changes");
                return;
            }

            if (!remoteHasCommitsNotLocal(repoDir, remoteRef)) {
                if (committedLocal || localHasCommitsNotRemote(repoDir, remoteRef)) {
                    pushCurrentBranch(repoDir, true);
                    compareLocalAndRemote();
                    setStatus("Pushed local changes");
                } else {
                    setStatus("Already up to date with GitHub");
                }
                return;
            }

            try {
                runGit(repoDir, "merge", "--no-edit", remoteRef);
                reloadSchematicsSoon();
            } catch (Exception mergeError) {
                abortMerge(repoDir);
                setStatus("Pull blocked: local and GitHub changed the same schematic. Resolve manually or Force Push Local.");
                setComparisonText("Merge conflict while pulling GitHub.\n"
                        + "Local and GitHub changed the same schematic file.\n"
                        + "No merge was kept; your local files were preserved.");
                Log.err(mergeError);
                return;
            }

            if (committedLocal || localHasCommitsNotRemote(repoDir, remoteRef)) {
                pushCurrentBranch(repoDir, true);
                compareLocalAndRemote();
                setStatus("Pulled GitHub and pushed local merge");
            } else {
                compareLocalAndRemote();
                setStatus("Pulled remote changes");
            }
        } catch (Exception e) {
            setStatus("Pull failed: " + describeGitFailure(e.getMessage()));
            Log.err(e);
        }
    }

    public void compareLocalAndRemote() {
        if (config.getRepoPath() == null || config.getRepoPath().trim().isEmpty()) {
            setStatus("No local repo path configured");
            return;
        }

        Fi repoDir = new Fi(config.getRepoPath());
        if (!repoDir.exists()) {
            setStatus("Repository path missing");
            return;
        }

        if (!config.getRepoUrl().trim().isEmpty()) {
            ensureRemote(repoDir);
        } else {
            setStatus("No GitHub remote configured");
            return;
        }
        ensureGitIdentity(repoDir);
        ensureAuthentication(repoDir);

        try {
            runGit(repoDir, "fetch", "origin");

            setComparisonText(buildComparisonSummary(repoDir, resolveRemoteBranch(repoDir)));
        } catch (Exception e) {
            String failure = describeGitFailure(e.getMessage());
            setStatus("Compare failed: " + failure);
            setComparisonText("Compare failed:\n" + failure);
            Log.err(e);
        }
    }

    public void refreshRepositoryStatus() {
        if (config.getRepoPath() == null || config.getRepoPath().trim().isEmpty()) {
            setStatus("No local repo path configured");
            setStatusReportText("No local repo path configured.");
            return;
        }

        Fi repoDir = new Fi(config.getRepoPath());
        if (!repoDir.exists()) {
            setStatus("Repository path missing");
            setStatusReportText("Repository path missing:\n" + repoDir.path());
            return;
        }

        ensureGitIdentity(repoDir);
        ensureAuthentication(repoDir);

        try {
            String currentBranch = currentBranch(repoDir);
            String remoteBranch = "";
            String remoteRef = "";
            boolean hasRemote = !config.getRepoUrl().trim().isEmpty();
            if (hasRemote) {
                ensureRemote(repoDir);
                runGit(repoDir, "fetch", "origin");
                remoteBranch = resolveRemoteBranch(repoDir);
                remoteRef = "origin/" + remoteBranch;
            }

            List<String> localChanges = worktreeChangeLines(repoDir);
            boolean localAhead = hasRemote && hasCommit(repoDir) && localHasCommitsNotRemote(repoDir, remoteRef);
            boolean githubAhead = hasRemote && remoteHasCommitsNotLocal(repoDir, remoteRef);

            if (hasRemote) {
                setComparisonText(buildComparisonSummary(repoDir, remoteBranch));
                setStatusReportText(getComparisonText());
            } else {
                setStatusReportText("Repository not linked to GitHub.");
                setComparisonText("Repository not linked to GitHub.");
            }
            if (githubAhead && (localAhead || !localChanges.isEmpty())) {
                setStatus("Status: local and GitHub both have changes");
            } else if (githubAhead) {
                setStatus("Status: GitHub has changes not pulled");
            } else if (localAhead || !localChanges.isEmpty()) {
                setStatus("Status: local changes waiting to push");
            } else if (hasRemote) {
                setStatus("Status: local and GitHub match");
            } else {
                setStatus("Status: local repo ready, no GitHub remote");
            }
        } catch (Exception e) {
            String failure = describeGitFailure(e.getMessage());
            setStatus("Status refresh failed: " + failure);
            setStatusReportText("Status refresh failed:\n" + failure);
            Log.err(e);
        }
    }

    public void testConnection() {
        if (config.getRepoPath() == null || config.getRepoPath().trim().isEmpty()) {
            setStatus("No local repo path configured");
            return;
        }

        Fi repoDir = new Fi(config.getRepoPath());
        if (!repoDir.exists()) {
            setStatus("Repository path missing");
            return;
        }

        ensureGitIdentity(repoDir);
        ensureAuthentication(repoDir);
        if (!config.getRepoUrl().trim().isEmpty()) {
            ensureRemote(repoDir);
        }

        try {
            runGit(repoDir, "ls-remote", "--heads", "origin");
            setStatus("GitHub connection successful");
        } catch (Exception e) {
            setStatus("Connection test failed: " + describeGitFailure(e.getMessage()));
            Log.err(e);
        }
    }

    public void pushLocal() {
        if (config.getRepoPath() == null || config.getRepoPath().trim().isEmpty()) {
            setStatus("No local repo path configured");
            return;
        }

        Fi repoDir = new Fi(config.getRepoPath());
        if (!repoDir.exists()) {
            setStatus("Repository path missing");
            return;
        }

        ensureGitIdentity(repoDir);
        ensureAuthentication(repoDir);

        try {
            String remoteBranch;
            String remoteRef;
            if (!config.getRepoUrl().trim().isEmpty()) {
                ensureRemote(repoDir);
                runGit(repoDir, "fetch", "origin");
                remoteBranch = resolveRemoteBranch(repoDir);
                remoteRef = "origin/" + remoteBranch;
            } else {
                setStatus("No GitHub remote configured");
                return;
            }

            if (remoteHasCommitsNotLocal(repoDir, remoteRef)) {
                setStatus("Push blocked: GitHub has changes. Compare, then pull GitHub before pushing.");
                return;
            }

            if (hasWorktreeChanges(repoDir)) {
                commitChanges(repoDir, "Push local changes");
            }

            if (!hasCommit(repoDir)) {
                setStatus("No local commits to push");
                return;
            }

            pushCurrentBranch(repoDir, true);
            compareLocalAndRemote();
            setStatus("Pushed local changes");
        } catch (Exception e) {
            setStatus("Push failed: " + describeGitFailure(e.getMessage()));
            Log.err(e);
        }
    }

    public void forcePushLocal() {
        if (config.getRepoPath() == null || config.getRepoPath().trim().isEmpty()) {
            setStatus("No local repo path configured");
            return;
        }

        Fi repoDir = new Fi(config.getRepoPath());
        if (!repoDir.exists()) {
            setStatus("Repository path missing");
            return;
        }

        ensureGitIdentity(repoDir);
        ensureAuthentication(repoDir);

        try {
            if (!config.getRepoUrl().trim().isEmpty()) {
                ensureRemote(repoDir);
                runGit(repoDir, "fetch", "origin");
            } else {
                setStatus("No GitHub remote configured");
                return;
            }

            if (hasWorktreeChanges(repoDir)) {
                commitChanges(repoDir, "Force push local changes");
            }

            if (!hasCommit(repoDir)) {
                setStatus("No local commits to force push");
                return;
            }

            forcePushCurrentBranch(repoDir);
            compareLocalAndRemote();
            setStatus("Force pushed local schematics to GitHub");
        } catch (Exception e) {
            setStatus("Force push failed: " + describeGitFailure(e.getMessage()));
            Log.err(e);
        }
    }

    public synchronized String getStatusText() {
        return statusText;
    }

    public synchronized boolean isSynced() {
        return synced;
    }

    public synchronized String getStatusLog() {
        return String.join("\n", statusLog);
    }

    public synchronized String getStatusReportText() {
        return statusReportText;
    }

    public synchronized String getComparisonText() {
        return comparisonText;
    }

    public synchronized void setStatus(String status) {
        statusText = status;
        synced = status.toLowerCase(Locale.ROOT).contains("sync completed")
                || status.toLowerCase(Locale.ROOT).contains("pushed local changes")
                || status.toLowerCase(Locale.ROOT).contains("pulled remote changes")
                || status.toLowerCase(Locale.ROOT).contains("repository ready")
                || status.toLowerCase(Locale.ROOT).contains("configured github remote")
                || status.toLowerCase(Locale.ROOT).contains("local and github match");
        statusLog.add(status);
        while (statusLog.size() > 10) {
            statusLog.remove(0);
        }
    }

    private void ensureGitIdentity(Fi repoDir) {
        try {
            runGit(repoDir, "config", "user.name", config.getGitUserName());
            runGit(repoDir, "config", "user.email", config.getGitUserEmail());
            runGit(repoDir, "config", "core.quotePath", "false");
        } catch (Exception e) {
            setStatus("Could not configure Git identity: " + e.getMessage());
            Log.err(e);
        }
    }

    private void ensureAuthentication(Fi repoDir) {
        if (config.getGitHubToken() == null || config.getGitHubToken().trim().isEmpty()) {
            return;
        }

        try {
            runGit(repoDir, "config", "credential.helper", "store");
            String username = config.getGitHubUsername().trim().isEmpty() ? "x-access-token" : config.getGitHubUsername().trim();
            String payload = "protocol=https\nhost=github.com\nusername=" + username + "\npassword=" + config.getGitHubToken().trim() + "\n";
            ProcessBuilder builder = new ProcessBuilder("git", "credential", "approve");
            builder.directory(new java.io.File(repoDir.path()));
            Process process = builder.start();
            process.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("credential approval failed");
            }
            setStatus("Configured GitHub authentication");
        } catch (Exception e) {
            setStatus("Could not configure GitHub authentication: " + e.getMessage());
            Log.err(e);
        }
    }

    private void ensureRemote(Fi repoDir) {
        String remoteUrl = normalizeRepositoryUrl(config.getRepoUrl());
        try {
            runGit(repoDir, "remote", "get-url", "origin");
            runGit(repoDir, "remote", "set-url", "origin", remoteUrl);
            setStatus("Configured GitHub remote");
        } catch (Exception ignored) {
            try {
                runGit(repoDir, "remote", "add", "origin", remoteUrl);
                setStatus("Configured GitHub remote");
            } catch (Exception e) {
                setStatus("Could not configure remote: " + e.getMessage());
                Log.err(e);
            }
        }
    }

    private String normalizeRepositoryUrl(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.startsWith("git@github.com:")) {
            return "https://github.com/" + value.substring("git@github.com:".length());
        }
        return value;
    }

    private boolean isGitRepo(Fi repoDir) {
        try {
            String[] output = runGit(repoDir, "rev-parse", "--is-inside-work-tree");
            return output.length > 0 && output[0].contains("true");
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized void setComparisonText(String comparisonText) {
        this.comparisonText = comparisonText;
    }

    private synchronized void setStatusReportText(String statusReportText) {
        this.statusReportText = statusReportText;
    }

    private String buildComparisonSummary(Fi repoDir, String remoteBranch) throws Exception {
        String remoteRef = "origin/" + remoteBranch;
        boolean localFilesChanged = hasWorktreeChanges(repoDir);
        boolean localCommits = hasCommit(repoDir) && localHasCommitsNotRemote(repoDir, remoteRef);
        boolean githubCommits = remoteHasCommitsNotLocal(repoDir, remoteRef);
        Set<String> differentSchems = changedSchematicPaths(repoDir, remoteRef);

        boolean localNeedsPush = localFilesChanged || localCommits;
        String recommendation;
        if (githubCommits) {
            recommendation = "Use Pull & Merge to bring GitHub in and push the merged local result.";
        } else if (localNeedsPush) {
            recommendation = config.isAutoSyncEnabled() ? "Auto Push should upload the local update." : "Use Pull & Merge to push local changes safely.";
        } else {
            recommendation = "Local and GitHub are up to date.";
        }

        StringBuilder report = new StringBuilder();
        report.append("Different schems: ").append(differentSchems.size()).append("\n");
        report.append("Local changes to push: ").append(localNeedsPush ? "yes" : "no").append("\n");
        report.append("GitHub updates to pull: ").append(githubCommits ? "yes" : "no").append("\n");
        report.append("Branch: ").append(remoteBranch).append("\n");
        report.append(recommendation);
        return report.toString();
    }

    private void reloadSchematicsSoon() {
        if (Core.app == null || Vars.schematics == null) return;
        Core.app.post(() -> {
            try {
                Vars.schematics.load();
            } catch (Exception e) {
                Log.err(e);
            }
        });
    }

    private boolean hasWorktreeChanges(Fi repoDir) throws Exception {
        String[] output = runGit(repoDir, schematicArgs("status", "--porcelain", "--"));
        boolean schematicChanges = Arrays.stream(output).anyMatch(line -> {
            String path = statusPath(line);
            return !line.isBlank() && path.endsWith(".msch") && !isFilteredGitPath(path);
        });
        return schematicChanges || hasTrackedFilteredFiles(repoDir);
    }

    private String buildCommitMessage(Fi repoDir, String fallbackMessage) throws Exception {
        int added = 0;
        int edited = 0;
        int removed = 0;
        int moved = 0;
        List<String> singleFiles = new ArrayList<>();
        List<String> singleActions = new ArrayList<>();
        String[] output = runGit(repoDir, schematicArgs("status", "--porcelain", "--"));
        for (String line : output) {
            if (line.isBlank()) continue;
            String fileName = statusFileName(line);
            String path = statusPath(line);
            if (!fileName.endsWith(".msch") || isFilteredGitPath(path)) continue;

            String action = inferActionFromStatus(line);
            if (singleFiles.contains(fileName)) continue;
            singleFiles.add(fileName);
            singleActions.add(action);
            if (action.equals("Add")) added++;
            else if (action.equals("Remove")) removed++;
            else if (action.equals("Move")) moved++;
            else edited++;
        }

        if (singleFiles.isEmpty()) {
            return fallbackMessage;
        }

        if (singleFiles.size() == 1) {
            return singleActions.get(0) + " " + singleFiles.get(0);
        }

        List<String> parts = new ArrayList<>();
        if (added > 0) parts.add(added + " added");
        if (edited > 0) parts.add(edited + " edited");
        if (removed > 0) parts.add(removed + " removed");
        if (moved > 0) parts.add(moved + " moved");
        return "Update " + singleFiles.size() + " schematics (" + String.join(", ", parts) + ")";
    }

    private String inferActionFromStatus(String line) {
        if (line.startsWith("??") || line.startsWith("A ") || line.startsWith("AM")) return "Add";
        if (line.startsWith(" D") || line.startsWith("D ") || line.startsWith("AD")) return "Remove";
        if (line.startsWith("R ") || line.startsWith("R")) return "Move";
        return "Edit";
    }

    private boolean commitChanges(Fi repoDir, String message) throws Exception {
        runGit(repoDir, schematicArgs("add", "-A", "--"));
        try {
            runGit(repoDir, "rm", "-r", "--cached", "--ignore-unmatch", SchemPackLayout.FILTER_FOLDER);
        } catch (Exception ignored) {
        }
        if (!hasStagedChanges(repoDir)) return false;
        runGit(repoDir, "commit", "-m", message);
        return true;
    }

    private boolean hasStagedChanges(Fi repoDir) throws Exception {
        String[] output = runGit(repoDir, schematicArgs("diff", "--cached", "--name-only", "--"));
        return Arrays.stream(output).anyMatch(line -> !line.isBlank());
    }

    private boolean hasTrackedFilteredFiles(Fi repoDir) {
        try {
            String[] output = runGit(repoDir, "ls-files", SchemPackLayout.FILTER_FOLDER);
            return Arrays.stream(output).anyMatch(line -> !line.isBlank());
        } catch (Exception ignored) {
            return false;
        }
    }

    private String statusFileName(String statusLine) {
        return fileNameOnly(statusPath(statusLine));
    }

    private String statusPath(String statusLine) {
        if (statusLine == null || statusLine.length() < 4) return "";

        String path = statusLine.substring(3).trim();
        int renameSeparator = path.indexOf(" -> ");
        if (renameSeparator >= 0) {
            path = path.substring(renameSeparator + 4).trim();
        }
        return stripGitQuotes(path).replace('\\', '/');
    }

    private String stripGitQuotes(String path) {
        if (path == null) return "";

        String value = path.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private String fileNameOnly(String path) {
        if (path == null) return "";

        String value = path.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private List<String> statusFiles(Fi repoDir, String... prefixes) throws Exception {
        String[] output = runGit(repoDir, schematicArgs("status", "--porcelain", "--"));
        List<String> files = new ArrayList<>();
        for (String line : output) {
            if (line.isBlank()) continue;
            for (String prefix : prefixes) {
                if (line.startsWith(prefix)) {
                    String fileName = statusFileName(line);
                    String path = statusPath(line);
                    if (fileName.endsWith(".msch") && !isFilteredGitPath(path) && !files.contains(fileName)) {
                        files.add(fileName);
                    }
                    break;
                }
            }
        }
        Collections.sort(files);
        return files;
    }

    private List<String> worktreeChangeLines(Fi repoDir) throws Exception {
        String[] output = runGit(repoDir, schematicArgs("status", "--porcelain", "--"));
        List<String> changes = new ArrayList<>();
        for (String line : output) {
            if (line.isBlank()) continue;
            String path = statusPath(line);
            if (!path.endsWith(".msch") || isFilteredGitPath(path)) continue;
            changes.add(line.substring(0, Math.min(2, line.length())).trim() + " " + path);
        }
        if (hasTrackedFilteredFiles(repoDir)) {
            changes.add("tracked filtered schematics need cleanup");
        }
        Collections.sort(changes);
        return changes;
    }

    private Set<String> changedSchematicPaths(Fi repoDir, String remoteRef) throws Exception {
        Set<String> paths = new LinkedHashSet<>();
        String[] status = runGit(repoDir, schematicArgs("status", "--porcelain", "--"));
        for (String line : status) {
            if (line.isBlank()) continue;
            String path = statusPath(line);
            if (path.endsWith(".msch") && !isFilteredGitPath(path)) {
                paths.add(fileNameOnly(path));
            }
        }

        if (hasCommit(repoDir)) {
            addDiffNamePaths(paths, runGit(repoDir, schematicArgs("diff", "--name-only", "HEAD.." + remoteRef, "--")));
            addDiffNamePaths(paths, runGit(repoDir, schematicArgs("diff", "--name-only", remoteRef + "..HEAD", "--")));
        }
        return paths;
    }

    private void addDiffNamePaths(Set<String> paths, String[] output) {
        for (String line : output) {
            if (line.isBlank()) continue;
            String path = stripGitQuotes(line).replace('\\', '/');
            if (path.endsWith(".msch") && !isFilteredGitPath(path)) {
                paths.add(fileNameOnly(path));
            }
        }
    }

    private List<String> remoteDiffFiles(Fi repoDir, String remoteRef, String changeType) throws Exception {
        if (!hasCommit(repoDir)) return Collections.emptyList();

        String[] output = runGit(repoDir, schematicArgs("diff", "--name-status", "HEAD.." + remoteRef, "--"));
        return diffFilesFromOutput(output, changeType);
    }

    private List<String> localDiffFiles(Fi repoDir, String remoteRef, String changeType) throws Exception {
        if (!hasCommit(repoDir)) return Collections.emptyList();

        String[] output = runGit(repoDir, schematicArgs("diff", "--name-status", remoteRef + "..HEAD", "--"));
        return diffFilesFromOutput(output, changeType);
    }

    private String[] schematicArgs(String... prefix) {
        List<String> args = new ArrayList<>(Arrays.asList(prefix));
        args.add("*.msch");
        args.add(":(glob)**/*.msch");
        return args.toArray(new String[0]);
    }

    private boolean isFilteredGitPath(String path) {
        if (path == null) return false;
        String normalized = path.replace('\\', '/');
        return normalized.equals(SchemPackLayout.FILTER_FOLDER)
                || normalized.startsWith(SchemPackLayout.FILTER_FOLDER + "/");
    }

    private List<String> diffFilesFromOutput(String[] output, String changeType) {
        List<String> files = new ArrayList<>();
        for (String line : output) {
            if (line.isBlank() || !line.startsWith(changeType)) continue;

            String[] parts = line.split("\\t");
            String rawPath = parts.length == 0 ? "" : parts[parts.length - 1];
            String path = stripGitQuotes(rawPath).replace('\\', '/');
            String fileName = fileNameOnly(path);
            if (fileName.endsWith(".msch") && !isFilteredGitPath(path) && !files.contains(fileName)) {
                files.add(fileName);
            }
        }
        Collections.sort(files);
        return files;
    }

    private List<String> mergeFileLists(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>();
        for (String file : first) {
            if (!merged.contains(file)) merged.add(file);
        }
        for (String file : second) {
            if (!merged.contains(file)) merged.add(file);
        }
        Collections.sort(merged);
        return merged;
    }

    private List<String> overlapFiles(List<String> first, List<String> second) {
        List<String> overlap = new ArrayList<>();
        for (String file : first) {
            if (second.contains(file) && !overlap.contains(file)) {
                overlap.add(file);
            }
        }
        Collections.sort(overlap);
        return overlap;
    }

    private void removeAll(List<String> files, List<String> removed) {
        files.removeIf(removed::contains);
    }

    private void appendReportSection(StringBuilder builder, String title, List<String> files) {
        builder.append(title).append(": ").append(files.size()).append("\n");
        for (int i = 0; i < Math.min(files.size(), 8); i++) {
            builder.append("  ").append(files.get(i)).append("\n");
        }
        if (files.size() > 8) {
            builder.append("  ... ").append(files.size() - 8).append(" more\n");
        }
    }

    private void appendShortList(StringBuilder builder, List<String> values, int limit) {
        for (int i = 0; i < Math.min(values.size(), limit); i++) {
            builder.append("  ").append(values.get(i)).append("\n");
        }
        if (values.size() > limit) {
            builder.append("  ... ").append(values.size() - limit).append(" more\n");
        }
    }

    private boolean hasCommit(Fi repoDir) {
        try {
            runGit(repoDir, "rev-parse", "--verify", "HEAD");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean remoteHasCommitsNotLocal(Fi repoDir, String remoteRef) {
        try {
            if (!hasCommit(repoDir)) {
                String[] remoteCommit = runGit(repoDir, "rev-parse", "--verify", remoteRef);
                return Arrays.stream(remoteCommit).anyMatch(line -> !line.isBlank());
            }

            String[] output = runGit(repoDir, "rev-list", "--count", "HEAD.." + remoteRef);
            if (output.length == 0 || output[0].trim().isEmpty()) return false;
            return Integer.parseInt(output[0].trim()) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean localHasCommitsNotRemote(Fi repoDir, String remoteRef) {
        try {
            if (!hasCommit(repoDir)) return false;

            String[] output = runGit(repoDir, "rev-list", "--count", remoteRef + "..HEAD");
            if (output.length == 0 || output[0].trim().isEmpty()) return false;
            return Integer.parseInt(output[0].trim()) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void abortMerge(Fi repoDir) {
        try {
            runGit(repoDir, "merge", "--abort");
        } catch (Exception ignored) {
        }
    }

    private void pushCurrentBranch(Fi repoDir, boolean setUpstream) throws Exception {
        String branch = resolveRemoteBranch(repoDir);
        alignCurrentBranch(repoDir, branch);
        if (setUpstream) {
            runGit(repoDir, "push", "-u", "origin", "HEAD:" + branch);
        } else {
            runGit(repoDir, "push", "origin", "HEAD:" + branch);
        }
    }

    private void forcePushCurrentBranch(Fi repoDir) throws Exception {
        String branch = resolveRemoteBranch(repoDir);
        alignCurrentBranch(repoDir, branch);
        runGit(repoDir, "push", "--force-with-lease", "-u", "origin", "HEAD:" + branch);
    }

    private void alignCurrentBranch(Fi repoDir, String branch) {
        String current = currentBranch(repoDir);
        if (current.equals(branch)) return;

        try {
            runGit(repoDir, "branch", "-M", branch);
        } catch (Exception ignored) {
        }
    }

    private String currentBranch(Fi repoDir) {
        try {
            String[] output = runGit(repoDir, "branch", "--show-current");
            if (output.length > 0 && !output[0].trim().isEmpty()) {
                return output[0].trim();
            }
        } catch (Exception ignored) {
        }
        return "main";
    }

    private String resolveRemoteBranch(Fi repoDir) {
        try {
            String[] output = runGit(repoDir, "ls-remote", "--symref", "origin", "HEAD");
            for (String line : output) {
                String prefix = "ref: refs/heads/";
                if (line.startsWith(prefix)) {
                    String branch = line.substring(prefix.length()).trim();
                    int whitespace = branch.indexOf('\t');
                    if (whitespace >= 0) {
                        branch = branch.substring(0, whitespace);
                    }
                    if (!branch.isEmpty()) {
                        return branch;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return currentBranch(repoDir);
    }

    private String describeGitFailure(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "Git command failed";
        }

        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("403") || lower.contains("permission") || lower.contains("access denied")) {
            return "GitHub denied write access. Check that the token has repo write permission for this repository.";
        }
        if (lower.contains("authentication failed") || lower.contains("could not read username")) {
            return "GitHub authentication failed. Check the username/token in Edit Repository.";
        }
        if (lower.contains("repository not found")) {
            return "GitHub repository not found or the token cannot access it.";
        }
        if (lower.contains("non-fast-forward") || lower.contains("fetch first")
                || lower.contains("current branch is behind")) {
            return "GitHub has commits your local schematics folder does not have. Compare, then pull GitHub before pushing.";
        }
        if (lower.contains("diverging branches") || lower.contains("not possible to fast-forward")) {
            return "Local and GitHub both have new commits. Use Pull & Merge, or Force Push Local.";
        }

        return message;
    }

    private String[] runGit(Fi repoDir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new java.io.File(repoDir.path()));
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException(output.trim().isEmpty() ? "git command failed" : output.trim());
        }
        return output.split("\\R");
    }
}
