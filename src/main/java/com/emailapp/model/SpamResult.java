
package com.emailapp.model;

import java.util.List;

public class SpamResult {

    private int score;
    private boolean suspectedSpam;
    private List<String> reasons;

    public SpamResult(
            int score,
            boolean suspectedSpam,
            List<String> reasons) {

        this.score = score;
        this.suspectedSpam = suspectedSpam;
        this.reasons = reasons;
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
