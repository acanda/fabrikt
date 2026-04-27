import com.cjbooms.fabrikt.cli.CodeGenerationType
import com.cjbooms.fabrikt.cli.ModelCodeGenOptionType
import com.cjbooms.fabrikt.model.GeneratedFile
import com.cjbooms.fabrikt.model.KotlinSourceSet
import com.cjbooms.fabrikt.model.ResourceFile
import com.cjbooms.fabrikt.model.ResourceSourceSet
import com.cjbooms.fabrikt.model.SimpleFile
import data.sampleOpenApiSpec
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.stream.appendHTML
import kotlinx.html.style
import kotlinx.html.unsafe
import lib.generateCodeSynchronized
import lib.GenerationSettings.Companion.receiveGenerationSettings
import views.elements.fileViewForFile
import views.elements.codeView
import views.elements.fileView
import views.elements.specForm
import views.layout.columnPanel
import views.layout.mainLayout
import views.respondHtmlFragmentDiv

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toIntOrNull() ?: 8080) {
        install(CallLogging)

        routing {
            staticResources("/static", "static")

            /**
             * GET endpoint to render the playground
             */
            get("/") {
                val generationSettings = call.queryParameters.receiveGenerationSettings()
                    .copy(inputSpec = sampleOpenApiSpec) // set the sample spec
                    .run {
                        // if no settings in query params we configure default
                        // to ensure something is generated with just the sample spec
                        if (call.queryParameters.isEmpty()) {
                            copy(genTypes = setOf(CodeGenerationType.HTTP_MODELS))
                        } else {
                            this
                        }
                    }

                call.respondHtml {
                    mainLayout {
                        div {
                            style = "position: fixed; top: 0; left: 0; right: 0; height: 48px; z-index: 10; background: white; border-bottom: 1px solid #e0e0e0; display: flex; align-items: center; padding: 0 16px;"
                            a(href = "/") {
                                style = "display: flex; align-items: center; text-decoration: none;"
                                img(src = "/static/fabrikt-horizontal-final.png", alt = "fabrikt") {
                                    style = "height: 28px; display: block;"
                                }
                            }
                            span {
                                style = "margin-left: auto; font-size: 11px; color: #999; margin-right: 14px;"
                                +"v/${Version.GIT_VERSION}"
                            }
                            a(href = "https://github.com/fabrikt-io/fabrikt", target = "_blank") {
                                style = "display: flex; align-items: center; color: #333;"
                                attributes["aria-label"] = "fabrikt on GitHub"
                                unsafe {
                                    +"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 98 96" width="22" height="22" fill="currentColor"><path d="M48.854 0C21.839 0 0 22 0 49.217c0 21.756 13.993 40.172 33.405 46.69 2.427.49 3.316-1.059 3.316-2.362 0-1.141-.08-5.052-.08-9.127-13.59 2.934-16.42-5.867-16.42-5.867-2.184-5.704-5.42-7.17-5.42-7.17-4.448-3.015.324-3.015.324-3.015 4.934.326 7.523 5.052 7.523 5.052 4.367 7.496 11.404 5.378 14.235 4.074.404-3.178 1.699-5.378 3.074-6.6-10.839-1.141-22.243-5.378-22.243-24.283 0-5.378 1.94-9.778 5.014-13.2-.485-1.222-2.184-6.275.486-13.038 0 0 4.125-1.304 13.426 5.052a46.97 46.97 0 0 1 12.214-1.63c4.125 0 8.33.571 12.213 1.63 9.302-6.356 13.427-5.052 13.427-5.052 2.67 6.763.97 11.816.485 13.038 3.155 3.422 5.015 7.822 5.015 13.2 0 18.905-11.404 23.06-22.324 24.283 1.78 1.548 3.316 4.481 3.316 9.126 0 6.6-.08 11.897-.08 13.526 0 1.304.89 2.853 3.316 2.364 19.412-6.52 33.405-24.935 33.405-46.691C97.707 22 75.788 0 48.854 0z"/></svg>"""
                                }
                            }
                        }
                        columnPanel(
                            flexSizes = listOf(1.0, 0.4, 1.5),
                            topOffset = 48,
                            // first column: spec form
                            {
                                specForm(generationSettings)
                            },
                            // second column: file sidebar
                            {
                                style = "flex: 0.4; padding: 0; overflow-y: hidden; display: flex; flex-direction: column; border-left: 1px solid #e0e0e0; border-right: 1px solid #e0e0e0;"
                                div {
                                    id = "file-sidebar"
                                    style = "flex: 1; overflow-y: auto; min-height: 0; padding: 10px 0;"
                                    div(classes = "file-sidebar-empty") {
                                        +"// Files will appear here"
                                    }
                                }
                            },
                            // third column: code output
                            {
                                codeView { fileView("// Output will appear here") }
                            }
                        )
                    }
                }
            }

            /**
             * POST endpoint to generate code from a spec
             *
             * Renders only the div containing the generated code.
             *
             * Loaded via AJAX with HTMX.
             */
            post("/generate") {
                val generationSettings = call.receiveParameters().receiveGenerationSettings()

                // validate input
                val inputSpec = generationSettings.inputSpec
                if (inputSpec.isBlank()) {
                    return@post call.respondText {
                        buildString { appendHTML().div {
                            fileView("// Error: No spec provided")
                            script { unsafe { +"Prism.highlightAll();" } } // trigger syntax highlighting
                        } }
                    }
                }

                runCatching {
                    generateCodeSynchronized(generationSettings)
                }.onSuccess { generatedFiles ->
                    val pathParams: String = generationSettings.toQueryParams()
                    call.response.header("HX-Replace-Url", "/?$pathParams")

                    val fileNames = generatedFiles.fileNamesWithLabels()

                    call.respondHtmlFragmentDiv {
                        if (generatedFiles.isEmpty()) {
                            fileView("// No files generated. Try adjusting your settings.")
                        } else {
                            generatedFiles.forEach {
                                fileViewForFile(it)
                            }
                        }
                        script { unsafe { +"Prism.highlightAll();" } } // trigger syntax highlighting
                        // OOB swap: update the file sidebar
                        div {
                            id = "file-sidebar"
                            attributes["hx-swap-oob"] = "innerHTML"
                            if (fileNames.isNotEmpty()) fileList(fileNames)
                        }
                    }
                }.onFailure { error ->
                    call.respondHtmlFragmentDiv {
                        fileView("// Error: ${error.message}")
                        script { unsafe { +"Prism.highlightAll();" } } // trigger syntax highlighting
                        // OOB swap: clear the file sidebar on error
                        div {
                            id = "file-sidebar"
                            attributes["hx-swap-oob"] = "innerHTML"
                        }
                    }
                }
            }
        }
    }.start(wait = true)
}

private fun List<GeneratedFile>.fileNamesWithLabels(): List<Pair<String, String>> = this.flatMap { generatedFile ->
    when (generatedFile) {
        is KotlinSourceSet -> {
            val label = generatedFile.files.firstOrNull()?.packageName?.let { pkg ->
                when {
                    "models" in pkg -> "Model"
                    "client" in pkg -> "Client"
                    "controllers" in pkg -> "Controller"
                    else -> "Config"
                }
            } ?: "Config"
            generatedFile.files.map { it.name to label }
        }
        is SimpleFile -> listOf(generatedFile.path.fileName.toString() to "Config")
        is ResourceFile -> listOf(generatedFile.fileName to "Config")
        is ResourceSourceSet -> generatedFile.files.map { it.fileName to "Config" }
    }
}
