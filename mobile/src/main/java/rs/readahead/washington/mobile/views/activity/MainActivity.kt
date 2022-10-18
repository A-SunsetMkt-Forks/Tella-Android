package rs.readahead.washington.mobile.views.activity

import android.Manifest
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.view.OrientationEventListener
import android.view.View
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.google.gson.Gson
import com.hzontal.tella_vault.VaultFile
import com.hzontal.tella_vault.filter.FilterType
import dagger.hilt.android.AndroidEntryPoint
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.RuntimePermissions
import rs.readahead.washington.mobile.MyApplication
import rs.readahead.washington.mobile.R
import rs.readahead.washington.mobile.bus.EventObserver
import rs.readahead.washington.mobile.bus.event.CamouflageAliasChangedEvent
import rs.readahead.washington.mobile.bus.event.LocaleChangedEvent
import rs.readahead.washington.mobile.data.sharedpref.Preferences
import rs.readahead.washington.mobile.databinding.ActivityMain2Binding
import rs.readahead.washington.mobile.mvp.contract.IHomeScreenPresenterContract
import rs.readahead.washington.mobile.mvp.contract.IMediaImportPresenterContract
import rs.readahead.washington.mobile.mvp.contract.IMetadataAttachPresenterContract
import rs.readahead.washington.mobile.mvp.presenter.HomeScreenPresenter
import rs.readahead.washington.mobile.mvp.presenter.MediaImportPresenter
import rs.readahead.washington.mobile.util.C
import rs.readahead.washington.mobile.util.CleanInsightUtils
import rs.readahead.washington.mobile.util.CleanInsightUtils.measureEvent
import rs.readahead.washington.mobile.views.fragment.uwazi.SubmittedPreviewFragment
import rs.readahead.washington.mobile.views.fragment.uwazi.attachments.*
import rs.readahead.washington.mobile.views.fragment.uwazi.download.DownloadedTemplatesFragment
import rs.readahead.washington.mobile.views.fragment.uwazi.entry.UwaziEntryFragment
import rs.readahead.washington.mobile.views.fragment.uwazi.send.UwaziSendFragment
import rs.readahead.washington.mobile.views.fragment.vault.attachements.AttachmentsFragment
import rs.readahead.washington.mobile.views.fragment.vault.home.VAULT_FILTER
import timber.log.Timber
import java.util.*

@AndroidEntryPoint
class MainActivity : MetadataActivity(), IHomeScreenPresenterContract.IView,
    IMediaImportPresenterContract.IView, IMetadataAttachPresenterContract.IView {
    private var mExit = false
    private var handler: Handler? = null
    private val disposables by lazy { MyApplication.bus().createCompositeDisposable() }
    private var homeScreenPresenter: HomeScreenPresenter? = null
    private var mediaImportPresenter: MediaImportPresenter? = null
    private var progressDialog: ProgressDialog? = null
    private var mOrientationEventListener: OrientationEventListener? = null
    private var navController: NavController? = null
    private var binding: ActivityMain2Binding? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMain2Binding.inflate(layoutInflater)
        setContentView(binding!!.root)

        //  setupToolbar();
        setupNavigation()
        handler = Handler()
        homeScreenPresenter = HomeScreenPresenter(this)
        mediaImportPresenter = MediaImportPresenter(this)
        initSetup()
        // todo: check this..
        //SafetyNetCheck.setApiKey(getString(R.string.share_in_report));
        if (intent.hasExtra(PHOTO_VIDEO_FILTER)) {
            val bundle = Bundle()
            bundle.putString(VAULT_FILTER, FilterType.PHOTO_VIDEO.name)
            navController!!.navigate(R.id.action_homeScreen_to_attachments_screen, bundle)
        }
    }

    private fun initSetup() {
        setOrientationListener()
        disposables.wire(
            LocaleChangedEvent::class.java,
            object : EventObserver<LocaleChangedEvent?>() {
                override fun onNext(event: LocaleChangedEvent) {
                    recreate()
                }
            })
        disposables.wire(
            CamouflageAliasChangedEvent::class.java,
            object : EventObserver<CamouflageAliasChangedEvent?>() {
                override fun onNext(event: CamouflageAliasChangedEvent) {
                    closeApp()
                }
            })
    }

    private fun setupNavigation() {
        navController = (supportFragmentManager
            .findFragmentById(R.id.fragment_nav_host) as NavHostFragment)
            .navController

        NavigationUI.setupWithNavController(binding!!.btmNavMain, navController!!)
        navController!!.addOnDestinationChangedListener { navController1: NavController?, navDestination: NavDestination, bundle: Bundle? ->
            when (navDestination.id) {
                R.id.micScreen -> {
                    checkLocationSettings(
                        C.START_AUDIO_RECORD
                    ) {}
                    showBottomNavigation()
                }
                R.id.homeScreen, R.id.formScreen, R.id.uwaziScreen -> showBottomNavigation()
                else -> hideBottomNavigation()
            }
        }
    }

    private fun isLocationSettingsRequestCode(requestCode: Int): Boolean {
        return requestCode == C.START_CAMERA_CAPTURE ||
                requestCode == C.START_AUDIO_RECORD
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == C.IMPORT_VIDEO) {
            if (data != null) {
                val video = data.data
                if (video != null) {
                    mediaImportPresenter!!.importVideo(video)
                }
            }
            return
        }
        if (requestCode == C.IMPORT_IMAGE) {
            if (data != null) {
                val image = data.data
                if (image != null) {
                    mediaImportPresenter!!.importImage(image)
                }
            }
            return
        }
        if (requestCode == C.IMPORT_FILE) {
            if (data != null) {
                val file = data.data
                if (file != null) {
                    mediaImportPresenter!!.importFile(file)
                }
            }
            return
        }
        if (!isLocationSettingsRequestCode(requestCode) && resultCode != RESULT_OK) {
            return  // user canceled evidence acquiring
        }
        val fragments = Objects.requireNonNull(
            supportFragmentManager.primaryNavigationFragment
        )?.childFragmentManager?.fragments
        if (fragments != null) {
            for (fragment in fragments) {
                fragment.onActivityResult(requestCode, resultCode, data)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        //MainActivityPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults);
    }

    override fun onBackPressed() {
        // if (maybeCloseCamera()) return;
        if (checkCurrentFragment()) return
        if (!checkIfShouldExit()) return
        closeApp()
    }

    private fun checkCurrentFragment(): Boolean {
        val fragments = Objects.requireNonNull(
            supportFragmentManager.primaryNavigationFragment
        )?.childFragmentManager?.fragments
        if (fragments != null) {
            for (fragment in fragments) {
                if (fragment is AttachmentsFragment) {
                    fragment.onBackPressed()
                    return true
                }
                if (fragment is DownloadedTemplatesFragment ||
                    fragment is SubmittedPreviewFragment ||
                    fragment is UwaziSendFragment
                ) {
                    navController!!.popBackStack()
                    return true
                }
                if (fragment is UwaziEntryFragment) {
                    fragment.onBackPressed()
                    return true
                }
            }
        }
        return false
    }

    private fun closeApp() {
        finish()
        lockApp()
    }

    private fun checkIfShouldExit(): Boolean {
        if (!mExit) {
            mExit = true
            showToast(R.string.home_toast_back_exit)
            handler!!.postDelayed({ mExit = false }, (3 * 1000).toLong())
            return false
        }
        return true
    }

    private fun lockApp() {
        if (!isLocked) {
            MyApplication.resetKeys()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (disposables != null) {
            disposables!!.dispose()
        }
        stopPresenter()
        hideProgressDialog()
    }

    override fun onResume() {
        super.onResume()
        binding!!.btmNavMain.menu.findItem(R.id.home).isChecked = true
        homeScreenPresenter!!.countCollectServers()
        homeScreenPresenter!!.countUwaziServers()
        startLocationMetadataListening()
        mOrientationEventListener!!.enable()
    }

    override fun onPause() {
        super.onPause()
        stopLocationMetadataListening()
        mOrientationEventListener!!.disable()
    }

    override fun onMetadataAttached(vaultFile: VaultFile) {
        val data = Intent()
        data.putExtra(C.CAPTURED_MEDIA_FILE_ID, vaultFile.id)
        setResult(RESULT_OK, data)
    }

    override fun onMetadataAttachError(throwable: Throwable?) {
        // onAddError(throwable);
    }

  /*  @NeedsPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun startCollectFormEntryActivity() {
        startActivity(Intent(this, CollectFormEntryActivity::class.java))
    }*/

    override fun onMediaFileImported(vaultFile: VaultFile) {
        val list: MutableList<String> = ArrayList()
        list.add(vaultFile.id)
        onActivityResult(
            C.MEDIA_FILE_ID,
            RESULT_OK,
            Intent().putExtra(VAULT_FILE_KEY, Gson().toJson(list))
        )
    }

    override fun onImportError(error: Throwable?) {}
    override fun onImportStarted() {}
    override fun onImportEnded() {}
    override fun getContext(): Context {
        return this
    }

    override fun onCountTUServersEnded(num: Long) {
        if (num > 0) {
            measureEvent(CleanInsightUtils.ServerType.SERVER_TELLA)
            maybeShowTUserver(num)
        }
    }

    override fun onCountTUServersFailed(throwable: Throwable?) {
        Timber.d(throwable)
    }

    override fun onCountCollectServersEnded(num: Long) {
        maybeShowFormsMenu(num)
        if (num > 0) {
            measureEvent(CleanInsightUtils.ServerType.SERVER_COLLECT)
        } else {
            Preferences.setJavarosa3Upgraded(true)
        }
        //homeScreenPresenter.countTUServers();
    }

    override fun onCountCollectServersFailed(throwable: Throwable?) {}
    override fun onCountUwaziServersEnded(num: Long) {
        maybeShowUwaziMenu(num)
        if (num > 0) measureEvent(CleanInsightUtils.ServerType.SERVER_UWAZI)
    }

    override fun onCountUwaziServersFailed(throwable: Throwable?) {}
    private fun stopPresenter() {
        if (homeScreenPresenter != null) {
            homeScreenPresenter!!.destroy()
            homeScreenPresenter = null
        }
        if (mediaImportPresenter != null) {
            mediaImportPresenter!!.destroy()
            mediaImportPresenter = null
        }
    }

    private fun hideProgressDialog() {
        if (progressDialog != null) {
            progressDialog!!.dismiss()
            progressDialog = null
        }
    }

    private fun setOrientationListener() {
        mOrientationEventListener =
            object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
                override fun onOrientationChanged(orientation: Int) {
                    //if (!isInCameraMode) return;
                    if (orientation == ORIENTATION_UNKNOWN) return
                    // handle rotation for tablets;
                }
            }
    }

    fun hideBottomNavigation() {
        binding!!.btmNavMain.visibility = View.GONE
    }

    fun showBottomNavigation() {
        binding!!.btmNavMain.visibility = View.VISIBLE
    }

    fun selectNavMic() {
        binding!!.btmNavMain.menu.findItem(R.id.mic).isChecked = true
    }

    fun selectNavForm() {
        binding!!.btmNavMain.menu.findItem(R.id.form).isChecked = true
    }

    private fun maybeShowFormsMenu(num: Long) {
        binding!!.btmNavMain.menu.findItem(R.id.form).isVisible = num > 0
        invalidateOptionsMenu()
    }

    private fun maybeShowUwaziMenu(num: Long) {
        binding!!.btmNavMain.menu.findItem(R.id.uwazi).isVisible = num > 0
        invalidateOptionsMenu()
    }

    fun selectHome() {
        binding!!.btmNavMain.menu.findItem(R.id.home).isChecked = true
        navController?.navigate(R.id.home)
    }

    private fun maybeShowTUserver(num: Long) {
        //btmNavMain.getMenu().findItem(R.id.reports).setVisible(num > 0);
        //  invalidateOptionsMenu();
    }

    companion object {
        const val PHOTO_VIDEO_FILTER = "gallery_filter"
    }
}