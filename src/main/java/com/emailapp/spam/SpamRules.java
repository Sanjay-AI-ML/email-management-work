
package com.emailapp.spam;

public final class SpamRules {

    private SpamRules() {
        // Prevent object creation
    }

    public static final int BLOCKED_SENDER = 50;
    public static final int SUSPICIOUS_PHRASE = 20;
    public static final int MANY_LINKS = 15;
    public static final int URGENT_SUBJECT = 10;

    public static final int SPAM_THRESHOLD = 50;
}
