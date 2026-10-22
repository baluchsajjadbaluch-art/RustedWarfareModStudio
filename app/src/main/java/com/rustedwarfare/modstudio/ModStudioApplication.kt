package com.rustedwarfare.modstudio

import android.app.Application
import com.rustedwarfare.modstudio.archive.ArchiveRepository
import com.rustedwarfare.modstudio.audio.AudioRepository
import com.rustedwarfare.modstudio.core.RecentsStore
import com.rustedwarfare.modstudio.core.SettingsStore
import com.rustedwarfare.modstudio.editor.EditorRepository
import com.rustedwarfare.modstudio.export.ExportRepository
import com.rustedwarfare.modstudio.image.ImageRepository
import com.rustedwarfare.modstudio.project.ProjectRepository
import com.rustedwarfare.modstudio.validator.ModValidator
import com.rustedwarfare.modstudio.wizard.WizardRepository

/**
 * Simple service locator holding the app-wide repositories.
 * Everything stays offline and in-process.
 */
class ModStudioApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(app: ModStudioApplication) {
    val settings = SettingsStore(app)
    val recents = RecentsStore(app)
    val project = ProjectRepository(app, recents)
    val archive = ArchiveRepository(app)
    val editor = EditorRepository(app, archive, settings, project)
    val validator = ModValidator(app)
    val image = ImageRepository(app, archive)
    val audio = AudioRepository(app, archive)
    val export = ExportRepository(app, archive, project)
    val wizard = WizardRepository(app)
}
