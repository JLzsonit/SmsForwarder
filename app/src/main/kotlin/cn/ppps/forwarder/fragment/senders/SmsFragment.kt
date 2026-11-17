package cn.ppps.forwarder.fragment.senders

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import com.google.gson.Gson
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.database.entity.Sender
import cn.ppps.forwarder.database.viewmodel.BaseViewModelFactory
import cn.ppps.forwarder.database.viewmodel.SenderViewModel
import cn.ppps.forwarder.databinding.FragmentSendersSmsBinding
import cn.ppps.forwarder.entity.MsgInfo
import cn.ppps.forwarder.entity.setting.SmsSetting
import cn.ppps.forwarder.utils.CommonUtils
import cn.ppps.forwarder.utils.EVENT_TOAST_ERROR
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.KEY_SENDER_CLONE
import cn.ppps.forwarder.utils.KEY_SENDER_ID
import cn.ppps.forwarder.utils.KEY_SENDER_TEST
import cn.ppps.forwarder.utils.KEY_SENDER_TYPE
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.XToastUtils
import cn.ppps.forwarder.utils.sender.SmsUtils
import com.jeremyliao.liveeventbus.LiveEventBus
import com.xuexiang.xaop.annotation.SingleClick
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xrouter.annotation.AutoWired
import com.xuexiang.xrouter.launcher.XRouter
import com.xuexiang.xui.utils.CountDownButtonHelper
import com.xuexiang.xui.widget.actionbar.TitleBar
import com.xuexiang.xui.widget.dialog.materialdialog.DialogAction
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog
import io.reactivex.SingleObserver
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.util.Date

@Page(name = "短信")
@Suppress("PrivatePropertyName")
class SmsFragment : BaseFragment<FragmentSendersSmsBinding?>(), View.OnClickListener {

    private val TAG: String = SmsFragment::class.java.simpleName
    private var titleBar: TitleBar? = null
    private val viewModel by viewModels<SenderViewModel> { BaseViewModelFactory(context) }
    private var mCountDownHelper: CountDownButtonHelper? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var syncingFallback = false

    @JvmField
    @AutoWired(name = KEY_SENDER_ID)
    var senderId: Long = 0

    @JvmField
    @AutoWired(name = KEY_SENDER_TYPE)
    var senderType: Int = 0

    @JvmField
    @AutoWired(name = KEY_SENDER_CLONE)
    var isClone: Boolean = false

    override fun initArgs() {
        XRouter.getInstance().inject(this)
    }

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentSendersSmsBinding {
        return FragmentSendersSmsBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar? {
        titleBar = super.initTitle()!!.setImmersive(false).setTitle(R.string.sms_menu)
        return titleBar
    }

    /**
     * 初始化控件
     */
    override fun initViews() {
        //检查发短信权限
        XXPermissions.with(this)
            .permission(PermissionLists.getSendSmsPermission())
            .request(object : OnPermissionCallback {
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    val allGranted = deniedList.isEmpty()
                    if (!allGranted) {
                        // 判断请求失败的权限是否被用户勾选了不再询问的选项
                        val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(requireActivity(), deniedList)
                        if (doNotAskAgain) {
                            XToastUtils.error(R.string.toast_denied_never)
                            // 如果是被永久拒绝就跳转到应用权限系统设置页面
                            XXPermissions.startPermissionActivity(requireContext(), deniedList)
                        }
                        // 处理权限请求失败的逻辑
                        XToastUtils.error(R.string.toast_denied)
                        HttpServerUtils.enableApiSmsSend = false
                        return
                    }
                    // 处理权限请求成功的逻辑

                }
            })

        //测试按钮增加倒计时，避免重复点击
        mCountDownHelper = CountDownButtonHelper(binding!!.btnTest, SettingUtils.requestTimeout)
        mCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnTest.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnTest.text = getString(R.string.test)
            }
        })

        //新增
        if (senderId <= 0) {
            titleBar?.setSubTitle(getString(R.string.add_sender))
            binding!!.btnDel.setText(R.string.discard)
            return
        }

        //编辑
        binding!!.btnDel.setText(R.string.del)
        Core.sender.get(senderId).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe(object : SingleObserver<Sender> {
            override fun onSubscribe(d: Disposable) {}

            override fun onError(e: Throwable) {
                e.printStackTrace()
                Log.e(TAG, "onError:$e")
            }

            override fun onSuccess(sender: Sender) {
                if (isClone) {
                    titleBar?.setSubTitle(getString(R.string.clone_sender) + ": " + sender.name)
                    binding!!.btnDel.setText(R.string.discard)
                } else {
                    titleBar?.setSubTitle(getString(R.string.edit_sender) + ": " + sender.name)
                }
                binding!!.etName.setText(sender.name)
                binding!!.sbEnable.isChecked = sender.status == 1
                val settingVo = Gson().fromJson(sender.jsonSetting, SmsSetting::class.java)
                Log.d(TAG, settingVo.toString())
                if (settingVo != null) {
                    binding!!.rgSimSlot.check(settingVo.getSmsSimSlotCheckId())
                    binding!!.etMobiles.setText(settingVo.mobiles)
                    //启用时机三选一（兼容旧版 onlyNoNetwork 开关数据）
                    binding!!.rgFallbackMode.check(settingVo.getSmsFallbackModeCheckId())
                    //同步全自动开关的显示状态
                    syncAutoSwitchFromRadio()
                }
            }
        })
    }

    override fun initListeners() {
        binding!!.btInsertSender.setOnClickListener(this)
        binding!!.btnTest.setOnClickListener(this)
        binding!!.btnDel.setOnClickListener(this)
        binding!!.btnSave.setOnClickListener(this)
        LiveEventBus.get(KEY_SENDER_TEST, String::class.java).observe(this) { mCountDownHelper?.finish() }

        //启用时机变化时，刷新实时网络状态提示；并同步全自动开关
        binding!!.rgFallbackMode.setOnCheckedChangeListener { _, _ ->
            if (!syncingFallback) syncAutoSwitchFromRadio()
            updateNetStatus()
        }
        //全自动开关：开=仅无网络时启用，关=始终启用；与下面三选一联动
        binding!!.sbAutoMode.setOnCheckedChangeListener { _, isChecked ->
            if (syncingFallback) return@setOnCheckedChangeListener
            syncingFallback = true
            binding!!.rgFallbackMode.check(
                if (isChecked) R.id.rb_fallback_no_network else R.id.rb_fallback_always
            )
            syncingFallback = false
            updateAutoSwitchText()
            updateNetStatus()
        }
        registerNetStatusCallback()
    }

    /** 根据单选按钮的启用时机，同步开关的显示状态 */
    private fun syncAutoSwitchFromRadio() {
        val b = binding ?: return
        val auto = b.rgFallbackMode.checkedRadioButtonId == R.id.rb_fallback_no_network
        if (b.sbAutoMode.isChecked != auto) {
            syncingFallback = true
            b.sbAutoMode.isChecked = auto
            syncingFallback = false
        }
        updateAutoSwitchText()
    }

    /** 更新开关下方的状态文字 */
    private fun updateAutoSwitchText() {
        val b = binding ?: return
        b.tvAutoModeState.text = if (b.sbAutoMode.isChecked)
            getString(R.string.SmsAutoModeOn) else getString(R.string.SmsAutoModeOff)
    }

    /**
     * 注册默认网络回调：网络变化（有网/断网）时自动刷新页面上的实时状态提示。
     * 无需手动开关：选「仅当无网络时启用」后，有网自动跳过短信、无网自动启用。
     */
    private fun registerNetStatusCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { postUpdateNetStatus() }
                override fun onLost(network: Network) { postUpdateNetStatus() }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            Log.e(TAG, "registerNetStatusCallback error: ${e.message}")
        }
    }

    private fun postUpdateNetStatus() {
        if (!isAdded || binding == null) return
        requireActivity().runOnUiThread { updateNetStatus() }
    }

    /**
     * 实时显示当前网络状态下短信通道的自动行为
     */
    private fun updateNetStatus() {
        val b = binding ?: return
        val mode = when (b.rgFallbackMode.checkedRadioButtonId) {
            R.id.rb_fallback_no_network -> 1
            R.id.rb_fallback_auto -> 2
            else -> 0
        }
        if (mode == 0) {
            b.tvNetStatus.visibility = View.GONE
            return
        }
        //ping 检测可能阻塞，放子线程
        Thread {
            val hasNet = SmsUtils.isNetworkReallyAvailable()
            if (!isAdded || binding == null) return@Thread
            requireActivity().runOnUiThread {
                val bb = binding ?: return@runOnUiThread
                bb.tvNetStatus.visibility = View.VISIBLE
                bb.tvNetStatus.text = when (mode) {
                    1 -> if (hasNet) "● 当前有网络：短信通道自动关闭（不发短信），断网后自动启用" else "● 当前无网络：短信通道自动启用（发短信），联网后自动关闭"
                    else -> if (hasNet) "● 当前有网络：短信通道待命，其他通道失败时自动改发短信" else "● 当前无网络：短信通道直接发送"
                }
            }
        }.start()
    }

    @SingleClick
    override fun onClick(v: View) {
        try {
            when (v.id) {
                R.id.bt_insert_sender -> {
                    CommonUtils.insertOrReplaceText2Cursor(binding!!.etMobiles, getString(R.string.tag_from))
                    return
                }

                R.id.btn_test -> {
                    mCountDownHelper?.start()
                    Thread {
                        try {
                            val settingVo = checkSetting()
                            Log.d(TAG, settingVo.toString())
                            val name = binding!!.etName.text.toString().trim().takeIf { it.isNotEmpty() } ?: getString(R.string.test_sender_name)
                            val msgInfo = MsgInfo("sms", getString(R.string.test_phone_num), String.format(getString(R.string.test_sender_sms), name), Date(), getString(R.string.test_sim_info))
                            //测试时强制发送：自动降级/仅无网络模式也不跳过，方便验证通道可用性
                            SmsUtils.sendMsg(settingVo, msgInfo, forceSend = true)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Log.e(TAG, "onError:$e")
                            LiveEventBus.get(EVENT_TOAST_ERROR, String::class.java).post(e.message.toString())
                        }
                        LiveEventBus.get(KEY_SENDER_TEST, String::class.java).post("finish")
                    }.start()
                    return
                }

                R.id.btn_del -> {
                    if (senderId <= 0 || isClone) {
                        popToBack()
                        return
                    }

                    MaterialDialog.Builder(requireContext()).title(R.string.delete_sender_title).content(R.string.delete_sender_tips).positiveText(R.string.lab_yes).negativeText(R.string.lab_no).onPositive { _: MaterialDialog?, _: DialogAction? ->
                        viewModel.delete(senderId)
                        XToastUtils.success(R.string.delete_sender_toast)
                        popToBack()
                    }.show()
                    return
                }

                R.id.btn_save -> {
                    val name = binding!!.etName.text.toString().trim()
                    if (TextUtils.isEmpty(name)) {
                        throw Exception(getString(R.string.invalid_name))
                    }

                    val status = if (binding!!.sbEnable.isChecked) 1 else 0
                    val settingVo = checkSetting()
                    if (isClone) senderId = 0
                    val senderNew = Sender(senderId, senderType, name, Gson().toJson(settingVo), status)
                    Log.d(TAG, senderNew.toString())

                    viewModel.insertOrUpdate(senderNew)
                    XToastUtils.success(R.string.tipSaveSuccess)
                    popToBack()
                    return
                }
            }
        } catch (e: Exception) {
            XToastUtils.error(e.message.toString())
            e.printStackTrace()
            Log.e(TAG, "onClick: $e")
        }
    }

    private fun checkSetting(): SmsSetting {
        val mobiles = binding!!.etMobiles.text.toString().trim()
        if (TextUtils.isEmpty(mobiles)) {
            throw Exception(getString(R.string.invalid_phone_num))
        }

        val simSlot = when (binding!!.rgSimSlot.checkedRadioButtonId) {
            R.id.rb_sim_slot_1 -> 1
            R.id.rb_sim_slot_2 -> 2
            else -> 0
        }
        //启用时机：0=始终启用、1=仅当无网络时启用、2=自动降级
        val fallbackMode = when (binding!!.rgFallbackMode.checkedRadioButtonId) {
            R.id.rb_fallback_no_network -> 1
            R.id.rb_fallback_auto -> 2
            else -> 0
        }
        //onlyNoNetwork 字段保留用于兼容旧版本数据
        val onlyNoNetwork = fallbackMode == 1

        return SmsSetting(simSlot, mobiles, onlyNoNetwork, fallbackMode)
    }

    override fun onResume() {
        super.onResume()
        syncAutoSwitchFromRadio()
        updateNetStatus()
    }

    override fun onDestroyView() {
        if (mCountDownHelper != null) mCountDownHelper!!.recycle()
        if (netCallback != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    cm.unregisterNetworkCallback(netCallback!!)
                }
            } catch (e: Exception) {
                Log.e(TAG, "unregisterNetworkCallback error: ${e.message}")
            }
            netCallback = null
        }
        super.onDestroyView()
    }

}
