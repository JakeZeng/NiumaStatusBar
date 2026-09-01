package com.aimonitor.app.kotlin;

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
    @Input
    @org.gradle.api.tasks.Optional
    private String soName;

    public String getRootDirRel() { return rootDirRel; }
    public void setRootDirRel(String rootDirRel) { this.rootDirRel = rootDirRel; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public Boolean getRelease() { return release; }
    public void setRelease(Boolean release) { this.release = release; }
    public String getSoName() { return soName; }
    public void setSoName(String soName) { this.soName = soName; }

    @TaskAction
    public void assemble() {
        if (rootDirRel == null) throw new GradleException("rootDirRel cannot be null");
        if (target == null) throw new GradleException("target cannot be null");
        if (release == null) throw new GradleException("release cannot be null");

        File rootDir = new File(getProject().getProjectDir(), rootDirRel).getAbsoluteFile();
        // Normalize so getName() returns the real directory name (not "..")
        final File cargoRoot;
        try {
            cargoRoot = rootDir.getCanonicalFile();
        } catch (IOException e) {
            throw new GradleException("Cannot resolve rootDir: " + rootDir, e);
        }
        getProject().getLogger().lifecycle("Rust rootDir = " + cargoRoot + " (name=" + cargoRoot.getName() + ")");

        // 1. Run cargo build directly (bypass tauri-cli android-studio-script which panics on server-addr)
        String cargo = Os.isFamily(Os.FAMILY_WINDOWS) ? "cargo.exe" : "cargo";
        // Cargo ≥1.79 reserves the "debug" profile name; use "dev" for debug builds
        // (output directory is still target/<triple>/debug/).
        String cargoProfile = release ? "release" : "dev";
        String outputDir = release ? "release" : "debug";

        getProject().exec(spec -> {
            spec.setWorkingDir(cargoRoot);
            spec.executable(cargo);
            spec.args("build", "--target", target, "--profile", cargoProfile);
            if (getProject().getLogger().isEnabled(LogLevel.DEBUG)) {
                spec.args("-vv");
            } else if (getProject().getLogger().isEnabled(LogLevel.INFO)) {
                spec.args("-v");
            }
            spec.environment("CARGO_TERM_COLOR", "always");
        }).assertNormalExitValue();

        // 2. Copy the built .so into the Android project
        // Prefer explicit soName from build.gradle; else "lib" + rootDir.name + ".so"; else libapp.so (tauri default).
        String resolvedSoName;
        if (soName != null && !soName.isEmpty()) {
            resolvedSoName = soName.startsWith("lib") ? soName : "lib" + soName + ".so";
        } else {
            resolvedSoName = "lib" + cargoRoot.getName() + ".so";
        }
        // If the derived name doesn't exist, fall back to scanning target dir for any .so (handles custom [lib] name in Cargo.toml).
        File targetDir = new File(cargoRoot, "target/" + target + "/" + outputDir);
        File derivedSrc = new File(targetDir, resolvedSoName);
        if (!derivedSrc.exists()) {
            // List direct .so files (skip incremental/deps/build/.fingerprint dirs).
            File[] candidates = targetDir.listFiles((d, n) -> n.endsWith(".so") && !n.startsWith("libstd-"));
            if (candidates != null && candidates.length > 0) {
                File best = null;
                for (File f : candidates) {
                    if (f.isDirectory()) continue;
                    if (best == null || f.length() > best.length()) best = f;
                }
                if (best != null) {
                    getProject().getLogger().lifecycle("Auto-detected .so: " + best + " (" + best.length() + " bytes)");
                    resolvedSoName = best.getName();
                }
            } else {
                getProject().getLogger().lifecycle("No .so found in " + targetDir + "; listing contents:");
                String[] all = targetDir.list();
                if (all != null) {
                    for (String n : all) getProject().getLogger().lifecycle("  " + n);
                }
            }
        }
        String abi;
        switch (target) {
            case "aarch64-linux-android": abi = "arm64-v8a"; break;
            case "armv7-linux-androideabi": abi = "armeabi-v7a"; break;
            case "i686-linux-android": abi = "x86"; break;
            case "x86_64-linux-android": abi = "x86_64"; break;
            default: abi = target;
        }

        File srcSo = new File(targetDir, resolvedSoName);
        File dstDir = new File(getProject().getProjectDir(), "src/main/jniLibs/" + abi);
        if (!dstDir.exists()) {
            dstDir.mkdirs();
        }
        File dstSo = new File(dstDir, resolvedSoName);

        try {
            if (srcSo.exists()) {
                Files.copy(srcSo.toPath(), dstSo.toPath(), StandardCopyOption.REPLACE_EXISTING);
                getProject().getLogger().lifecycle("Copied " + srcSo + " -> " + dstSo);
            } else {
                throw new GradleException("Built .so not found at " + srcSo);
            }
        } catch (IOException e) {
            throw new GradleException("Failed to copy .so", e);
        }
    }
}
