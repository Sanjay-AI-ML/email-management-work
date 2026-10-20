package ems.model;

import java.util.Objects;

/** A locally identified Gmail account. OAuth credentials are never part of this model. */
public final class Account {
    private final long accountId;
    private final String emailAddress;

    public Account(long accountId, String emailAddress) {
        this.accountId = accountId;
        this.emailAddress = Objects.requireNonNull(emailAddress, "emailAddress");
    }

    public long getAccountId() {
        return accountId;
    }

    public String getEmailAddress() {
        return emailAddress;
    }
}
