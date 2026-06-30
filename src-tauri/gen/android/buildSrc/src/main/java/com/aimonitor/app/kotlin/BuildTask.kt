import java.io.File
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

open class BuildTask : DefaultTask() {
    @Input
    var rootDirRel: String? = null
    @Input
    var target: String? = null
    @Input
    var release: Boolean? = null

    @TaskAction
    fun assemble() {
        val rootDirRel = rootDirRel ?: throw GradleException("rootDirRel cannot be null")
        val target = target ?: throw GradleException("target cannot be null")
        val release = release ?: throw GradleException("release cannot be null")

        val rootDir = File(project.projectDir, rootDirRel)

        // 1. Run cargo build directly (bypass tauri-cli android-studio-script which panics on server-addr)
        val cargo = if (Os.isFamily(Os.FAMILY_WINDOWS)) "cargo.exe" else "cargo"
        val profile = if (release) "release" else "debug"
        val cargoArgs = mutableListOf("build", "--target", target, "--profile", profile)

        project.exec {
            workingDir(rootDir)
            executable(cargo)
            args(cargoArgs)
            if (project.logger.isEnabled(LogLevel.DEBUG)) {
                args("-vv")
            } else if (project.logger.isEnabled(LogLevel.INFO)) {
                args("-v")
            }
            environment("CARGO_TERM_COLOR", "always")
        }.assertNormalExitValue()

        // 2. Copy the built .so into the Android project so the APK packager finds it
        val soName = "lib${rootDir.name}.so"
        val abi = when (target) {
            "aarch64-linux-android" -> "arm64-v8a"
            "armv7-linux-androideabi" -> "armeabi-v7a"
            "i686-linux-android" -> "x86"
            "x86_64-linux-android" -> "x86_64"
            else -> target
        }
        val srcSo = File(rootDir, "target/$target/$profile/$soName")
        val dstDir = File(project.projectDir, "src/main/jniLibs/$abi")
        if (!dstDir.exists()) {
            dstDir.mkdirs()
        }
        val dstSo = File(dstDir, soName)
        if (srcSo.exists()) {
            srcSo.copyTo(dstSo, overwrite = true)
            project.logger.lifecycle("Copied $srcSo -> $dstSo")
        } else {
            // Fallback: try libapp.so (tauri default lib name)
            val fallbackSo = File(rootDir, "target/$target/$profile/libapp.so")
            if (fallbackSo.exists()) {
                fallbackSo.copyTo(dstSo, overwrite = true)
                project.logger.lifecycle("Copied $fallbackSo -> $dstSo")
            } else {
                throw GradleException("Built .so not found at $srcSo or $fallbackSo")
            }
        }
    }
}
