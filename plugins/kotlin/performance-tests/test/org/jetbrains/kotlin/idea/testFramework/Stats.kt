// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.testFramework

import org.jetbrains.kotlin.idea.perf.profilers.ProfilerConfig
import org.jetbrains.kotlin.idea.perf.util.*
import org.jetbrains.kotlin.util.PerformanceCounter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.*
import kotlin.system.measureTimeMillis

class Stats(
    val name: String = "",
    val outputConfig: OutputConfig = OutputConfig(),
    val profilerConfig: ProfilerConfig = ProfilerConfig()
) : AutoCloseable {

    private val perfTestRawDataMs = mutableListOf<Long>()
    private var metric: Metric? = null

    init {
        PerformanceCounter.setTimeCounterEnabled(true)
    }
    private fun calcAndProcessMetrics(id: String, statInfosArray: Array<StatInfos>, rawMetricChildren: MutableList<Metric>) {
        val timingsMs = toTimingsMs(statInfosArray)

        val calcMean = calcMean(timingsMs)

        val metricChildren = mutableListOf<Metric>()
        val hasError = if (rawMetricChildren.any { it.hasError == true }) true else null
        metric = Metric(
            id, metricValue = calcMean.mean.toLong(), metricError = calcMean.stdDev.toLong(),
            hasError = hasError, metrics = metricChildren
        )
        // TODO:

        metricChildren.add(
            Metric(
                "_value", metricValue = calcMean.mean.toLong(),
                hasError = hasError,
                metricError = calcMean.stdDev.toLong(),
                rawMetrics = rawMetricChildren
            )
        )
        metricChildren.add(Metric("mean", metricValue = calcMean.mean.toLong()))
        // keep geomMean for bwc
        metricChildren.add(Metric(GEOM_MEAN, metricValue = calcMean.geomMean.toLong()))
        metricChildren.add(Metric("stdDev", metricValue = calcMean.stdDev.toLong()))

        statInfosArray.filterNotNull()
            .map { it.keys }
            .fold(mutableSetOf<String>()) { acc, keys -> acc.addAll(keys); acc }
            .filter { it != TEST_KEY && it != ERROR_KEY }
            .sorted().forEach { perfCounterName ->
                val values = statInfosArray.map { (it?.get(perfCounterName) as? Long) ?: 0L }.toLongArray()
                val statInfoMean = calcMean(values)

                val n = "$id : $perfCounterName"
                val mean = statInfoMean.mean.toLong()

                val shortName = if (perfCounterName.endsWith(": time")) n.removeSuffix(": time") else null
                val metricShortName = if (perfCounterName.endsWith(": time")) perfCounterName.removeSuffix(": time") else perfCounterName

                metricChildren.add(Metric(": $metricShortName", metricValue = mean))

                TeamCity.test(shortName, durationMs = mean) {}
            }

        perfTestRawDataMs.addAll(timingsMs.toList())
        metric!!.writeTeamCityStats(name)
    }

    private fun toTimingsMs(statInfosArray: Array<StatInfos>) =
        statInfosArray.map { info -> info?.let { it[TEST_KEY] as? Long }?.nsToMs ?: 0L }.toLongArray()

    private fun calcMean(statInfosArray: Array<StatInfos>): Mean =
        calcMean(toTimingsMs(statInfosArray))

    internal fun stabilityPercentage(statInfosArray: Array<StatInfos>): Int = stabilityPercentage(calcMean(statInfosArray))

    private fun stabilityPercentage(mean: Mean): Int =
        round(mean.stdDev * 100.0 / mean.mean).toInt()

    internal fun convertStatInfoIntoMetrics(
        prefix: String,
        statInfoArray: Array<StatInfos>,
        printOnlyErrors: Boolean = false,
        metricChildren: MutableList<Metric>,
        warmUp: Boolean = false,
        attemptFn: (Int) -> String = { attempt -> "#$attempt" }
    ) {
        for (statInfoIndex in statInfoArray.withIndex()) {
            val attempt = statInfoIndex.index
            val statInfo = statInfoIndex.value ?: continue
            val attemptString = attemptFn(attempt)
            val s = "$prefix $attemptString"
            val n = "$name: $s"
            val childrenMetrics = mutableListOf<Metric>()

            val t = statInfo[ERROR_KEY] as? Throwable
            if (t != null) {
                TeamCity.test(n, errors = listOf(t)) {}
                //logMessage { "metricChildren.add(Metric('$attemptString', value = null, hasError = true))" }
                metricChildren.add(Metric(attemptString, metricValue = null, hasError = true))
            } else if (!printOnlyErrors) {
                val durationMs = (statInfo[TEST_KEY] as Long).nsToMs
                TeamCity.test(n, durationMs = durationMs, includeStats = false) {
                    for ((k, v) in statInfo) {
                        if (k == TEST_KEY) continue
                        (v as? Number)?.let {
                            childrenMetrics.add(Metric(k, metricValue = v.toLong()))
                            //TeamCity.metadata(n, k, it)
                        }
                    }
                }
                metricChildren.add(
                    Metric(
                        metricName = attemptString,
                        index = attempt,
                        warmUp = if (warmUp) true else null,
                        metricValue = durationMs,
                        metrics = childrenMetrics
                    )
                )
            }
        }
    }

    fun printWarmUpTimings(
        prefix: String,
        warmUpStatInfosArray: Array<StatInfos>,
        metricChildren: MutableList<Metric>
    ) = convertStatInfoIntoMetrics(prefix, warmUpStatInfosArray, warmUp = true, metricChildren = metricChildren) { attempt -> "warm-up #$attempt" }

    fun processTimings(
        prefix: String,
        statInfosArray: Array<StatInfos>,
        metricChildren: MutableList<Metric>
    ) {
        try {
            convertStatInfoIntoMetrics(prefix, statInfosArray, metricChildren = metricChildren)
        } finally {
            calcAndProcessMetrics(prefix, statInfosArray, metricChildren)
        }
    }

    override fun close() {
        flush()
    }

    internal fun flush() {
        flushTeamCityStats()

        try {
            toBenchmark()?.let {
                outputConfig.write(it)
            }
        } finally {
            perfTestRawDataMs.clear()
            metric = null
        }
    }

    private fun toBenchmark(): Benchmark? {
        return metric?.let {
            BENCHMARK_STUB.copy(
                benchmark = name,
                name = it.metricName,
                metricValue = it.metricValue,
                metricError = it.metricError,
                metrics = it.metrics ?: emptyList()
            )
        }
    }

    private fun flushTeamCityStats(consumer: (String, Long) -> Unit = { propertyName, value ->
        TeamCity.statValue(propertyName, value)
    }) {
        if (perfTestRawDataMs.isNotEmpty()) {
            val geomMeanMs = geomMean(perfTestRawDataMs.toList()).toLong()
            Metric(GEOM_MEAN, metricValue = geomMeanMs).writeTeamCityStats(name, consumer = consumer)
        }
    }

    companion object {
        const val TEST_KEY = "test"
        const val ERROR_KEY = "error"

        const val WARM_UP = "warm-up"
        const val GEOM_MEAN = "geomMean"

        internal val extraMetricNames = setOf("", "_value", GEOM_MEAN, "mean", "stdDev")

        @JvmStatic
        private val BENCHMARK_STUB by lazy {
            val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
            simpleDateFormat.timeZone = TimeZone.getTimeZone("UTC")

            var buildId: Int? = null
            var agentName: String? = null
            var buildBranch: String? = null
            var commit: String? = null

            System.getenv("TEAMCITY_BUILD_PROPERTIES_FILE")?.let { teamcityConfig ->
                val buildProperties = Properties()
                buildProperties.load(FileInputStream(teamcityConfig))

                buildId = buildProperties["teamcity.build.id"]?.toString()?.toInt()
                agentName = buildProperties["agent.name"]?.toString()
                buildBranch = (buildProperties["teamcity.build.branch"] ?: System.getProperty("teamcity.build.branch"))?.toString()
                commit = (buildProperties["build.vcs.number"] ?: System.getProperty("build.vcs.number"))?.toString()
            }

            buildBranch = buildBranch?.takeIf { it != "<default>" } ?: runGit("branch", "--show-current")
            if (buildBranch == null || buildBranch == "<default>") {
                val gitPath = System.getenv("TEAMCITY_GIT_PATH") ?: "git"
                val processBuilder = ProcessBuilder(gitPath, "branch", "--show-current")
                val process = processBuilder.start()
                var line: String?
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    line = reader.readLine()
                }
                if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    buildBranch = line
                }
            }
            check(buildBranch != null && buildBranch != "<default>") { "buildBranch='$buildBranch' is expected to be set by TeamCity" }
            commit = commit ?: runGit("rev-parse", "HEAD")

            Benchmark(
                agentName = agentName,
                buildBranch = buildBranch,
                commit = commit,
                buildId = buildId,
                benchmark = "",
                buildTimestamp = simpleDateFormat.format(Date()),
                metrics = emptyList()
            )
        }

        inline fun runAndMeasure(note: String, block: () -> Unit) {
            val openProjectMillis = measureTimeMillis {
                block()
            }
            logMessage { "$note took $openProjectMillis ms" }
        }
    }

}

internal fun calcMean(values: LongArray): Mean {
    val mean = values.average()

    val stdDev = if (values.size > 1) (sqrt(
        values.fold(0.0,
                    { accumulator, next -> accumulator + (1.0 * (next - mean)).pow(2) })
    ) / (values.size - 1))
    else 0.0

    val geomMean = geomMean(values.toList())

    return Mean(mean, stdDev, geomMean)
}

private fun geomMean(data: List<Long>) = exp(data.fold(0.0, { mul, next -> mul + ln(1.0 * next) }) / data.size)

internal data class Mean(val mean: Double, val stdDev: Double, val geomMean: Double)