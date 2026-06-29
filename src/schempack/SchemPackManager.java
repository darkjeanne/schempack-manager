package schempack;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.graphics.Color;
import arc.scene.ui.Button;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.util.Log;
import arc.util.Scaling;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Schematic;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.mod.Mod;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.SchematicsDialog;
import mindustry.ui.dialogs.SchematicsDialog.SchematicImage;
import mindustry.ui.dialogs.BaseDialog;
import arc.scene.ui.layout.Table;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextField;
import arc.util.Time;
import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

public class SchemPackManager extends Mod {
    public static SchemPackManager instance;

    private SchemPackConfig config;
    private GitRepoManager gitRepoManager;
    private SchemSyncController syncController;
    private boolean initialized;
    private SchematicsDialog buttonTarget;
    private float autoPushCheckTimer;
    private long lastSchematicSnapshot = Long.MIN_VALUE;
    private final Pattern ignoreSymbols = Pattern.compile("[`~!@#$%^&*()\\-_=+{}|;:'\",<.>/?]");

    public SchemPackManager() {
        instance = this;
        Log.info("SchemPackManager initialized");
        Events.on(EventType.ClientLoadEvent.class, event -> {
            Time.runTask(10f, this::initialize);
        });
    }

    @Override
    public void init() {
        super.init();
        Events.on(EventType.ClientLoadEvent.class, event -> {
            Time.runTask(5f, this::attachButtonToSchematicsDialog);
            Time.runTask(10f, this::attachButtonToSchematicsDialog);
        });
    }

    private void initialize() {
        if (initialized) return;
        initialized = true;

        config = new SchemPackConfig();
        gitRepoManager = new GitRepoManager(config);
        syncController = new SchemSyncController(config, gitRepoManager);

        installSchematicsButton();
        installAutoPushChangeMonitor();
        syncController.startWatching();
        Log.info("SchemPackManager ready");
    }

    private void installAutoPushChangeMonitor() {
        Events.run(EventType.Trigger.update, () -> {
            if (config == null || syncController == null) {
                lastSchematicSnapshot = Long.MIN_VALUE;
                autoPushCheckTimer = 0f;
                return;
            }

            autoPushCheckTimer += Time.delta;
            if (autoPushCheckTimer < 120f) return;
            autoPushCheckTimer = 0f;

            long snapshot = schematicSnapshot();
            if (snapshot == Long.MIN_VALUE) return;
            if (lastSchematicSnapshot == Long.MIN_VALUE) {
                lastSchematicSnapshot = snapshot;
                return;
            }
            if (snapshot != lastSchematicSnapshot) {
                lastSchematicSnapshot = snapshot;
                if (config.isAutoSyncEnabled()) {
                    syncController.requestImmediateAutoSync();
                } else {
                    syncController.requestCompare();
                }
            }
        });
    }

    private void installSchematicsButton() {
        try {
            addButtonToSchematicsDialog();
            Events.on(EventType.ClientLoadEvent.class, event -> {
                Time.runTask(5f, this::addButtonToSchematicsDialog);
                Time.runTask(10f, this::addButtonToSchematicsDialog);
            });
        } catch (Exception e) {
            Log.err(e);
        }
    }

    private void attachButtonToSchematicsDialog() {
        addButtonToSchematicsDialog();
    }

    private void addButtonToSchematicsDialog() {
        try {
            if (Vars.ui == null || Vars.ui.schematics == null) return;
            if (buttonTarget == Vars.ui.schematics) return;

            buttonTarget = Vars.ui.schematics;
            Vars.ui.schematics.hidden(() -> {
                if (syncController != null && config != null && config.isAutoSyncEnabled()) {
                    syncController.requestImmediateAutoSync();
                }
            });

            Vars.ui.schematics.buttons.button("SchemPack", () -> {
                Vars.ui.schematics.hide();
                openManagerDialog();
            }).size(210f, 64f);
            Vars.ui.schematics.buttons.invalidateHierarchy();
            Vars.ui.schematics.pack();
            Log.info("Installed SchemPack button into schematics dialog");
        } catch (Exception e) {
            Log.err(e);
        }
    }

    public void openManagerDialog() {
        if (config == null) {
            return;
        }

        if (!config.hasConfiguredRepository()) {
            openConfigDialog();
            return;
        }

        syncController.requestCompare();

        BaseDialog dialog = new BaseDialog("SchemPack Manager");
        dialog.cont.defaults().pad(6f).left();
        dialog.cont.table(header -> {
            header.defaults().left();
            header.add("SchemPack").row();
            header.label(() -> "Mode: " + SchemSyncPolicy.displayName(SchemSyncPolicy.modeFor(config.isAutoSyncEnabled(), config.isAutoPullEnabled()))).color(Color.lightGray).row();
        }).growX().row();

        dialog.cont.table(panel -> {
            panel.defaults().left().pad(3f);
            panel.add("Repository").row();
            panel.label(() -> gitRepoManager.getComparisonText()).width(560f).wrap().row();
        }).growX().row();

        dialog.cont.table(actions -> {
            actions.center();
            actions.defaults().pad(3f).height(44f);

            actions.button("Compare", () -> {
                syncController.requestCompare();
            }).width(160f);

            if (!config.isAutoSyncEnabled()) {
                actions.button("Pull & Merge", () -> {
                    syncController.requestPull();
                }).width(160f);
            }

            actions.button("Force Push Local", () -> {
                syncController.requestForcePush();
            }).width(180f);
        }).center().row();

        dialog.cont.table(settings -> {
            settings.center();
            settings.defaults().pad(3f).height(40f);

            settings.button("Auto Push: " + (config.isAutoSyncEnabled() ? "On" : "Off"), () -> {
                boolean enabled = !config.isAutoSyncEnabled();
                config.setAutoSyncEnabled(enabled);
                config.save();
                if (enabled) {
                    syncController.startWatching();
                } else {
                    syncController.stopWatching();
                }
                dialog.hide();
                openManagerDialog();
            }).width(160f);

            if (config.isAutoSyncEnabled()) {
                settings.button("Auto Pull: " + (config.isAutoPullEnabled() ? "On" : "Off"), () -> {
                    config.setAutoPullEnabled(!config.isAutoPullEnabled());
                    config.save();
                    dialog.hide();
                    openManagerDialog();
                }).width(160f);
            }

            settings.button("Edit Repository", () -> {
                dialog.hide();
                openConfigDialog();
            }).width(160f);
        }).center().row();

        dialog.cont.table(row -> row.button("Filter Schematics", () -> {
            if (syncController.isBusy()) {
                gitRepoManager.setStatus("Wait for the current Git operation to finish");
                return;
            }
            openFilterOverlayDialog();
        }).disabled(button -> syncController.isBusy()).size(220f, 46f)).center().row();

        dialog.addCloseButton();
        dialog.show();
    }

    private void openFilterOverlayDialog() {
        normalizeLayoutForFilterMenu();

        BaseDialog filterDialog = new BaseDialog("Filter Schematics");
        filterDialog.cont.defaults().pad(4f).left();

        TextField searchField = new TextField();
        searchField.setMessageText("Search name or description");
        filterDialog.cont.table(search -> {
            search.image(Icon.zoom).padRight(4f);
            search.add(searchField).growX();
        }).growX().row();

        Set<String> stagedFiltered = currentFilteredNames();
        Set<String> selectedTags = new LinkedHashSet<>();
        List<FilterEntry> allEntries = collectSchematicFilterEntries("", Collections.emptySet(), stagedFiltered);

        Table tagTable = new Table();
        filterDialog.cont.table(tags -> {
            tags.left();
            tags.add("Tags").color(Color.lightGray).padRight(4f);
            tags.pane(Styles.noBarPane, tagTable).growX().height(46f).scrollY(false);
        }).growX().height(50f).row();

        Table listTable = new Table() {
            @Override
            public void setCullingArea(Rect cullingArea) {
                super.setCullingArea(cullingArea);
                getChildren().<Table>each(child -> child instanceof Table, child -> {
                    if (getCullingArea() == null || child.getCullingArea() == null) return;
                    Vec2 position = getCullingArea().getPosition(Tmp.v1);
                    child.getCullingArea().setSize(getCullingArea().width, getCullingArea().height)
                            .setPosition(child.parentToLocalCoordinates(position));
                });
            }
        };
        listTable.setCullingArea(new Rect());
        ScrollPane scrollPane = new ScrollPane(listTable);
        scrollPane.setOverscroll(false, true);
        filterDialog.cont.add(scrollPane).grow().height(420f).row();

        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            rebuildFilterTags(tagTable, selectedTags, rebuild[0]);
            rebuildFilterOverlay(listTable, allEntries, searchField.getText(), selectedTags, stagedFiltered);
        };
        searchField.changed(() -> rebuild[0].run());
        rebuild[0].run();

        filterDialog.buttons.defaults().size(210f, 64f);
        filterDialog.buttons.button("Back", Icon.left, () -> {
            applyFilterChanges(stagedFiltered);
            filterDialog.hide();
            syncController.requestImmediateAutoSync();
        });

        filterDialog.show();
    }

    private void rebuildFilterTags(Table tagTable, Set<String> selectedTags, Runnable rebuild) {
        tagTable.clearChildren();
        tagTable.left();
        tagTable.defaults().pad(2f).height(42f);
        for (String tag : collectFilterTags()) {
            tagTable.button(tag, Styles.togglet, () -> {
                if (selectedTags.contains(tag)) {
                    selectedTags.remove(tag);
                } else {
                    selectedTags.add(tag);
                }
                rebuild.run();
            }).checked(selectedTags.contains(tag)).with(button -> button.getLabel().setWrap(false));
        }
    }

    private void rebuildFilterOverlay(Table listTable, List<FilterEntry> allEntries, String searchText, Set<String> selectedTags, Set<String> stagedFiltered) {
        listTable.clearChildren();
        listTable.top();

        List<FilterEntry> entries = filterEntries(allEntries, searchText, selectedTags, stagedFiltered);
        if (entries.isEmpty()) {
            listTable.add("No schematics found").color(Color.lightGray).row();
            return;
        }

        int cols = Math.max((int)(Core.graphics.getWidth() / 230f), 1);
        int i = 0;
        for (FilterEntry entry : entries) {
            Button card = listTable.button(cell -> {
                cell.top();
                cell.margin(0f);
                Runnable[] toggle = new Runnable[1];
                cell.table(buttons -> {
                    buttons.left();
                    buttons.defaults().height(50f).pad(2f);
                    ImageButtonStyle style = new ImageButtonStyle(Styles.emptyi);
                    ImageButton eye = buttons.button(Icon.eyeSmall, style, () -> toggle[0].run()).size(50f).tooltip("Toggle GitHub sync").get();
                    eye.update(() -> eye.getStyle().imageUp = stagedFiltered.contains(entry.fileName) ? Icon.eyeOffSmall : Icon.eyeSmall);
                    buttons.label(() -> stagedFiltered.contains(entry.fileName) ? "Filtered" : "Included").color(Color.lightGray).left().growX();
                }).growX().height(50f);
                cell.row();
                cell.stack(new SchematicImage(entry.schematic).setScaling(Scaling.fit), new Table(name -> {
                    name.top();
                    name.table(Styles.black3, c -> {
                        Label label = c.add(entry.schematic.name()).style(Styles.outlineLabel).color(Color.white).top().growX().maxWidth(192f).get();
                        label.setEllipsis(true);
                    }).growX().margin(1f).pad(4f).maxWidth(192f).padBottom(0f);
                })).size(200f);
                toggle[0] = () -> toggleFilter(entry.fileName, stagedFiltered);
            }, () -> {
                toggleFilter(entry.fileName, stagedFiltered);
            }).pad(4f).style(Styles.flati).get();
            card.getStyle().up = Tex.pane;

            if (++i % cols == 0) {
                listTable.row();
            }
        }
    }

    private void toggleFilter(String fileName, Set<String> stagedFiltered) {
        if (stagedFiltered.contains(fileName)) {
            stagedFiltered.remove(fileName);
        } else {
            stagedFiltered.add(fileName);
        }
    }

    private List<FilterEntry> filterEntries(List<FilterEntry> entries, String searchText, Set<String> selectedTags, Set<String> stagedFiltered) {
        String needle = normalizedSearch(searchText);
        List<FilterEntry> filteredEntries = new ArrayList<>();
        for (FilterEntry entry : entries) {
            if (!selectedTags.isEmpty() && !hasAllLabels(entry.schematic, selectedTags)) continue;
            if (!needle.isEmpty()
                    && !normalizedSearch(entry.fileName).contains(needle)
                    && !normalizedSearch(entry.schematic.name()).contains(needle)
                    && !normalizedSearch(entry.schematic.description()).contains(needle)
                    && !normalizedSearch(entry.relativePath).contains(needle)) continue;
            filteredEntries.add(entry.withFiltered(stagedFiltered.contains(entry.fileName)));
        }
        filteredEntries.sort((left, right) -> {
            if (left.filtered != right.filtered) {
                return left.filtered ? -1 : 1;
            }
            return left.schematic.name().compareToIgnoreCase(right.schematic.name());
        });
        return filteredEntries;
    }

    private List<FilterEntry> collectSchematicFilterEntries(String searchText, Set<String> selectedTags, Set<String> stagedFiltered) {
        List<FilterEntry> entries = new ArrayList<>();
        File schematicDir = new File(Vars.schematicDirectory.path());
        if (!schematicDir.exists() || !schematicDir.isDirectory()) {
            return entries;
        }

        String needle = normalizedSearch(searchText);
        for (Schematic schematic : Vars.schematics.all()) {
            if (schematic.file == null || schematic.mod != null) continue;
            if (!selectedTags.isEmpty() && !hasAllLabels(schematic, selectedTags)) continue;

            File file = new File(schematic.file.path());
            if (!isInside(schematicDir, file) || !file.getName().endsWith(".msch")) continue;

            String fileName = file.getName();
            String relativePath = SchemPackLayout.relativePath(schematicDir, file);
            boolean filtered = stagedFiltered.contains(fileName);
            if (!needle.isEmpty()
                    && !normalizedSearch(fileName).contains(needle)
                    && !normalizedSearch(schematic.name()).contains(needle)
                    && !normalizedSearch(schematic.description()).contains(needle)
                    && !normalizedSearch(relativePath).contains(needle)) continue;

            entries.add(new FilterEntry(schematic, file, fileName, relativePath, filtered));
        }

        entries.sort((left, right) -> {
            if (left.filtered != right.filtered) {
                return left.filtered ? -1 : 1;
            }
            return left.schematic.name().compareToIgnoreCase(right.schematic.name());
        });
        return entries;
    }

    private Set<String> collectFilterTags() {
        Set<String> tags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Schematic schematic : Vars.schematics.all()) {
            if (schematic.mod != null) continue;
            for (String tag : schematic.labels) {
                if (tag != null && !tag.trim().isEmpty()) {
                    tags.add(tag);
                }
            }
        }
        return tags;
    }

    private String normalizedSearch(String value) {
        if (value == null) return "";
        return ignoreSymbols.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private boolean hasAllLabels(Schematic schematic, Set<String> selectedTags) {
        for (String tag : selectedTags) {
            if (!schematic.labels.contains(tag)) return false;
        }
        return true;
    }

    private List<FilterEntry> collectSchematicFilterEntries(String searchText, Set<String> stagedFiltered) {
        return collectSchematicFilterEntries(searchText, Collections.emptySet(), stagedFiltered);
    }

    private Set<String> currentFilteredNames() {
        Set<String> names = new LinkedHashSet<>(config.getFilteredSchematics());
        try {
            File schematicDir = new File(Vars.schematicDirectory.path());
            for (Schematic schematic : Vars.schematics.all()) {
                if (schematic.file == null || schematic.mod != null) continue;
                File file = new File(schematic.file.path());
                if (SchemPackLayout.isFilteredPath(schematicDir, file)) {
                    names.add(file.getName());
                }
            }
        } catch (Exception e) {
            Log.err(e);
        }
        return names;
    }

    private void applyFilterChanges(Set<String> stagedFiltered) {
        try {
            File schematicDir = new File(Vars.schematicDirectory.path());
            List<FilterEntry> entries = collectSchematicFilterEntries("", stagedFiltered);
            for (FilterEntry entry : entries) {
                boolean actualFiltered = SchemPackLayout.isFilteredPath(schematicDir, entry.file);
                boolean desiredFiltered = stagedFiltered.contains(entry.fileName);
                if (actualFiltered != desiredFiltered) {
                    SchemPackLayout.moveToFilterState(schematicDir, entry.file, desiredFiltered);
                }
            }
            config.setFilteredSchematics(stagedFiltered);
            SchemPackLayout.LayoutResult result = SchemPackLayout.normalize(schematicDir, config.getFilteredSchematics());
            config.setFilteredSchematics(result.filteredNames);
            config.save();
            Vars.schematics.load();
        } catch (Exception e) {
            gitRepoManager.setStatus("Filter failed: " + e.getMessage());
            Log.err(e);
        }
    }

    private static class FilterEntry {
        private final Schematic schematic;
        private final File file;
        private final String fileName;
        private final String relativePath;
        private final boolean filtered;

        private FilterEntry(Schematic schematic, File file, String fileName, String relativePath, boolean filtered) {
            this.schematic = schematic;
            this.file = file;
            this.fileName = fileName;
            this.relativePath = relativePath;
            this.filtered = filtered;
        }

        private FilterEntry withFiltered(boolean filtered) {
            if (this.filtered == filtered) return this;
            return new FilterEntry(schematic, file, fileName, relativePath, filtered);
        }
    }

    private void normalizeLayoutForFilterMenu() {
        try {
            File schematicDir = new File(Vars.schematicDirectory.path());
            SchemPackLayout.LayoutResult result = SchemPackLayout.normalize(schematicDir, config.getFilteredSchematics());
            config.setFilteredSchematics(result.filteredNames);
            config.save();
            if (result.movedFiles > 0) {
                Vars.schematics.load();
            }
        } catch (Exception e) {
            gitRepoManager.setStatus("Filter setup failed: " + e.getMessage());
            Log.err(e);
        }
    }

    private boolean isInside(File root, File file) {
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            return filePath.startsWith(rootPath);
        } catch (Exception ignored) {
            return false;
        }
    }

    private long schematicSnapshot() {
        try {
            File root = new File(Vars.schematicDirectory.path());
            if (!root.exists() || !root.isDirectory()) return Long.MIN_VALUE;
            return schematicSnapshot(root, root, 1469598103934665603L);
        } catch (Exception e) {
            Log.err(e);
            return Long.MIN_VALUE;
        }
    }

    private long schematicSnapshot(File root, File file, long hash) {
        File[] children = file.listFiles();
        if (children == null) return hash;
        Arrays.sort(children, Comparator.comparing(File::getName));

        for (File child : children) {
            String name = child.getName();
            if (name.equals(".git") || name.equals(SchemPackLayout.FILTER_FOLDER)) continue;

            if (child.isDirectory()) {
                hash = schematicSnapshot(root, child, mix(hash, name.hashCode()));
            } else if (name.toLowerCase(Locale.ROOT).endsWith(".msch")) {
                String relative = SchemPackLayout.relativePath(root, child);
                hash = mix(hash, relative.hashCode());
                hash = mix(hash, child.length());
                hash = mix(hash, child.lastModified());
            }
        }
        return hash;
    }

    private long mix(long hash, long value) {
        hash ^= value;
        return hash * 1099511628211L;
    }

    private void openConfigDialog() {
        BaseDialog dialog = new BaseDialog("SchemPack Setup");
        dialog.cont.defaults().pad(4f).left();

        dialog.cont.add("Repository").row();
        dialog.cont.add("Use a GitHub repository URL like https://github.com/user/repo.git. The game schematic folder is the local Git repository.").width(420f).wrap().row();

        TextField repoField = new TextField(config.getRepoUrl());
        dialog.cont.add("GitHub repository URL").row();
        dialog.cont.add(repoField).width(420f).row();

        TextField usernameField = new TextField(config.getGitHubUsername());
        dialog.cont.add("GitHub username").row();
        dialog.cont.add(usernameField).width(420f).row();

        TextField tokenField = new TextField(config.getGitHubToken());
        dialog.cont.add("Personal access token").row();
        dialog.cont.add("The token needs repository Contents read/write access. For private repositories, it must be allowed to access this repo.").width(420f).wrap().row();
        dialog.cont.add(tokenField).width(420f).row();

        dialog.cont.button("Connect", () -> {
            config.setRepoUrl(repoField.getText());
            config.setRepoPath("");
            config.setGitHubUsername(usernameField.getText());
            config.setGitHubToken(tokenField.getText());
            config.save();
            gitRepoManager.refreshFromConfig();
            syncController.requestCompare();
            dialog.hide();
            openManagerDialog();
        }).size(160f, 44f).row();

        dialog.addCloseButton();
        dialog.show();
    }

    @Override
    public void loadContent() {
        Log.info("SchemPackManager content loaded");
    }
}
