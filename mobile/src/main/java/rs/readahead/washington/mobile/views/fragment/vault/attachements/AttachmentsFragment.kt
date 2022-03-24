package rs.readahead.washington.mobile.views.fragment.vault.attachements

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hzontal.tella_locking_ui.common.extensions.toggleVisibility
import com.hzontal.tella_locking_ui.ui.pin.pinview.ResourceUtils.getColor
import com.hzontal.tella_vault.VaultFile
import com.hzontal.tella_vault.filter.FilterType
import com.hzontal.tella_vault.filter.Sort
import com.hzontal.utils.MediaFile.isAudioFileType
import com.hzontal.utils.MediaFile.isImageFileType
import com.hzontal.utils.MediaFile.isVideoFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hzontal.shared_ui.appbar.ToolbarComponent
import org.hzontal.shared_ui.bottomsheet.BottomSheetUtils
import org.hzontal.shared_ui.bottomsheet.BottomSheetUtils.ActionConfirmed
import org.hzontal.shared_ui.bottomsheet.BottomSheetUtils.showConfirmSheet
import org.hzontal.shared_ui.bottomsheet.VaultSheetUtils
import org.hzontal.shared_ui.breadcrumb.BreadcrumbsView
import org.hzontal.shared_ui.breadcrumb.DefaultBreadcrumbsCallback
import org.hzontal.shared_ui.breadcrumb.model.BreadcrumbItem
import org.hzontal.shared_ui.breadcrumb.model.Item
import org.hzontal.shared_ui.utils.DialogUtils
import rs.readahead.washington.mobile.MyApplication
import rs.readahead.washington.mobile.R
import rs.readahead.washington.mobile.bus.EventObserver
import rs.readahead.washington.mobile.bus.event.CaptureEvent
import rs.readahead.washington.mobile.bus.event.MediaFileDeletedEvent
import rs.readahead.washington.mobile.bus.event.VaultFileRenameEvent
import rs.readahead.washington.mobile.data.sharedpref.Preferences
import rs.readahead.washington.mobile.media.MediaFileHandler
import rs.readahead.washington.mobile.media.MediaFileHandler.walkAllFilesWithDirectories
import rs.readahead.washington.mobile.util.*
import rs.readahead.washington.mobile.views.activity.*
import rs.readahead.washington.mobile.views.activity.CameraActivity.VAULT_CURRENT_ROOT_PARENT
import rs.readahead.washington.mobile.views.base_ui.BaseFragment
import rs.readahead.washington.mobile.views.fragment.vault.adapters.attachments.AttachmentsRecycleViewAdapter
import rs.readahead.washington.mobile.views.fragment.vault.adapters.attachments.IGalleryVaultHandler
import rs.readahead.washington.mobile.views.fragment.vault.home.VAULT_FILTER
import rs.readahead.washington.mobile.views.fragment.vault.info.VAULT_FILE_INFO_TOOLBAR
import timber.log.Timber
import java.util.*
import kotlin.collections.ArrayList


const val VAULT_FILE_ARG = "VaultFileArg"
const val WRITE_REQUEST_CODE = 1002

class AttachmentsFragment : BaseFragment(), View.OnClickListener,
    IGalleryVaultHandler,
    IAttachmentsPresenter.IView,
    OnNavBckListener {
    private lateinit var attachmentsRecyclerView: RecyclerView
    private val attachmentsAdapter by lazy {
        AttachmentsRecycleViewAdapter(
            activity, this,
            MediaFileHandler(), gridLayoutManager
        )
    }
    private val attachmentsPresenter by lazy { AttachmentsPresenter(this) }
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var detailsFab: FloatingActionButton
    private lateinit var toolbar: ToolbarComponent
    private lateinit var listCheck: ImageView
    private lateinit var gridCheck: ImageView
    private lateinit var filterNameTv: TextView
    private lateinit var cancelMove: TextView
    private lateinit var moveHere: TextView
    private lateinit var emptyViewMsgContainer: LinearLayout
    private lateinit var checkBoxList: AppCompatImageView
    private lateinit var root: View
    private lateinit var breadcrumbView: BreadcrumbsView
    private lateinit var appBar: AppBarLayout
    private lateinit var moveContainer: LinearLayout
    private var progressDialog: ProgressDialog? = null
    private val disposables by lazy { MyApplication.bus().createCompositeDisposable() }
    private var filterType = FilterType.ALL
    private lateinit var sort: Sort
    private var vaultFile: VaultFile? = null
    private var currentRootID: String? = null
    private var currentMove: String? = null
    private var isListCheckOn = false
    private var isMoveModeEnabled = false
    private var importAndDelete = false
    private var uriToDelete: Uri? = null
    private val bundle by lazy { Bundle() }
    private var selectAll = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        setHasOptionsMenu(true)
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_vault_attachments, container, false)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        if (isListCheckOn && !isMoveModeEnabled) {
            inflater.inflate(R.menu.home_menu_selected, menu)
            maybeShowUploadIcon(menu)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_more -> {
                if (attachmentsAdapter.selectedMediaFiles.size > 0) {
                    showFileActionsSheet(null, true)
                }
                true
            }
            R.id.action_share -> {
                startShareActivity(false)
                return true
            }

            R.id.action_check -> {
                selectAll = !selectAll
                if (selectAll) {
                    attachmentsAdapter.selectAll()
                } else {
                    attachmentsAdapter.clearSelected()
                }
                updateAttachmentsToolbar(true)
                return true
            }

            R.id.action_upload -> {
                exportVaultFiles(true, null)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setToolbarLabel() {
        when (filterType) {
            FilterType.PHOTO -> toolbar.setStartTextTitle(getString(R.string.Vault_Images_Title))
            FilterType.VIDEO -> toolbar.setStartTextTitle(getString(R.string.Vault_Videos_Title))
            FilterType.AUDIO -> toolbar.setStartTextTitle(getString(R.string.Vault_Audios_Title))
            FilterType.DOCUMENTS -> toolbar.setStartTextTitle(getString(R.string.Vault_Documents_Title))
            FilterType.OTHERS -> toolbar.setStartTextTitle(getString(R.string.Vault_Others_Title))
            FilterType.ALL -> toolbar.setStartTextTitle(getString(R.string.Vault_AllFiles_Title))
            FilterType.PHOTO_VIDEO -> toolbar.setStartTextTitle(getString(R.string.Vault_PhotosAndVideos_Title))
        }
    }

    private fun setUpToolbar() {
        val activity = context as MainActivity
        activity.setSupportActionBar(toolbar)
    }

    override fun initView(view: View) {
        breadcrumbView = view.findViewById(R.id.breadcrumbs_view)
        attachmentsRecyclerView = view.findViewById(R.id.attachmentsRecyclerView)
        listCheck = view.findViewById(R.id.listCheck)
        gridCheck = view.findViewById(R.id.gridCheck)
        emptyViewMsgContainer = view.findViewById(R.id.emptyViewMsgContainer)
        filterNameTv = view.findViewById(R.id.filterNameTv)
        cancelMove = view.findViewById(R.id.cancelMove)
        moveHere = view.findViewById(R.id.moveHere)
        moveContainer = view.findViewById(R.id.moveContainer)
        toolbar = view.findViewById(R.id.toolbar)
        root = view.findViewById(R.id.root)
        appBar = view.findViewById(R.id.appbar)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            appBar.outlineProvider = null
        } else {
            appBar.bringToFront()
        }
        gridLayoutManager = GridLayoutManager(activity, 1)
        attachmentsRecyclerView.apply {
            adapter = attachmentsAdapter
            layoutManager = gridLayoutManager
        }
        detailsFab = view.findViewById(R.id.fab_button)
        checkBoxList = view.findViewById(R.id.checkBoxList)
        // enableMoveTheme()
        initListeners()
        setUpToolbar()
        initData()
        setUpBreadCrumb()
    }

    private fun initListeners() {
        detailsFab.setOnClickListener(this)
        listCheck.setOnClickListener(this)
        gridCheck.setOnClickListener(this)
        checkBoxList.setOnClickListener(this)
        filterNameTv.setOnClickListener(this)
        moveHere.setOnClickListener(null)
        cancelMove.setOnClickListener(this)
    }

    private fun initData() {
        arguments?.getString(VAULT_FILTER)?.let {
            filterType = FilterType.valueOf(it)
        }
        initSorting()
        setToolbarLabel()
        attachmentsPresenter.getRootId()
        onFileDeletedEventListener()
        onFileRenameEventListener()
        onCaptureEventListener()
        initViewType()
    }

    private fun initSorting() {
        sort = Sort()
        sort.type = Sort.Type.NAME
        sort.direction = Sort.Direction.ASC
    }

    private fun initViewType() {
        if (filterType == FilterType.PHOTO_VIDEO) {
            setGridView()
        }
    }

    private fun setUpBreadCrumb() {
        breadcrumbView.setCallback(object : DefaultBreadcrumbsCallback<BreadcrumbItem?>() {
            override fun onNavigateBack(item: BreadcrumbItem?, position: Int) {
                if (position == 0) {
                    breadcrumbView.visibility = View.GONE
                }
                currentRootID = item?.items?.get(item.selectedIndex)?.id
                attachmentsPresenter.addNewVaultFiles()
                if (isMoveModeEnabled) highlightMoveBackground()
            }

            override fun onNavigateNewLocation(newItem: BreadcrumbItem?, changedPosition: Int) {
                showToast(changedPosition.toString())
                currentRootID = newItem?.items?.get(newItem.selectedIndex)?.id
                attachmentsPresenter.addNewVaultFiles()
                if (isMoveModeEnabled) highlightMoveBackground()
            }
        })
    }

    private fun setGridView() {
        gridCheck.toggleVisibility(false)
        listCheck.toggleVisibility(true)
        gridLayoutManager.spanCount = 4
        attachmentsAdapter.setLayoutManager(gridLayoutManager)
        attachmentsAdapter.notifyItemRangeChanged(0, attachmentsAdapter.itemCount)
        attachmentsRecyclerView.setMargins(leftMarginDp = 13,rightMarginDp = 13)
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.gridCheck -> {
                setGridView()
            }
            R.id.listCheck -> {
                gridCheck.toggleVisibility(true)
                listCheck.toggleVisibility(false)
                gridLayoutManager.spanCount = 1
                attachmentsAdapter.setLayoutManager(gridLayoutManager)
                attachmentsAdapter.notifyItemRangeChanged(0, attachmentsAdapter.itemCount)
                attachmentsRecyclerView.setMargins(leftMarginDp = 0,rightMarginDp = 0)
            }
            R.id.checkBoxList -> {
                handleSelectMode()
            }
            R.id.filterNameTv -> {
                handleSortSheet()
            }
            R.id.fab_button -> {
                VaultSheetUtils.showVaultManageFilesSheet(
                    activity.supportFragmentManager,
                    getString(R.string.Vault_TakePhotoVideo_SheetAction),
                    getString(R.string.Vault_RecordAudio_SheetAction),
                    getString(R.string.Vault_Import_SheetAction),
                    getString(R.string.Vault_ImportDelete_SheetAction),
                    getString(R.string.Vault_CreateFolder_SheetAction),
                    getString(R.string.Vault_ManageFiles_SheetTitle),
                    getString(R.string.Vault_DeleteFile_SheetDesc),
                    filterType != FilterType.OTHERS,
                    action = object : VaultSheetUtils.IVaultManageFiles {
                        override fun goToCamera() {
                            val intent = Intent(activity, CameraActivity::class.java)
                            intent.putExtra(VAULT_CURRENT_ROOT_PARENT, currentRootID)
                            activity.startActivity(intent)
                        }

                        override fun goToRecorder() {
                            bundle.putString(VAULT_CURRENT_ROOT_PARENT, currentRootID)
                            nav().navigate(R.id.action_attachments_screen_to_micScreen, bundle)
                        }

                        override fun import() {
                            importAndDelete = false
                            activity.changeTemporaryTimeout()
                            MediaFileHandler.startImportFiles(activity, true,getCurrentType())
                        }

                        override fun importAndDelete() {
                            importAndDelete = true
                            MediaFileHandler.startImportFiles(activity, true,getCurrentType())
                        }

                        override fun createFolder() {
                            VaultSheetUtils.showVaultRenameSheet(
                                activity.supportFragmentManager,
                                getString(R.string.Vault_CreateFolder_SheetAction),
                                getString(R.string.action_cancel),
                                getString(R.string.action_ok),
                                requireActivity(),
                                null
                            ) {
                                currentRootID?.let { root ->
                                    attachmentsPresenter.createFolder(
                                        it,
                                        root
                                    )
                                }

                            }

                        }

                    }
                )
            }
            R.id.moveHere -> {
                if (attachmentsAdapter.selectedMediaFiles.size > 0) {
                    attachmentsPresenter.moveFiles(
                        currentRootID,
                        attachmentsAdapter.selectedMediaFiles
                    )
                }
            }
            R.id.cancelMove -> {
                isMoveModeEnabled = false
                enableMoveTheme(enable = false)
                attachmentsAdapter.clearSelected()
                updateAttachmentsToolbar(false)
            }
        }
    }

    private fun handleSelectMode() {
        isListCheckOn = !isListCheckOn
        attachmentsAdapter.enableSelectMode(isListCheckOn)
        updateAttachmentsToolbar(isListCheckOn)
        if (!isListCheckOn) {
            attachmentsAdapter.clearSelected()
            enableMoveTheme(false)
        }
    }

    private fun updateAttachmentsToolbar(isItemsChecked: Boolean) {
        activity.invalidateOptionsMenu()

        if (isItemsChecked) {
            toolbar.setToolbarNavigationIcon(R.drawable.ic_close_white_24dp)
            val itemsSize = attachmentsAdapter.selectedMediaFiles.size
            toolbar.setToolbarNavigationIcon(R.drawable.ic_close_white_24dp)
            if (itemsSize == 0) {
                toolbar.setStartTextTitle(getString(R.string.Vault_Select_Title))
            } else {
                toolbar.setStartTextTitle(attachmentsAdapter.selectedMediaFiles.size.toString() + " "+getString(R.string.Vault_Items))
            }
        } else {
            toolbar.setToolbarNavigationIcon(R.drawable.ic_arrow_back_white_24dp)
            setToolbarLabel()

            attachmentsAdapter.clearSelected()
            enableMoveTheme(false)
        }
    }

    override fun playMedia(vaultFile: VaultFile) {
        when (vaultFile.type) {
            VaultFile.Type.DIRECTORY -> {
                if (isMoveModeEnabled) {
                    if (isMoveModeEnabled && attachmentsAdapter.selectedMediaFiles.contains(
                            vaultFile
                        )
                    ) return
                    openDirectory(vaultFile)
                } else {
                    attachmentsAdapter.clearSelected()
                    isListCheckOn = false
                    updateAttachmentsToolbar(false)
                    openDirectory(vaultFile)
                }

            }
            VaultFile.Type.FILE -> {
                if (vaultFile.mimeType != null) {
                    when {
                        isImageFileType(vaultFile.mimeType) -> {
                            val intent = Intent(activity, PhotoViewerActivity::class.java)
                            intent.putExtra(PhotoViewerActivity.VIEW_PHOTO, vaultFile)
                            startActivity(intent)
                        }
                        isAudioFileType(vaultFile.mimeType) -> {
                            val intent = Intent(activity, AudioPlayActivity::class.java)
                            intent.putExtra(AudioPlayActivity.PLAY_MEDIA_FILE_ID_KEY, vaultFile.id)
                            startActivity(intent)
                        }
                        isVideoFileType(vaultFile.mimeType) -> {
                            val intent = Intent(activity, VideoViewerActivity::class.java)
                            intent.putExtra(VideoViewerActivity.VIEW_VIDEO, vaultFile)
                            startActivity(intent)
                        }
                        else -> {
                            BottomSheetUtils.showStandardSheet(
                                activity.supportFragmentManager,
                                activity.getString(R.string.Vault_Export_SheetAction) + " " + vaultFile.name + "?",
                                activity.getString(R.string.Vault_ViewerOther_SheetDesc),
                                activity.getString(R.string.Vault_Export_SheetAction),
                                activity.getString(R.string.action_cancel),
                                onConfirmClick = { exportVaultFiles(false, vaultFile) }
                            )
                        }
                    }
                }
            }
            else -> {

            }
        }
    }

    private fun openDirectory(vaultFile: VaultFile) {
        if (currentRootID != vaultFile.id) {
            currentRootID = vaultFile.id
            highlightMoveBackground()
            attachmentsPresenter.addNewVaultFiles()
            breadcrumbView.visibility = View.VISIBLE
            breadcrumbView.addItem(createItem(vaultFile))
        }
    }

    override fun onSelectionNumChange(num: Int) {
    }

    override fun onMediaSelected(vaultFile: VaultFile) {
        updateAttachmentsToolbar(!attachmentsAdapter.selectedMediaFiles.isNullOrEmpty())
    }

    override fun onMediaDeselected(vaultFile: VaultFile) {
        updateAttachmentsToolbar(!attachmentsAdapter.selectedMediaFiles.isNullOrEmpty())
    }

    override fun onMoreClicked(vaultFile: VaultFile) {
        showFileActionsSheet(vaultFile, false)
    }

    private fun showFileActionsSheet(vaultFile: VaultFile?, isMultipleFiles: Boolean) {
        VaultSheetUtils.showVaultActionsSheet(activity.supportFragmentManager,
            getSheetName(vaultFile, isMultipleFiles),
            getString(R.string.Vault_Upload_SheetAction),
            getString(R.string.Vault_Share_SheetAction),
            getString(R.string.Vault_Move_SheetDesc),
            getString(R.string.Vault_Rename_SheetAction),
            getString(R.string.gallery_action_desc_save_to_device),
            getString(R.string.Vault_File_SheetAction),
            getString(R.string.Vault_Delete_SheetAction),
            isDirectory = vaultFile?.type == VaultFile.Type.DIRECTORY,
            isMultipleFiles = isMultipleFiles,
            isUploadVisible = false,
            isMoveVisible = filterType == FilterType.ALL,
            action = object : VaultSheetUtils.IVaultActions {
                override fun upload() {
                }

                override fun share() {
                    MediaFileHandler.startShareActivity(activity, vaultFile, false)
                }

                override fun move() {
                    currentMove = currentRootID
                    if (vaultFile != null) {
                        isListCheckOn = true
                        attachmentsAdapter.enableSelectMode(isListCheckOn)
                        attachmentsAdapter.selectMediaFile(vaultFile)
                        updateAttachmentsToolbar(true)
                    }
                    isMoveModeEnabled = true
                    enableMoveTheme(enable = true)
                }

                override fun rename() {
                    VaultSheetUtils.showVaultRenameSheet(
                        activity.supportFragmentManager,
                        getString(R.string.Vault_RenameFile_SheetTitle),
                        getString(R.string.action_cancel),
                        getString(R.string.action_ok),
                        requireActivity(),
                        vaultFile?.name
                    ) {
                        vaultFile?.let { it1 -> attachmentsPresenter.renameVaultFile(it1.id, it) }
                    }
                }

                override fun save() {
                    showConfirmSheet(
                        activity.supportFragmentManager,
                        getString(R.string.gallery_save_to_device_dialog_title),
                        getString(R.string.gallery_save_to_device_dialog_expl),
                        getString(R.string.action_save),
                        getString(R.string.action_cancel),
                        consumer = object : ActionConfirmed {
                            override fun accept(isConfirmed: Boolean) {
                                this@AttachmentsFragment.vaultFile = vaultFile
                                exportVaultFiles(isMultipleFiles, vaultFile)
                            }
                        })
                }

                override fun info() {
                    vaultFile.let {
                        val bundle = Bundle()
                        bundle.putSerializable(VAULT_FILE_ARG, it)
                        bundle.putBoolean(VAULT_FILE_INFO_TOOLBAR, true)
                        nav().navigate(R.id.action_attachments_screen_to_info_screen, bundle)
                    }
                }

                override fun delete() {
                    showConfirmSheet(
                        activity.supportFragmentManager,
                        getString(R.string.Vault_DeleteFile_SheetTitle),
                        getString(R.string.Vault_deleteFile_SheetDesc),
                        getString(R.string.action_delete),
                        getString(R.string.action_cancel),
                        consumer = object : ActionConfirmed {
                            override fun accept(isConfirmed: Boolean) {
                                if (isMultipleFiles) {
                                    attachmentsPresenter.deleteVaultFiles(attachmentsAdapter.selectedMediaFiles)
                                } else {
                                    attachmentsPresenter.deleteVaultFile(vaultFile)
                                }

                            }
                        }
                    )

                }

            }

        )
    }

    override fun onGetFilesStart() {
    }

    override fun onGetFilesEnd() {
    }

    override fun onGetFilesSuccess(files: List<VaultFile?>) {
        if (files.isEmpty()) {
            attachmentsRecyclerView.visibility = View.GONE
            emptyViewMsgContainer.visibility = View.VISIBLE
        } else {
            attachmentsRecyclerView.visibility = View.VISIBLE
            emptyViewMsgContainer.visibility = View.GONE
        }
        attachmentsAdapter.setFiles(files)
        attachmentsAdapter.enableSelectMode(isListCheckOn)
    }

    override fun onGetFilesError(error: Throwable?) {
        Timber.d(error, javaClass.name)
    }

    override fun onMediaImported(vaultFile: List<VaultFile?>) {
        attachmentsPresenter.addNewVaultFiles()
    }

    override fun onMediaImportedWithDelete(vaultFile: List<VaultFile?>, uris: List<Uri?>) {
        for (uri in uris) {
            lifecycleScope.launch {
                uri?.let {

                    deleteFileFromExternalStorage(it)
                    uriToDelete = it
                }
            }
        }
        attachmentsPresenter.addNewVaultFiles()
    }

    override fun onImportError(error: Throwable?) {}

    override fun onImportStarted() {

    }

    override fun onImportEnded() {
    }

    override fun onMediaFilesAdded() {
        attachmentsPresenter.getFiles(currentRootID, filterType, sort)
    }

    override fun onMediaFilesAddingError(error: Throwable?) {
    }

    override fun onMediaFilesDeleted(num: Int) {
        attachmentsPresenter.addNewVaultFiles()
        isListCheckOn = !isListCheckOn
        updateAttachmentsToolbar(false)
    }

    override fun onMediaFilesDeletionError(throwable: Throwable?) {
    }

    override fun onMediaFileDeleted() {
        attachmentsPresenter.addNewVaultFiles()
    }

    override fun onMediaFileDeletionError(throwable: Throwable?) {
        DialogUtils.showBottomMessage(
            activity,
            getString(R.string.gallery_toast_fail_deleting_files),
            true
        )
    }

    override fun onMediaExported(num: Int) {
    }

    override fun onExportError(error: Throwable?) {
    }

    override fun onExportStarted() {
        progressDialog = DialogsUtil.showProgressDialog(
            activity,
            getString(R.string.gallery_save_to_device_dialog_progress_expl)
        )
        detailsFab.hide()
    }

    override fun onExportEnded() {
        hideProgressDialog()
        detailsFab.show()
    }

    override fun onCountTUServersEnded(num: Long?) {
    }

    override fun onCountTUServersFailed(throwable: Throwable?) {

    }

    override fun onRenameFileStart() {
        activity.toggleLoading(true)
    }

    override fun onRenameFileEnd() {
        activity.toggleLoading(false)
    }

    override fun onRenameFileSuccess() {
        attachmentsPresenter.addNewVaultFiles()
    }

    override fun onRenameFileError(error: Throwable?) {
        DialogUtils.showBottomMessage(activity, error?.localizedMessage, true)
    }

    override fun onCreateFolderSuccess() {
        attachmentsPresenter.addNewVaultFiles()
    }

    override fun onCreateFolderError(error: Throwable?) {

    }

    override fun onGetRootIdSuccess(vaultFile: VaultFile?) {
        currentRootID = vaultFile?.id
        breadcrumbView.addItem(BreadcrumbItem.createSimpleItem(Item("", vaultFile?.id)))
        attachmentsPresenter.addNewVaultFiles()
    }

    override fun onGetRootIdError(error: Throwable?) {

    }

    override fun onMoveFilesSuccess() {
        attachmentsPresenter.addNewVaultFiles()
        enableMoveTheme(false)
        currentMove = null
        updateAttachmentsToolbar(false)
    }

    override fun onMoveFilesError(error: Throwable?) {
        enableMoveTheme(false)
        currentMove = null
        updateAttachmentsToolbar(false)
    }

    private fun exportVaultFiles(isMultipleFiles: Boolean, vaultFile: VaultFile?) {
        if (hasStoragePermissions(activity)) {
            if (isMultipleFiles) {
                val selected: List<VaultFile> = attachmentsAdapter.selectedMediaFiles
                attachmentsPresenter.exportMediaFiles(selected)
            } else {
                vaultFile?.let { attachmentsPresenter.exportMediaFiles(arrayListOf(vaultFile)) }
            }
        } else {
            requestStoragePermissions()
        }
    }

    private fun hideProgressDialog() {
        if (progressDialog != null) {
            progressDialog?.dismiss()
            progressDialog = null
        }
    }

    private fun startShareActivity(includeMetadata: Boolean) {
        val selected: List<VaultFile> = attachmentsAdapter.selectedMediaFiles
        if (selected.isNullOrEmpty()) return
        if (selected.size > 1) {
            val attachments = walkAllFilesWithDirectories(selected)
            MediaFileHandler.startShareActivity(activity, attachments, includeMetadata)
        } else {
            if (selected[0].type == VaultFile.Type.DIRECTORY) {
                val attachments = walkAllFilesWithDirectories(selected)
                MediaFileHandler.startShareActivity(activity, attachments, includeMetadata)
            } else {
                MediaFileHandler.startShareActivity(activity, selected[0], includeMetadata)
            }
        }
    }

    @SuppressLint("NeedOnRequestPermissionsResult")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == WRITE_REQUEST_CODE) {
            context?.let {
                exportVaultFiles(attachmentsAdapter.selectedMediaFiles.size == 0, vaultFile)
            }
            LockTimeoutManager().lockTimeout = Preferences.getLockTimeout()
        }
    }

    private fun onFileDeletedEventListener() {
        disposables.wire(
            MediaFileDeletedEvent::class.java,
            object : EventObserver<MediaFileDeletedEvent?>() {
                override fun onNext(event: MediaFileDeletedEvent) {
                    onMediaFilesDeleted(1)
                }
            })
    }

    private fun onFileRenameEventListener() {
        disposables.wire(
            VaultFileRenameEvent::class.java,
            object : EventObserver<VaultFileRenameEvent?>() {
                override fun onNext(event: VaultFileRenameEvent) {
                    onRenameFileSuccess()
                }
            })
    }

    private fun onCaptureEventListener() {
        disposables.wire(
            CaptureEvent::class.java,
            object : EventObserver<CaptureEvent?>() {
                override fun onNext(event: CaptureEvent) {
                    attachmentsPresenter.addNewVaultFiles()
                }
            })
    }

    private fun handleSortSheet() {
        VaultSheetUtils.showVaultSortSheet(
            activity.supportFragmentManager,
            getString(R.string.gallery_subheading_sort_by),
            getString(R.string.Vault_SortNameAsc_SheetAction),
            getString(R.string.Vault_SortNameDesc_SheetAction),
            getString(R.string.Vault_SortDateAsc_SheetAction),
            getString(R.string.Vault_SortDateDesc_SheetAction),
            sort = object : VaultSheetUtils.IVaultSortActions {
                override fun onSortDateASC() {
                    filterNameTv.text = getString(R.string.Vault_SortDateAsc_SheetAction)
                    sort.type = Sort.Type.DATE
                    sort.direction = Sort.Direction.ASC
                    attachmentsPresenter.addNewVaultFiles()
                }

                override fun onSortDateDESC() {
                    filterNameTv.text = getString(R.string.Vault_SortDateDesc_SheetAction)
                    sort.type = Sort.Type.DATE
                    sort.direction = Sort.Direction.DESC
                    attachmentsPresenter.addNewVaultFiles()
                }

                override fun onSortNameDESC() {
                    filterNameTv.text = getString(R.string.Vault_SortNameDesc_SheetAction)
                    sort.type = Sort.Type.NAME
                    sort.direction = Sort.Direction.DESC
                    attachmentsPresenter.addNewVaultFiles()
                }

                override fun onSortNameASC() {
                    filterNameTv.text = getString(R.string.Vault_SortNameAsc_SheetAction)
                    sort.type = Sort.Type.NAME
                    sort.direction = Sort.Direction.ASC
                    attachmentsPresenter.addNewVaultFiles()
                }

            }

        )
    }

    private fun isLocationSettingsRequestCode(requestCode: Int): Boolean {
        return requestCode == C.START_CAMERA_CAPTURE ||
                requestCode == C.START_AUDIO_RECORD
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!isLocationSettingsRequestCode(requestCode) && resultCode != Activity.RESULT_OK) {
            return  // user canceled evidence acquiring
        }
        when (requestCode) {
            C.IMPORT_MULTIPLE_FILES -> {
                if (null != data) {
                    val listVaultFilesUris = arrayListOf<Uri?>()
                    if (data.clipData != null) {
                        for (i in 0 until data.clipData?.itemCount!!) {
                            val uri = data.clipData?.getItemAt(i)?.uri
                            listVaultFilesUris.add(uri)
                        }
                    } else {
                        data.data?.let { returnedUri ->
                            listVaultFilesUris.add(returnedUri)
                        }
                    }
                    attachmentsPresenter.importVaultFiles(
                        listVaultFilesUris,
                        currentRootID,
                        importAndDelete
                    )
                }
            }
            C.CAMERA_CAPTURE or C.RECORDED_AUDIO -> {
                attachmentsPresenter.addNewVaultFiles()
            }
            WRITE_REQUEST_CODE -> {
                exportVaultFiles(false, vaultFile)
            }

        }
    }

    private fun hasStoragePermissions(context: Context): Boolean {
        return if (SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val result: Int =
                ContextCompat.checkSelfPermission(context, READ_EXTERNAL_STORAGE)
            val result1: Int =
                ContextCompat.checkSelfPermission(context, WRITE_EXTERNAL_STORAGE)
            result == PackageManager.PERMISSION_GRANTED && result1 == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermissions() {
        if (SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent: Intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse(
                    String.format(
                        "package:%s",
                        activity.application.packageName)
                )
                startActivityForResult(intent, WRITE_REQUEST_CODE)
            } catch (e: Exception) {
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                startActivityForResult(intent, WRITE_REQUEST_CODE)
            }
        } else {
            //below android 11
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(WRITE_EXTERNAL_STORAGE),
                WRITE_REQUEST_CODE
            )
        }

    }

    private fun createItem(file: VaultFile): BreadcrumbItem {
        val list: MutableList<Item> = ArrayList()
        list.add(Item(file.name, file.id))
        return BreadcrumbItem(list)
    }

    private fun getSheetName(vaultFile: VaultFile?, isMultipleFiles: Boolean): String? {
        return if (isMultipleFiles) {
            attachmentsAdapter.selectedMediaFiles.size.toString() + " " + "items"
        } else {
            vaultFile?.name
        }
    }

    private fun handleOnBackPressed() {
        toolbar.backClickListener = {
            handleBackStack()
        }
        (activity as MainActivity).onBackPressedDispatcher
            .addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackStack()
                }
            })
    }

    private fun handleBackStack() {
        when {
            attachmentsAdapter.selectedMediaFiles.size == 0 && isListCheckOn -> {
                handleSelectMode()
            }
            attachmentsAdapter.selectedMediaFiles.size > 0 -> {
                attachmentsAdapter.clearSelected()
                updateAttachmentsToolbar(false)
            }
            breadcrumbView.items.size > 1 -> {
                if (breadcrumbView.items.size == 2) {
                    breadcrumbView.visibility = View.GONE
                }
                breadcrumbView.removeLastItem()
                currentRootID = breadcrumbView.getCurrentItem<BreadcrumbItem>().selectedItem.id
                attachmentsPresenter.addNewVaultFiles()
            }
            filterType == FilterType.PHOTO_VIDEO -> {
                nav().navigate(R.id.action_attachments_screen_to_camera)
                activity.finish()
            }
            else -> {
                nav().navigateUp()
            }
        }
    }

    private fun enableMoveTheme(enable: Boolean) {
        if (enable) {
            isMoveModeEnabled = true
            (activity as MainActivity).setTheme(R.style.AppTheme_DarkNoActionBar_Blue)
            toolbar.background = ColorDrawable(getColor(activity, R.color.prussian_blue))
            root.background = ColorDrawable(getColor(activity, R.color.prussian_blue))
            appBar.background = ColorDrawable(getColor(activity, R.color.prussian_blue))
            activity.supportActionBar?.setBackgroundDrawable(
                ColorDrawable(
                    getColor(
                        activity,
                        R.color.prussian_blue
                    )
                )
            )
            moveContainer.visibility = View.VISIBLE
            checkBoxList.visibility = View.GONE
            detailsFab.setMargins(17, 0, 17, 70)
            with(attachmentsRecyclerView) {
                setMargins(17, 0, 17, 37)
                updatePadding(right = 2, left = 2)
                background = ColorDrawable(resources.getColor(R.color.wa_white_12))
            }
            activity.window.changeStatusColor(activity, R.color.prussian_blue)
        } else {
            isMoveModeEnabled = false
            (activity as MainActivity).setTheme(R.style.AppTheme_DarkNoActionBar)
            toolbar.background = ColorDrawable(getColor(activity, R.color.space_cadet))
            root.background = ColorDrawable(getColor(activity, R.color.space_cadet))
            appBar.background = ColorDrawable(getColor(activity, R.color.space_cadet))
            activity.supportActionBar?.setBackgroundDrawable(
                ColorDrawable(
                    getColor(
                        activity,
                        R.color.space_cadet
                    )
                )
            )
            moveContainer.visibility = View.GONE
            checkBoxList.visibility = View.VISIBLE
            detailsFab.setMargins(17, 0, 17, 17)
            with(attachmentsRecyclerView) {
                setMargins(0, 0, 0, 17)
                updatePadding(right = 0, left = 0)
                background = ColorDrawable(getColor(activity, R.color.space_cadet))
            }
            activity.window.changeStatusColor(activity, R.color.space_cadet)
        }
        activity.invalidateOptionsMenu()
        attachmentsAdapter.enableMoveMode(enable)
    }

    private fun highlightMoveBackground() {
        if (currentMove != currentRootID) {
            moveHere.setOnClickListener(this)
            moveHere.setTextColor(getColor(activity, R.color.wa_white))
        } else {
            moveHere.setOnClickListener(null)
            moveHere.setTextColor(getColor(activity, R.color.wa_white_12))
        }
    }

    private suspend fun deleteFileFromExternalStorage(uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                activity.contentResolver.delete(uri, null, null)
            } catch (e: SecurityException) {

            }
        }
    }

    private fun maybeShowUploadIcon(menu: Menu) {
        menu.findItem(R.id.action_upload).isVisible = false
    }

    override fun onResume() {
        super.onResume()
        handleOnBackPressed()
    }

    override fun onBackPressed(): Boolean {
        handleBackStack()
        return true
    }

    private fun getCurrentType(): String {
        return when (filterType) {
            FilterType.ALL -> "*/*"
            FilterType.DOCUMENTS -> "application/*"
            FilterType.PHOTO -> "image/*"
            FilterType.VIDEO -> "video/*"
            FilterType.AUDIO -> "audio/*"
            FilterType.PHOTO_VIDEO -> "image/*|video/*"
            else -> "image/*"
        }
    }
}
