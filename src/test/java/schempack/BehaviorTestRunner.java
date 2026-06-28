package schempack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.zip.DeflaterOutputStream;

public final class BehaviorTestRunner {
    private static int assertions;

    public static void main(String[] args) {
        assertMode(true, SchemSyncPolicy.Mode.AUTO_PUSH_ONLY);
        assertMode(true, false, SchemSyncPolicy.Mode.AUTO_PUSH_ONLY);
        assertMode(true, true, SchemSyncPolicy.Mode.AUTO_PUSH_PULL);
        assertMode(false, SchemSyncPolicy.Mode.MANUAL_CONTROL);
        assertMode(false, true, SchemSyncPolicy.Mode.MANUAL_CONTROL);

        assertAllowed(SchemSyncPolicy.Mode.AUTO_PUSH_ONLY, SchemSyncPolicy.Action.COMPARE, true);
        assertAllowed(SchemSyncPolicy.Mode.AUTO_PUSH_ONLY, SchemSyncPolicy.Action.BACKUP_LOCAL, true);
        assertAllowed(SchemSyncPolicy.Mode.AUTO_PUSH_ONLY, SchemSyncPolicy.Action.PUSH_LOCAL, false);
        assertAllowed(SchemSyncPolicy.Mode.AUTO_PUSH_ONLY, SchemSyncPolicy.Action.PULL_GITHUB, false);
        assertAllowed(SchemSyncPolicy.Mode.AUTO_PUSH_ONLY, SchemSyncPolicy.Action.FORCE_PUSH_LOCAL, true);

        assertAllowed(SchemSyncPolicy.Mode.AUTO_PUSH_PULL, SchemSyncPolicy.Action.COMPARE, true);
        assertAllowed(SchemSyncPolicy.Mode.AUTO_PUSH_PULL, SchemSyncPolicy.Action.BACKUP_LOCAL, true);
        assertAllowed(SchemSyncPolicy.Mode.AUTO_PUSH_PULL, SchemSyncPolicy.Action.PUSH_LOCAL, false);
        assertAllowed(SchemSyncPolicy.Mode.AUTO_PUSH_PULL, SchemSyncPolicy.Action.PULL_GITHUB, false);
        assertAllowed(SchemSyncPolicy.Mode.AUTO_PUSH_PULL, SchemSyncPolicy.Action.FORCE_PUSH_LOCAL, true);

        assertAllowed(SchemSyncPolicy.Mode.MANUAL_CONTROL, SchemSyncPolicy.Action.COMPARE, true);
        assertAllowed(SchemSyncPolicy.Mode.MANUAL_CONTROL, SchemSyncPolicy.Action.BACKUP_LOCAL, false);
        assertAllowed(SchemSyncPolicy.Mode.MANUAL_CONTROL, SchemSyncPolicy.Action.PUSH_LOCAL, true);
        assertAllowed(SchemSyncPolicy.Mode.MANUAL_CONTROL, SchemSyncPolicy.Action.PULL_GITHUB, true);
        assertAllowed(SchemSyncPolicy.Mode.MANUAL_CONTROL, SchemSyncPolicy.Action.FORCE_PUSH_LOCAL, true);

        assertBlockedMessage(SchemSyncPolicy.Mode.AUTO_PUSH_ONLY, SchemSyncPolicy.Action.PULL_GITHUB, "Auto Push off");
        assertBlockedMessage(SchemSyncPolicy.Mode.MANUAL_CONTROL, SchemSyncPolicy.Action.BACKUP_LOCAL, "Auto Push on");

        assertAutoDecision(SchemSyncPolicy.ChangeState.CLEAN, SchemSyncPolicy.AutoDecision.IDLE);
        assertAutoDecision(SchemSyncPolicy.ChangeState.LOCAL_ONLY, SchemSyncPolicy.AutoDecision.BACKUP_LOCAL);
        assertAutoDecision(SchemSyncPolicy.ChangeState.GITHUB_ONLY, false, SchemSyncPolicy.AutoDecision.TURN_OFF_AUTO_PUSH);
        assertAutoDecision(SchemSyncPolicy.ChangeState.BOTH_SIDES, false, SchemSyncPolicy.AutoDecision.TURN_OFF_AUTO_PUSH);
        assertAutoDecision(SchemSyncPolicy.ChangeState.GITHUB_ONLY, true, SchemSyncPolicy.AutoDecision.PULL_REMOTE);
        assertAutoDecision(SchemSyncPolicy.ChangeState.BOTH_SIDES, true, SchemSyncPolicy.AutoDecision.MERGE_THEN_PUSH);

        assertManualGuidance(SchemSyncPolicy.ChangeState.LOCAL_ONLY, "Pull & Merge will push");
        assertManualGuidance(SchemSyncPolicy.ChangeState.GITHUB_ONLY, "Use Pull & Merge");
        assertManualGuidance(SchemSyncPolicy.ChangeState.BOTH_SIDES, "Use Pull & Merge");

        assertLayoutMovesConfiguredFilterToFolderZero();
        assertLayoutManualMoveOutOfFolderZeroUnfiltersToRoot();
        assertLayoutMovesOldActiveFolderSchemsToRoot();
        assertLayoutCollapsesNumberedEditCopies();
        assertLayoutKeepsRealNumberedSchems();

        System.out.println("Behavior tests passed (" + assertions + " assertions).");
    }

    private static void assertMode(boolean autoSyncEnabled, SchemSyncPolicy.Mode expected) {
        check(SchemSyncPolicy.modeFor(autoSyncEnabled) == expected,
                "Expected autoSyncEnabled=" + autoSyncEnabled + " to map to " + expected);
    }

    private static void assertMode(boolean autoPushEnabled, boolean autoPullEnabled, SchemSyncPolicy.Mode expected) {
        check(SchemSyncPolicy.modeFor(autoPushEnabled, autoPullEnabled) == expected,
                "Expected autoPushEnabled=" + autoPushEnabled + ", autoPullEnabled=" + autoPullEnabled + " to map to " + expected);
    }

    private static void assertAllowed(SchemSyncPolicy.Mode mode, SchemSyncPolicy.Action action, boolean expected) {
        check(SchemSyncPolicy.isAllowed(mode, action) == expected,
                "Expected " + action + " allowed in " + mode + " to be " + expected);
    }

    private static void assertBlockedMessage(SchemSyncPolicy.Mode mode, SchemSyncPolicy.Action action, String expectedText) {
        String message = SchemSyncPolicy.blockedReason(mode, action);
        check(message.contains(expectedText),
                "Expected blocked message for " + action + " in " + mode + " to contain " + expectedText + ", got: " + message);
    }

    private static void assertAutoDecision(SchemSyncPolicy.ChangeState state, SchemSyncPolicy.AutoDecision expected) {
        check(SchemSyncPolicy.autoDecision(state) == expected,
                "Expected auto decision for " + state + " to be " + expected);
    }

    private static void assertAutoDecision(SchemSyncPolicy.ChangeState state, boolean autoPullEnabled, SchemSyncPolicy.AutoDecision expected) {
        check(SchemSyncPolicy.autoDecision(state, autoPullEnabled) == expected,
                "Expected auto decision for " + state + " with autoPullEnabled=" + autoPullEnabled + " to be " + expected);
    }

    private static void assertManualGuidance(SchemSyncPolicy.ChangeState state, String expectedText) {
        String message = SchemSyncPolicy.manualGuidance(state);
        check(message.contains(expectedText),
                "Expected manual guidance for " + state + " to contain " + expectedText + ", got: " + message);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void assertLayoutMovesConfiguredFilterToFolderZero() {
        Path root = tempRoot();
        try {
            Files.writeString(root.resolve("alpha.msch"), "x");
            Set<String> filtered = new HashSet<>();
            filtered.add("alpha.msch");

            SchemPackLayout.LayoutResult result = SchemPackLayout.normalize(root.toFile(), filtered);

            check(Files.exists(root.resolve("schempack-0").resolve("alpha.msch")), "Expected filtered file in schempack-0");
            check(result.filteredNames.contains("alpha.msch"), "Expected alpha.msch in effective filtered names");
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            deleteTree(root);
        }
    }

    private static void assertLayoutManualMoveOutOfFolderZeroUnfiltersToRoot() {
        Path root = tempRoot();
        try {
            Path filtered = root.resolve("schempack-0");
            Files.createDirectories(filtered);
            Files.writeString(filtered.resolve("beta.msch"), "x");

            SchemPackLayout.moveToFilterState(root.toFile(), filtered.resolve("beta.msch").toFile(), false);
            SchemPackLayout.LayoutResult result = SchemPackLayout.normalize(root.toFile(), Set.of());

            check(Files.exists(root.resolve("beta.msch")), "Expected unfiltered file in schematic root");
            check(!Files.exists(filtered.resolve("beta.msch")), "Expected unfiltered file removed from schempack-0");
            check(!result.filteredNames.contains("beta.msch"), "Expected beta.msch to be unfiltered");
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            deleteTree(root);
        }
    }

    private static void assertLayoutMovesOldActiveFolderSchemsToRoot() {
        Path root = tempRoot();
        try {
            Path active = root.resolve("schempack-1");
            Files.createDirectories(active);
            Files.writeString(active.resolve("legacy.msch"), "x");

            SchemPackLayout.normalize(root.toFile(), Set.of());

            check(Files.exists(root.resolve("legacy.msch")), "Expected old active folder schematic moved to root");
            check(!Files.exists(active.resolve("legacy.msch")), "Expected old active folder copy removed");
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            deleteTree(root);
        }
    }

    private static void assertLayoutCollapsesNumberedEditCopies() {
        Path root = tempRoot();
        try {
            Path original = root.resolve("gamma.msch");
            Path edited = root.resolve("gamma_1.msch");
            writeFakeSchematic(original, "gamma", "old");
            writeFakeSchematic(edited, "gamma", "new");
            byte[] editedBytes = Files.readAllBytes(edited);
            Files.setLastModifiedTime(original, FileTime.fromMillis(1000));
            Files.setLastModifiedTime(edited, FileTime.fromMillis(2000));

            SchemPackLayout.normalize(root.toFile(), Set.of());

            check(Files.exists(root.resolve("gamma.msch")), "Expected edited copy to replace gamma.msch");
            check(!Files.exists(root.resolve("gamma_1.msch")), "Expected gamma_1.msch to be removed");
            check(Arrays.equals(Files.readAllBytes(root.resolve("gamma.msch")), editedBytes), "Expected gamma.msch to contain edited content");
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            deleteTree(root);
        }
    }

    private static void assertLayoutKeepsRealNumberedSchems() {
        Path root = tempRoot();
        try {
            Path original = root.resolve("delta.msch");
            Path numbered = root.resolve("delta_1.msch");
            writeFakeSchematic(original, "delta", "base");
            writeFakeSchematic(numbered, "delta_1", "real numbered");

            SchemPackLayout.normalize(root.toFile(), Set.of());

            check(Files.exists(root.resolve("delta.msch")), "Expected delta.msch to remain");
            check(Files.exists(root.resolve("delta_1.msch")), "Expected real delta_1.msch to remain");
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            deleteTree(root);
        }
    }

    private static void writeFakeSchematic(Path file, String name, String marker) throws Exception {
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        try (DataOutputStream body = new DataOutputStream(new DeflaterOutputStream(bodyBytes))) {
            body.writeShort(1);
            body.writeShort(1);
            body.writeByte(2);
            body.writeUTF("name");
            body.writeUTF(name);
            body.writeUTF("description");
            body.writeUTF(marker);
            body.writeByte(0);
            body.writeInt(0);
        }

        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeByte('m');
            out.writeByte('s');
            out.writeByte('c');
            out.writeByte('h');
            out.writeByte(1);
            out.write(bodyBytes.toByteArray());
        }
    }

    private static Path tempRoot() {
        try {
            return Files.createTempDirectory("schempack-behavior-");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void deleteTree(Path root) {
        try {
            if (root == null || !Files.exists(root)) return;
            try (var stream = Files.walk(root)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }
}
