package fr.frankois944.spm.kmp.plugin.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

public abstract class OldTemplateExampleTask : DefaultTask() {
    init {
        description = "Just a sample template task"
        // Don't forget to set the group here.
        // group = BasePlugin.BUILD_GROUP
    }

    @get:Input
    @get:Option(option = "message", description = "A message to be printed in the output file")
    public abstract val message: Property<String>

    @get:Input
    @get:Option(option = "tag", description = "A Tag to be used for debug and in the output file")
    @get:Optional
    public abstract val tag: Property<String>

    @get:OutputFile
    public abstract val outputFile: RegularFileProperty

    @TaskAction
    public fun sampleAction() {
        val prettyTag = tag.orNull?.let { "[$it]" }.orEmpty()

        logger.lifecycle("$prettyTag message is: ${message.orNull}")
        logger.lifecycle("$prettyTag tag is: ${tag.orNull}")
        logger.lifecycle("$prettyTag outputFile is: ${outputFile.orNull}")

        outputFile.get().asFile.writeText("$prettyTag ${message.get()}")
    }
}
