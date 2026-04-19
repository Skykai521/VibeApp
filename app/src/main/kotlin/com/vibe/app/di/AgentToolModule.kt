package com.vibe.app.di

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.tool.DeleteProjectFileTool
import com.vibe.app.feature.agent.tool.EditProjectFileTool
import com.vibe.app.feature.agent.tool.FixCrashGuideTool
import com.vibe.app.feature.agent.tool.CloseAppTool
import com.vibe.app.feature.agent.tool.InspectUiTool
import com.vibe.app.feature.agent.tool.LaunchAppTool
import com.vibe.app.feature.agent.tool.InteractUiTool
import com.vibe.app.feature.agent.tool.ListProjectFilesTool
import com.vibe.app.feature.agent.tool.ReadProjectFileTool
import com.vibe.app.feature.agent.tool.ReadRuntimeLogTool
import com.vibe.app.feature.agent.tool.RenameProjectTool
import com.vibe.app.feature.agent.tool.RunBuildPipelineTool
import com.vibe.app.feature.agent.tool.SearchIconTool
import com.vibe.app.feature.agent.tool.UpdateProjectIconCustomTool
import com.vibe.app.feature.agent.tool.UpdateProjectIconTool
import com.vibe.app.feature.agent.tool.WriteProjectFileTool
import com.vibe.app.feature.agent.tool.CreatePlanTool
import com.vibe.app.feature.agent.tool.UpdatePlanStepTool
import com.vibe.app.feature.agent.tool.WebSearchTool
import com.vibe.app.feature.agent.tool.FetchWebPageTool
import com.vibe.app.feature.agent.tool.GrepProjectFilesTool
import com.vibe.app.feature.agent.tool.SearchUiPatternTool
import com.vibe.app.feature.agent.tool.GetUiPatternTool
import com.vibe.app.feature.agent.tool.GetDesignGuideTool
import com.vibe.app.feature.agent.tool.GetProjectMemoTool
import com.vibe.app.feature.agent.tool.UpdateProjectIntentTool
import com.vibe.app.feature.agent.tool.AddDependencyV2Tool
import com.vibe.app.feature.agent.tool.AssembleDebugV2Tool
import com.vibe.app.feature.agent.tool.CreateComposeProjectTool
import com.vibe.app.feature.agent.tool.ExportProjectSourceV2Tool
import com.vibe.app.feature.agent.tool.InstallApkV2Tool
import com.vibe.app.feature.agent.tool.RemoveDependencyV2Tool
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentToolModule {

    @Binds @IntoSet abstract fun bindReadProjectFile(tool: ReadProjectFileTool): AgentTool
    @Binds @IntoSet abstract fun bindWriteProjectFile(tool: WriteProjectFileTool): AgentTool
    @Binds @IntoSet abstract fun bindEditProjectFile(tool: EditProjectFileTool): AgentTool
    @Binds @IntoSet abstract fun bindDeleteProjectFile(tool: DeleteProjectFileTool): AgentTool
    @Binds @IntoSet abstract fun bindListProjectFiles(tool: ListProjectFilesTool): AgentTool
    @Binds @IntoSet abstract fun bindGrepProjectFiles(tool: GrepProjectFilesTool): AgentTool
    @Binds @IntoSet abstract fun bindRunBuildPipeline(tool: RunBuildPipelineTool): AgentTool
    @Binds @IntoSet abstract fun bindRenameProject(tool: RenameProjectTool): AgentTool
    @Binds @IntoSet abstract fun bindSearchIcon(tool: SearchIconTool): AgentTool
    @Binds @IntoSet abstract fun bindUpdateProjectIcon(tool: UpdateProjectIconTool): AgentTool
    @Binds @IntoSet abstract fun bindUpdateProjectIconCustom(tool: UpdateProjectIconCustomTool): AgentTool
    @Binds @IntoSet abstract fun bindReadRuntimeLog(tool: ReadRuntimeLogTool): AgentTool
    @Binds @IntoSet abstract fun bindFixCrashGuide(tool: FixCrashGuideTool): AgentTool
    @Binds @IntoSet abstract fun bindLaunchApp(tool: LaunchAppTool): AgentTool
    @Binds @IntoSet abstract fun bindInspectUi(tool: InspectUiTool): AgentTool
    @Binds @IntoSet abstract fun bindInteractUi(tool: InteractUiTool): AgentTool
    @Binds @IntoSet abstract fun bindCloseApp(tool: CloseAppTool): AgentTool
    @Binds @IntoSet abstract fun bindCreatePlan(tool: CreatePlanTool): AgentTool
    @Binds @IntoSet abstract fun bindUpdatePlanStep(tool: UpdatePlanStepTool): AgentTool
    @Binds @IntoSet abstract fun bindWebSearch(tool: WebSearchTool): AgentTool
    @Binds @IntoSet abstract fun bindFetchWebPage(tool: FetchWebPageTool): AgentTool
    @Binds @IntoSet abstract fun bindSearchUiPattern(tool: SearchUiPatternTool): AgentTool
    @Binds @IntoSet abstract fun bindGetUiPattern(tool: GetUiPatternTool): AgentTool
    @Binds @IntoSet abstract fun bindGetDesignGuide(tool: GetDesignGuideTool): AgentTool
    @Binds @IntoSet abstract fun bindUpdateProjectIntent(tool: UpdateProjectIntentTool): AgentTool
    @Binds @IntoSet abstract fun bindGetProjectMemo(tool: GetProjectMemoTool): AgentTool

    // ── v2 (GRADLE_COMPOSE) tools ──────────────────────────────────
    @Binds @IntoSet abstract fun bindCreateComposeProject(tool: CreateComposeProjectTool): AgentTool
    @Binds @IntoSet abstract fun bindAssembleDebugV2(tool: AssembleDebugV2Tool): AgentTool
    @Binds @IntoSet abstract fun bindInstallApkV2(tool: InstallApkV2Tool): AgentTool
    @Binds @IntoSet abstract fun bindAddDependencyV2(tool: AddDependencyV2Tool): AgentTool
    @Binds @IntoSet abstract fun bindRemoveDependencyV2(tool: RemoveDependencyV2Tool): AgentTool
    @Binds @IntoSet abstract fun bindExportProjectSourceV2(tool: ExportProjectSourceV2Tool): AgentTool
}
