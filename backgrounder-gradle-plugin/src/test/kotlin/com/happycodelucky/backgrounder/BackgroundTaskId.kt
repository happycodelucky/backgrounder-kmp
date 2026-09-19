package com.happycodelucky.backgrounder

/** Test-local twin of the library annotation: same FQN, so the scanner's descriptor matches. */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
annotation class BackgroundTaskId
