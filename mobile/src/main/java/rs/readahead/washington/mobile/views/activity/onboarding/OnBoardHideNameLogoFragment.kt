package rs.readahead.washington.mobile.views.activity.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rs.readahead.washington.mobile.MyApplication
import rs.readahead.washington.mobile.R
import rs.readahead.washington.mobile.bus.event.CamouflageAliasChangedEvent
import rs.readahead.washington.mobile.util.CamouflageManager
import rs.readahead.washington.mobile.views.adapters.CamouflageRecycleViewAdapter
import rs.readahead.washington.mobile.views.base_ui.BaseFragment
import rs.readahead.washington.mobile.views.settings.OnFragmentSelected
import java.lang.IndexOutOfBoundsException

class OnBoardHideNameLogoFragment : BaseFragment() {

    private val cm = CamouflageManager.getInstance()
    private lateinit var adapter: CamouflageRecycleViewAdapter
    private lateinit var recyclerView: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_name_and_logo, container, false)

        initView(view)

        return view
    }

    override fun initView(view: View) {
        (activity as OnBoardActivityInterface).hideProgress()

        recyclerView = view.findViewById(R.id.iconsRecyclerView)

        adapter = CamouflageRecycleViewAdapter()
        val galleryLayoutManager: RecyclerView.LayoutManager =
            GridLayoutManager(requireContext(), 3)
        recyclerView.setLayoutManager(galleryLayoutManager)
        recyclerView.setAdapter(adapter)

        adapter.setIcons(cm.options, cm.selectedAliasPosition)

        view.findViewById<View>(R.id.back).setOnClickListener {
            activity.onBackPressed()
        }

        view.findViewById<View>(R.id.next).setOnClickListener {
            camouflage(adapter.selectedPosition)
        }
    }

    private fun camouflage(position: Int) {
        try {
            val option = cm.options[position]
            if (option != null) {
                if (cm.setLauncherActivityAlias(requireContext(), option.alias)) {
                    MyApplication.bus()
                        .post(CamouflageAliasChangedEvent())
                }
            }
        } catch (ignored: IndexOutOfBoundsException) {
        } finally {
            activity.addFragment(
                this,
                OnBoardHideTellaSet(),
                R.id.rootOnboard
            )
        }
    }
}