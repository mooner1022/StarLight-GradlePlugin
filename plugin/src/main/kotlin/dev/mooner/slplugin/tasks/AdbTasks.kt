package dev.mooner.slplugin.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/** Base for tasks that talk to a device through adb. */
@DisableCachingByDefault(because = "Interacts with a device")
abstract class AdbTask : DefaultTask() {

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @get:Internal
    abstract val adbExecutable: RegularFileProperty

    /** adb serial (`adb devices`) or `host:port` for wireless debugging. Optional when exactly one device is connected. */
    @get:Input @get:Optional
    abstract val device: Property<String>

    /** Package name of the StarLight app. */
    @get:Input
    abstract val appPackage: Property<String>

    internal fun connectAdb(): Adb {
        val adb = Adb(execOperations, adbExecutable.get().asFile, device.orNull)
        adb.connectIfNeeded()
        // Wireless debugging devices can connect on their own, so always say which device is being used.
        logger.lifecycle("Using device ${adb.run("get-serialno").trim()}")
        return adb
    }

    // Launches an explicit component rather than using monkey, which aborts on devices without physical keys
    // (redroid, some emulators) and picks a random activity when there are several launcher activities.
    internal fun restartStarlight(adb: Adb) {
        val packageName = appPackage.get()
        // Resolved before force-stopping, so a missing app fails without touching the device.
        val launchers = adb.shell(
            "cmd", "package", "query-activities", "--brief",
            "-a", ACTION_MAIN, "-c", CATEGORY_LAUNCHER, packageName,
        )
        val activity = pickLauncherActivity(launchers, packageName)
            ?: throw GradleException("Could not launch $packageName. Is StarLight installed on the device?\n${launchers.trim()}")
        adb.shell("am", "force-stop", packageName)
        // adb shell joins its arguments into one shell command line, so quote the component (nested classes contain '$').
        val output = adb.shell("am", "start", "-a", ACTION_MAIN, "-c", CATEGORY_LAUNCHER, "-n", "'$activity'")
        // am start exits with 0 even when it fails.
        if (output.lineSequence().any { it.trim().startsWith("Error") })
            throw GradleException("Could not launch $activity:\n${output.trim()}")
        logger.lifecycle("Restarted $packageName")
    }

    private companion object {
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    }
}

/**
 * Picks the activity to launch from `cmd package query-activities --brief` output, whose component lines look like
 * `dev.mooner.starlight/.ui.splash.SplashActivity`. Debug builds can have more than one launcher activity
 * (LeakCanary adds its own), so the app's own activities are preferred over ones from libraries.
 */
internal fun pickLauncherActivity(queryOutput: String, packageName: String): String? {
    val components = queryOutput.lineSequence().map(String::trim).filter { it.startsWith("$packageName/") }.toList()
    return components.firstOrNull { it.startsWith("$packageName/.") || it.startsWith("$packageName/$packageName.") }
        ?: components.firstOrNull()
}

/** Pushes the variant's `.slp` into StarLight's plugin folder, choosing the split that matches the device ABI. */
@DisableCachingByDefault(because = "Pushes files to a device")
abstract class DeploySlp : AdbTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val slpDirectory: DirectoryProperty

    @get:Input
    abstract val remoteDirectory: Property<String>

    @get:Input
    abstract val restartApp: Property<Boolean>

    @TaskAction
    fun deploy() {
        val directory = slpDirectory.get().asFile
        val outputs = SlpOutputs.readFrom(directory)
        val adb = connectAdb()

        val output = outputs.outputs.singleOrNull() ?: run {
            val deviceAbis = adb.shell("getprop", "ro.product.cpu.abilist").trim().split(',').filter(String::isNotEmpty)
            deviceAbis.firstNotNullOfOrNull { abi -> outputs.outputs.find { it.abi == abi } }
                ?: outputs.outputs.find { it.abi == null }
                ?: throw GradleException(
                    "None of ${outputs.outputs.map { it.file }} matches the device ABIs $deviceAbis."
                )
        }

        val remoteDir = remoteDirectory.get().trimEnd('/')
        val remoteFile = "$remoteDir/${outputs.id}.slp"
        adb.shell("mkdir", "-p", remoteDir)
        adb.run("push", File(directory, output.file).absolutePath, remoteFile)
        logger.lifecycle("Deployed ${output.file} to $remoteFile")
        warnAboutDuplicates(adb, remoteDir, outputs.id)

        if (restartApp.get())
            restartStarlight(adb)
        else
            logger.lifecycle("Restart StarLight to load it (run restartStarlight, or set starlight { deploy { restartApp = true } }).")
    }

    /** StarLight keys plugins by id, so another file with the same id makes it undefined which one is loaded. */
    private fun warnAboutDuplicates(adb: Adb, remoteDir: String, id: String) {
        val suspicious = adb.shell("ls", remoteDir).lines().map(String::trim)
            .filter { it.endsWith(".slp") && it != "$id.slp" }
            .filter { it == "plugin.slp" || it.startsWith("$id-") || it.startsWith("$id.") }
        if (suspicious.isNotEmpty())
            logger.warn(
                "w: [StarLight] $remoteDir also contains ${suspicious.joinToString()}, which may be an older copy of '$id'. " +
                    "If two files share an id, StarLight loads only one of them. Delete the stale file."
            )
    }
}

/** Force-stops and relaunches StarLight so it loads plugin changes (StarLight has no hot reload). */
@DisableCachingByDefault(because = "Interacts with a device")
abstract class RestartStarlight : AdbTask() {

    @TaskAction
    fun restart() = restartStarlight(connectAdb())
}

/** Streams the StarLight app's logcat output until interrupted. */
@DisableCachingByDefault(because = "Interacts with a device")
abstract class StarlightLogcat : AdbTask() {

    @TaskAction
    fun logcat() {
        val adb = connectAdb()
        val packageName = appPackage.get()
        val pid = try {
            adb.shell("pidof", packageName).trim().substringBefore(' ')
        } catch (e: GradleException) {
            ""
        }
        if (pid.isEmpty())
            throw GradleException("$packageName is not running on the device. Start StarLight first (or run restartStarlight).")
        logger.lifecycle("Streaming logcat of $packageName (pid $pid). Press Ctrl+C to stop.")
        adb.stream("logcat", "--pid=$pid")
    }
}

/** Fails early with a clear message when an SDK AAR configured in `starlight { sdk { ... } }` does not exist. */
@DisableCachingByDefault(because = "Only checks that files exist")
abstract class VerifyStarlightSdk : DefaultTask() {

    @get:Internal
    abstract val pluginCore: RegularFileProperty

    @get:Internal
    abstract val configDsl: RegularFileProperty

    @TaskAction
    fun verify() {
        val missing = listOf("pluginCore" to pluginCore, "configDsl" to configDsl)
            .mapNotNull { (name, property) -> property.orNull?.asFile?.takeUnless(File::isFile)?.let { "starlight.sdk.$name: $it" } }
        if (missing.isNotEmpty())
            throw GradleException(
                "StarLight SDK files not found:\n" + missing.joinToString("\n") { "  - $it" } +
                    "\nBuild them from the StarLight repository (:PluginCore:assembleRelease, :ConfigDSL:assembleRelease) " +
                    "or copy them from another plugin project."
            )
    }
}

internal class Adb(
    private val execOperations: ExecOperations,
    private val executable: File,
    private val device: String?,
) {
    fun connectIfNeeded() {
        if (device == null || !NETWORK_ADDRESS.matches(device))
            return
        val output = run("connect", device, selectDevice = false)
        if ("connected to" !in output)
            throw GradleException("adb could not connect to $device: ${output.trim()}")
    }

    fun shell(vararg args: String): String = run("shell", *args)

    fun run(vararg args: String, selectDevice: Boolean = true): String {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result = execOperations.exec { spec ->
            spec.executable = checkedExecutable().absolutePath
            spec.args = deviceArgs(selectDevice) + args
            spec.standardOutput = stdout
            spec.errorOutput = stderr
            spec.isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            val message = (stderr.toString(Charsets.UTF_8.name()) + stdout.toString(Charsets.UTF_8.name())).trim()
            throw GradleException("adb ${args.joinToString(" ")} failed (exit code ${result.exitValue}): $message${hint(message)}")
        }
        return stdout.toString(Charsets.UTF_8.name())
    }

    /** Runs adb with its output forwarded to the console. */
    fun stream(vararg args: String) {
        execOperations.exec { spec ->
            spec.executable = checkedExecutable().absolutePath
            spec.args = deviceArgs(selectDevice = true) + args
        }
    }

    private fun deviceArgs(selectDevice: Boolean): List<String> =
        if (selectDevice && device != null) listOf("-s", device) else emptyList()

    private fun checkedExecutable(): File =
        executable.takeIf(File::isFile)
            ?: throw GradleException("adb not found at $executable. Install Android SDK Platform-Tools.")

    private fun hint(message: String): String = when {
        "more than one device" in message ->
            "\nSeveral devices are connected. Choose one with starlight { deploy { device = \"<serial>\" } } or -Pstarlight.device=<serial>."
        "no devices" in message || "not found" in message ->
            "\nConnect a device with USB debugging, or set device = \"<ip>:<port>\" for wireless debugging."
        else -> ""
    }

    private companion object {
        val NETWORK_ADDRESS = Regex("""[^\s:]+:\d+""")
    }
}
