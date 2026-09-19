package com.happycodelucky.backgrounder

/**
 * Per-invocation state handed to a [BackgroundWorker]'s `execute()` call.
 *
 * Cancellation flows through coroutine cancellation — there is *no separate
 * scope field*. To launch sibling work inside a worker, use [coroutineScope] /
 * [supervisorScope] within `execute()`.
 *
 * Constructed by the library; not user-instantiable.
 */
public class WorkerContext internal constructor(
    /** The stable task id this invocation was dispatched for. */
    public val taskId: String,
    /** 0-based attempt counter. Increments on each [WorkResult.Retry] cycle. */
    public val attempt: Int,
    /** Key/value bag supplied at schedule time via [WorkRequest.input]. */
    public val input: WorkInput,
    /** Runtime constraints and capabilities the platform offers this invocation. */
    public val capabilities: PlatformCapabilities,
)
