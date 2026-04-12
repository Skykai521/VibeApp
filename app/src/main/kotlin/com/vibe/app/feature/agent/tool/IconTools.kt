package com.vibe.app.feature.agent.tool

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.AgentToolCall
import com.vibe.app.feature.agent.AgentToolContext
import com.vibe.app.feature.agent.AgentToolDefinition
import com.vibe.app.feature.agent.AgentToolResult
import com.vibe.app.feature.project.ProjectManager
import com.vibe.app.feature.projecticon.ProjectIconRenderer
import com.vibe.app.feature.projecticon.iconlibrary.IconLibrary
import com.vibe.app.feature.projecticon.iconlibrary.IconVectorDrawableBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

private const val ICON_BACKGROUND_PATH = "src/main/res/drawable/ic_launcher_background.xml"
private const val ICON_FOREGROUND_PATH = "src/main/res/drawable/ic_launcher_foreground.xml"

@Singleton
class SearchIconTool @Inject constructor(
    private val iconLibrary: IconLibrary,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "search_icon",
        description = "Search the bundled Lucide icon library by keyword. " +
            "Returns a list of icon ids you can pass to update_project_icon. " +
            "Prefer this over writing raw vector XML — the preset icons are " +
            "guaranteed to render correctly. Try a few broad keywords " +
            "(e.g. 'house', 'calculator', 'cloud').",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("keyword", stringProp("Keyword to search, e.g. 'house' or 'calculator'."))
                    put("limit", intProp("Maximum hits to return, default 20, max 50."))
                },
            )
            put("required", requiredFields("keyword"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val keyword = call.arguments.requireString("keyword")
        val limit = call.arguments.optionalInt("limit", 20).coerceIn(1, 50)
        val hits = iconLibrary.search(keyword, limit)
        val output = buildJsonObject {
            put("total", JsonPrimitive(hits.size))
            put(
                "hits",
                buildJsonArray {
                    for (hit in hits) {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(hit.id))
                                if (hit.categories.isNotEmpty()) {
                                    put(
                                        "categories",
                                        buildJsonArray {
                                            hit.categories.forEach { add(JsonPrimitive(it)) }
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }
        return call.result(output)
    }
}

@Singleton
class UpdateProjectIconTool @Inject constructor(
    private val projectManager: ProjectManager,
    private val iconLibrary: IconLibrary,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "update_project_icon",
        description = "Set the launcher icon from the preset icon library. " +
            "First call search_icon to pick an iconId, then call this with " +
            "a foreground color and a background style. Colors are #RRGGBB hex.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("iconId", stringProp("Icon id from search_icon, e.g. 'house'."))
                    put("foregroundColor", stringProp("Stroke color for the icon, #RRGGBB hex."))
                    put(
                        "backgroundStyle",
                        stringProp("One of: solid | linearGradient | radialGradient."),
                    )
                    put("backgroundColor1", stringProp("Primary background color, #RRGGBB hex."))
                    put(
                        "backgroundColor2",
                        stringProp("Secondary color for gradients. Optional for solid."),
                    )
                },
            )
            put(
                "required",
                requiredFields("iconId", "foregroundColor", "backgroundStyle", "backgroundColor1"),
            )
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val iconId = call.arguments.requireString("iconId")
        val foreground = call.arguments.requireString("foregroundColor")
        val styleStr = call.arguments.requireString("backgroundStyle")
        val bg1 = call.arguments.requireString("backgroundColor1")
        val bg2 = call.arguments.optionalString("backgroundColor2")

        val icon = iconLibrary.get(iconId)
            ?: return call.errorResult(
                "Unknown iconId '$iconId'. Call search_icon first to pick a valid id.",
            )

        val style = when (styleStr.lowercase()) {
            "solid" -> IconVectorDrawableBuilder.BackgroundStyle.SOLID
            "lineargradient", "linear", "linear_gradient" ->
                IconVectorDrawableBuilder.BackgroundStyle.LINEAR_GRADIENT
            "radialgradient", "radial", "radial_gradient" ->
                IconVectorDrawableBuilder.BackgroundStyle.RADIAL_GRADIENT
            else -> return call.errorResult(
                "backgroundStyle must be one of: solid, linearGradient, radialGradient.",
            )
        }

        val result = IconVectorDrawableBuilder.build(
            IconVectorDrawableBuilder.Request(
                icon = icon,
                foregroundColor = foreground,
                backgroundStyle = style,
                backgroundColor1 = bg1,
                backgroundColor2 = bg2,
            ),
        )

        val workspace = projectManager.openWorkspace(context.projectId)
        workspace.writeTextFile(ICON_BACKGROUND_PATH, result.backgroundXml)
        workspace.writeTextFile(ICON_FOREGROUND_PATH, result.foregroundXml)
        ProjectIconRenderer.renderPngIcons(workspace.rootDir.absolutePath)
        return call.okResult()
    }
}

@Singleton
class UpdateProjectIconCustomTool @Inject constructor(
    private val projectManager: ProjectManager,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "update_project_icon_custom",
        description = "Escape hatch: set the launcher icon by writing raw " +
            "vector-drawable XML directly. ONLY use this when search_icon " +
            "has no suitable icon across multiple keywords. Prefer " +
            "update_project_icon in all other cases — hand-written path " +
            "geometry is rarely correct.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "backgroundXml",
                        stringProp("Complete XML for src/main/res/drawable/ic_launcher_background.xml"),
                    )
                    put(
                        "foregroundXml",
                        stringProp("Complete XML for src/main/res/drawable/ic_launcher_foreground.xml"),
                    )
                },
            )
            put("required", requiredFields("backgroundXml", "foregroundXml"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val backgroundXml = call.arguments.requireString("backgroundXml")
        val foregroundXml = call.arguments.requireString("foregroundXml")
        val workspace = projectManager.openWorkspace(context.projectId)
        workspace.writeTextFile(ICON_BACKGROUND_PATH, backgroundXml)
        workspace.writeTextFile(ICON_FOREGROUND_PATH, foregroundXml)
        ProjectIconRenderer.renderPngIcons(workspace.rootDir.absolutePath)
        return call.okResult()
    }
}
