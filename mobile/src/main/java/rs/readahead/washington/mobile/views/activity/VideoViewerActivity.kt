package rs.readahead.washington.mobile.views.activity

import android.Manifest
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayerFactory
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.ExtractorMediaSource
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelection
import com.google.android.exoplayer2.ui.PlayerControlView
import com.google.android.exoplayer2.ui.SimpleExoPlayerView
import com.google.android.exoplayer2.upstream.BandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.hzontal.tella_vault.VaultFile
import org.hzontal.shared_ui.bottomsheet.BottomSheetUtils
import org.hzontal.shared_ui.bottomsheet.BottomSheetUtils.ActionConfirmed
import org.hzontal.shared_ui.bottomsheet.BottomSheetUtils.RadioOptionConsumer
import org.hzontal.shared_ui.bottomsheet.VaultSheetUtils
import org.hzontal.shared_ui.bottomsheet.VaultSheetUtils.IVaultActions
import org.hzontal.shared_ui.utils.DialogUtils
import permissions.dispatcher.*
import rs.readahead.washington.mobile.MyApplication
import rs.readahead.washington.mobile.R
import rs.readahead.washington.mobile.bus.event.MediaFileDeletedEvent
import rs.readahead.washington.mobile.bus.event.VaultFileRenameEvent
import rs.readahead.washington.mobile.databinding.ActivityVideoViewerBinding
import rs.readahead.washington.mobile.media.MediaFileHandler
import rs.readahead.washington.mobile.media.exo.ExoEventListener
import rs.readahead.washington.mobile.media.exo.MediaFileDataSourceFactory
import rs.readahead.washington.mobile.mvp.contract.IMediaFileViewerPresenterContract
import rs.readahead.washington.mobile.mvp.presenter.MediaFileViewerPresenter
import rs.readahead.washington.mobile.util.DialogsUtil
import rs.readahead.washington.mobile.util.PermissionUtil
import rs.readahead.washington.mobile.views.base_ui.BaseLockActivity
import rs.readahead.washington.mobile.views.fragment.vault.info.VaultInfoFragment

@RuntimePermissions
class VideoViewerActivity : BaseLockActivity(), PlayerControlView.VisibilityListener,
    IMediaFileViewerPresenterContract.IView {
    private var simpleExoPlayerView: SimpleExoPlayerView? = null
    private var player: SimpleExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var needRetrySource = false
    private var shouldAutoPlay = false
    private var withMetadata = false
    private var resumeWindow = 0
    private var resumePosition: Long = 0
    private var vaultFile: VaultFile? = null
    private var toolbar: Toolbar? = null
    private var actionsDisabled = false
    private var presenter: MediaFileViewerPresenter? = null
    private var alertDialog: AlertDialog? = null
    private var progressDialog: ProgressDialog? = null
    private var isInfoShown = false
    private var binding: ActivityVideoViewerBinding? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoViewerBinding.inflate(layoutInflater)
        setContentView(binding!!.getRoot())
        overridePendingTransition(R.anim.slide_in_start, R.anim.fade_out)
        if (intent.hasExtra(NO_ACTIONS)) {
            actionsDisabled = true
        }
        setupToolbar()
        shouldAutoPlay = true
        clearResumePosition()
        simpleExoPlayerView = binding!!.playerView
        simpleExoPlayerView!!.setControllerVisibilityListener(this)
        simpleExoPlayerView!!.requestFocus()
        presenter = MediaFileViewerPresenter(this)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_end, R.anim.slide_out_start)
    }

    public override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        releasePlayer()
        shouldAutoPlay = true
        clearResumePosition()
        setIntent(intent)
    }

    public override fun onStart() {
        super.onStart()
        if (SDK_INT > 23) {
            initializePlayer()
        }
    }

    public override fun onResume() {
        super.onResume()
        if (SDK_INT <= 23 || player == null) {
            initializePlayer()
        }
    }

    public override fun onPause() {
        super.onPause()
        if (SDK_INT <= 23) {
            releasePlayer()
        }
    }

    public override fun onStop() {
        super.onStop()
        if (SDK_INT > 23) {
            releasePlayer()
        }
    }

    override fun onDestroy() {
        if (alertDialog != null && alertDialog!!.isShowing) {
            alertDialog!!.dismiss()
        }
        hideProgressDialog()
        if (presenter != null) {
            presenter!!.destroy()
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        //VideoViewerActivityPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults);
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
        maybeChangeTemporaryTimeout {
            alertDialog = PermissionUtil.showRationale(
                this,
                request!!,
                getString(R.string.permission_dialog_expl_device_storage)
            )
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
        progressDialog = DialogsUtil.showProgressDialog(
            this,
            getString(R.string.gallery_save_to_device_dialog_progress_expl)
        )
    }

    override fun onExportEnded() {
        hideProgressDialog()
    }

    override fun onMediaFileDeleted() {
        MyApplication.bus().post(MediaFileDeletedEvent())
        finish()
    }

    override fun onMediaFileDeletionError(throwable: Throwable?) {
        showToast(R.string.gallery_toast_fail_deleting_files)
    }

    override fun onMediaFileRename(vaultFile: VaultFile?) {
        if (vaultFile != null) {
            toolbar!!.title = vaultFile.name
            this.vaultFile = vaultFile
        }
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

    override fun getContext(): Context {
        return this
    }

    private fun showExportDialog() {
        /*alertDialog = DialogsUtil.showExportMediaDialog(this, (dialog, which) ->{}*/
        //VideoViewerActivityPermissionsDispatcher.exportMediaFileWithPermissionCheck(VideoViewerActivity.this));
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        // Show the controls on any key event.
        simpleExoPlayerView!!.showController()
        // If the event was not handled then see if the player view can handle it as a media key event.
        return super.dispatchKeyEvent(event) || simpleExoPlayerView!!.dispatchMediaKeyEvent(event)
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

    private fun initializePlayer() {
        val needNewPlayer = player == null
        if (needNewPlayer) {
            val bandwidthMeter: BandwidthMeter = DefaultBandwidthMeter()
            val videoTrackSelectionFactory: TrackSelection.Factory =
                AdaptiveTrackSelection.Factory(bandwidthMeter)
            trackSelector = DefaultTrackSelector(videoTrackSelectionFactory)
            player = ExoPlayerFactory.newSimpleInstance(this, trackSelector)
            if (player != null) {
                player!!.addListener(ExoEventListener())
                simpleExoPlayerView!!.setPlayer(player)
                player!!.setPlayWhenReady(shouldAutoPlay)
            }
        }
        if (needNewPlayer || needRetrySource) {
            if (intent.hasExtra(VIEW_VIDEO) && intent.extras != null) {
                val vaultFile = intent.extras!![VIEW_VIDEO] as VaultFile?
                if (vaultFile != null) {
                    this.vaultFile = vaultFile
                    toolbar!!.title = vaultFile.name
                    setupMetadataMenuItem(vaultFile.metadata != null)
                }
            }
            val mediaFileDataSourceFactory = MediaFileDataSourceFactory(
                this,
                vaultFile!!, null
            )
            val mediaSource: MediaSource = ExtractorMediaSource(
                MediaFileHandler.getEncryptedUri(this, vaultFile),
                mediaFileDataSourceFactory,
                DefaultExtractorsFactory(),
                null, null
            )
            val haveResumePosition = resumeWindow != C.INDEX_UNSET
            if (haveResumePosition) {
                player!!.seekTo(resumeWindow, resumePosition)
            }
            player!!.prepare(mediaSource, !haveResumePosition, false)
            needRetrySource = false
        }
    }

    private fun releasePlayer() {
        if (player != null) {
            shouldAutoPlay = player!!.playWhenReady
            //updateResumePosition(); // todo: fix source skipping..
            player!!.release()
            player = null
            trackSelector = null
            clearResumePosition()
        }
    }

    private fun clearResumePosition() {
        resumeWindow = C.INDEX_UNSET
        resumePosition = C.TIME_UNSET
    }

    override fun onVisibilityChange(visibility: Int) {
        if (!isInfoShown) {
            toolbar!!.visibility = visibility
        } else {
            toolbar!!.visibility = View.VISIBLE
        }
    }

    private fun setupToolbar() {
        toolbar = binding!!.playerToolbar
        toolbar?.setNavigationIcon(R.drawable.ic_arrow_back_white_24dp)
        toolbar!!.setNavigationOnClickListener { v: View? -> onBackPressed() }
        if (!actionsDisabled) {
            toolbar!!.inflateMenu(R.menu.video_view_menu)
            if (vaultFile != null) {
                setupMetadataMenuItem(vaultFile!!.metadata != null)
            }
            toolbar!!.menu.findItem(R.id.menu_item_more)
                .setOnMenuItemClickListener { item: MenuItem? ->
                    showVaultActionsDialog(vaultFile!!)
                    false
                }
        }
    }

    private fun showMetadata() {
        val viewMetadata = Intent(
            this,
            MetadataViewerActivity::class.java
        )
        viewMetadata.putExtra(MetadataViewerActivity.VIEW_METADATA, vaultFile)
        startActivity(viewMetadata)
    }

    private fun hideProgressDialog() {
        if (progressDialog != null) {
            progressDialog!!.dismiss()
            progressDialog = null
        }
    }

    private fun setupMetadataMenuItem(visible: Boolean) {
        if (actionsDisabled) {
            return
        }
        val mdMenuItem = toolbar!!.menu.findItem(R.id.menu_item_metadata)
        if (visible) {
            mdMenuItem.setVisible(true).setOnMenuItemClickListener { item: MenuItem? ->
                showMetadata()
                false
            }
        } else {
            mdMenuItem.isVisible = false
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
                    }
                }

                override fun move() {}
                override fun rename() {
                    VaultSheetUtils.showVaultRenameSheet(
                        supportFragmentManager,
                        getString(R.string.Vault_CreateFolder_SheetAction),
                        getString(R.string.action_cancel),
                        getString(R.string.action_ok),
                        this@VideoViewerActivity,
                        vaultFile.name
                    ) { name: String? ->
                        presenter!!.renameVaultFile(vaultFile.id, name)
                    }
                }

                override fun save() {
                    BottomSheetUtils.showConfirmSheet(
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
                    isInfoShown = true
                    onVisibilityChange(View.VISIBLE)
                    toolbar!!.title = getString(R.string.Vault_FileInfo)
                    toolbar!!.menu.findItem(R.id.menu_item_more).isVisible = false
                    toolbar!!.menu.findItem(R.id.menu_item_metadata).isVisible = false
                    invalidateOptionsMenu()
                    addFragment(VaultInfoFragment().newInstance(vaultFile, false), R.id.container)
                }

                override fun delete() {
                    BottomSheetUtils.showConfirmSheet(
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

    override fun onBackPressed() {
        super.onBackPressed()
        /*toolbar.setStartTextTitle(vaultFile.name);
        toolbar.getMenu().findItem(R.id.menu_item_more).setVisible(true);
        setupMetadataMenuItem(vaultFile.metadata != null);
        invalidateOptionsMenu();
        isInfoShown = false;*/finish()
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
                object : RadioOptionConsumer {
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
        const val VIEW_VIDEO = "vv"
        const val NO_ACTIONS = "na"
        val SDK_INT =
            if (Build.VERSION.SDK_INT == 25 && Build.VERSION.CODENAME[0] == 'O') 26 else Build.VERSION.SDK_INT
    }
}