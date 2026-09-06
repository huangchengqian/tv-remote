package com.lizongying.mytv

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ListRowPresenter.SelectItemViewHolderTask
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.lifecycleScope
import com.lizongying.mytv.api.YSP
import com.lizongying.mytv.models.ProgramType
import com.lizongying.mytv.models.TVListViewModel
import com.lizongying.mytv.models.TVViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainFragment : BrowseSupportFragment() {

    private var itemPosition = 0

    private var rowsAdapter: ArrayObjectAdapter? = null

    var tvListViewModel = TVListViewModel()

    private var lastVideoUrl = ""

    private var firstLoad = true

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        headersState = HEADERS_DISABLED
    }

//    override fun onCreateView(
//        inflater: LayoutInflater,
//        container: ViewGroup?,
//        savedInstanceState: Bundle?
//    ): View? {
//        val rootView = super.onCreateView(inflater, container, savedInstanceState)
//        rootView?.setOnClickListener {
//            Log.i(TAG, "main on click")
//            fragmentManager!!.beginTransaction().hide(this).commit()
//        }
//        mainFragment.view?.setOnClickListener {
//            Log.i(TAG, "mainFragment on click")
//            fragmentManager!!.beginTransaction().hide(this).commit()
//        }
//        getRowsSupportFragment().view?.setOnClickListener {
//            Log.i(TAG, "getRowsSupportFragment on click")
//            fragmentManager!!.beginTransaction().hide(this).commit()
//        }
//
//
//        return rootView
//    }

    override fun onStart() {
        Log.i(TAG, "onStart")
        super.onStart()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        activity?.let { YSP.init(it) }

        lifecycleScope.launch {
            var sourceOrigin: ChannelSource.SourceOrigin? = null
            context?.let { ctx ->
                val result = withContext(Dispatchers.IO) { ChannelSource.loadCacheFirst(ctx) }
                if (result != null) {
                    TVList.list = result.channels
                    sourceOrigin = result.origin
                    Log.i(TAG, "source loaded, origin=${result.origin}, groups=${result.channels.size}")
                } else {
                    Log.i(TAG, "no custom source, use built-in list")
                }
            }

            loadRows()

            setupEventListeners()

            registerObservers()

            (activity as MainActivity).fragmentReady("MainFragment")

            // 后台静默刷新远程源：内容有变化时无缝换源，保留当前频道
            if (sourceOrigin != ChannelSource.SourceOrigin.LOCAL) {
                context?.let { ctx ->
                    val refreshed = withContext(Dispatchers.IO) { ChannelSource.refreshRemote(ctx) }
                    if (refreshed != null
                        && ChannelSource.channelsKey(refreshed) != ChannelSource.channelsKey(TVList.list)
                    ) {
                        Log.i(TAG, "source updated in background")
                        Toast.makeText(context, "直播源已更新", Toast.LENGTH_SHORT).show()
                        rebuildRows(refreshed)
                    }
                }
            }
        }
    }

    /**
     * 用新的源内容重建频道列表，保留当前频道。必须在主线程调用。
     */
    fun applySource(source: Map<String, List<TV>>) {
        view?.post {
            rebuildRows(source)
        }
    }

    private fun rebuildRows(source: Map<String, List<TV>>) {
        val current = tvListViewModel.getTVViewModel(itemPosition)?.getTV()
        TVList.list = source
        tvListViewModel = TVListViewModel()
        adapter = null
        loadRows()
        setupEventListeners()
        registerObservers()

        // 保持用户当前所在位置不跳动：
        // 1) 原序号对应的频道（分组+名称）一致时直接保持
        // 2) 否则按分组+名称查找
        // 3) 都没有则保持原序号
        // 不调用 changed()：不打断当前播放，也不拉走列表焦点
        var target = itemPosition
        val list = tvListViewModel.tvListViewModel.value
        if (current != null && list != null) {
            if (list.getOrNull(itemPosition)
                    ?.let { it.getTV().title == current.title && it.getTV().channel == current.channel }
                != true
            ) {
                val found = list.indexOfFirst {
                    it.getTV().title == current.title && it.getTV().channel == current.channel
                }
                if (found >= 0) {
                    target = found
                }
            }
        }
        if (target in 0 until tvListViewModel.size()) {
            itemPosition = target
            tvListViewModel.setItemPosition(itemPosition)
            setSelectedPosition(itemPosition, false)
        }

        // 当前播放中的频道若在新源里地址变了，静默换流（不动列表）
        val newViewModel = tvListViewModel.getTVViewModel(itemPosition)
        val oldUrl = current?.videoUrl?.firstOrNull()
        val newUrl = newViewModel?.getTV()?.videoUrl?.firstOrNull()
        if (newViewModel != null && oldUrl != null && oldUrl != newUrl) {
            (activity as? MainActivity)?.play(newViewModel)
        }
    }

    private fun registerObservers() {
        tvListViewModel.tvListViewModel.value?.forEach { tvViewModel ->
            tvViewModel.errInfo.observe(viewLifecycleOwner) { _ ->
                if (tvViewModel.errInfo.value != null
                    && tvViewModel.getTV().id == itemPosition
                ) {
                    Toast.makeText(context, tvViewModel.errInfo.value, Toast.LENGTH_SHORT).show()
                }
            }
            tvViewModel.ready.observe(viewLifecycleOwner) { _ ->

                // not first time && channel not change
                if (tvViewModel.ready.value != null
                    && tvViewModel.getTV().id == itemPosition
                    && check(tvViewModel)
                ) {
                    Log.i(TAG, "ready ${tvViewModel.getTV().title}")
                    (activity as? MainActivity)?.play(tvViewModel)
                }
            }
            tvViewModel.change.observe(viewLifecycleOwner) { _ ->
                if (tvViewModel.change.value != null) {
                    val title = tvViewModel.getTV().title
                    Log.i(TAG, "switch $title")
                    if (tvViewModel.getTV().pid != "") {
                        Log.i(TAG, "request $title")
                        lifecycleScope.launch(Dispatchers.IO) {
                            tvViewModel.let { Request.fetchData(it) }
                        }
                        (activity as? MainActivity)?.showInfoFragment(tvViewModel)
                        setSelectedPosition(
                            tvViewModel.getRowPosition(), true,
                            SelectItemViewHolderTask(tvViewModel.getItemPosition())
                        )
                    } else {
                        if (check(tvViewModel)) {
                            (activity as? MainActivity)?.play(tvViewModel)
                            (activity as? MainActivity)?.showInfoFragment(tvViewModel)
                            setSelectedPosition(
                                tvViewModel.getRowPosition(), true,
                                SelectItemViewHolderTask(tvViewModel.getItemPosition())
                            )
                        }
                    }
                }
            }
        }
    }

    fun toLastPosition() {
        setSelectedPosition(
            selectedPosition, false,
            SelectItemViewHolderTask(tvListViewModel.maxNum[selectedPosition] - 1)
        )
    }

    fun toFirstPosition() {
        setSelectedPosition(
            selectedPosition, false,
            SelectItemViewHolderTask(0)
        )
    }

    override fun startHeadersTransition(withHeaders: Boolean) {
    }

    private fun loadRows() {
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

        val cardPresenter = CardPresenter(context!!)

        var idx: Long = 0
        for ((k, v) in TVList.list) {
            val listRowAdapter = ArrayObjectAdapter(cardPresenter)
            for ((idx2, v1) in v.withIndex()) {
                val tvViewModel = TVViewModel(v1)
                tvViewModel.setRowPosition(idx.toInt())
                tvViewModel.setItemPosition(idx2)
                tvListViewModel.addTVViewModel(tvViewModel)
                listRowAdapter.add(tvViewModel)
            }
            tvListViewModel.maxNum.add(v.size)
            val header = HeaderItem(idx, k)
            rowsAdapter!!.add(ListRow(header, listRowAdapter))
            idx++
        }

        adapter = rowsAdapter

        // 仅首次加载读取持久化的位置；换源重建时保持内存中的当前位置
        if (firstLoad) {
            itemPosition = SP.itemPosition
            firstLoad = false
        }
        if (itemPosition >= tvListViewModel.size()) {
            itemPosition = 0
        }
        tvListViewModel.setItemPosition(itemPosition)
    }

    fun prevSource() {
        view?.post {
            val tvViewModel = tvListViewModel.getTVViewModel(itemPosition)
            if (tvViewModel != null) {
                if (tvViewModel.videoUrl.value!!.size > 1) {
                    val videoIndex = tvViewModel.videoIndex.value?.minus(1)
                    if (videoIndex == -1) {
                        tvViewModel.setVideoIndex(tvViewModel.videoUrl.value!!.size - 1)
                    }
                    tvViewModel.changed()
                }
            }
        }
    }

    fun nextSource() {
        view?.post {
            val tvViewModel = tvListViewModel.getTVViewModel(itemPosition)
            if (tvViewModel != null) {
                if (tvViewModel.videoUrl.value!!.size > 1) {
                    val videoIndex = tvViewModel.videoIndex.value?.plus(1)
                    if (videoIndex == tvViewModel.videoUrl.value!!.size) {
                        tvViewModel.setVideoIndex(0)
                    }
                    tvViewModel.changed()
                }
            }
        }
    }

    private fun setupEventListeners() {
        onItemViewClickedListener = ItemViewClickedListener()
        onItemViewSelectedListener = ItemViewSelectedListener()
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder,
            item: Any,
            rowViewHolder: RowPresenter.ViewHolder,
            row: Row
        ) {
            if (item is TVViewModel) {
                if (itemPosition != item.getTV().id) {
                    itemPosition = item.getTV().id
                    tvListViewModel.setItemPosition(itemPosition)
                    tvListViewModel.getTVViewModel(itemPosition)?.changed()
                }
                (activity as? MainActivity)?.switchMainFragment()
            }
        }
    }

    private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
        override fun onItemSelected(
            itemViewHolder: Presenter.ViewHolder?, item: Any?,
            rowViewHolder: RowPresenter.ViewHolder, row: Row
        ) {
            if (item is TVViewModel) {
                tvListViewModel.setItemPositionCurrent(item.getTV().id)
                (activity as MainActivity).mainActive()
            }
        }
    }

    fun check(tvViewModel: TVViewModel): Boolean {
        val title = tvViewModel.getTV().title
        val videoUrl = tvViewModel.videoIndex.value?.let { tvViewModel.videoUrl.value?.get(it) }
        if (videoUrl == null || videoUrl == "") {
            Log.e(TAG, "$title videoUrl is empty")
            return false
        }

        if (videoUrl == lastVideoUrl) {
            Log.e(TAG, "$title videoUrl is duplication")
            return false
        }

        return true
    }

    fun fragmentReady() {
        tvListViewModel.getTVViewModel(itemPosition)?.changed()

        tvListViewModel.tvListViewModel.value?.forEach { tvViewModel ->
            updateEPG(tvViewModel)
        }
    }

    fun play(itemPosition: Int) {
        view?.post {
            if (itemPosition > -1 && itemPosition < tvListViewModel.size()) {
                this.itemPosition = itemPosition
                tvListViewModel.setItemPosition(itemPosition)
                tvListViewModel.getTVViewModel(itemPosition)?.changed()
            } else {
                Toast.makeText(context, "频道不存在", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun prev() {
        view?.post {
            itemPosition--
            if (itemPosition == -1) {
                itemPosition = tvListViewModel.size() - 1
            }
            tvListViewModel.setItemPosition(itemPosition)
            tvListViewModel.getTVViewModel(itemPosition)?.changed()
        }
    }

    fun next() {
        view?.post {
            itemPosition++
            if (itemPosition == tvListViewModel.size()) {
                itemPosition = 0
            }
            tvListViewModel.setItemPosition(itemPosition)
            tvListViewModel.getTVViewModel(itemPosition)?.changed()
        }
    }

    private fun updateEPG(tvViewModel: TVViewModel) {
        when (tvViewModel.getTV().programType) {
            ProgramType.Y_PROTO -> {
                Request.fetchYProtoEPG(tvViewModel)
            }

            ProgramType.Y_JCE -> {
                Request.fetchYJceEPG(tvViewModel)
            }

            ProgramType.F -> {
                Request.fetchFEPG(tvViewModel)
            }

            ProgramType.NONE -> {
                // 自定义直播源，无节目单
            }
        }
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
    }

    override fun onStop() {
        Log.i(TAG, "onStop")
        super.onStop()
        SP.itemPosition = itemPosition
        Log.i(TAG, "$POSITION $itemPosition saved")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainFragment"
        private const val POSITION = "position"
    }
}