import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.apache.tools.ant.taskdefs.condition.Os;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

public class BuildTask extends DefaultTask {
    @Input
    private String rootDirRel;
    @Input
    private String target;
    @Input
    private Boolean release;

    public String getRootDirRel() { return rootDirRel; }
    public void setRootDirRel(String rootDirRel) { this.rootDirRel = rootDirRel; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public Boolean getRelease() { return release; }
    public void setRelease(Boolean release) { this.release = release; }

    @TaskAction
    public void assemble() {
        if (rootDirRel == null) throw new GradleException("rootDirRel cannot be null");
        if (target == null) throw new GradleException("target cannot be null");
        if (release == null) throw new GradleException("release cannot be null");

        File rootDir = new File(getProject().getProjectDir(), rootDirRel);

        // 1. Run cargo build directly (bypass tauri-cli android-studio-script which panics on server-addr)
        String cargo = Os.isFamily(Os.FAMILY_WINDOWS) ? "cargo.exe" : "cargo";
        String profile = release ? "release" : "debug";

        getProject().exec(spec -> {
            spec.setWorkingDir(rootDir);
            spec.executable(cargo);
            spec.args("build", "--target", target, "--profile", profile);
            if (getProject().getLogger().isEnabled(LogLevel.DEBUG)) {
                spec.args("-vv");
            } else if (getProject().getLogger().isEnabled(LogLevel.INFO)) {
                spec.args("-v");
            }
            spec.environment("CARGO_TERM_COLOR", "always");
        }).assertNormalExitValue();

        // 2. Copy the built .so into the Android project
        String soName = "lib" + rootDir.getName() + ".so";
        String abi;
        switch (target) {
            case "aarch64-linux-android": abi = "arm64-v8a"; break;
            case "armv7-linux-androideabi": abi = "armeabi-v7a"; break;
            case "i686-linux-android": abi = "x86"; break;
            case "x86_64-linux-android": abi = "x86_64"; break;
            default: abi = target;
        }

        File srcSo = new File(rootDir, "target/" + target + "/" + profile + "/" + soName);
        File dstDir = new File(getProject().getProjectDir(), "src/main/jniLibs/" + abi);
        if (!dstDir.exists()) {
            dstDir.mkdirs();
        }
        File dstSo = new File(dstDir, soName);

        try {
            if (srcSo.exists()) {
                Files.copy(srcSo.toPath(), dstSo.toPath(), StandardCopyOption.REPLACE_EXISTING);
                getProject().getLogger().lifecycle("Copied " + srcSo + " -> " + dstSo);
            } else {
                // Fallback: try libapp.so (tauri default)
                File fallbackSo = new File(rootDir, "target/" + target + "/" + profile + "/libapp.so");
                if (fallbackSo.exists()) {
                    Files.copy(fallbackSo.toPath(), dstSo.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    getProject().getLogger().lifecycle("Copied " + fallbackSo + " -> " + dstSo);
                } else {
                    throw new GradleException("Built .so not found at " + srcSo + " or " + fallbackSo);
                }
            }
        } catch (IOException e) {
            throw new GradleException("Failed to copy .so", e);
        }
    }
}
