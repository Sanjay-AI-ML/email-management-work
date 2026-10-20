package ems.gmail;

import ems.exception.AppException;

/** Separates authorization from HTTP mailbox operations and permits local-server tests. */
public interface AccessTokenProvider {
    void authorize() throws AppException;
    String accessToken() throws AppException;
    String refreshAccessToken() throws AppException;
    void disconnect();
}
