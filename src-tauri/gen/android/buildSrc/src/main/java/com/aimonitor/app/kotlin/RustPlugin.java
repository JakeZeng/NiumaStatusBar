package com.aimonitor.app.kotlin;

import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.dsl.ApplicationProductFlavor;
import com.android.build.api.dsl.ProductFlavor;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public class RustPlugin implements Plugin<Project> {
    private Config config;

    @Override
    public void apply(Project project) {
        config = project.getExtensions().create("rust", Config.class);

        List<String> defaultAbiList = Arrays.asList("arm64-v8a", "armeabi-v7a", "x86", "x86_64");
        String abiListProp = (String) project.findProperty("abiList");
        List<String> abiList = abiListProp != null ? Arrays.asList(abiListProp.split(",")) : defaultAbiList;

        List<String> defaultArchList = Arrays.asList("arm64", "arm", "x86", "x86_64");
        String archListProp = (String) project.findProperty("archList");
        List<String> archList = archListProp != null ? Arrays.asList(archListProp.split(",")) : defaultArchList;

        String targetListProp = (String) project.findProperty("targetList");
        List<String> targetsList = targetListProp != null ? Arrays.asList(targetListProp.split(",")) : Arrays.asList("aarch64", "armv7", "i686", "x86_64");

        project.getExtensions().configure(ApplicationExtension.class, extension -> {
            extension.getFlavorDimensions().add("abi");
            extension.getProductFlavors().create("universal", flavor -> {
                setFlavorDimension(flavor, "abi");
                flavor.getNdk().getAbiFilters().addAll(abiList);
            });
            for (int i = 0; i < defaultArchList.size(); i++) {
                String arch = defaultArchList.get(i);
                final int index = i;
                extension.getProductFlavors().create(arch, flavor -> {
                    setFlavorDimension(flavor, "abi");
                    flavor.getNdk().getAbiFilters().add(defaultAbiList.get(index));
                });
            }
        });

        project.afterEvaluate(p -> {
            for (String profile : Arrays.asList("debug", "release")) {
                String profileCapitalized = profile.substring(0, 1).toUpperCase() + profile.substring(1);
                DefaultTask buildTask = p.getTasks().maybeCreate(
                        "rustBuildUniversal" + profileCapitalized,
                        DefaultTask.class
                );
                buildTask.setGroup("rust");
                buildTask.setDescription("Build dynamic library in " + profile + " mode for all targets");

                p.getTasks().getByName("mergeUniversal" + profileCapitalized + "JniLibFolders").dependsOn(buildTask);

                for (int i = 0; i < targetsList.size(); i++) {
                    String targetName = targetsList.get(i);
                    String targetArch = archList.get(i);
                    String targetArchCapitalized = targetArch.substring(0, 1).toUpperCase() + targetArch.substring(1);
                    BuildTask targetBuildTask = p.getTasks().maybeCreate(
                            "rustBuild" + targetArchCapitalized + profileCapitalized,
                            BuildTask.class
                    );
                    targetBuildTask.setGroup("rust");
                    targetBuildTask.setDescription("Build dynamic library in " + profile + " mode for " + targetArch);
                    targetBuildTask.setRootDirRel(config.getRootDirRel());
                    targetBuildTask.setTarget(targetName);
                    targetBuildTask.setRelease(profile.equals("release"));

                    buildTask.dependsOn(targetBuildTask);
                    p.getTasks().getByName("merge" + targetArchCapitalized + profileCapitalized + "JniLibFolders").dependsOn(targetBuildTask);
                }
            }
        });
    }

    private void setFlavorDimension(Object flavor, String dimension) {
        try {
            Method method = flavor.getClass().getMethod("setDimension", String.class);
            method.invoke(flavor, dimension);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set flavor dimension", e);
        }
    }
}
