package com.close.hook.ads.ui.fragment.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.close.hook.ads.R
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.model.ConfiguredBean
import com.close.hook.ads.databinding.BottomDialogAppInfoBinding
import com.close.hook.ads.databinding.BottomDialogSwitchesBinding
import com.close.hook.ads.databinding.FragmentAppsBinding
import com.close.hook.ads.hook.preference.PreferencesHelper
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.ui.adapter.AppsAdapter
import com.close.hook.ads.ui.adapter.FooterAdapter
import com.close.hook.ads.ui.fragment.base.BaseFragment
import com.close.hook.ads.ui.viewmodel.AppsViewModel
import com.close.hook.ads.util.AppUtils
import com.close.hook.ads.util.CacheDataManager.getFormatSize
import com.close.hook.ads.util.INavContainer
import com.close.hook.ads.util.IOnFabClickContainer
import com.close.hook.ads.util.IOnFabClickListener
import com.close.hook.ads.util.IOnTabClickContainer
import com.close.hook.ads.util.IOnTabClickListener
import com.close.hook.ads.util.LinearItemDecoration
import com.close.hook.ads.util.OnCLearCLickContainer
import com.close.hook.ads.util.OnClearClickListener
import com.close.hook.ads.util.dp
import com.close.hook.ads.util.setSpaceFooterView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppsFragment : BaseFragment<FragmentAppsBinding>(), AppsAdapter.OnItemClickListener,
    IOnTabClickListener, OnClearClickListener, IOnFabClickListener {

    private val viewModel by viewModels<AppsViewModel>(ownerProducer = { if (arguments?.getString("type") == "configured") requireActivity() else this })
    private lateinit var mAdapter: AppsAdapter
    private val footerAdapter = FooterAdapter()
    private var appConfigDialog: BottomSheetDialog? = null
    private var appInfoDialog: BottomSheetDialog? = null
    private lateinit var configBinding: BottomDialogSwitchesBinding
    private lateinit var infoBinding: BottomDialogAppInfoBinding
    private val childrenCheckBoxes by lazy {
        listOf(
            configBinding.switchOne,
            configBinding.switchTwo,
            configBinding.switchThree,
            configBinding.switchFour,
            configBinding.switchFive,
            configBinding.switchSix,
            configBinding.switchSeven,
            configBinding.switchEight
        )
    }
    private val prefKeys = listOf(
        "switch_one_",
        "switch_two_",
        "switch_three_",
        "switch_four_",
        "switch_five_",
        "switch_six_",
        "switch_seven_",
        "switch_eight_"
    )
    private val prefsHelper by lazy {
        PreferencesHelper(
            requireContext(),
            "com.close.hook.ads_preferences"
        )
    }

    companion object {
        @JvmStatic
        fun newInstance(type: String) =
            AppsFragment().apply {
                arguments = Bundle().apply {
                    putString("type", type)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        with(arguments?.getString("type") ?: "user") {
            viewModel.type = this
            if (this == "configured")
                (parentFragment as? IOnFabClickContainer)?.fabController = this@AppsFragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initView()
        initRefresh()
        initSheet()
        initObserve()

    }

    private fun initSheet() {
        // AppConfig
        appConfigDialog = BottomSheetDialog(requireContext())
        configBinding = BottomDialogSwitchesBinding.inflate(layoutInflater, null, false)
        appConfigDialog?.setContentView(configBinding.root)
        initAppConfig()

        // AppInfo
        appInfoDialog = BottomSheetDialog(requireContext())
        infoBinding = BottomDialogAppInfoBinding.inflate(layoutInflater, null, false)
        appInfoDialog?.setContentView(infoBinding.root)
        initAppInfo()
    }

    private fun initAppInfo() {
        infoBinding.apply {
            close.setOnClickListener {
                appInfoDialog?.dismiss()
            }
            detail.setOnClickListener {
                openAppDetails(packageName.value.text.toString())
                appInfoDialog?.dismiss()
            }
            launch.setOnClickListener {
                launchApp(packageName.value.text.toString())
                appInfoDialog?.dismiss()
            }
        }
    }

    private fun initAppConfig() {
        configBinding.apply {
            buttonClose.setOnClickListener {
                appConfigDialog?.dismiss()
            }

            var isUpdatingChildren = false

            fun updateChildrenCheckBoxes(isChecked: Boolean) {
                isUpdatingChildren = true
                childrenCheckBoxes.forEach { childCheckBox ->
                    childCheckBox.isChecked = isChecked
                }
                isUpdatingChildren = false
            }

            val onParentCheckedChange = { checkBox: MaterialCheckBox, state: Int ->
                if (state != MaterialCheckBox.STATE_INDETERMINATE) {
                    updateChildrenCheckBoxes(checkBox.isChecked)
                }
            }

            selectAll.addOnCheckedStateChangedListener(onParentCheckedChange)

            fun updateParentCheckBoxState() {
                val checkedCount = childrenCheckBoxes.count { it.isChecked }
                val allChecked = checkedCount == childrenCheckBoxes.size
                val noneChecked = checkedCount == 0

                selectAll.removeOnCheckedStateChangedListener(onParentCheckedChange)
                selectAll.checkedState = when {
                    allChecked -> MaterialCheckBox.STATE_CHECKED
                    noneChecked -> MaterialCheckBox.STATE_UNCHECKED
                    else -> MaterialCheckBox.STATE_INDETERMINATE
                }
                selectAll.addOnCheckedStateChangedListener(onParentCheckedChange)
            }

            childrenCheckBoxes.forEach { childCheckBox ->
                childCheckBox.addOnCheckedStateChangedListener { _, _ ->
                    if (!isUpdatingChildren) {
                        updateParentCheckBoxState()
                    }
                }
            }
        }
    }

    private fun openAppDetails(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppUtils.showToast(requireContext(), getString(R.string.open_app_details_failed))
        }
    }

    private fun launchApp(packageName: String) {
        val intent = requireContext().packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                AppUtils.showToast(requireContext(), getString(R.string.launch_app_failed))
            }
        } else {
            AppUtils.showToast(requireContext(), getString(R.string.launch_app_failed))
        }
    }

    private fun initRefresh() {
        binding.swipeRefresh.apply {
            setColorSchemeColors(
                MaterialColors.getColor(
                    requireContext(),
                    com.google.android.material.R.attr.colorPrimary,
                    -1
                )
            )
            setOnRefreshListener {
                viewModel.refreshApps()
            }
        }
    }

    private fun initView() {
        mAdapter = AppsAdapter(requireContext(), this)
        binding.recyclerView.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(30)
            adapter = ConcatAdapter(mAdapter)
            layoutManager = LinearLayoutManager(requireContext())

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private var totalDy = 0
                private val scrollThreshold = 20

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    totalDy += dy
                    val navContainer = activity as? INavContainer
                    if (totalDy > scrollThreshold) {
                        navContainer?.hideNavigation()
                        totalDy = 0
                    } else if (totalDy < -scrollThreshold) {
                        navContainer?.showNavigation()
                        totalDy = 0
                    }
                }
            })

            addItemDecoration(LinearItemDecoration(4.dp))
            FastScrollerBuilder(this).useMd2Style().build()
        }

        binding.vfContainer.setOnDisplayedChildChangedListener {
            binding.recyclerView.setSpaceFooterView(footerAdapter)
        }
    }

    private fun initObserve() {
        viewModel.appsLiveData.observe(viewLifecycleOwner) { list ->
            mAdapter.submitList(list)

            binding.swipeRefresh.isRefreshing = false
            binding.progressBar.isVisible = false
            updateSearchHint(list.size)

            val adapter = binding.recyclerView.adapter as ConcatAdapter
            if (list.isEmpty()) {
                if (adapter.adapters.contains(footerAdapter)) {
                    adapter.removeAdapter(footerAdapter)
                }
            } else {
                if (!adapter.adapters.contains(footerAdapter)) {
                    adapter.addAdapter(footerAdapter)
                }
            }

            if (binding.vfContainer.displayedChild != list.size) {
                binding.vfContainer.displayedChild = list.size
            }
        }
    }

    private fun updateSearchHint(size: Int) {
        if (this.isResumed)
            (parentFragment as? AppsPagerFragment)?.setHint(size)
    }

    override fun onItemClick(appInfo: AppInfo) {
        if (!MainActivity.isModuleActivated()) {
            AppUtils.showToast(requireContext(), getString(R.string.module_not_activated))
            return
        }
        configBinding.apply {
            sheetAppName.text = appInfo.appName
            version.text = appInfo.versionName

            icon.setImageDrawable(viewModel.appsLiveData.value?.find { it.packageName == appInfo.packageName }?.appIcon)

            childrenCheckBoxes.forEachIndexed { index, checkBox ->
                val key = prefKeys[index] + appInfo.packageName
                checkBox.isChecked = prefsHelper.getBoolean(key, false)
            }

            buttonUpdate.setOnClickListener {
                childrenCheckBoxes.forEachIndexed { index, checkBox ->
                    val key = prefKeys[index] + appInfo.packageName
                    prefsHelper.setBoolean(key, checkBox.isChecked)
                }
                AppUtils.showToast(requireContext(), getString(R.string.save_success))
                appConfigDialog?.dismiss()
            }
        }
        appConfigDialog?.show()
    }

    @SuppressLint("SetTextI18n")
    override fun onItemLongClick(appInfo: AppInfo) {
        infoBinding.apply {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            icon.setImageDrawable(viewModel.appsLiveData.value?.find { it.packageName == appInfo.packageName }?.appIcon)

            appName.text = appInfo.appName
            packageName.apply {
                title.text = getString(R.string.apk_package_name)
                value.text = appInfo.packageName
            }
            appSize.apply {
                title.text = getString(R.string.apk_size)
                value.text = getFormatSize(appInfo.size)
            }
            versionName.apply {
                title.text = getString(R.string.version_name)
                value.text = appInfo.versionName
            }
            versionCode.apply {
                title.text = getString(R.string.version_code)
                value.text = appInfo.versionCode.toString()
            }
            targetSdk.apply {
                title.text = getString(R.string.target_sdk)
                value.text = appInfo.targetSdk.toString()
            }
            minSdk.apply {
                title.text = getString(R.string.min_sdk)
                value.text = appInfo.minSdk.toString()
            }
            installTime.apply {
                title.text = getString(R.string.install_time)
                value.text = dateFormat.format(Date(appInfo.firstInstallTime))
            }
            updateTime.apply {
                title.text = getString(R.string.update_time)
                value.text = dateFormat.format(Date(appInfo.lastUpdateTime))
            }
        }
        appInfoDialog?.show()
    }

    override fun onReturnTop() {
        binding.recyclerView.scrollToPosition(0)
        (activity as? MainActivity)?.showNavigation()
    }

    override fun onResume() {
        super.onResume()
        (parentFragment as? IOnTabClickContainer)?.tabController = this
        (parentFragment as? OnCLearCLickContainer)?.controller = this
        updateSearchHint(viewModel.appsLiveData.value?.size ?: 0)
    }

    override fun onPause() {
        super.onPause()
        (parentFragment as? IOnTabClickContainer)?.tabController = null
        (parentFragment as? OnCLearCLickContainer)?.controller = null
    }

    override fun onDestroyView() {
        super.onDestroyView()

        appConfigDialog?.dismiss()
        appInfoDialog?.dismiss()
        appConfigDialog = null
        appInfoDialog = null

        viewModel.appsLiveData.removeObservers(viewLifecycleOwner)
        viewLifecycleOwner.lifecycleScope.cancel()
    }

    override fun updateSortList(
        filter: Pair<String, List<String>>,
        keyWord: String,
        isReverse: Boolean
    ) {
        viewModel.updateList(
            filter,
            keyWord,
            isReverse
        )
    }

    override fun onExport() {
        val configuredList = viewModel.appsLiveData.value?.map { appInfo ->
            ConfiguredBean(
                appInfo.packageName,
                prefKeys.map { key ->
                    prefsHelper.getBoolean(key + appInfo.packageName, false)
                })
        } ?: emptyList()
        try {
            val content = GsonBuilder().setPrettyPrinting().create().toJson(configuredList)
            if (saveFile(content)) {
                backupSAFLauncher.launch("configured_list.json")
            } else {
                Toast.makeText(requireContext(), getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                requireContext(),
                getString(R.string.export_no_app_found),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun saveFile(content: String): Boolean {
        return try {
            val dir = File(requireContext().cacheDir.toString())
            if (!dir.exists())
                dir.mkdir()
            val file = File("${requireContext().cacheDir}/configured_list.json")
            if (!file.exists())
                file.createNewFile()
            else {
                file.delete()
                file.createNewFile()
            }
            val fileOutputStream = FileOutputStream(file)
            fileOutputStream.write(content.toByteArray())
            fileOutputStream.flush()
            fileOutputStream.close()
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    private val backupSAFLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) backup@{ uri ->
            if (uri == null) return@backup
            try {
                File("${requireContext().cacheDir}/configured_list.json").inputStream()
                    .use { input ->
                        requireContext().contentResolver.openOutputStream(uri).use { output ->
                            if (output == null) {
                                Toast.makeText(requireContext(), getString(R.string.export_failed), Toast.LENGTH_SHORT)
                                    .show()
                            } else {
                                input.copyTo(output)
                                Toast.makeText(requireContext(), getString(R.string.export_success), Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

    override fun onRestore() {
        restoreSAFLauncher.launch(arrayOf("application/json"))
    }

    private val restoreSAFLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        val string = requireContext().contentResolver
                            .openInputStream(uri)?.reader().use { it?.readText() }
                            ?: throw IOException("Backup file was damaged")
                        val dataList: List<ConfiguredBean> = Gson().fromJson(
                            string,
                            Array<ConfiguredBean>::class.java
                        ).toList()
                        dataList.forEach { bean ->
                            bean.switchStates.forEachIndexed { index, state ->
                                prefsHelper.setBoolean(prefKeys[index] + bean.packageName, state)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), getString(R.string.import_success), Toast.LENGTH_SHORT).show()
                        }
                    }.onFailure { e ->
                        withContext(Dispatchers.Main) {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(getString(R.string.import_failed))
                                .setMessage(e.message ?: "Unknown error")
                                .setPositiveButton(android.R.string.ok, null)
                                .setNegativeButton(getString(R.string.crash_log)) { _, _ ->
                                    MaterialAlertDialogBuilder(requireContext())
                                        .setTitle(getString(R.string.crash_log))
                                        .setMessage(e.stackTraceToString())
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show()
                                }
                                .show()
                        }
                    }
                }
            }
        }

}