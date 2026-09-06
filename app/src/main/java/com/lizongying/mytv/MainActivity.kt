package com.lizongying.mytv

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.lizongying.mytv.models.TVViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainActivity : FragmentActivity(), Request.RequestListener {

    private var ready = 0
    private val playerFragment = PlayerFragment()
    private val mainFragment = MainFragment()
    private val infoFragment = InfoFragment()
    private val channelFragment = ChannelFragment()
    private var timeFragment = TimeFragment()
    private val settingFragment = SettingFragment()
    private val errorFragment = ErrorFragment()
    private val channelListFragment = ChannelListFragment()

    private var controlServer: ControlServer? = null

    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var doubleBackToExitPressedOnce = false

    private lateinit var gestureDetector: GestureDetector

    private val handler = Handler()
    private val delayHideMain: Long = 10000
    private val delayHideSetting: Long = 10000

    init {
        lifecycleScope.launch(Dispatchers.IO) {
            val utilsJob = async(start = CoroutineStart.LAZY) { Utils.init() }

            utilsJob.start()

//            utilsJob.await()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        Request.setRequestListener(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = SYSTEM_UI_FLAG_HIDE_NAVIGATION

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, playerFragment)
                .add(R.id.main_browse_fragment, timeFragment)
                .add(R.id.main_browse_fragment, infoFragment)
                .add(R.id.main_browse_fragment, channelFragment)
                .add(R.id.main_browse_fragment, channelListFragment)
                .add(R.id.main_browse_fragment, mainFragment)
                .hide(mainFragment)
                .commit()
        }
        gestureDetector = GestureDetector(this, GestureListener())

        errorFragment.buttonClickListener = View.OnClickListener {
            supportFragmentManager.beginTransaction()
                .remove(errorFragment)
                .commit()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.registerDefaultNetworkCallback(object :
                ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.i(TAG, "net ${Build.VERSION.SDK_INT}")
                    if (this@MainActivity.isNetworkConnected) {
                        Log.i(TAG, "net isNetworkConnected")
                        ready++
                    }
                }
            })
        } else {
            Log.i(TAG, "net ${Build.VERSION.SDK_INT}")
            ready++
        }

        requestStoragePermission()

        startControlServer()

    }

    private fun startControlServer() {
        try {
            controlServer = ControlServer(this, 9958)
            controlServer?.start(5000, true)
            Log.i(TAG, "control server started on 9958")
        } catch (e: Exception) {
            Log.e(TAG, "control server start error", e)
        }
    }

    //region 手机控制接口（ControlServer 调用，写入操作已在服务端切到主线程）

    fun controlPlay(position: Int) {
        play(position)
    }

    fun controlPrevChannel() {
        if (SP.channelReversal) {
            next()
        } else {
            prev()
        }
    }

    fun controlNextChannel() {
        if (SP.channelReversal) {
            prev()
        } else {
            next()
        }
    }

    fun controlShowSetting() {
        showSetting()
    }

    fun controlOk() {
        switchMainFragment()
    }

    fun controlBack() {
        back()
    }

    fun currentChannelPosition(): Int {
        return mainFragment.tvListViewModel.itemPosition.value ?: 0
    }

    fun currentChannelTitle(): String? {
        return mainFragment.tvListViewModel.getTVViewModelCurrent()?.getTV()?.title
    }

    fun currentChannelGroup(): String? {
        return mainFragment.tvListViewModel.getTVViewModelCurrent()?.getTV()?.channel
    }

    fun forEachChannel(block: (group: String, title: String) -> Unit) {
        mainFragment.tvListViewModel.tvListViewModel.value?.forEach {
            block(it.getTV().channel, it.getTV().title)
        }
    }

    fun currentVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max == 0) {
            return 0
        }
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun controlSetVolume(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, percent * max / 100, 0)
    }

    /**
     * 手机设置了新的源地址：从远程拉取并换源（设置页地址优先，其次默认源链）。
     */
    fun reloadSourceFromNetwork() {
        lifecycleScope.launch {
            val source = withContext(Dispatchers.IO) { ChannelSource.refreshRemote(this@MainActivity) }
            if (source != null) {
                Toast.makeText(this@MainActivity, "直播源已更新", Toast.LENGTH_SHORT).show()
                mainFragment.applySource(source)
            } else {
                Toast.makeText(this@MainActivity, "直播源拉取失败，请检查地址", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 手机推送了源内容：保存后重新加载并换源。
     */
    fun savePushedSource(content: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { ChannelSource.savePushContent(this@MainActivity, content) }
            val source = withContext(Dispatchers.IO) { ChannelSource.loadFresh(this@MainActivity) }
            if (source != null) {
                Toast.makeText(this@MainActivity, "已应用推送的直播源", Toast.LENGTH_SHORT).show()
                mainFragment.applySource(source)
            } else {
                Toast.makeText(this@MainActivity, "推送的直播源解析失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    //endregion

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || Build.VERSION.SDK_INT > 32
            || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            1
        )
    }

    fun showInfoFragment(tvViewModel: TVViewModel) {
        infoFragment.show(tvViewModel)
        if (SP.channelNum) {
            channelFragment.show(tvViewModel)
        }
    }

    private fun showChannel(channel: String) {
        if (!mainFragment.isHidden) {
            return
        }

        if (settingFragment.isVisible) {
            return
        }

        if (SP.channelNum) {
            channelFragment.show(channel)
        }
    }

    fun play(tvViewModel: TVViewModel) {
        playerFragment.play(tvViewModel)
        mainFragment.view?.requestFocus()
    }

    fun play(itemPosition: Int) {
        mainFragment.play(itemPosition)
    }

    fun prev() {
        mainFragment.prev()
    }

    fun next() {
        mainFragment.next()
    }

    private fun prevSource() {
//        mainFragment.prevSource()
    }

    private fun nextSource() {
//        mainFragment.nextSource()
    }

    fun switchMainFragment() {
        if (channelListFragment.isVisible) {
            hideChannelList()
        } else {
            showChannelList()
        }
    }

    private fun showChannelList() {
        channelListFragment.show()
        mainActive()
    }

    fun hideChannelList() {
        if (channelListFragment.isVisible) {
            channelListFragment.hide()
        }
    }

    fun mainActive() {
        handler.removeCallbacks(hideMain)
        handler.postDelayed(hideMain, delayHideMain)
    }

    fun settingDelayHide() {
        handler.removeCallbacks(hideSetting)
        handler.postDelayed(hideSetting, delayHideSetting)
        showTime()
    }

    fun settingHideNow() {
        handler.removeCallbacks(hideSetting)
        handler.postDelayed(hideSetting, 0)
    }

    fun settingNeverHide() {
        handler.removeCallbacks(hideSetting)
    }

    private val hideMain = Runnable {
        if (!mainFragment.isHidden) {
            supportFragmentManager.beginTransaction().hide(mainFragment).commit()
        }
        hideChannelList()
    }

    private fun mainFragmentIsHidden(): Boolean {
        return mainFragment.isHidden
    }

    private fun hideMainFragment() {
        if (!mainFragment.isHidden) {
            supportFragmentManager.beginTransaction()
                .hide(mainFragment)
                .commit()
        }
    }

    fun fragmentReady(tag: String) {
        ready++
        Log.i(TAG, "ready $tag $ready ")
        if (ready == 6) {
            mainFragment.fragmentReady()
            showTime()
        }
    }

    private fun showTime() {
        Log.i(TAG, "showTime ${SP.time}")
        if (SP.time) {
            timeFragment.show()
        } else {
            timeFragment.hide()
        }
    }

    fun isPlaying() {
        if (errorFragment.isVisible) {
            supportFragmentManager.beginTransaction()
                .remove(errorFragment)
                .commit()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            gestureDetector.onTouchEvent(event)
        }
        return super.onTouchEvent(event)
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            switchMainFragment()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            showSetting()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (velocityY > 0) {
                if (mainFragment.isHidden) {
                    prev()
                } else {
//                    if (mainFragment.selectedPosition == 0) {
//                        mainFragment.setSelectedPosition(
//                            mainFragment.tvListViewModel.maxNum.size - 1,
//                            false
//                        )
//                    }
                }
            }
            if (velocityY < 0) {
                if (mainFragment.isHidden) {
                    next()
                } else {
//                    if (mainFragment.selectedPosition == mainFragment.tvListViewModel.maxNum.size - 1) {
////                        mainFragment.setSelectedPosition(0, false)
//                        hideMainFragment()
//                        return false
//                    }
                }
            }
            return super.onFling(e1, e2, velocityX, velocityY)
        }
    }

    private fun showSetting() {
        if (!mainFragment.isHidden) {
            return
        }

        Log.i(TAG, "settingFragment ${settingFragment.isVisible}")
        if (!settingFragment.isVisible) {
            settingFragment.show(supportFragmentManager, "setting")
            settingDelayHide()
        } else {
            handler.removeCallbacks(hideSetting)
            settingFragment.dismiss()
        }
    }

    private val hideSetting = Runnable {
        if (settingFragment.isVisible) {
            settingFragment.dismiss()
        }
    }

    private fun channelUp() {
        if (mainFragment.isHidden) {
            if (SP.channelReversal) {
                next()
                return
            }
            prev()
        } else {
//                    if (mainFragment.selectedPosition == 0) {
//                        mainFragment.setSelectedPosition(
//                            mainFragment.tvListViewModel.maxNum.size - 1,
//                            false
//                        )
//                    }
        }
    }

    private fun channelDown() {
        if (mainFragment.isHidden) {
            if (SP.channelReversal) {
                prev()
                return
            }
            next()
        } else {
//                    if (mainFragment.selectedPosition == mainFragment.tvListViewModel.maxNum.size - 1) {
////                        mainFragment.setSelectedPosition(0, false)
//                        hideMainFragment()
//                        return false
//                    }
        }
    }

    private fun back() {
        if (channelListFragment.isVisible) {
            hideChannelList()
            return
        }

        if (doubleBackToExitPressedOnce) {
            super.onBackPressed()
            return
        }

        doubleBackToExitPressedOnce = true
        Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            doubleBackToExitPressedOnce = false
        }, 2000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.i(TAG, "keyCode $keyCode, event $event")
        when (keyCode) {
            KeyEvent.KEYCODE_0 -> {
                showChannel("0")
                return true
            }

            KeyEvent.KEYCODE_1 -> {
                showChannel("1")
                return true
            }

            KeyEvent.KEYCODE_2 -> {
                showChannel("2")
                return true
            }

            KeyEvent.KEYCODE_3 -> {
                showChannel("3")
                return true
            }

            KeyEvent.KEYCODE_4 -> {
                showChannel("4")
                return true
            }

            KeyEvent.KEYCODE_5 -> {
                showChannel("5")
                return true
            }

            KeyEvent.KEYCODE_6 -> {
                showChannel("6")
                return true
            }

            KeyEvent.KEYCODE_7 -> {
                showChannel("7")
                return true
            }

            KeyEvent.KEYCODE_8 -> {
                showChannel("8")
                return true
            }

            KeyEvent.KEYCODE_9 -> {
                showChannel("9")
                return true
            }

            KeyEvent.KEYCODE_ESCAPE -> {
                back()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                back()
                return true
            }

            KeyEvent.KEYCODE_BOOKMARK -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_UNKNOWN -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_HELP -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_SETTINGS -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_MENU -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_ENTER -> {
                switchMainFragment()
            }

            KeyEvent.KEYCODE_DPAD_CENTER -> {
                switchMainFragment()
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                channelUp()
            }

            KeyEvent.KEYCODE_CHANNEL_UP -> {
                channelUp()
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                channelDown()
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                channelDown()
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!channelListFragment.isVisible && !settingFragment.isVisible) {
                    switchMainFragment()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!channelListFragment.isVisible && !settingFragment.isVisible) {
                    showSetting()
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun getAppSignature() = this.appSignature

    override fun onStart() {
        Log.i(TAG, "onStart")
        super.onStart()
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
        if (!mainFragment.isHidden) {
            handler.postDelayed(hideMain, delayHideMain)
        }
    }

    override fun onPause() {
        Log.i(TAG, "onPause")
        super.onPause()
        handler.removeCallbacks(hideMain)
    }

    override fun onDestroy() {
        super.onDestroy()
        controlServer?.stop()
        controlServer = null
        Request.onDestroy()
    }

    override fun onRequestFinished(message: String?) {
        if (message != null && !errorFragment.isVisible) {
            supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, errorFragment)
                .commitNow()
            errorFragment.setErrorContent(message)
        }
        fragmentReady("Request")
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}