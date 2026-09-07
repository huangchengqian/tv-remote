package com.lizongying.mytv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.lizongying.mytv.databinding.SettingBinding


class SettingFragment : DialogFragment() {

    private var _binding: SettingBinding? = null
    private val binding get() = _binding!!

    private lateinit var updateManager: UpdateManager

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext() // It‘s safe to get context here.
        _binding = SettingBinding.inflate(inflater, container, false)
        binding.versionName.text = "当前版本: v${context.appVersionName}"
        binding.version.text = "https://github.com/huangchengqian/tv-remote"

        binding.switchChannelReversal.run {
            isChecked = SP.channelReversal
            setOnCheckedChangeListener { _, isChecked ->
                SP.channelReversal = isChecked
                (activity as MainActivity).settingDelayHide()
            }
        }

        binding.switchChannelNum.run {
            isChecked = SP.channelNum
            setOnCheckedChangeListener { _, isChecked ->
                SP.channelNum = isChecked
                (activity as MainActivity).settingDelayHide()
            }
        }

        binding.switchTime.run {
            isChecked = SP.time
            setOnCheckedChangeListener { _, isChecked ->
                SP.time = isChecked
                (activity as MainActivity).settingDelayHide()
            }
        }

        binding.switchBootStartup.run {
            isChecked = SP.bootStartup
            setOnCheckedChangeListener { _, isChecked ->
                SP.bootStartup = isChecked
                (activity as MainActivity).settingDelayHide()
            }
        }

        binding.sourceUrl.setText(SP.sourceUrl)
        binding.saveSource.setOnClickListener {
            (activity as MainActivity).settingDelayHide()
            SP.sourceUrl = binding.sourceUrl.text.toString().trim()
            android.widget.Toast.makeText(
                context, "已保存，正在刷新频道", android.widget.Toast.LENGTH_SHORT
            ).show()
            dismiss()
            requireActivity().recreate()
        }

        updateManager = UpdateManager(context, this, context.appVersionCode)
        binding.checkVersion.setOnClickListener(
            OnClickListenerCheckVersion(
                activity as MainActivity,
                updateManager
            )
        )

        showControlQrcode()

        binding.exit.setOnClickListener{
            requireActivity().finishAffinity()
        }

        return binding.root
    }

    /**
     * 显示手机控制页地址二维码，手机扫码即可遥控电视。
     */
    private fun showControlQrcode() {
        val ip = Utils.getLocalIpAddress()
        if (ip == null) {
            binding.controlUrl.text = "手机控制：未连接网络"
            return
        }
        val url = "http://$ip:9958"
        binding.controlUrl.text = "手机控制（同一WiFi）：$url"
        try {
            val size = 320
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                url, com.google.zxing.BarcodeFormat.QR_CODE, size, size
            )
            val bitmap = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.RGB_565
            )
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(
                        x, y,
                        if (matrix.get(x, y)) android.graphics.Color.BLACK
                        else android.graphics.Color.WHITE
                    )
                }
            }
            binding.qrcode.setImageBitmap(bitmap)
        } catch (e: Exception) {
            binding.qrcode.visibility = View.GONE
        }
    }

    fun setVersionName(versionName: String) {
        if (_binding != null) {
            binding.versionName.text = versionName
        }
    }

    internal class OnClickListenerCheckVersion(
        private val mainActivity: MainActivity,
        private val updateManager: UpdateManager
    ) :
        View.OnClickListener {
        override fun onClick(view: View?) {
            mainActivity.settingDelayHide()
            updateManager.checkAndUpdate()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingFragment"
    }
}

