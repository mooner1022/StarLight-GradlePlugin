package dev.mooner.slplugin.tasks

import dev.mooner.slplugin.manifest.ManifestIssue
import dev.mooner.slplugin.manifest.Severity
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger

internal object ManifestReporting {

    /** Logs warnings and fails with every error at once, so users can fix them in one go. */
    fun reportOrThrow(logger: Logger, source: String, issues: Collection<ManifestIssue>) {
        issues.filter { it.severity == Severity.WARNING }
            .forEach { logger.warn("w: [StarLight] ${it.message}") }
        val errors = issues.filter { it.severity == Severity.ERROR }
        if (errors.isNotEmpty())
            throw GradleException(
                "StarLight plugin validation failed for $source:\n" +
                    errors.joinToString("\n") { "  - ${it.message}" }
            )
    }
}
