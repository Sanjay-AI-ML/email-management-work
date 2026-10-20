
package com.emailapp.model;

public class Account {

    private long accountId;
    private String emailAddress;

    public Account(long accountId, String emailAddress) {
        this.accountId = accountId;
        this.emailAddress = emailAddress;
    }

    public long getAccountId() {
        return accountId;
    }

    public String getEmailAddress() {
        return emailAddress;
    }
}
