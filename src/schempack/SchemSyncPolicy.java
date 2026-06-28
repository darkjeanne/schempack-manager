package schempack;

public final class SchemSyncPolicy {
    private SchemSyncPolicy() {
    }

    public enum Mode {
        MANUAL_CONTROL,
        AUTO_PUSH_ONLY,
        AUTO_PUSH_PULL
    }

    public enum Action {
        COMPARE,
        BACKUP_LOCAL,
        PUSH_LOCAL,
        PULL_GITHUB,
        FORCE_PUSH_LOCAL
    }

    public enum ChangeState {
        CLEAN,
        LOCAL_ONLY,
        GITHUB_ONLY,
        BOTH_SIDES
    }

    public enum AutoDecision {
        IDLE,
        BACKUP_LOCAL,
        PULL_REMOTE,
        MERGE_THEN_PUSH,
        TURN_OFF_AUTO_PUSH
    }

    public static Mode modeFor(boolean autoSyncEnabled) {
        return modeFor(autoSyncEnabled, false);
    }

    public static Mode modeFor(boolean autoPushEnabled, boolean autoPullEnabled) {
        if (!autoPushEnabled) return Mode.MANUAL_CONTROL;
        return autoPullEnabled ? Mode.AUTO_PUSH_PULL : Mode.AUTO_PUSH_ONLY;
    }

    public static String displayName(Mode mode) {
        switch (mode) {
            case AUTO_PUSH_ONLY:
                return "Auto push";
            case AUTO_PUSH_PULL:
                return "Auto push + pull";
            case MANUAL_CONTROL:
            default:
                return "Manual control";
        }
    }

    public static boolean isAllowed(Mode mode, Action action) {
        switch (mode) {
            case AUTO_PUSH_ONLY:
            case AUTO_PUSH_PULL:
                return action == Action.COMPARE || action == Action.BACKUP_LOCAL || action == Action.FORCE_PUSH_LOCAL;
            case MANUAL_CONTROL:
                return action == Action.COMPARE
                        || action == Action.PUSH_LOCAL
                        || action == Action.PULL_GITHUB
                        || action == Action.FORCE_PUSH_LOCAL;
            default:
                return false;
        }
    }

    public static String blockedReason(Mode mode, Action action) {
        if (isAllowed(mode, action)) return "";
        if (mode == Mode.AUTO_PUSH_ONLY || mode == Mode.AUTO_PUSH_PULL) return "Turn Auto Push off before manual GitHub actions.";
        if (action == Action.BACKUP_LOCAL) return "Turn Auto Push on to use Backup Local.";
        return "Action is not available in this mode.";
    }

    public static AutoDecision autoDecision(ChangeState state) {
        return autoDecision(state, false);
    }

    public static AutoDecision autoDecision(ChangeState state, boolean autoPullEnabled) {
        switch (state) {
            case CLEAN:
                return AutoDecision.IDLE;
            case LOCAL_ONLY:
                return AutoDecision.BACKUP_LOCAL;
            case GITHUB_ONLY:
                return autoPullEnabled ? AutoDecision.PULL_REMOTE : AutoDecision.TURN_OFF_AUTO_PUSH;
            case BOTH_SIDES:
                return autoPullEnabled ? AutoDecision.MERGE_THEN_PUSH : AutoDecision.TURN_OFF_AUTO_PUSH;
            default:
                return AutoDecision.TURN_OFF_AUTO_PUSH;
        }
    }

    public static String manualGuidance(ChangeState state) {
        switch (state) {
            case CLEAN:
                return "Local and GitHub match.";
            case LOCAL_ONLY:
                return "Local changed and GitHub did not. Pull & Merge will push local changes safely.";
            case GITHUB_ONLY:
                return "GitHub changed. Use Pull & Merge, or Force Push Local.";
            case BOTH_SIDES:
                return "Local and GitHub both changed. Use Pull & Merge, or Force Push Local.";
            default:
                return "Compare local and GitHub before choosing an action.";
        }
    }
}
