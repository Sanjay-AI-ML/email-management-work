package ems.check;

import org.junit.jupiter.api.Test;

/** Runs the teammate's standalone contract checks in every Maven/CI test run. */
class MemberTwoContractTest {
    @Test void memberTwoContract() throws Exception {
        MemberTwoChecks.main(new String[0]);
    }
}
