package schempack;

import java.io.File;
import java.io.IOException;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.InflaterInputStream;

public final class SchemPackLayout {
    public static final String FILTER_FOLDER = "schempack-0";
    public static final String ACTIVE_PREFIX = "schempack-";

    private SchemPackLayout() {
    }

    public static LayoutResult normalize(File root, Set<String> filteredNames) throws IOException {
        if (root == null) return new LayoutResult(0, 0, Collections.emptySet());

        Files.createDirectories(root.toPath().resolve(FILTER_FOLDER));
        List<Path> files = collectSchematics(root.toPath());
        int moved = collapseNumberedCopies(root.toPath(), files);
        if (moved > 0) {
            files = collectSchematics(root.toPath());
        }

        Set<String> effectiveFiltered = new LinkedHashSet<>();

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            boolean inFilteredFolder = isInFolder(root.toPath(), file, FILTER_FOLDER);
            boolean filtered = inFilteredFolder || filteredNames.contains(fileName);

            if (filtered) {
                effectiveFiltered.add(fileName);
                Path target = uniqueTarget(root.toPath().resolve(FILTER_FOLDER), fileName, file);
                if (!file.equals(target)) {
                    Files.createDirectories(target.getParent());
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                    moved++;
                }
            } else {
                Path target = uniqueTarget(root.toPath(), fileName, file);
                if (!file.equals(target)) {
                    Files.createDirectories(target.getParent());
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                    moved++;
                }
            }
        }

        removeEmptySchempackFolders(root.toPath());
        return new LayoutResult(moved, files.size() - effectiveFiltered.size(), effectiveFiltered);
    }

    public static void moveToFilterState(File root, File file, boolean filtered) throws IOException {
        if (root == null || file == null || !file.exists()) return;
        Path rootPath = root.toPath();
        Path source = file.toPath();
        String fileName = source.getFileName().toString();
        Path target = filtered
                ? uniqueTarget(rootPath.resolve(FILTER_FOLDER), fileName, source)
                : uniqueTarget(rootPath, fileName, source);
        Files.createDirectories(target.getParent());
        if (!source.equals(target)) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static boolean isFilteredPath(File root, File file) {
        return root != null && file != null && isInFolder(root.toPath(), file.toPath(), FILTER_FOLDER);
    }

    public static String relativePath(File root, File file) {
        if (root == null || file == null) return "";
        return root.toPath().relativize(file.toPath()).toString().replace('\\', '/');
    }

    private static int collapseNumberedCopies(Path root, List<Path> files) throws IOException {
        Map<String, Path> baseFiles = new HashMap<>();
        List<Path> numberedFiles = new ArrayList<>();

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String baseName = numberedBaseFileName(fileName);
            if (baseName == null) {
                baseFiles.putIfAbsent(fileName.toLowerCase(Locale.ROOT), file);
            } else {
                numberedFiles.add(file);
            }
        }

        numberedFiles.sort((left, right) -> {
            try {
                return Long.compare(Files.getLastModifiedTime(left).toMillis(), Files.getLastModifiedTime(right).toMillis());
            } catch (IOException ignored) {
                return left.compareTo(right);
            }
        });

        int changed = 0;
        for (Path numbered : numberedFiles) {
            if (!Files.exists(numbered)) continue;

            String baseName = numberedBaseFileName(numbered.getFileName().toString());
            if (baseName == null) continue;
            if (!isNumberedEditCopy(numbered, baseName)) continue;

            Path target = baseFiles.get(baseName.toLowerCase(Locale.ROOT));
            if (target == null) {
                target = numbered.resolveSibling(baseName);
                Files.move(numbered, target, StandardCopyOption.REPLACE_EXISTING);
                baseFiles.put(baseName.toLowerCase(Locale.ROOT), target);
                changed++;
                continue;
            }

            if (Files.exists(target) && isSameOrNewer(numbered, target)) {
                Files.move(numbered, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(numbered);
            }
            changed++;
        }

        return changed;
    }

    private static boolean isNumberedEditCopy(Path numbered, String baseName) {
        String schematicName = readSchematicName(numbered);
        if (schematicName == null || schematicName.trim().isEmpty()) return false;

        String baseDisplayName = baseName.substring(0, baseName.length() - ".msch".length());
        return schematicName.equals(baseDisplayName);
    }

    private static String readSchematicName(Path file) {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            if (input.readByte() != 'm' || input.readByte() != 's' || input.readByte() != 'c' || input.readByte() != 'h') {
                return null;
            }

            input.readUnsignedByte();
            try (DataInputStream stream = new DataInputStream(new InflaterInputStream(input))) {
                stream.readShort();
                stream.readShort();
                int tags = stream.readUnsignedByte();
                for (int i = 0; i < tags; i++) {
                    String key = stream.readUTF();
                    String value = stream.readUTF();
                    if (key.equals("name")) return value;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isSameOrNewer(Path candidate, Path existing) {
        try {
            return Files.getLastModifiedTime(candidate).toMillis() >= Files.getLastModifiedTime(existing).toMillis();
        } catch (IOException ignored) {
            return true;
        }
    }

    private static String numberedBaseFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".msch")) return null;

        String name = fileName.substring(0, fileName.length() - ".msch".length());
        int underscore = name.lastIndexOf('_');
        if (underscore <= 0 || underscore == name.length() - 1) return null;

        for (int i = underscore + 1; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) return null;
        }

        return name.substring(0, underscore) + ".msch";
    }

    private static List<Path> collectSchematics(Path root) throws IOException {
        if (!Files.exists(root)) return Collections.emptyList();
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".msch"))
                    .filter(path -> !containsSegment(root, path, ".git"))
                    .forEach(files::add);
        }
        return files;
    }

    private static Path uniqueTarget(Path folder, String fileName, Path source) throws IOException {
        Files.createDirectories(folder);
        Path target = folder.resolve(fileName);
        if (source != null && source.equals(target)) return target;
        if (!Files.exists(target)) return target;

        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }

        int index = 1;
        do {
            target = folder.resolve(base + "_" + index + ext);
            index++;
        } while (Files.exists(target) && (source == null || !source.equals(target)));
        return target;
    }

    private static boolean isInFolder(Path root, Path file, String folderName) {
        Path relative = root.relativize(file);
        return relative.getNameCount() > 1 && relative.getName(0).toString().equals(folderName);
    }

    private static boolean containsSegment(Path root, Path file, String segment) {
        Path relative = root.relativize(file);
        for (Path part : relative) {
            if (part.toString().equals(segment)) return true;
        }
        return false;
    }

    private static void removeEmptySchempackFolders(Path root) throws IOException {
        File[] folders = root.toFile().listFiles(file -> file.isDirectory() && file.getName().startsWith(ACTIVE_PREFIX));
        if (folders == null) return;
        for (File folder : folders) {
            String[] children = folder.list();
            if (children != null && children.length == 0) {
                Files.deleteIfExists(folder.toPath());
            }
        }
    }

    public static final class LayoutResult {
        public final int movedFiles;
        public final int includedFiles;
        public final Set<String> filteredNames;

        private LayoutResult(int movedFiles, int includedFiles, Set<String> filteredNames) {
            this.movedFiles = movedFiles;
            this.includedFiles = includedFiles;
            this.filteredNames = new HashSet<>(filteredNames);
        }
    }
}
