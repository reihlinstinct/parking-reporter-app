package io.github.reihlinstinct.reporter.municipal

/** Inactive login-only CLI for a separately authorized CI VPN diagnostic.
 * This class never accepts a report/contact/photo or a caller-selected endpoint.
 * Login is read-only with respect to municipal cases; success is NOT write proof.
 */
object ApiVpnLoginProbe {
    fun probe(client: MunicipalClient): Boolean = client.login().isNotBlank()

    @JvmStatic fun main(args: Array<String>) {
        val ok = try {
            require(args.isEmpty())
            require(System.getenv("API_VPN_PROBE_CONFIRM") == "READ_ONLY_LOGIN")
            fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
                ?: error("missing private configuration")
            probe(OkHttpMunicipalClient(env("J106_SUBSCRIPTION_KEY"),
                env("J106_APP_USERNAME"), env("J106_APP_PASSWORD")))
        } catch (_: Throwable) { false }
        // Never print a token, exception, endpoint, body or account identifier.
        println(if (ok) "API_VPN_LOGIN_OK_NO_CASE_WRITES" else "API_VPN_LOGIN_BLOCKED_NO_CASE_WRITES")
        if (!ok) kotlin.system.exitProcess(1)
    }
}
