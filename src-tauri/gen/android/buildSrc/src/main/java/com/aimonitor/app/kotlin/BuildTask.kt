import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

/**
 * Builds the Rust crate for one Android target and copies the resulting .so
 * into `app/src/main/jniLibs/<abi>/`.
 *
 * Bypasses `pnpm tauri android android-studio-script` on purpose:
 * the Tauri CLI symlinks the .so into jniLibs, which fails on Windows
 * outside Developer Mode / admin. Direct `cargo build` + `Files.copy`
 * works on any host.
 */
open class BuildTask : DefaultTask() {
    @Input
    var rootDirRel: String? = null
    @Input
    var target: String? = null
    @Input
    var release: Boolean? = null
    @Input
    @Optional
    var soName: String? = null

    @TaskAction
    fun assemble() {
        val rootDirRel = rootDirRel ?: throw GradleException("rootDirRel cannot be null")
        val target = target ?: throw GradleException("target cannot be null")
        val release = release ?: throw GradleException("release cannot be null")

        val cargoRoot = File(project.projectDir, rootDirRel).absoluteFile.let { dir ->
            try {
                dir.canonicalFile
            } catch (e: IOException) {
                throw GradleException("Cannot resolve rootDir: $dir", e)
            }
        }
        logger.lifecycle("Rust rootDir = $cargoRoot (name=${cargoRoot.name})")

        // 1. Run cargo build directly (skip tauri-cli, which symlinks on Windows).
        val cargo = if (Os.isFamily(Os.FAMILY_WINDOWS)) "cargo.exe" else "cargo"
        // Cargo ≥1.79 reserves "debug" profile name; "dev" still writes to target/<triple>/debug/.
        val cargoProfile = if (release) "release" else "dev"
        val outputDir = if (release) "release" else "debug"

        project.exec {
            workingDir(cargoRoot)
            executable(cargo)
            args("build", "--target", target, "--profile", cargoProfile)
            if (logger.isEnabled(LogLevel.DEBUG)) {
                args("-vv")
            } else if (logger.isEnabled(LogLevel.INFO)) {
                args("-v")
            }
            environment("CARGO_TERM_COLOR", "always")
        }.assertNormalExitValue()

        // 2. Locate the built .so. Prefer explicit soName, then "lib<rootDir>.so",
        //    then fall back to scanning target dir for any non-libstd- .so.
        var resolvedSoName = if (!soName.isNullOrEmpty()) {
            if (soName!!.startsWith("lib")) soName!! else "lib$soName.so"
        } else {
            "lib${cargoRoot.name}.so"
        }
        val targetDir = File(cargoRoot, "target/$target/$outputDir")
        var srcSo = File(targetDir, resolvedSoName)
        if (!srcSo.exists()) {
            val candidates = targetDir.listFiles { _, n -> n.endsWith(".so") && !n.startsWith("libstd-") }
            if (candidates != null && candidates.isNotEmpty()) {
                val best = candidates.filter { it.isFile }.maxByOrNull { it.length() }
                if (best != null) {
                    logger.lifecycle("Auto-detected .so: $best (${best.length()} bytes)")
                    resolvedSoName = best.name
                    srcSo = best
                }
            } else {
                logger.lifecycle("No .so found in $targetDir; listing contents:")
                targetDir.list()?.forEach { logger.lifecycle("  $it") }
            }
        }

        // 3. Map Rust target triple → Android ABI folder.
        val abi = when (target) {
            "aarch64-linux-android" -> "arm64-v8a"
            "armv7-linux-androideabi" -> "armeabi-v7a"
            "i686-linux-android" -> "x86"
            "x86_64-linux-android" -> "x86_64"
            else -> target
        }

        val dstDir = File(project.projectDir, "src/main/jniLibs/$abi")
        if (!dstDir.exists()) dstDir.mkdirs()
        val dstSo = File(dstDir, resolvedSoName)

        if (srcSo.exists()) {
            try {
                Files.copy(srcSo.toPath(), dstSo.toPath(), StandardCopyOption.REPLACE_EXISTING)
                logger.lifecycle("Copied $srcSo -> $dstSo")
            } catch (e: IOException) {
                throw GradleException("Failed to copy .so", e)
            }
        } else {
            throw GradleException("Built .so not found at $srcSo")
        }
    }
}
