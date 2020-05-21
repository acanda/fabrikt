package com.cjbooms.fabrikt.generators

import com.cjbooms.fabrikt.configurations.PackagesConfig
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.functionName
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.getBodyResponses
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.getHeaderParams
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.getPathParams
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.getPrimaryAcceptMediaType
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.getPrimaryContentMediaType
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.getQueryParams
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.primaryPropertiesConstructor
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.simpleClientName
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.toBodyParameterSpec
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.toBodyRequestSchema
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.toClassName
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.toKCodeName
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.toKdoc
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.toParameterSpec
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.toReturnType
import com.cjbooms.fabrikt.generators.ClientGeneratorUtils.toVarName
import com.cjbooms.fabrikt.generators.JacksonModelGenerator.Companion.toModelType
import com.cjbooms.fabrikt.model.ClientType
import com.cjbooms.fabrikt.model.Destinations
import com.cjbooms.fabrikt.model.GeneratedFile
import com.cjbooms.fabrikt.model.HandlebarsTemplates
import com.cjbooms.fabrikt.model.KotlinTypeInfo
import com.cjbooms.fabrikt.model.SourceApi
import com.cjbooms.fabrikt.util.KaizenParserExtensions.routeToPaths
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.javaparser.utils.CodeGenerationUtils
import com.reprezen.kaizen.oasparser.model3.Operation
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName

class OkHttpSimpleClientGenerator(
    private val config: PackagesConfig,
    private val api: SourceApi
) {

    fun generateDynamicClientCode(): Collection<ClientType> {
        return api.openApi3.routeToPaths().map { (resourceName, paths) ->
            val funcSpecs: List<FunSpec> = paths.flatMap { (resource, path) ->
                path.operations.map { (verb, operation) ->
                    FunSpec
                        .builder(functionName(resource, verb))
                        .addModifiers(KModifier.PUBLIC)
                        .addKdoc(operation.toKdoc())
                        .addAnnotation(AnnotationSpec.builder(Throws::class)
                            .addMember("%T::class", "ApiException".toClassName(config.packages.client)).build()
                        )
                        .addParameters(operation.requestBody.toBodyParameterSpec(config.packages.base))
                        .addParameters(operation.parameters.map { it.toParameterSpec(config.packages.base) })
                        .addCode(SimpleClientOperationStatement(config, resource, verb, operation).toStatement())
                        .returns(operation.toReturnType(config))
                        .build()
                }
            }

            val clientType = TypeSpec.classBuilder(simpleClientName(resourceName))
                .primaryPropertiesConstructor(
                    PropertySpec.builder("objectMapper", ObjectMapper::class.asTypeName(), KModifier.PRIVATE).build(),
                    PropertySpec.builder("baseUrl", String::class.asTypeName(), KModifier.PRIVATE).build(),
                    PropertySpec.builder("client", "OkHttpClient".toClassName("okhttp3"), KModifier.PRIVATE).build()
                )
                .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("%S", "unused").build())
                .addFunctions(funcSpecs)
                .build()

            ClientType(clientType, config.packages.base)
        }.toSet()
    }

    fun generateLibrary(): Collection<GeneratedFile> {
        val codeDir = Destinations.MAIN_KT_SRC.resolve(CodeGenerationUtils.packageToPath(config.packages.base))
        val clientDir = codeDir.resolve("client")
        return setOf(
            HandlebarsTemplates.applyTemplate(
                HandlebarsTemplates.clientApiModels,
                config,
                clientDir,
                "ApiModels.kt"
            ),
            HandlebarsTemplates.applyTemplate(
                HandlebarsTemplates.clientHttpUtils,
                config,
                clientDir,
                "HttpUtil.kt"
            ),
            HandlebarsTemplates.applyTemplate(
                HandlebarsTemplates.clientOAuth,
                config,
                clientDir,
                "OAuth.kt"
            ),
            HandlebarsTemplates.applyTemplate(
                HandlebarsTemplates.clientLoggingInterceptor,
                config,
                clientDir,
                "LoggingInterceptor.kt"
            )
        )
    }
}

data class SimpleClientOperationStatement(
    private val config: PackagesConfig,
    private val resource: String,
    private val verb: String,
    private val operation: Operation
) {
    fun toStatement(): CodeBlock =
        CodeBlock.builder()
            .addUrlStatement()
            .addPathParamStatement()
            .addQueryParamStatement()
            .addHeaderParamStatement()
            .addRequestStatement()
            .addRequestExecutionStatement()
            .build()

    private fun CodeBlock.Builder.addUrlStatement(): CodeBlock.Builder {
        this.add("val httpUrl: %T = \"%L\"", "HttpUrl".toClassName("okhttp3"), "\$baseUrl$resource")
        return this
    }

    private fun CodeBlock.Builder.addPathParamStatement(): CodeBlock.Builder {
        operation.getPathParams().map { this.add("\n.pathParam(%S to %N)", "{${it.name}}", it.name.toKCodeName()) }
        this.add("\n.%T()\n.newBuilder()", "toHttpUrl".toClassName("okhttp3.HttpUrl.Companion"))
        return this
    }

    /**
     * By now it's only supported `form` style query params with a comma-separated delimiter. See [Open API 3.0
     * serialization](https://swagger.io/docs/specification/serialization) query parameters style values
     */
    private fun CodeBlock.Builder.addQueryParamStatement(): CodeBlock.Builder {
        operation.getQueryParams().map {
            when (KotlinTypeInfo.from(it.schema)) {
                is KotlinTypeInfo.Array -> this.add("\n.%T(%S, %N, %S)", "queryParam".toClassName(config.packages.client), it.name, it.name.toKCodeName(), ",")
                else -> this.add("\n.%T(%S, %N)", "queryParam".toClassName(config.packages.client), it.name, it.name.toKCodeName())
            }
        }
        return this.add("\n.build()\n")
    }

    private fun CodeBlock.Builder.addHeaderParamStatement(): CodeBlock.Builder {
        this.add("\nval httpHeaders: %T = Headers.Builder()", "Headers".toClassName("okhttp3"))
        operation.getHeaderParams().map {
            this.add("\n.%T(%S, %N)", "header".toClassName(config.packages.client), it.name, it.name.toKCodeName())
        }
        operation.getBodyResponses().firstOrNull()?.let {
            this.add("\n.%T(%S, %S)", "header".toClassName(config.packages.client), "Accept", "application/json")
        }
        return this.add("\n.build()\n")
    }

    private fun CodeBlock.Builder.addRequestStatement(): CodeBlock.Builder {
        this.add("\nval request: %T = Request.Builder()", "Request".toClassName("okhttp3"))
        this.add("\n.url(httpUrl)\n.headers(httpHeaders)")
        when (val op = verb.toUpperCase()) {
            "PUT" -> this.addRequestSerializerStatement("put")
            "POST" -> this.addRequestSerializerStatement("post")
            "PATCH" -> this.addRequestSerializerStatement("patch")
            "HEAD" -> this.add("\n.head()")
            "GET" -> this.add("\n.get()")
            "DELETE" -> this.add("\n.delete()")
            else -> throw NotImplementedError("API operation $op is not supported")
        }
        return this.add("\n.build()\n")
    }

    private fun CodeBlock.Builder.addRequestExecutionStatement(): CodeBlock.Builder {
        val returnType = operation.getPrimaryAcceptMediaType()?.value?.schema?.let {
            toModelType(config.packages.base, KotlinTypeInfo.from(it))
        }
        return this.add("\nreturn request.execute(client, objectMapper, %T::class.java)", returnType ?: Unit::class.asTypeName())
    }

    private fun CodeBlock.Builder.addRequestSerializerStatement(verb: String) {
        val requestBody = operation.requestBody
        requestBody.toBodyRequestSchema().firstOrNull()?.let {
            this.add(
                "\n.%N(objectMapper.writeValueAsString(%N).%T(%S.%T()))",
                verb,
                it.toVarName(),
                "toRequestBody".toClassName("okhttp3.RequestBody.Companion"),
                requestBody.getPrimaryContentMediaType()?.key,
                "toMediaType".toClassName("okhttp3.MediaType.Companion")
            )
        } ?: this.add("\n%N()", verb)
    }
}
