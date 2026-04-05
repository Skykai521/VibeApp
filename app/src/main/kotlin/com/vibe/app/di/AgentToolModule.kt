package com.vibe.app.di

import com.vibe.app.feature.agent.AgentTool
import com.vibe.app.feature.agent.tool.DeleteProjectFileTool
import com.vibe.app.feature.agent.tool.EditProjectFileTool
import com.vibe.app.feature.agent.tool.FixCrashGuideTool
import com.vibe.app.feature.agent.tool.InspectUiTool
import com.vibe.app.feature.agent.tool.InteractUiTool
import com.vibe.app.feature.agent.tool.ListProjectFilesTool
import com.vibe.app.feature.agent.tool.ReadProjectFileTool
import com.vibe.app.feature.agent.tool.ReadRuntimeLogTool
import com.vibe.app.feature.agent.tool.RenameProjectTool
import com.vibe.app.feature.agent.tool.RunBuildPipelineTool
import com.vibe.app.feature.agent.tool.UpdateProjectIconTool
import com.vibe.app.feature.agent.tool.WriteProjectFileTool
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
    @Binds @IntoSet abstract fun bindRunBuildPipeline(tool: RunBuildPipelineTool): AgentTool
    @Binds @IntoSet abstract fun bindRenameProject(tool: RenameProjectTool): AgentTool
    @Binds @IntoSet abstract fun bindUpdateProjectIcon(tool: UpdateProjectIconTool): AgentTool
    @Binds @IntoSet abstract fun bindReadRuntimeLog(tool: ReadRuntimeLogTool): AgentTool
    @Binds @IntoSet abstract fun bindFixCrashGuide(tool: FixCrashGuideTool): AgentTool
    @Binds @IntoSet abstract fun bindInspectUi(tool: InspectUiTool): AgentTool
    @Binds @IntoSet abstract fun bindInteractUi(tool: InteractUiTool): AgentTool
}
