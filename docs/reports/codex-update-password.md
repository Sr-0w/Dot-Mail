# Password reauthorization

AccountRepository.updatePassword(accountId, password): VerifyResult verifies both IMAP and SMTP before replacing the password/app password through CredentialVault. Only a successful encrypted write clears syncError/authError and requests sync. Failed verification leaves the prior secret and error untouched. Missing accounts, blank input, and OAuth accounts have safe failure results; OAuth continues through browser reauthorization. The save is serialized against sync, rejects account/credential changes made during verification, and does not recreate removed accounts. Verification now propagates coroutine cancellation.

Validation: scripts/gw :data:testDebugUnitTest passed, including GreenMail coverage for wrong password, IMAP success with SMTP unavailable, successful replacement + auth-error clearing, missing accounts, blank input and OAuth refusal. No schema/dependency change or phone access.
