package com.carevoice.documentation.application

import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * Use case: username + password in, access token out (`POST /v1/auth/token`).
 *
 * No user enumeration: an unknown user and a wrong password give the same [ApplicationError.Unauthorized], and both
 * paths run exactly one bcrypt verification (against [PasswordHasher.dummyHash] when the user does not exist), so the
 * response time does not reveal which usernames exist either.
 *
 * bcrypt at cost 12 is deliberately slow CPU work (~250 ms), so it runs on [cpuDispatcher] (Dispatchers.Default),
 * never on an event-loop or IO thread.
 */
class AuthenticateUser(
    private val users: UserRepository,
    private val hasher: PasswordHasher,
    private val tokens: TokenIssuer,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val log = LoggerFactory.getLogger(AuthenticateUser::class.java)

    /** Checks the credentials and returns a signed token. Every failure throws the same 401 error. */
    suspend fun execute(username: String, password: String): IssuedToken {
        if (username.isBlank() || password.isEmpty() || username.length > MAX_LENGTH || password.length > MAX_LENGTH) {
            // Still pay for one hash so even malformed attempts take the usual time.
            withContext(cpuDispatcher) { hasher.verify(password.take(MAX_LENGTH), hasher.dummyHash) }
            loginFailed("malformed-credentials")
            throw ApplicationError.Unauthorized(INVALID_CREDENTIALS)
        }
        val user = users.findByUsername(username)
        val valid = withContext(cpuDispatcher) { hasher.verify(password, user?.passwordHash ?: hasher.dummyHash) }
        if (user == null) {
            // The submitted name is not logged: an unknown "username" is often a password typed into the wrong field.
            loginFailed("unknown-user")
            throw ApplicationError.Unauthorized(INVALID_CREDENTIALS)
        }
        if (!valid) {
            loginFailed(
                "wrong-password",
                "userId" to user.username,
                "tenantId" to user.tenantId,
                "wardId" to user.wardId,
            )
            throw ApplicationError.Unauthorized(INVALID_CREDENTIALS)
        }
        log.event(
            Level.INFO,
            LogType.BUSINESS,
            "Login succeeded",
            "userId" to user.username,
            "tenantId" to user.tenantId,
            "wardId" to user.wardId,
            "role" to user.role.name,
        )
        return tokens.issue(user)
    }

    /** INFO like every auth attempt; a burst is found by counting `reason`. The reason stays internal. */
    private fun loginFailed(reason: String, vararg fields: Pair<String, Any?>) {
        log.event(Level.INFO, LogType.BUSINESS, "Login failed", "reason" to reason, *fields)
    }

    companion object {
        const val INVALID_CREDENTIALS = "Invalid username or password."

        /** bcrypt only uses the first 72 bytes; longer input is refused rather than silently truncated. */
        const val MAX_LENGTH = 72
    }
}
