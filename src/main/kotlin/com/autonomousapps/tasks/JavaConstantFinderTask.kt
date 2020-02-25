@file:Suppress("UnstableApiUsage")

package com.autonomousapps.tasks

import com.autonomousapps.TASK_GROUP_DEP
import com.autonomousapps.internal.*
import com.autonomousapps.internal.asm.ClassReader
import com.autonomousapps.internal.grammar.JavaBaseListener
import com.autonomousapps.internal.grammar.JavaLexer
import com.autonomousapps.internal.grammar.JavaParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile
import javax.inject.Inject

abstract class JavaConstantFinderTask @Inject constructor(private val workerExecutor: WorkerExecutor) : DefaultTask() {

    init {
        group = TASK_GROUP_DEP
        description = "Produces a report of constants, from other components, that have been used"
    }

    /**
     * Upstream artifacts.
     */
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFile
    abstract val artifacts: RegularFileProperty

    /**
     * The Java source of the current project.
     */
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFiles
    abstract val javaSourceFiles: ConfigurableFileCollection

    /**
     * TODO@tsr.
     */
    @get:OutputFile
    abstract val constantUsageReport: RegularFileProperty

    @TaskAction
    fun action() {
        workerExecutor.noIsolation().submit(JavaConstantFinderWorkAction::class.java) {
            artifacts.set(this@JavaConstantFinderTask.artifacts)
            javaSourceFiles.setFrom(this@JavaConstantFinderTask.javaSourceFiles)
            constantUsageReport.set(this@JavaConstantFinderTask.constantUsageReport)
        }
    }
}

interface JavaConstantFinderParameters : WorkParameters {
    val artifacts: RegularFileProperty
    val javaSourceFiles: ConfigurableFileCollection
    val constantUsageReport: RegularFileProperty
}

abstract class JavaConstantFinderWorkAction : WorkAction<JavaConstantFinderParameters> {

    private val logger = getLogger<JavaConstantFinderTask>()

    override fun execute() {
        // Output
        val constantUsageReportFile = parameters.constantUsageReport.get().asFile
        constantUsageReportFile.delete()

        // Inputs
        val artifacts = parameters.artifacts.get().asFile.readText().fromJsonList<Artifact>()

        val usedComponents = JavaConstantFinder(logger, artifacts, parameters.javaSourceFiles).find()

        logger.debug("Constants usage:\n${usedComponents.toPrettyString()}")
        constantUsageReportFile.writeText(usedComponents.toJson())
    }
}

/*
 * TODO@tsr all this stuff below looks very similar to InlineMemberExtractionTask
 */

private class JavaConstantFinder(
    private val logger: Logger,
    private val artifacts: List<Artifact>,
    private val javaSourceFiles: FileCollection
) {

    fun find(): Set<Dependency> {
        val constantImports: Set<ComponentWithConstantMembers> = artifacts
            .map { artifact ->
                artifact to JavaConstantMemberFinder(logger, ZipFile(artifact.file)).find()
            }.filterNot { (_, imports) -> imports.isEmpty() }
            .map { (artifact, imports) -> ComponentWithConstantMembers(artifact.dependency, imports) }
            .toSortedSet()

        return JavaConstantUsageFinder(javaSourceFiles, constantImports).find()
    }
}

private class JavaConstantMemberFinder(
    private val logger: Logger,
    private val zipFile: ZipFile
) {

    /**
     * Returns either an empty list, if there are no constants, or a list of import candidates. E.g.:
     * ```
     * [
     *   "com.myapp.BuildConfig.*",
     *   "com.myapp.BuildConfig.DEBUG"
     * ]
     * ```
     * An import statement with either of those would import the `com.myapp.BuildConfig.DEBUG` constant, contributed by
     * the "com.myapp" module.
     */
    fun find(): Set<String> {
        val entries = zipFile.entries().toList()

        return entries
            .filter { it.name.endsWith(".class") }
            .flatMap { entry ->
                val classReader = zipFile.getInputStream(entry).use { ClassReader(it.readBytes()) }
                val constantVisitor = ConstantVisitor(logger)
                classReader.accept(constantVisitor, 0)

                val fqcn = constantVisitor.className
                    .replace("/", ".")
                    .replace("$", ".")
                val constantMembers = constantVisitor.classes

                listOf("$fqcn.*") + constantMembers.map { name -> "$fqcn.$name" }
            }.toSortedSet()
    }
}

private class JavaConstantUsageFinder(
    private val javaSourceFiles: FileCollection,
    private val constantImportCandidates: Set<ComponentWithConstantMembers>
) {

    /**
     * Looks at all the Java source in the project and scans for any import that is for a known constant member.
     * Returns the set of [Dependency]s that contribute these used constant members.
     */
    fun find(): Set<Dependency> {
        return javaSourceFiles
            .flatMap { source -> parseJavaSourceFileForImports(source) }
            .mapNotNull { actualImport -> findActualConstantImports(actualImport) }
            .toSet()
    }

    private fun parseJavaSourceFileForImports(file: File): Set<String> {
        val parser = newJavaParser(file)
        val importFinder = walkTree(parser)
        return importFinder.imports()
    }

    private fun newJavaParser(file: File): JavaParser {
        val input = FileInputStream(file).use { fis -> CharStreams.fromStream(fis) }
        val lexer = JavaLexer(input)
        val tokens = CommonTokenStream(lexer)
        return JavaParser(tokens)
    }

    private fun walkTree(parser: JavaParser): ImportFinder {
        val tree = parser.compilationUnit()
        val walker = ParseTreeWalker()
        val importFinder = ImportFinder()
        walker.walk(importFinder, tree)
        return importFinder
    }

    /**
     * [actualImport] is, e.g.,
     * * `com.myapp.BuildConfig.DEBUG`
     * * `com.myapp.BuildConfig.*`
     */
    private fun findActualConstantImports(actualImport: String): Dependency? {
        return constantImportCandidates.find {
            it.imports.contains(actualImport)
        }?.dependency
    }
}

private class ImportFinder : JavaBaseListener() {

    private val imports = mutableSetOf<String>()

    internal fun imports(): Set<String> = imports

    override fun enterImportDeclaration(ctx: JavaParser.ImportDeclarationContext) {
        val qualifiedName = ctx.qualifiedName().text
        val import = if (ctx.children.any { it.text == "*" }) {
            "$qualifiedName.*"
        } else {
            qualifiedName
        }

        imports.add(import)
    }
}
