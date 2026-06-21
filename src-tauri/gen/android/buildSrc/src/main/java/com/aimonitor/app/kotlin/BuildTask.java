package com.aimonitor.app.kotlin;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.apache.tools.ant.taskdefs.condition.Os;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

public class BuildTask extends DefaultTask {
    private String rootDirRel;
    private String target;
    private Boolean release;

    @Input
    public String getRootDirRel() { return rootDirRel; }
    public void setRootDirRel(String rootDirRel) { this.rootDirRel = rootDirRel; }

    @Input
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    @Input
    public Boolean getRelease() { return release; }
    public void setRelease(Boolean release) { this.release = release; }

    @TaskAction
    public void assemble() {
        String executable = "pnpm";
        try {
            runTauriCli(executable);
        } catch (Exception e) {
            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                List<String> fallbacks = Arrays.asList(
                        executable + ".exe",
                        executable + ".cmd",
                        executable + ".bat"
                );
                Exception lastException = e;
                for (String fallback : fallbacks) {
                    try {
                        runTauriCli(fallback);
                        return;
                    } catch (Exception fallbackException) {
                        lastException = fallbackException;
                    }
                }
                throw new GradleException("Failed to run Tauri CLI", lastException);
            } else {
                throw new GradleException("Failed to run Tauri CLI", e);
            }
        }
    }

    private void runTauriCli(String executable) {
        if (rootDirRel == null) throw new GradleException("rootDirRel cannot be null");
        if (target == null) throw new GradleException("target cannot be null");
        if (release == null) throw new GradleException("release cannot be null");

        List<String> args = Arrays.asList("tauri", "android", "android-studio-script");

        getProject().exec(execSpec -> {
            execSpec.workingDir(new File(getProject().getProjectDir(), rootDirRel));
            execSpec.executable(executable);
            execSpec.args(args);
            if (getProject().getLogger().isEnabled(LogLevel.DEBUG)) {
                execSpec.args("-vv");
            } else if (getProject().getLogger().isEnabled(LogLevel.INFO)) {
                execSpec.args("-v");
            }
            if (release) {
                execSpec.args("--release");
            }
            execSpec.args(Arrays.asList("--target", target));
        }).assertNormalExitValue();
    }
}
