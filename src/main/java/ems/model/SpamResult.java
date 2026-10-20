package ems.model;

import java.util.List;

/**
 * The local detector's result. The score is a rule score, not a probability.
 * Gmail's own SPAM label remains separate from this classification.
 */
public final class SpamResult {
    private final int score;
    private final boolean suspectedSpam;
    private final List<String> reasons;

    public SpamResult(int score, boolean suspectedSpam, List<String> reasons) {
        this.score = score;
        this.suspectedSpam = suspectedSpam;
        this.reasons = List.copyOf(reasons);
    }

    public int getScore() {
        return score;
    }

    public boolean isSuspectedSpam() {
        return suspectedSpam;
    }

    public List<String> getReasons() {
        return reasons;
    }
}
