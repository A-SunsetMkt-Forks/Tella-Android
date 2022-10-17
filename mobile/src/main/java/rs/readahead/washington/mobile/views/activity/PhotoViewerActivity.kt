package rs.readahead.washington.mobile.views.activity

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.GlideDrawable
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.hzontal.tella_vault.VaultFile
import org.hzontal.shared_ui.bottomsheet.BottomSheetUtils
import org.hzontal.shared_ui.bottomsheet.BottomSheetUtils.ActionConfirmed
import org.hzontal.shared_ui.bottomsheet.BottomSheetUtils.showConfirmSheet
import org.hzontal.shared_ui.bottomsheet.VaultSheetUtils
import org.hzontal.shared_ui.bottomsheet.VaultSheetUtils.IVaultActions
import org.hzontal.shared_ui.bottomsheet.VaultSheetUtils.showVaultRenameSheet
import org.hzontal.shared_ui.utils.DialogUtils
import permissions.dispatcher.*
import rs.readahead.washington.mobile.MyApplication
import rs.readahead.washington.mobile.R
import rs.readahead.washington.mobile.bus.event.MediaFileDeletedEvent
import rs.readahead.washington.mobile.bus.event.VaultFileRenameEvent
import rs.readahead.washington.mobile.databinding.ActivityPhotoViewerBinding
import rs.readahead.washington.mobile.media.MediaFileHandler
import rs.readahead.washington.mobile.media.VaultFileUrlLoader
import rs.readahead.washington.mobile.mvp.contract.IMediaFileViewerPresenterContract
import rs.readahead.washington.mobile.mvp.presenter.MediaFileViewerPresenter
import rs.readahead.washington.mobile.presentation.entity.VaultFileLoaderModel
import rs.readahead.washington.mobile.util.PermissionUtil.showRationale
import rs.readahead.washington.mobile.views.base_ui.BaseLockActivity
import rs.readahead.washington.mobile.views.fragment.vault.info.VaultInfoFragment

const val PICKER_FILE_REQUEST_CODE = 100

@RuntimePermissions
class PhotoViewerActivity : BaseLockActivity(),
    IMediaFileViewerPresenterContract.IView {
    private var presenter: MediaFileViewerPresenter? = null
    private var vaultFile: VaultFile? = null
    private var showActions = false
    private var actionsDisabled = false
    private var withMetadata = false
    private var alertDialog: AlertDialog? = null
    private var menu: Menu? = null
    private var binding: ActivityPhotoViewerBinding? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding!!.root)
        setSupportActionBar(binding!!.toolbar)
        title = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            binding!!.appbar.outlineProvider = null
        } else {
            binding!!.appbar.bringToFront()
        }
        overridePendingTransition(R.anim.slide_in_start, R.anim.fade_out)
        presenter = MediaFileViewerPresenter(this)
        if (intent.hasExtra(VIEW_PHOTO)) {
            val vaultFile = intent.extras!![VIEW_PHOTO] as VaultFile?
            if (vaultFile != null) {
                this.vaultFile = vaultFile
            }
        }
        title = vaultFile!!.name
        if (intent.hasExtra(NO_ACTIONS)) {
            actionsDisabled = true
        }
        openMedia()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (!actionsDisabled && showActions) {
            menuInflater.inflate(R.menu.photo_view_menu, menu)
            if (vaultFile!!.metadata != null) {
                val item = menu.findItem(R.id.menu_item_metadata)
                item.isVisible = true
            }
        }
        this.menu = menu
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == android.R.id.home) {
            onBackPressed()
            return true
        }
        if (id == R.id.menu_item_more) {
            showVaultActionsDialog(vaultFile!!)
            return true
        }
        if (id == R.id.menu_item_metadata) {
            showMetadata()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_end, R.anim.slide_out_start)
    }

    override fun onDestroy() {
        stopPresenter()
        if (alertDialog != null && alertDialog!!.isShowing) {
            alertDialog!!.dismiss()
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onRequestPermissionsResult(requestCode, grantResults)
    }

    @OnPermissionDenied(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun onWriteExternalStoragePermissionDenied() {
    }

    @OnNeverAskAgain(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun onWriteExternalStorageNeverAskAgain() {
    }

    @NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun exportMediaFile() {
        if (vaultFile != null && presenter != null) {
            if (vaultFile!!.metadata != null) {
                showExportWithMetadataDialog()
            } else {
                withMetadata = false
                maybeChangeTemporaryTimeout {
                    performFileSearch()
                }
            }
        }
    }

    @OnShowRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun showWriteExternalStorageRationale(request: PermissionRequest?) {
        maybeChangeTemporaryTimeout()
        alertDialog = showRationale(
            this,
            request!!,
            getString(R.string.permission_dialog_expl_device_storage)
        )
    }

    private fun openMedia() {
        showGalleryImage(vaultFile!!)
        if (!actionsDisabled) {
            showActions = true
            invalidateOptionsMenu()
        }
    }

    private fun performFileSearch() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, PICKER_FILE_REQUEST_CODE)
        } else {
            presenter!!.exportNewMediaFile(withMetadata, vaultFile, null)
        }
    }

    override fun onMediaExported() {
        showToast(resources.getQuantityString(R.plurals.gallery_toast_files_exported, 1, 1))
    }

    override fun onExportError(error: Throwable?) {
        showToast(R.string.gallery_toast_fail_exporting_to_device)
    }

    override fun onExportStarted() {
        binding!!.content.progressBar.visibility = View.VISIBLE
    }

    override fun onExportEnded() {
        binding!!.content.progressBar.visibility = View.GONE
    }

    override fun onMediaFileDeleted() {
        MyApplication.bus().post(MediaFileDeletedEvent())
        finish()
    }

    override fun onMediaFileDeletionError(throwable: Throwable?) {
        DialogUtils.showBottomMessage(
            this,
            getString(R.string.gallery_toast_fail_deleting_files),
            true
        )
    }

    override fun onMediaFileRename(vaultFile: VaultFile) {
        binding!!.toolbar.title = vaultFile.name
        MyApplication.bus().post(VaultFileRenameEvent())
    }

    override fun onMediaFileRenameError(throwable: Throwable?) {
        //TODO CHECK ERROR MSG WHEN RENAME
        DialogUtils.showBottomMessage(
            this,
            getString(R.string.gallery_toast_fail_deleting_files),
            true
        )
    }

    override fun onBackPressed() {
        super.onBackPressed()
        if (menu!!.findItem(R.id.menu_item_more) != null) {
            menu!!.findItem(R.id.menu_item_more).isVisible = true
        }
        if (vaultFile!!.metadata != null && menu!!.findItem(R.id.menu_item_metadata) != null) {
            menu!!.findItem(R.id.menu_item_metadata).isVisible = true
        }
        binding!!.toolbar.title = vaultFile!!.name
        finish()
    }

    override fun getContext(): Context {
        return this
    }

    private fun shareMediaFile() {
        if (vaultFile == null) {
            return
        }
        if (vaultFile!!.metadata != null) {
            showShareWithMetadataDialog()
        } else {
            startShareActivity(false)
        }
    }

    private fun startShareActivity(includeMetadata: Boolean) {
        if (vaultFile == null) {
            return
        }
        MediaFileHandler.startShareActivity(this, vaultFile, includeMetadata)
    }

    private fun showGalleryImage(vaultFile: VaultFile) {
        Glide.with(this)
            .using(VaultFileUrlLoader(this, MediaFileHandler()))
            .load(VaultFileLoaderModel(vaultFile, VaultFileLoaderModel.LoadType.ORIGINAL))
            .listener(object : RequestListener<VaultFileLoaderModel?, GlideDrawable?> {
                override fun onException(
                    e: Exception?, model: VaultFileLoaderModel?,
                    target: Target<GlideDrawable?>?, isFirstResource: Boolean
                ): Boolean {
                    binding!!.content.progressBar.visibility = View.GONE
                    return false
                }

                override fun onResourceReady(
                    resource: GlideDrawable?,
                    model: VaultFileLoaderModel?,
                    target: Target<GlideDrawable?>?,
                    isFromMemoryCache: Boolean,
                    isFirstResource: Boolean
                ): Boolean {
                    binding!!.content.progressBar.visibility = View.GONE
                    return false
                }
            })
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .into(binding!!.content.photoImageView)
    }

    private fun showMetadata() {
        val viewMetadata = Intent(
            this,
            MetadataViewerActivity::class.java
        )
        viewMetadata.putExtra(MetadataViewerActivity.VIEW_METADATA, vaultFile)
        startActivity(viewMetadata)
    }

    private fun stopPresenter() {
        if (presenter != null) {
            presenter!!.destroy()
            presenter = null
        }
    }

    private fun showVaultActionsDialog(vaultFile: VaultFile) {
        VaultSheetUtils.showVaultActionsSheet(
            supportFragmentManager,
            vaultFile.name,
            getString(R.string.Vault_Upload_SheetAction),
            getString(R.string.Vault_Share_SheetAction),
            getString(R.string.Vault_Move_SheetDesc),
            getString(R.string.Vault_Rename_SheetAction),
            getString(R.string.gallery_action_desc_save_to_device),
            getString(R.string.Vault_File_SheetAction),
            getString(R.string.Vault_Delete_SheetAction),
            false,
            false,
            false,
            false,
            object : IVaultActions {
                override fun upload() {}
                override fun share() {
                    maybeChangeTemporaryTimeout {
                        shareMediaFile()
                        Unit
                    }
                }

                override fun move() {}
                override fun rename() {
                    showVaultRenameSheet(
                        supportFragmentManager,
                        getString(R.string.Vault_CreateFolder_SheetAction),
                        getString(R.string.action_cancel),
                        getString(R.string.action_ok),
                        this@PhotoViewerActivity,
                        vaultFile.name
                    ) { name: String? ->
                        presenter!!.renameVaultFile(vaultFile.id, name)
                        Unit
                    }
                }

                override fun save() {
                    showConfirmSheet(
                        supportFragmentManager,
                        getString(R.string.gallery_save_to_device_dialog_title),
                        getString(R.string.gallery_save_to_device_dialog_expl),
                        getString(R.string.action_save),
                        getString(R.string.action_cancel),
                        object : ActionConfirmed {
                            override fun accept(isConfirmed: Boolean) {
                                exportMediaFileWithPermissionCheck()
                            }
                        }
                    )
                }

                override fun info() {
                    binding!!.toolbar.title = getString(R.string.Vault_FileInfo)
                    menu!!.findItem(R.id.menu_item_more).isVisible = false
                    menu!!.findItem(R.id.menu_item_metadata).isVisible = false
                    invalidateOptionsMenu()
                    addFragment(
                        VaultInfoFragment().newInstance(vaultFile, false),
                        R.id.photo_viewer_container
                    )
                }

                override fun delete() {
                    showConfirmSheet(
                        supportFragmentManager,
                        getString(R.string.Vault_DeleteFile_SheetTitle),
                        getString(R.string.Vault_deleteFile_SheetDesc),
                        getString(R.string.action_delete),
                        getString(R.string.action_cancel),
                        object : ActionConfirmed {
                            override fun accept(isConfirmed: Boolean) {
                                presenter!!.deleteMediaFiles(
                                    vaultFile
                                )
                            }
                        }
                    )
                }
            }
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICKER_FILE_REQUEST_CODE) {
            assert(data != null)
            presenter!!.exportNewMediaFile(withMetadata, vaultFile, data!!.data)
        }
    }

    private fun showShareWithMetadataDialog() {
        val options = LinkedHashMap<Int, Int>()
        options[1] = R.string.verification_share_select_media_and_verification
        options[0] = R.string.verification_share_select_only_media
        Handler().post {
            BottomSheetUtils.showRadioListOptionsSheet(
                supportFragmentManager,
                context,
                options,
                getString(R.string.verification_share_dialog_title),
                getString(R.string.verification_share_dialog_expl),
                getString(R.string.action_ok),
                getString(R.string.action_cancel),
                object : BottomSheetUtils.RadioOptionConsumer {
                    override fun accept(option: Int) {
                        startShareActivity(option > 0)
                    }
                }
            )
        }
    }

    private fun showExportWithMetadataDialog() {
        val options = LinkedHashMap<Int, Int>()
        options[1] = R.string.verification_share_select_media_and_verification
        options[0] = R.string.verification_share_select_only_media
        Handler().post {
            BottomSheetUtils.showRadioListOptionsSheet(
                supportFragmentManager,
                context,
                options,
                getString(R.string.verification_share_dialog_title),
                getString(R.string.verification_share_dialog_expl),
                getString(R.string.action_ok),
                getString(R.string.action_cancel),
                object : BottomSheetUtils.RadioOptionConsumer {
                    override fun accept(option: Int) {
                        withMetadata = option > 0
                        maybeChangeTemporaryTimeout {
                            performFileSearch()
                        }
                    }
                }
            )
        }
    }

    companion object {
        const val VIEW_PHOTO = "vp"
        const val NO_ACTIONS = "na"
    }
}