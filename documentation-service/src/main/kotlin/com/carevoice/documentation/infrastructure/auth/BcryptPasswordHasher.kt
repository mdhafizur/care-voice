package com.carevoice.documentation.infrastructure.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.carevoice.documentation.application.PasswordHasher
import java.security.SecureRandom
import java.util.Base64

/**
 * Adapter: bcrypt (cost 12, docs/CONTRACTS.md, section 5). bcrypt is deliberately slow and salted, so a leaked
 * `users` table does not turn into passwords cheaply. [cost] is injectable so unit tests can use the minimum (4).
 */
class BcryptPasswordHasher(private val cost: Int = DEFAULT_COST) : PasswordHasher {
    /** Hashes with bcrypt at [cost]; a random salt is part of every hash. */
    override fun hash(password: String): String = BCrypt.withDefaults().hashToString(cost, password.toCharArray())

    /** Checks the password against a bcrypt hash; a malformed hash gives false instead of an exception. */
    override fun verify(password: String, hash: String): Boolean =
        runCatching { BCrypt.verifyer().verify(password.toCharArray(), hash.toCharArray()).verified }
            .getOrDefault(false)

    /** Same cost as real hashes, so verifying against it takes the same time as verifying a real user. */
    override val dummyHash: String by lazy {
        // 24 random bytes give a 32-character base64 password, well below bcrypt's limit of 72 bytes.
        val random = ByteArray(24).also(SecureRandom()::nextBytes)
        hash(Base64.getEncoder().encodeToString(random))
    }

    companion object {
        const val DEFAULT_COST = 12
    }
}
