package com.happycodelucky.backgrounder.gradle

import com.happycodelucky.backgrounder.BGTaskSchedulerPermittedIdentifier

@BGTaskSchedulerPermittedIdentifier const val TOP_LEVEL_ID = "dev.example.fixtures.top-level"

object FixtureIds {
    private const val PREFIX = "dev.example.fixtures"

    @BGTaskSchedulerPermittedIdentifier const val SYNC = "$PREFIX.sync"

    @BGTaskSchedulerPermittedIdentifier const val TICK = "$PREFIX.background-tick"

    const val NOT_ANNOTATED = "$PREFIX.ignored"
}

class FixtureWorker {
    companion object {
        @BGTaskSchedulerPermittedIdentifier const val UPLOAD = "dev.example.fixtures.upload"

        /** Non-const: the scanner must report this rather than silently skip it. */
        @BGTaskSchedulerPermittedIdentifier val NOT_CONST: String = "dev.example.fixtures.not-const"
    }
}
