@file:OptIn(ExperimentalStdlibApi::class)

package io.github.frankois944.spmForKmp

import io.github.frankois944.spmForKmp.definition.PackageRootDefinitionExtension
import io.github.frankois944.spmForKmp.definition.helpers.filterExportableDependency
import io.github.frankois944.spmForKmp.tasks.CompileSwiftPackageTask
import io.github.frankois944.spmForKmp.tasks.GenerateCInteropDefinitionTask
import io.github.frankois944.spmForKmp.tasks.GenerateExportableManifestTask
import io.github.frankois944.spmForKmp.tasks.GenerateManifestTask
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.reflect.TypeOf
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.extensions.stdlib.capitalized
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import org.jetbrains.kotlin.konan.target.HostManager
import java.io.File
import kotlin.reflect.javaType
import kotlin.reflect.typeOf

internal const val PLUGIN_NAME: String = "swiftPackageConfig"
internal const val TASK_GENERATE_MANIFEST: String = "generateSwiftPackage"
internal const val TASK_COMPILE_PACKAGE: String = "compileSwiftPackage"
internal const val TASK_GENERATE_CINTEROP_DEF: String = "generateCInteropDefinition"
internal const val TASK_GENERATE_EXPORTABLE_PACKAGE: String = "generateExportableSwiftPackage"

@Suppress("UnnecessaryAbstractClass")
public abstract class SpmForKmpPlugin : Plugin<Project> {
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override fun apply(target: Project): Unit =
        with(target) {
            if (!HostManager.hostIsMac) {
                logger.error("The plugin SPMKMPPlugin can only run on macos")
                return
            }

            // check if the multiplatform plugin is loaded
            if (!plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
                throw RuntimeException("The plugin SPMKMPPlugin requires the kotlin multiplatform plugin")
            }

            val swiftPackageEntries: NamedDomainObjectContainer<out PackageRootDefinitionExtension> =
                objects.domainObjectContainer(PackageRootDefinitionExtension::class.java) { name ->
                    objects.newInstance(PackageRootDefinitionExtension::class.java, name)
                }

            val type =
                TypeOf.typeOf<NamedDomainObjectContainer<out PackageRootDefinitionExtension>>(
                    typeOf<NamedDomainObjectContainer<PackageRootDefinitionExtension>>().javaType,
                )

            extensions.add(type, PLUGIN_NAME, swiftPackageEntries)

            val kotlinExtension =
                extensions.getByName("kotlin") as KotlinMultiplatformExtension

            afterEvaluate {
                val taskGroup = mutableMapOf<CompileTarget, Task>()
                val exportManifestTask = mutableMapOf<String, Task>()
                val dependencyTaskNames = mutableMapOf<String, File>()
                swiftPackageEntries.forEach { extension ->

                    val sourcePackageDir =
                        resolveAndCreateDir(
                            layout.buildDirectory.asFile.get(),
                            "spmKmpPlugin/${extension.name}",
                        )

                    val packageScratchDir = resolveAndCreateDir(sourcePackageDir, "scratch")
                    val sharedCacheDir: File? = extension.sharedCachePath?.let { resolveAndCreateDir(File(it)) }
                    val sharedConfigDir: File? = extension.sharedConfigPath?.let { resolveAndCreateDir(File(it)) }
                    val sharedSecurityDir: File? = extension.sharedSecurityPath?.let { resolveAndCreateDir(File(it)) }
                    val userSourcePackageDir =
                        resolveAndCreateDir(
                            File(extension.customPackageSourcePath),
                            extension.name,
                        )

                    val manifestTask =
                        tasks
                            .register(
                                // name =
                                getTaskName(TASK_GENERATE_MANIFEST, extension.name),
                                // type =
                                GenerateManifestTask::class.java,
                            ) { manifest ->
                                manifest.packageDependencies.set(extension.packageDependencies)
                                manifest.packageName.set(extension.name)
                                manifest.minIos.set(extension.minIos)
                                manifest.minTvos.set(extension.minTvos)
                                manifest.minMacos.set(extension.minMacos)
                                manifest.minWatchos.set(extension.minWatchos)
                                manifest.toolsVersion.set(extension.toolsVersion)
                                manifest.manifestFile.set(sourcePackageDir.resolve("Package.swift"))
                                manifest.packageScratchDir.set(packageScratchDir)
                                manifest.sharedCacheDir.set(sharedCacheDir)
                                manifest.sharedConfigDir.set(sharedConfigDir)
                                manifest.sharedSecurityDir.set(sharedSecurityDir)
                            }
                    val exportablePackage =
                        extension.packageDependencies.filterExportableDependency()
                    val manifestDir =
                        layout.projectDirectory.asFile
                            .resolve("exported${extension.name.capitalized()}")
                    val exportedManifestTask: TaskProvider<GenerateExportableManifestTask>? =
                        if (exportablePackage.isNotEmpty()) {
                            tasks
                                .register(
                                    // name =
                                    getTaskName(TASK_GENERATE_EXPORTABLE_PACKAGE, extension.name),
                                    // type =
                                    GenerateExportableManifestTask::class.java,
                                ) { manifest ->
                                    manifest.packageDependencies.set(exportablePackage)
                                    manifest.packageName.set("exported${extension.name.capitalized()}")
                                    manifest.minIos.set(extension.minIos)
                                    manifest.minTvos.set(extension.minTvos)
                                    manifest.minMacos.set(extension.minMacos)
                                    manifest.minWatchos.set(extension.minWatchos)
                                    manifest.toolsVersion.set(extension.toolsVersion)
                                    manifestDir.mkdirs()
                                    manifest.manifestFile.set(manifestDir.resolve("Package.swift"))
                                    logger.warn(
                                        "Spm4Kmp: A local Swift package has been generated in $manifestDir",
                                    )
                                    logger.warn(
                                        "Please add it to your xcode project as a local package dependency.",
                                    )
                                }
                        } else {
                            manifestDir.deleteRecursively()
                            null
                        }

                    val allTargets =
                        tasks
                            .withType(CInteropProcess::class.java)
                            .filter {
                                it.name.startsWith("cinterop" + extension.name.capitalized())
                            }.mapNotNull { CompileTarget.byKonanName(it.konanTarget.name) }

                    allTargets.forEach { cinteropTarget ->
                        val targetBuildDir =
                            packageScratchDir
                                .resolve(cinteropTarget.getPackageBuildDir())
                                .resolve(if (extension.debug) "debug" else "release")

                        val compileTask =
                            tasks
                                .register(
                                    // name =
                                    getTaskName(
                                        TASK_COMPILE_PACKAGE,
                                        extension.name,
                                        cinteropTarget,
                                    ),
                                    // type =
                                    CompileSwiftPackageTask::class.java,
                                ) {
                                    it.manifestFile.set(File(sourcePackageDir, "Package.swift"))
                                    it.target.set(cinteropTarget)
                                    it.debugMode.set(extension.debug)
                                    it.packageScratchDir.set(packageScratchDir)
                                    it.compiledTargetDir.set(targetBuildDir)
                                    it.customSourcePackage.set(userSourcePackageDir)
                                    it.osVersion.set(
                                        cinteropTarget.getOsVersion(
                                            minIos = extension.minIos,
                                            minWatchos = extension.minWatchos,
                                            minTvos = extension.minTvos,
                                            minMacos = extension.minMacos,
                                        ),
                                    )
                                    it.sharedCacheDir.set(sharedCacheDir)
                                    it.sharedConfigDir.set(sharedConfigDir)
                                    it.sharedSecurityDir.set(sharedSecurityDir)
                                }

                        val definitionTask =
                            tasks
                                .register(
                                    // name =
                                    getTaskName(
                                        task = TASK_GENERATE_CINTEROP_DEF,
                                        extension = extension.name,
                                        cinteropTarget = cinteropTarget,
                                    ),
                                    // type =
                                    GenerateCInteropDefinitionTask::class.java,
                                ) {
                                    it.compiledBinary.set(targetBuildDir.resolve("lib${extension.name}.a"))
                                    it.target.set(cinteropTarget)
                                    it.productName.set(extension.name)
                                    it.packages.set(extension.packageDependencies)
                                    it.debugMode.set(extension.debug)
                                    it.osVersion.set(
                                        cinteropTarget.getOsVersion(
                                            minIos = extension.minIos,
                                            minWatchos = extension.minWatchos,
                                            minTvos = extension.minTvos,
                                            minMacos = extension.minMacos,
                                        ),
                                    )
                                    it.manifestFile.set(sourcePackageDir.resolve("Package.swift"))
                                    it.scratchDir.set(packageScratchDir)
                                }

                        val dependenciesFiles = definitionTask.get().outputFiles
                        if (dependenciesFiles.isNotEmpty()) {
                            val ktTarget =
                                kotlinExtension.targets.findByName(cinteropTarget.name) as? KotlinNativeTarget
                            if (ktTarget != null) {
                                val mainCompilation = ktTarget.compilations.getByName("main")
                                dependenciesFiles.forEachIndexed { index, file ->
                                    val fullTaskName =
                                        if (index > 0) {
                                            val cinteropName = file.nameWithoutExtension + extension.name.capitalized()
                                            // create cinterop tasks for the dependencies
                                            mainCompilation.cinterops.create(
                                                cinteropName,
                                            ) { settings ->
                                                settings.definitionFile.set(file)
                                            }
                                            getCInteropTaskName(cinteropName, cinteropTarget)
                                        } else {
                                            getCInteropTaskName(file.nameWithoutExtension, cinteropTarget)
                                        }
                                    // store the cinterop task name for retrieving the file later
                                    dependencyTaskNames[fullTaskName] = file
                                }
                            }

                            taskGroup[cinteropTarget] =
                                definitionTask
                                    .get()
                                    .dependsOn(
                                        compileTask
                                            .get()
                                            .dependsOn(
                                                listOfNotNull(
                                                    manifestTask.get(),
                                                    exportedManifestTask?.get(),
                                                ),
                                            ),
                                    )
                        }
                    }
                }
                // link the main definition File
                tasks.withType(CInteropProcess::class.java).configureEach { cinterop ->
                    cinterop.onlyIf {
                        plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")
                    }
                    val cinteropTarget =
                        CompileTarget.byKonanName(cinterop.konanTarget.name)
                            ?: return@configureEach
                    cinterop.dependsOn(taskGroup[cinteropTarget])
                    cinterop.mustRunAfter(taskGroup[cinteropTarget])
                    val definitionFile = dependencyTaskNames[cinterop.name]
                    cinterop.settings.definitionFile.set(definitionFile)
                }
            }
        }

    private fun getTaskName(
        task: String,
        extension: String,
        cinteropTarget: CompileTarget? = null,
    ) = "${PLUGIN_NAME.capitalized()}${extension.capitalized()}${task.capitalized()}${
        cinteropTarget?.name?.capitalized().orEmpty()
    }"

    private fun getCInteropTaskName(
        name: String,
        cinteropTarget: CompileTarget?,
    ): String = "cinterop${name.capitalized()}${cinteropTarget?.name?.capitalized().orEmpty()}"

    private fun Project.resolvePath(destination: File): File =
        if (destination.isAbsolute) {
            destination
        } else {
            layout.projectDirectory.asFile
                .resolve(destination)
        }

    private fun Project.resolveAndCreateDir(
        base: File,
        nestedPath: String? = null,
    ): File {
        val resolved = resolvePath(base).let { if (nestedPath != null) it.resolve(nestedPath) else it }
        resolved.mkdirs()
        return resolved
    }
}
