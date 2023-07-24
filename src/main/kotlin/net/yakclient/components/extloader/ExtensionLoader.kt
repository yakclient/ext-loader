package net.yakclient.components.extloader

import arrow.core.continuations.either
import arrow.core.handleErrorWith
import net.yakclient.archive.mapper.ArchiveMapping
import net.yakclient.archive.mapper.transform.*
import net.yakclient.archives.ArchiveReference
import net.yakclient.archives.Archives
import net.yakclient.archives.mixin.*
import net.yakclient.archives.transform.MethodSignature
import net.yakclient.archives.transform.ProvidedInstructionReader
import net.yakclient.boot.BootInstance
import net.yakclient.boot.component.ComponentInstance
import net.yakclient.boot.loader.ArchiveClassProvider
import net.yakclient.boot.loader.ClassProvider
import net.yakclient.boot.loader.DelegatingClassProvider
import net.yakclient.boot.loader.IntegratedLoader
import net.yakclient.boot.security.PrivilegeAccess
import net.yakclient.boot.security.PrivilegeManager
import net.yakclient.client.api.*
import net.yakclient.common.util.immutableLateInit
import net.yakclient.common.util.resolve
import net.yakclient.components.extloader.extension.*
import net.yakclient.components.extloader.extension.archive.ExtensionArchiveReference
import net.yakclient.components.extloader.extension.artifact.ExtensionArtifactRequest
import net.yakclient.components.extloader.mixin.MixinInjectionProvider
import net.yakclient.minecraft.bootstrapper.MinecraftBootstrapper
import net.yakclient.minecraft.bootstrapper.MixinMetadata
import java.util.logging.Level
import java.util.logging.Logger
import net.yakclient.components.extloader.mapping.*
import net.yakclient.components.extloader.mixin.InjectionPoints
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

public class ExtensionLoader(
    private val boot: BootInstance,
    private val configuration: ExtLoaderConfiguration,
    private val minecraft: MinecraftBootstrapper,
) : ComponentInstance<ExtLoaderConfiguration> {
    private lateinit var graph: ExtensionGraph
    private val extensions = ArrayList<ExtensionNode>()

    override fun start() {
        minecraft.start()

        // net.yakclient:example:example->maven.yakclient.net@default;another
        val mappings = minecraft.minecraftHandler.minecraftReference.mappings
        val injectionProviders = getInjectionProviders(mappings)



        graph = ExtensionGraph(
            boot.location resolve RELATIVE_CACHE,
            Archives.Finders.ZIP_FINDER,
            PrivilegeManager(null, PrivilegeAccess.emptyPrivileges()),
            this::class.java.classLoader,
            boot.dependencyTypes,
            mappings,
            minecraft.minecraftHandler.minecraftReference.archive,
            minecraft.minecraftHandler.version
        )

        val either = either.eager {
            configuration.extension.map { (request, settings) ->
                graph.get(request).handleErrorWith {
                    graph.cacherOf(settings).cache(ExtensionArtifactRequest(request))

                    graph.get(request)
                }
            }.map { it.bind() }
        }

        val nodes = checkNotNull(either.orNull()) { "Failed to load extensions due to exception '$either'" }

        this.extensions.addAll(nodes)


        val minecraftHandler = minecraft.minecraftHandler

        val flatMap = this.extensions.flatMap { node ->
            val allMixins = node.archiveReference?.enabledPartitions?.flatMap(ExtensionVersionPartition::mixins) ?: listOf()

            val flatMap = allMixins.flatMap { mixin: ExtensionMixin ->
                mixin.injections.map {
                    val provider = injectionProviders[it.type]
                        ?: throw IllegalArgumentException("Unknown mixin type: '${it.type}' in mixin class: '${mixin.classname}'")

                    MixinMetadata(
                        provider.parseData(it.options, node.archiveReference!!),
                        provider.get() as MixinInjection<MixinInjection.InjectionData>
                    ) to (mappings.mapClassName(mixin.destination.withSlashes(), MappingDirection.TO_FAKE)?.withDots()
                        ?: mixin.destination.withSlashes())
                }
            }

            flatMap
        }
        flatMap.forEach { (it, to) -> minecraftHandler.registerMixin(to, it) }

        // This part has some black magic that i while try to explain here. The main
        // issue is that minecraft needs to be able to load classes from extensions,
        // and extensions need to be able to load classes from minecraft. Other modding
        // systems achieve this by loading everything with one classloader, with our more
        // modular design we opted to not do this, however the drawback is that we now have
        // to deal with circularly dependent classloaders. The first issue is one of infinite
        // recursion (stackoverflow) if extensions are trying to access a class in minecraft,
        // it doesn't have it, so tries to get it from the extensions, which throw it back to
        // minecraft etc. This is fixed with our MinecraftLinker, which you can go check out.
        // This next bit of code then addresses instantiating. Unfortunately, this is a bit messy
        // but is the best way ive found of doing it.

        // Setup class providers and let them be initialized later
        var mcProvider: ClassProvider by immutableLateInit()
        var extProvider: ClassProvider by immutableLateInit()

        // Create linker with delegating to the uninitialized class providers
        val linker = MinecraftLinker(
            object : ClassProvider {
                override val packages: Set<String>
                    get() = extProvider.packages

                override fun findClass(name: String): Class<*>? = extProvider.findClass(name)

                override fun findClass(name: String, module: String): Class<*>?= extProvider.findClass(name, module)
            },
            object : ClassProvider {
                override val packages: Set<String>
                    get() = mcProvider.packages

                override fun findClass(name: String): Class<*>? = mcProvider.findClass(name)

                override fun findClass(name: String, module: String): Class<*>?= mcProvider.findClass(name, module)
            }
        )

        // Create the minecraft parent classloader with the delegating linker
        val parentLoader = IntegratedLoader(
            cp = linker.extensionClassProvider,
            parent = this::class.java.classLoader
        )

        // Init minecraft
        minecraftHandler.writeAll()
        minecraftHandler.loadMinecraft(parentLoader)

        // Initialize the first clas provider to allow extensions access to minecraft
        mcProvider = ArchiveClassProvider(minecraftHandler.archive)

        // Setup extensions, dont init yet
        this.extensions.forEach {
            val ref = it.extension?.process?.ref
            ref?.supplyMinecraft(linker)
        }

        // Setup the extension class provider for minecraft
        // TODO this will not support loading new extensions at runtime.
        extProvider = DelegatingClassProvider(this.extensions.mapNotNull(ExtensionNode::archive).map(::ArchiveClassProvider))

        // Call init on all extensions
        // TODO this may initialize parent extensions before their dependencies
        this.extensions.forEach {
            val extension = it.extension?.process?.ref?.extension

            extension?.init()
        }

        // Start minecraft
        minecraftHandler.startMinecraft()
    }

    override fun end() {
        extensions.forEach {
            it.extension?.process?.ref?.extension?.cleanup()
        }

        minecraft.end()
    }

    internal companion object {
        private val logger = Logger.getLogger(this::class.simpleName)
        private val RELATIVE_CACHE = "extensions"

        fun getInjectionProviders(mappings: ArchiveMapping): Map<String, MixinInjectionProvider<*>> {
            fun Map<String, String>.notNull(name: String): String =
                checkNotNull(this[name]) { "Invalid Mixin options, no '$name' value provided in '$this'." }

            fun ArchiveMapping.justMapSignatureDescriptor(signature: String): String {
                val (name, desc, ret) = MethodSignature.of(signature)

                val retOrBlank = ret ?: ""
                return name + mapMethodDesc("($desc)$retOrBlank", MappingDirection.TO_FAKE)
            }

            return mutableListOf(
                object : MixinInjectionProvider<SourceInjectionData> {
                    override val type: String = "source"
                    private val pointCache = HashMap<String, SourceInjectionPoint>()

                    override fun parseData(
                        options: Map<String, String>,
                        ref: ExtensionArchiveReference
                    ): SourceInjectionData {
                        val self = options.notNull("self")
                        val point = options.notNull("point")

                        val clsSelf = ref.reader["${self.withSlashes()}.class"]
                            ?: throw IllegalArgumentException("Failed to find class: '$self' in extension when loading source injection.")

                        val node = ClassNode().also { ClassReader(clsSelf.resource.open()).accept(it, 0) }

                        val toWithSlashes = options.notNull("to").withSlashes()
                        val methodTo = options.notNull("methodTo")
                        val data = SourceInjectionData(
                            mappings.mapClassName(toWithSlashes, MappingDirection.TO_FAKE)?.withDots()
                                ?: toWithSlashes,
                            self,
                            run {
                                val methodFrom = options.notNull("methodFrom")

                                ProvidedInstructionReader(
                                    node.methods.firstOrNull {
                                        (it.name + it.desc) == mappings.justMapSignatureDescriptor(methodFrom)
                                    }?.instructions
                                        ?: throw IllegalArgumentException("Failed to find method: '$methodFrom'.")
                                )
                            },
                            run {
                                val signature = MethodSignature.of(methodTo)

                                val fullDesc = "(${signature.desc})${signature.returnType}"
                                val name = mappings.mapMethodName(
                                    toWithSlashes,
                                    signature.name,
                                    fullDesc,
                                    MappingDirection.TO_FAKE
                                ) ?: signature.name
                                val desc = mappings.mapMethodDesc(fullDesc, MappingDirection.TO_FAKE)
                                name + desc
                            },
                            pointCache[point] ?: run {
                                loadInjectionPoint(point, ref).also { pointCache[point] = it }
                            }
                        )

                        return data
                    }

                    override fun get(): MixinInjection<SourceInjectionData> = SourceInjection
                },
                object : MixinInjectionProvider<MethodInjectionData> {
                    override val type: String = "method"

                    override fun parseData(
                        options: Map<String, String>,
                        ref: ExtensionArchiveReference
                    ): MethodInjectionData {
                        val self = options.notNull("self")
                        val classSelf = ref.reader["${self.withSlashes()}.class"] ?: throw IllegalArgumentException(
                            "Failed to find class: '$self' when loading method injections."
                        )
                        val node = ClassNode().also { ClassReader(classSelf.resource.open()).accept(it, 0) }

                        val to = options.notNull("to").withSlashes()
                        return MethodInjectionData(
                            mappings.mapClassName(to, MappingDirection.TO_FAKE)?.withDots() ?: to,
                            self,
                            run {
                                val methodFrom = options.notNull("methodFrom")

                                ProvidedInstructionReader(
                                    node.methods.firstOrNull {
                                        (it.name + it.desc) == mappings.justMapSignatureDescriptor(methodFrom)
                                    }?.instructions
                                        ?: throw IllegalArgumentException("Failed to find method: '$methodFrom'.")
                                )
                            },

                            options.notNull("access").toInt(),
                            options.notNull("name"),
                            options.notNull("description"),
                            options["signature"],
                            options.notNull("exceptions").split(',')
                        )
                    }

                    override fun get(): MixinInjection<MethodInjectionData> = MethodInjection
                },
                object : MixinInjectionProvider<FieldInjectionData> {
                    override val type: String = "field"

                    override fun parseData(
                        options: Map<String, String>,
                        ref: ExtensionArchiveReference
                    ): FieldInjectionData {
                        return FieldInjectionData(
                            options.notNull("access").toInt(),
                            options.notNull("name"),
                            options.notNull("type"),
                            options["signature"],
                            run {
                                if (options["value"] != null) logger.log(
                                    Level.WARNING,
                                    "Cannot set initial values in mixins at this time, this will eventually be a feature."
                                )
                                null
                            }
                        )
                    }

                    override fun get(): MixinInjection<FieldInjectionData> = FieldInjection
                }
            ).associateByTo(HashMap()) { it.type }
        }

//        fun createContext(mappings: ArchiveMapping): YakContext {
//            fun Map<String, String>.notNull(name: String): String =
//                    checkNotNull(this[name]) { "Invalid Mixin options, no '$name' value provided in '$this'." }
//
//            fun ArchiveMapping.justMapSignatureDescriptor(signature: String): String {
//                val (name, desc, ret) = MethodSignature.of(signature)
//
//                val retOrBlank = ret ?: ""
//                return name + mapMethodDesc("($desc)$retOrBlank", MappingDirection.TO_FAKE)
//            }
//
//
//            return YakContext(context, mutableListOf.associateByTo(HashMap(), MixinInjectionProvider<*>::type),
//                    listOf(
//                            ProGuardMappingParser
//                    ).associateByTo(HashMap(), MappingProvider::type)
//            )
//        }

        private fun loadInjectionPoint(name: String, archive: ArchiveReference): SourceInjectionPoint {
            return when (name) {
                AFTER_BEGIN -> InjectionPoints.AfterBegin()
                BEFORE_END -> InjectionPoints.BeforeEnd()
                BEFORE_INVOKE -> InjectionPoints.BeforeInvoke()
                BEFORE_RETURN -> InjectionPoints.BeforeReturn()
                OVERWRITE -> InjectionPoints.Overwrite()
                else -> throw IllegalArgumentException("Invalid injection point: '$name'. Custom injections points are not yet implemented.")
            }
        }
    }


}