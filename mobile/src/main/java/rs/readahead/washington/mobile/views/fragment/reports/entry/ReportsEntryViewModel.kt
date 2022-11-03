package rs.readahead.washington.mobile.views.fragment.reports.entry

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.hzontal.tella_vault.VaultFile
import dagger.hilt.android.lifecycle.HiltViewModel
import rs.readahead.washington.mobile.domain.entity.EntityStatus
import rs.readahead.washington.mobile.domain.entity.collect.FormMediaFile
import rs.readahead.washington.mobile.domain.entity.collect.FormMediaFileStatus
import rs.readahead.washington.mobile.domain.entity.reports.ReportFormInstance
import rs.readahead.washington.mobile.domain.entity.reports.TellaReportServer
import rs.readahead.washington.mobile.domain.usecases.reports.GetReportsServersUseCase
import rs.readahead.washington.mobile.domain.usecases.reports.GetReportsUseCase
import rs.readahead.washington.mobile.domain.usecases.reports.SaveReportFormInstanceUseCase
import javax.inject.Inject


@HiltViewModel
class ReportsEntryViewModel @Inject constructor(
    private val getReportsServersUseCase: GetReportsServersUseCase,
    private val saveReportFormInstanceUseCase: SaveReportFormInstanceUseCase,
    private val getReportsUseCase: GetReportsUseCase
) :
    ViewModel() {

    private val _progress = MutableLiveData<Boolean>()
    val progress: LiveData<Boolean> get() = _progress
    private val _serversList = MutableLiveData<List<TellaReportServer>>()
    val serversList: LiveData<List<TellaReportServer>> get() = _serversList
    private var _error = MutableLiveData<Throwable>()
    val error: LiveData<Throwable> get() = _error
    private val _draftReportFormInstance = MutableLiveData<ReportFormInstance>()
    val draftReportFormInstance: LiveData<ReportFormInstance> get() = _draftReportFormInstance
    private val _outboxReportFormInstance = MutableLiveData<ReportFormInstance>()
    val outboxReportFormInstance: LiveData<ReportFormInstance> get() = _outboxReportFormInstance

    init {
        listServers()
    }

    private fun listServers() {
        _progress.postValue(true)
        getReportsServersUseCase.execute(
            onSuccess = { result ->
                _serversList.postValue(result)
            },
            onError = {
                _error.postValue(it)
            },
            onFinished = {
                _progress.postValue(false)
            }
        )
    }

    fun saveDraft(reportFormInstance: ReportFormInstance) {
        _progress.postValue(true)
        saveReportFormInstanceUseCase.setReportFormInstance(reportFormInstance)
        saveReportFormInstanceUseCase.execute(
            onSuccess = { result ->
                _draftReportFormInstance.postValue(result)
            },
            onError = {
                _error.postValue(it)
            },
            onFinished = {
                _progress.postValue(false)
            }
        )
    }

    fun saveOutbox(reportFormInstance: ReportFormInstance) {
        _progress.postValue(true)
        saveReportFormInstanceUseCase.setReportFormInstance(reportFormInstance)
        saveReportFormInstanceUseCase.execute(
            onSuccess = { result ->
                _outboxReportFormInstance.postValue(result)
            },
            onError = {
                _error.postValue(it)
            },
            onFinished = {
                _progress.postValue(false)
            }
        )
    }

    private fun listDrafts() {

    }

    private fun listOutbox() {

    }

    private fun listSubmitted() {

    }

    fun getDraftFormInstance(
        title: String,
        description: String,
        files: List<FormMediaFile>?,
        server: TellaReportServer
    ): ReportFormInstance {
        return ReportFormInstance(
            title = title,
            description = description,
            status = EntityStatus.DRAFT,
            widgetMediaFiles = files ?: emptyList(),
            formPartStatus = FormMediaFileStatus.NOT_SUBMITTED,
            serverId = server.id
        )
    }

    fun getOutboxFormInstance(
        title: String,
        description: String,
        files: List<FormMediaFile>?,
        server: TellaReportServer
    ): ReportFormInstance {
        return ReportFormInstance(
            title = title,
            description = description,
            status = EntityStatus.FINALIZED,
            widgetMediaFiles = files ?: emptyList(),
            formPartStatus = FormMediaFileStatus.NOT_SUBMITTED,
            serverId = server.id
        )
    }

    fun vaultFilesToMediaFiles(files: List<VaultFile>): List<FormMediaFile> {
        val vaultFiles = mutableListOf<FormMediaFile>()
        files.map { vaultFile ->
            vaultFiles.add(FormMediaFile.fromMediaFile(vaultFile))
        }

        return vaultFiles
    }
}

