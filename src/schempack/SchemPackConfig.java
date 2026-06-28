package schempack;

import arc.files.Fi;
import arc.util.Log;
import mindustry.Vars;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SchemPackConfig {
    private static final String CONFIG_FILE_NAME = "schempack-config.properties";

    private boolean autoSyncEnabled = false;
    private boolean autoPullEnabled = false;
    private String repoUrl = "";
    private String repoPath = "";
    private String githubUsername = "";
    private String githubToken = "";
    private String gitUserName = "SchemPack Manager";
    private String gitUserEmail = "schempack@example.com";
    private final Set<String> filteredSchematics = new LinkedHashSet<>();

    public SchemPackConfig() {
        load();
    }

    public void load() {
        Fi configFile = Vars.modDirectory.child(CONFIG_FILE_NAME);
        if (!configFile.exists()) return;

        Properties props = new Properties();
        try (InputStream in = configFile.read()) {
            props.load(in);
            autoSyncEnabled = Boolean.parseBoolean(props.getProperty("autoSyncEnabled", "false"));
            autoPullEnabled = Boolean.parseBoolean(props.getProperty("autoPullEnabled", "false"));
            if (!autoSyncEnabled) autoPullEnabled = false;
            repoUrl = props.getProperty("repoUrl", "");
            repoPath = props.getProperty("repoPath", "");
            githubUsername = props.getProperty("githubUsername", "");
            githubToken = props.getProperty("githubToken", "");
            gitUserName = props.getProperty("gitUserName", "SchemPack Manager");
            gitUserEmail = props.getProperty("gitUserEmail", "schempack@example.com");
            String filters = props.getProperty("filteredSchematics", "");
            filteredSchematics.clear();
            if (!filters.isEmpty()) {
                String[] parts = filters.split(",");
                for (String part : parts) {
                    String name = part.trim();
                    if (!name.isEmpty()) filteredSchematics.add(name);
                }
            }
        } catch (Exception e) {
            Log.err(e);
        }
    }

    public void save() {
        Properties props = new Properties();
        props.setProperty("autoSyncEnabled", Boolean.toString(autoSyncEnabled));
        props.setProperty("autoPullEnabled", Boolean.toString(autoSyncEnabled && autoPullEnabled));
        props.setProperty("repoUrl", repoUrl == null ? "" : repoUrl);
        props.setProperty("repoPath", repoPath == null ? "" : repoPath);
        props.setProperty("githubUsername", githubUsername == null ? "" : githubUsername);
        props.setProperty("githubToken", githubToken == null ? "" : githubToken);
        props.setProperty("gitUserName", gitUserName == null ? "SchemPack Manager" : gitUserName);
        props.setProperty("gitUserEmail", gitUserEmail == null ? "schempack@example.com" : gitUserEmail);
        props.setProperty("filteredSchematics", String.join(",", filteredSchematics));

        Fi configFile = Vars.modDirectory.child(CONFIG_FILE_NAME);
        try (OutputStream out = configFile.write(false)) {
            props.store(out, "SchemPack Manager config");
        } catch (Exception e) {
            Log.err(e);
        }
    }

    public boolean isAutoSyncEnabled() {
        return autoSyncEnabled;
    }

    public boolean isAutoPullEnabled() {
        return autoSyncEnabled && autoPullEnabled;
    }

    public boolean hasConfiguredRepository() {
        return repoUrl != null && !repoUrl.trim().isEmpty();
    }

    public void setAutoSyncEnabled(boolean autoSyncEnabled) {
        this.autoSyncEnabled = autoSyncEnabled;
        if (!autoSyncEnabled) this.autoPullEnabled = false;
    }

    public void setAutoPullEnabled(boolean autoPullEnabled) {
        this.autoPullEnabled = autoSyncEnabled && autoPullEnabled;
    }

    public String getRepoUrl() {
        return repoUrl == null ? "" : repoUrl;
    }

    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl == null ? "" : repoUrl;
    }

    public String getRepoPath() {
        return defaultRepoPath();
    }

    public void setRepoPath(String repoPath) {
        this.repoPath = repoPath == null ? "" : repoPath;
    }

    public String getGitHubUsername() {
        return githubUsername == null ? "" : githubUsername;
    }

    public void setGitHubUsername(String githubUsername) {
        this.githubUsername = githubUsername == null ? "" : githubUsername;
    }

    public String getGitHubToken() {
        return githubToken == null ? "" : githubToken;
    }

    public void setGitHubToken(String githubToken) {
        this.githubToken = githubToken == null ? "" : githubToken;
    }

    public String getGitUserName() {
        return gitUserName == null || gitUserName.trim().isEmpty() ? "SchemPack Manager" : gitUserName;
    }

    public void setGitUserName(String gitUserName) {
        this.gitUserName = gitUserName == null ? "SchemPack Manager" : gitUserName;
    }

    public String getGitUserEmail() {
        return gitUserEmail == null || gitUserEmail.trim().isEmpty() ? "schempack@example.com" : gitUserEmail;
    }

    public void setGitUserEmail(String gitUserEmail) {
        this.gitUserEmail = gitUserEmail == null ? "schempack@example.com" : gitUserEmail;
    }

    public Set<String> getFilteredSchematics() {
        return filteredSchematics;
    }

    public void setFilteredSchematics(Set<String> names) {
        filteredSchematics.clear();
        if (names == null) return;
        for (String name : names) {
            if (name != null && !name.trim().isEmpty()) {
                filteredSchematics.add(name.trim());
            }
        }
    }

    public void addFilteredSchematic(String name) {
        if (name != null && !name.trim().isEmpty()) {
            filteredSchematics.add(name.trim());
        }
    }

    public void removeFilteredSchematic(String name) {
        filteredSchematics.remove(name);
    }

    public boolean isFiltered(String schematicName) {
        return filteredSchematics.contains(schematicName);
    }

    private String defaultRepoPath() {
        try {
            if (Vars.schematicDirectory != null) {
                return Vars.schematicDirectory.path();
            }
        } catch (Exception ignored) {
        }
        return "schematics";
    }
}
