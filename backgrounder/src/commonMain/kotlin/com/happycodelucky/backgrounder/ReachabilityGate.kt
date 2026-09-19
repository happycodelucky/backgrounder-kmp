package com.happycodelucky.backgrounder

import com.happycodelucky.reachable.Reachability
import com.happycodelucky.reachable.ReachabilityStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pre-execution network gate that holds a worker dispatch for a short window
 * until [Reachability] reports the requirement is satisfied.
 *
 * Closes the iOS / macOS gap where Apple's background-task primitives either
 * advisory-honour or entirely ignore `WorkConstraints.networkRequired` — and
 * the JVM gap, where no OS constraint concept exists at all. The
 * gate is invisible to user worker code — schedulers / dispatchers call
 * [awaitReachable] between the OS firing the worker and `worker.execute(...)`
 * being invoked. On timeout the caller short-circuits to [WorkResult.Retry]
 * so the scheduler reschedules per the request's `BackoffPolicy`.
 *
 * Android does **not** use this class. Jetpack `WorkManager` natively refuses
 * to dispatch a worker whose `Constraints.networkType` is unmet; the OS holds
 * the worker for us. Carrying the gate on Android would be both wasted work
 * and a behaviour drift versus the WorkManager contract.
 *
 * **Wait window.** Call sites pass the **raw** per-invocation budget
 * (`PlatformCapabilities.maxExecutionTime`); [awaitReachable] owns the single
 * `min(5.seconds, budget / 4)` derivation. Quartering keeps the gate from
 * burning more than a quarter of a `BGAppRefreshTask`'s ~30-second runway, and
 * the 5-second cap ([MAX_WAIT]) bounds the long-budget cases — `BGProcessingTask`
 * (several minutes), the in-process foreground feed and the JVM scheduler (both
 * `Duration.INFINITE`). Callers must **not** pre-quarter the budget before
 * passing it: doing so quarters twice and collapses the real wait to a fraction
 * of what's documented (see LESSONS.md B-028).
 *
 * Concurrency: [awaitReachable] is `suspend`-safe and respects upstream
 * coroutine cancellation. `withTimeoutOrNull` propagates the test scheduler's
 * virtual time under `runTest`, so unit tests need no clock injection.
 */
internal class ReachabilityGate(
    private val reachability: Reachability,
) {
    /**
     * Outcome of a single gate call. Sealed (CLAUDE.md §3) so callers can
     * distinguish "no wait needed" from "we waited and timed out" for
     * structured logging / metrics without re-deriving from a `Boolean`.
     */
    internal sealed interface GateResult {
        /** The request didn't require a network — gate short-circuited without observing reachability. */
        public data object NotRequired : GateResult

        /** Network is reachable (and matches the metering requirement, where applicable). */
        public data object Met : GateResult

        /**
         * Budget exhausted without the requirement being met. Caller should map to [WorkResult.Retry].
         *
         * [waited] is the effective wait window the gate held the dispatch for
         * before giving up — `min(MAX_WAIT, budget / 4)`. Callers surface it in
         * `DeferralReason.ReachabilityTimeout.waited` so observers see the real
         * hold time, not the raw execution budget.
         */
        public data class TimedOut(public val waited: Duration) : GateResult
    }

    /**
     * Suspend until [requirement] is satisfied, or until the wait window
     * derived from [budget] elapses.
     *
     * [budget] is the **raw** per-invocation execution budget
     * (`PlatformCapabilities.maxExecutionTime`); the gate derives its own wait
     * window as `min(MAX_WAIT, budget / 4)`. Do not pre-quarter — see the class
     * KDoc and LESSONS.md B-028.
     *
     * `NetworkRequirement.None` returns [GateResult.NotRequired] without
     * touching the reachability flow — zero allocation on the hot path for
     * the common no-constraint case.
     *
     * `NetworkRequirement.Unmetered` is honoured against
     * `ReachabilityStatus.isDataMetered == false` — wifi or ethernet only.
     * An iPhone on cellular hotspot has `isReachable = true, isDataMetered = true`;
     * the `Unmetered` gate correctly keeps waiting in that case. Fidelity
     * improvement over the legacy iOS behaviour of downgrading `Unmetered`
     * to `Any`.
     */
    suspend fun awaitReachable(
        requirement: NetworkRequirement,
        budget: Duration,
    ): GateResult {
        if (requirement == NetworkRequirement.None) return GateResult.NotRequired
        if (matches(requirement, reachability.status.value)) return GateResult.Met
        val wait = minOf(MAX_WAIT, budget / 4)
        val result =
            withTimeoutOrNull(wait) {
                reachability.status.first { matches(requirement, it) }
            }
        return if (result != null) GateResult.Met else GateResult.TimedOut(wait)
    }

    private fun matches(
        requirement: NetworkRequirement,
        status: ReachabilityStatus,
    ): Boolean {
        if (!status.isReachable) return false
        return when (requirement) {
            NetworkRequirement.None -> true
            NetworkRequirement.Any -> true
            NetworkRequirement.Unmetered -> !status.isDataMetered
        }
    }

    internal companion object {
        /** Hard cap on the gate wait — never holds a worker longer than this regardless of budget. */
        internal val MAX_WAIT: Duration = 5.seconds
    }
}
