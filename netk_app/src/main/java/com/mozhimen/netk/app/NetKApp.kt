package com.mozhimen.netk.app

import android.content.Context
import android.content.pm.PackageInfo
import android.util.Log
import androidx.annotation.AnyThread
import androidx.lifecycle.ProcessLifecycleOwner
import com.liulishuo.okdownload.core.breakpoint.IBreakpointCompare
import com.liulishuo.okdownload.core.exception.ServerCanceledException
import com.mozhimen.basick.elemk.commons.I_Listener
import com.mozhimen.basick.lintk.optin.OptInApiCall_BindLifecycle
import com.mozhimen.basick.lintk.optin.OptInApiInit_ByLazy
import com.mozhimen.basick.lintk.optin.OptInApiInit_InApplication
import com.mozhimen.basick.postk.event.PostKEventLiveData
import com.mozhimen.basick.utilk.android.app.UtilKPermission
import com.mozhimen.basick.utilk.android.content.UtilKPackageInfo
import com.mozhimen.basick.utilk.bases.BaseUtilK
import com.mozhimen.basick.utilk.java.io.UtilKFileDir
import com.mozhimen.basick.utilk.kotlin.strFilePath2file
import com.mozhimen.installk.manager.InstallKManager
import com.mozhimen.installk.manager.commons.IPackagesChangeListener
import com.mozhimen.netk.app.commons.INetKAppState
import com.mozhimen.netk.app.cons.CNetKAppErrorCode
import com.mozhimen.netk.app.cons.CNetKAppEvent
import com.mozhimen.netk.app.cons.CNetKAppState
import com.mozhimen.netk.app.cons.ENetKAppFinishType
import com.mozhimen.netk.app.task.db.AppTaskDaoManager
import com.mozhimen.netk.app.unzip.NetKAppUnzipManager
import com.mozhimen.netk.app.task.db.AppTaskDbManager
import com.mozhimen.netk.app.download.NetKAppDownloadManager
import com.mozhimen.netk.app.download.mos.AppDownloadException
import com.mozhimen.netk.app.download.mos.intAppErrorCode2appDownloadException
import com.mozhimen.netk.app.install.NetKAppInstallManager
import com.mozhimen.netk.app.install.NetKAppInstallProxy
import com.mozhimen.netk.app.install.NetKAppUnInstallManager
import com.mozhimen.netk.app.task.NetKAppTaskManager
import com.mozhimen.netk.app.task.db.AppTask
import com.mozhimen.netk.app.task.cons.CNetKAppTaskState
import com.mozhimen.netk.app.utils.intAppState2strAppState
import com.mozhimen.netk.okdownload.ext.NetKOkDownloadExt

/**
 * @ClassName NetKAppDownload
 * @Description TODO
 * @Author Mozhimen & Kolin Zhao
 * @Date 2023/10/12 9:38
 * @Version 1.0
 */
@OptInApiInit_InApplication
object NetKApp : INetKAppState, BaseUtilK() {
    private val _appDownloadStateListeners = mutableListOf<INetKAppState>()

    @OptIn(OptInApiCall_BindLifecycle::class, OptInApiInit_ByLazy::class)
    private val _netKAppInstallProxy by lazy { NetKAppInstallProxy(_context, ProcessLifecycleOwner.get()) }

    @OptIn(OptInApiCall_BindLifecycle::class, OptInApiInit_ByLazy::class)
    val netKAppInstallProxy get() = _netKAppInstallProxy

    /////////////////////////////////////////////////////////////////
    // init
    /////////////////////////////////////////////////////////////////
    //region # init
    @OptIn(OptInApiCall_BindLifecycle::class, OptInApiInit_ByLazy::class)
    @JvmStatic
    fun init(context: Context, compare: IBreakpointCompare) {
        _netKAppInstallProxy.bindLifecycle(ProcessLifecycleOwner.get())// 注册应用安装的监听 InstalledApkReceiver.registerReceiver(this)
//        NetKOkDownloadExt.init(context)
        AppTaskDbManager.init(context)
        InstallKManager.apply {
            init(context)
            registerPackagesChangeListener(object : IPackagesChangeListener {
                override fun onPackageAdd(packageInfo: PackageInfo) {
                    NetKAppInstallManager.onInstallSuccess(packageInfo.packageName, UtilKPackageInfo.getVersionCode(packageInfo))
                }

                override fun onPackageRemove(packageInfo: PackageInfo) {
                    NetKAppUnInstallManager.onUninstallSuccess(packageInfo.packageName)
                }
            })
        }
        NetKAppDownloadManager.init(context, compare)
    }

    @JvmStatic
    fun registerDownloadStateListener(listener: INetKAppState) {
        if (!_appDownloadStateListeners.contains(listener)) {
            _appDownloadStateListeners.add(listener)
        }
    }

    @JvmStatic
    fun unregisterDownloadListener(listener: INetKAppState) {
        val indexOf = _appDownloadStateListeners.indexOf(listener)
        if (indexOf >= 0)
            _appDownloadStateListeners.removeAt(indexOf)
    }
    //endregion

    /////////////////////////////////////////////////////////////////
    // control
    /////////////////////////////////////////////////////////////////
    //region # control
    @JvmStatic
    fun taskRetry(appTask: AppTask) {
        NetKAppUnInstallManager.deleteFileApk(appTask)//删除本地文件.apk + .npk

        if (appTask.downloadUrlCurrent != appTask.downloadUrl) {//重新使用内部地址下载
            if (appTask.downloadUrl.isNotEmpty()) {
                appTask.downloadUrlCurrent = appTask.downloadUrl
                taskStart(appTask)
            } else {
                appTask.apkVerifyNeed = false//重新下载，下次不校验MD5值
                taskStart(appTask)
            }
        }
    }

    @JvmStatic
    fun taskStart(appTask: AppTask) {
        try {
            if (appTask.isTaskProcess() && !appTask.isTaskPause()) {
                Log.d(TAG, "taskStart: the task already start")
                return
            }
//            if (NetKAppDownloadManager.getDownloadTaskCount() >= 3) {
//                /**
//                 * [CNetKAppTaskState.STATE_TASK_FAIL]
//                 */
//                onTaskFinish(appTask, ENetKAppFinishType.FAIL(CNetKAppErrorCode.CODE_DOWNLOAD_ENOUGH.intAppErrorCode2appDownloadException()))
//                return
//            }
            if (InstallKManager.hasPackageNameAndSatisfyVersion(appTask.apkPackageName, appTask.apkVersionCode)) {
                //throw CNetKAppErrorCode.CODE_TASK_HAS_INSTALL.intAppErrorCode2appDownloadException()
                Log.d(TAG, "taskStart: hasPackageNameAndSatisfyVersion")
                /**
                 * [CNetKAppState.STATE_INSTALL_SUCCESS]
                 */
                onInstallSuccess(appTask)
                return
            }

            if (appTask.apkFileSize != 0L) {
                //当前剩余的空间
                val availMemory = UtilKFileDir.External.getFilesRootFreeSpace()
                //需要的最小空间
                val needMinMemory: Long = (appTask.apkFileSize * 1.2).toLong()
                //如果当前需要的空间大于剩余空间，提醒清理空间
                if (availMemory < needMinMemory) {
                    throw CNetKAppErrorCode.CODE_TASK_NEED_MEMORY_APK.intAppErrorCode2appDownloadException()
                }

                //判断是否为npk,如果是npk,判断空间是否小于需要的2.2倍，如果小于2.2，提示是否继续
                if (appTask.apkFileName.endsWith(".npk") || (appTask.apkFileName.endsWith(".apk") && appTask.apkUnzipNeed)) {
                    //警告空间
                    val warningsMemory: Long = (appTask.apkFileSize * 2.2).toLong()
                    //如果当前空间小于警告空间，
                    if (availMemory < warningsMemory) {
                        /*                    NiuAlertDialog.Builder(currentActivity)
                                                .setTitle("提示")
                                                .setMessage("存储空间不足，可能会导致安装失败,是否继续下载？")
                                                .setLeftButton("是") { dialog, witch ->
                                                    DownloadManager.download(appTask)
                                                    downloadCallback?.invoke(true)
                                                    dialog.dismiss()
                                                }
                                                .setRightButton("否") { dialog, witch ->
                                                    dialog.dismiss()
                                                }
                                                .show()*/
                        throw CNetKAppErrorCode.CODE_TASK_NEED_MEMORY_NPK.intAppErrorCode2appDownloadException()
                    }
                }
            }
            AppTaskDaoManager.addAppTask2Database(appTask)

            /**
             * [CNetKAppTaskState.STATE_TASK_CREATE]
             */
            onTaskCreate(appTask, appTask.taskState == CNetKAppTaskState.STATE_TASK_UPDATE)

            NetKAppDownloadManager.download(appTask/*, listener*/)
        } catch (exception: AppDownloadException) {
            /**
             * [CNetKAppState.STATE_DOWNLOAD_FAIL]
             */
            onDownloadFail(appTask, exception)
        }
    }

    @JvmStatic
    fun taskCancel(appTask: AppTask/*, onCancelBlock: IAB_Listener<Boolean, Int>? = null*/) {
        Log.d(TAG, "taskCancel: appTask $appTask")
        if (!appTask.isTaskProcess()) {
            Log.d(TAG, "taskCancel: task is not process")
            return
        }
        /*if (*//*appTask.isTaskDownload() && *//*appTask.isTaskWait()) {
            Log.d(TAG, "taskCancel: downloadWaitCancel")
            NetKAppDownloadManager.downloadWaitCancel(appTask*//*, onCancelBlock*//*)

        } else*/ if (appTask.isTaskDownload() && appTask.isDownloading() || appTask.isTaskPause()) {
            Log.d(TAG, "taskCancel: downloadCancel")
            NetKAppDownloadManager.downloadCancel(appTask/*, onCancelBlock*/)//从数据库中移除掉
        } else if (appTask.isTaskUnzip() && appTask.isUnzipSuccess()) {
            Log.d(TAG, "taskCancel: installCancel")
            NetKAppInstallManager.installCancel(appTask)
        } else if (appTask.isTaskUnzip() && NetKAppUnzipManager.isUnziping(appTask)) {
            Log.d(TAG, "taskCancel: CODE_TASK_CANCEL_FAIL_ON_UNZIPING")
//            onCancelBlock?.invoke(false, CNetKAppErrorCode.CODE_TASK_CANCEL_FAIL_ON_UNZIPING)
        } else {
            Log.d(TAG, "taskCancel: other")
        }
    }

    @JvmStatic
    fun taskPause(appTask: AppTask) {
        if (!appTask.isTaskProcess()) {
            Log.d(TAG, "taskPause: task is not process")
            return
        }
        if (appTask.isTaskDownload() && appTask.isTasking()) {
            Log.d(TAG, "taskPause: downloadPause")
            NetKAppDownloadManager.downloadPause(appTask)
        }
    }

    @JvmStatic
    fun taskResume(appTask: AppTask) {
        if (!appTask.isTaskProcess()) {
            Log.d(TAG, "downloadResume: task is not process")
            return
        }
        if (appTask.isTaskPause()) {
            Log.d(TAG, "taskPause: downloadResume")
            NetKAppDownloadManager.downloadResume(appTask)
        }
    }

    @JvmStatic
    fun taskInstall(appTask: AppTask) {
        NetKAppInstallManager.install(appTask, appTask.apkPathName.strFilePath2file())
    }

    @JvmStatic
    fun resetTaskStateOfInstall(appTask: AppTask) {
        NetKAppInstallManager.onInstallSuccess(appTask.apkPackageName, appTask.apkVersionCode)
    }

    @JvmStatic
    fun taskUnzip(appTask: AppTask) {
        if (appTask.apkPathName.isNotEmpty()) {
            NetKAppUnzipManager.unzip(appTask)
        }
    }

    @JvmStatic
    fun taskDelete(appTask: AppTask) {
        AppTaskDaoManager.delete(appTask)
        NetKAppUnInstallManager.deleteFileApk(appTask)
    }

    @JvmStatic
    fun onDestroy() {
        NetKAppDownloadManager.downloadPauseAll()
    }
    //endregion

    /////////////////////////////////////////////////////////////////
    // state
    /////////////////////////////////////////////////////////////////
    //region # state
    @JvmStatic
    fun generateAppTaskByPackageName(appTask: AppTask): AppTask {
        if (
            getAppTaskByTaskId_PackageName_VersionCode(appTask.taskId, appTask.apkPackageName, appTask.apkVersionCode) == null &&
            InstallKManager.hasPackageNameAndSatisfyVersion(appTask.apkPackageName, appTask.apkVersionCode)
        ) {
            Log.d(TAG, "generateAppTaskByPackageName: hasPackageNameAndSatisfyVersion appTask $appTask")
            onInstallSuccess(appTask/*, ENetKAppFinishType.SUCCESS*/)
        } else if (
            (appTask.apkIsInstalled || appTask.taskState == CNetKAppTaskState.STATE_TASK_SUCCESS) &&
            !InstallKManager.hasPackageNameAndSatisfyVersion(appTask.apkPackageName, appTask.apkVersionCode)
        ) {
            when (appTask.taskState) {
                CNetKAppTaskState.STATE_TASK_SUCCESS -> {
                    /**
                     * [CNetKAppTaskState.STATE_TASK_CREATE]
                     */
                    onTaskCreate(appTask.apply {
                        apkIsInstalled = false
                    }, false)
                }

                CNetKAppTaskState.STATE_TASK_UNAVAILABLE -> {
                    /**
                     * [CNetKAppTaskState.STATE_TASK_UNAVAILABLE]
                     */
                    onTaskUnavailable(appTask.apply {
                        apkIsInstalled = false
                    })
                }

                CNetKAppTaskState.STATE_TASK_UPDATE -> {
                    /**
                     * [CNetKAppTaskState.STATE_TASK_UPDATE]
                     */
                    onTaskCreate(appTask.apply {
                        apkIsInstalled = false
                    }, true)
                }
            }
        } else if (getAppTaskByTaskId_PackageName_VersionCode(appTask.taskId, appTask.apkPackageName, appTask.apkVersionCode) == null) {
            when (appTask.taskState) {
                CNetKAppTaskState.STATE_TASK_CREATE -> {
                    /**
                     * [CNetKAppTaskState.STATE_TASK_CREATE]
                     */
                    onTaskCreate(appTask.apply {
                        apkIsInstalled = false
                    }, false)
                }

                CNetKAppTaskState.STATE_TASK_UPDATE -> {
                    /**
                     * [CNetKAppTaskState.STATE_TASK_UPDATE]
                     */
                    onTaskCreate(appTask.apply {
                        apkIsInstalled = false
                    }, true)
                }

                CNetKAppTaskState.STATE_TASK_UNAVAILABLE -> {
                    /**
                     * [CNetKAppTaskState.STATE_TASK_UNAVAILABLE]
                     */
                    onTaskUnavailable(appTask.apply {
                        apkIsInstalled = false
                    })
                }
            }
        }
        return appTask
    }

    /////////////////////////////////////////////////////////////////

    @JvmStatic
    fun getAppTasksIsProcess(): List<AppTask> =
        AppTaskDaoManager.getAppTasksIsProcess()

    @JvmStatic
    fun getAppTasksIsInstalled(): List<AppTask> =
        AppTaskDaoManager.getAppTasksIsInstalled()

    /////////////////////////////////////////////////////////////////

//    @JvmStatic
//    fun getAppTaskByTaskId_PackageName(taskId: String, packageName: String): AppTask? =
//        AppTaskDaoManager.getByTaskId_PackageName(taskId, packageName)

    @JvmStatic
    fun getAppTaskByTaskId_PackageName_VersionCode(taskId: String, packageName: String, versionCode: Int): AppTask? =
        AppTaskDaoManager.getByTaskId_PackageName_VersionCode(taskId, packageName, versionCode)

    /**
     * 通过保存名称获取下载信息
     */
    @JvmStatic
    fun getAppTaskByApkName(apkName: String): AppTask? {
        return AppTaskDaoManager.getByApkName(apkName)
    }

//    /**
//     * 通过包名获取下载信息
//     */
//    fun getAppTaskByApkPackageName(apkPackageName: String): AppTask? {
//        return AppTaskDaoManager.getByApkPackageName(apkPackageName)
//    }

    /**
     * 通过包名获取下载信息
     */
    fun getAppTaskByApkPackageName_VersionCode(apkPackageName: String, versionCode: Int): AppTask? {
        return AppTaskDaoManager.getByApkPackageName_VersionCode(apkPackageName, versionCode)
    }

    /////////////////////////////////////////////////////////////////

    /**
     * 是否有正在下载的任务
     */
    @JvmStatic
    @AnyThread
    fun hasDownloading(): Boolean {
        return AppTaskDaoManager.hasDownloading()
    }

    /**
     * 是否有正在校验的任务
     */
    @JvmStatic
    fun hasVerifying(): Boolean {
        return AppTaskDaoManager.hasVerifying()
    }

    /**
     * 是否有正在解压的任务
     */
    @JvmStatic
    fun hasUnziping(): Boolean {
        return AppTaskDaoManager.hasUnziping()
    }

    /**
     * 判断是否正在下载中
     * @return true 正在下载中  false 当前不是正在下载中
     */
    @JvmStatic
    fun isDownloading(appTask: AppTask): Boolean {
        return NetKAppDownloadManager.getAppDownloadProgress(appTask)?.isDownloading() ?: false//查询下载状态
    }

    @JvmStatic
    fun isDeleteApkFile(isDelete: Boolean) {
        NetKAppTaskManager.isDeleteApkFile = isDelete
    }

    @JvmStatic
    fun isDeleteApkFile(): Boolean =
        NetKAppTaskManager.isDeleteApkFile

    //endregion

    /////////////////////////////////////////////////////////////////


    override fun onTaskCreate(appTask: AppTask, isUpdate: Boolean) {
        /*        //将结果传递给服务端
        GlobalScope.launch(Dispatchers.IO) {
            if (appTask.appId == "2") {
                ApplicationService.updateAppDownload("1")
            } else {
                ApplicationService.updateAppDownload(appTask.appId)
            }
        }*/
        applyAppTaskState(appTask, if (isUpdate) CNetKAppTaskState.STATE_TASK_UPDATE else CNetKAppTaskState.STATE_TASK_CREATE)
    }

//    override fun onTaskWait(appTask: AppTask) {
//        applyAppTaskState(appTask, CNetKAppTaskState.STATE_TASK_WAIT)
//    }

    override fun onTasking(appTask: AppTask, state: Int) {
        applyAppTaskState(appTask, state)
    }

    override fun onTaskPause(appTask: AppTask) {
        applyAppTaskState(appTask, CNetKAppTaskState.STATE_TASK_PAUSE)
    }

    fun onTaskUnavailable(appTask: AppTask) {
        applyAppTaskState(appTask, CNetKAppTaskState.STATE_TASK_UNAVAILABLE)
    }

//    fun onTaskUpdate(appTask: AppTask) {
//        applyAppTaskState(appTask, CNetKAppTaskState.STATE_TASK_UPDATE)
//    }

    override fun onTaskFinish(appTask: AppTask, finishType: ENetKAppFinishType) {
        when (finishType) {
            ENetKAppFinishType.SUCCESS ->
                applyAppTaskState(appTask, CNetKAppTaskState.STATE_TASK_SUCCESS, finishType = finishType)

            ENetKAppFinishType.CANCEL -> {
                appTask.apply {
                    downloadProgress = 0
                    downloadFileSize = 0
                }
                applyAppTaskState(appTask, CNetKAppTaskState.STATE_TASK_CANCEL, finishType = finishType, onNext = {
//                    //推送任务取消的指令
                    PostKEventLiveData.instance.with<String>(CNetKAppEvent.EVENT_TASK_FINISH_OR_FAIL).postValue(appTask.taskId)

                    /**
                     * [CNetKAppTaskState.STATE_TASK_CREATE]
                     */
                    onTaskCreate(appTask, appTask.taskStateInit == CNetKAppTaskState.STATE_TASK_UPDATE)
                })
            }

            is ENetKAppFinishType.FAIL -> {
//                appTask.apply {
//                    downloadProgress = 0
//                    downloadFileSize = 0
//                }
                applyAppTaskState(appTask, CNetKAppTaskState.STATE_TASK_FAIL, finishType = finishType, onNext = {
                    //                    //推送任务失败的指令
                    PostKEventLiveData.instance.with<String>(CNetKAppEvent.EVENT_TASK_FINISH_OR_FAIL).postValue(appTask.taskId)

                    /**
                     * [CNetKAppTaskState.STATE_TASK_PAUSE]
                     */
                    onTaskPause(appTask)
                })
            }
        }
    }

    /////////////////////////////////////////////////////////////////

//    override fun onDownloadWait(appTask: AppTask) {
//        applyAppTaskState(appTask, CNetKAppState.STATE_DOWNLOAD_WAIT, onNext = {
//            /**
//             * [CNetKAppTaskState.STATE_TASK_WAIT]
//             */
//            onTaskWait(appTask)
//        })
//    }

    override fun onDownloading(appTask: AppTask, progress: Int, currentIndex: Long, totalIndex: Long, offsetIndexPerSeconds: Long) {
        applyAppTaskState(appTask, CNetKAppState.STATE_DOWNLOADING, progress, currentIndex, totalIndex, offsetIndexPerSeconds, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASKING]
             */
            onTasking(appTask, CNetKAppState.STATE_DOWNLOADING)
        })
    }

    override fun onDownloadPause(appTask: AppTask) {
        applyAppTaskState(appTask, CNetKAppState.STATE_DOWNLOAD_PAUSE, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASK_PAUSE]
             */
            onTaskPause(appTask)
        })
    }

    override fun onDownloadCancel(appTask: AppTask) {
        applyAppTaskState(appTask, CNetKAppState.STATE_DOWNLOAD_CANCEL, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASK_CANCEL]
             */
            onTaskFinish(appTask, ENetKAppFinishType.CANCEL)
        })
    }

    override fun onDownloadSuccess(appTask: AppTask) {
        applyAppTaskState(appTask, CNetKAppState.STATE_DOWNLOAD_SUCCESS, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASKING]
             */
            onTasking(appTask, CNetKAppState.STATE_DOWNLOAD_SUCCESS)
        })
    }

    fun onDownloadFail(appTask: AppTask, exception: Exception?) {
        if (exception is ServerCanceledException) {
            if (exception.responseCode == 404 && appTask.downloadUrlCurrent != appTask.downloadUrl && appTask.downloadUrl.isNotEmpty()) {
                appTask.downloadUrlCurrent = appTask.downloadUrl
                appTask.taskState = CNetKAppTaskState.STATE_TASK_CREATE
                taskStart(appTask)
            } else {
                /**
                 * [CNetKAppState.STATE_DOWNLOAD_FAIL]
                 */
                onDownloadFail(appTask, CNetKAppErrorCode.CODE_DOWNLOAD_SERVER_CANCELED.intAppErrorCode2appDownloadException(exception.message ?: ""))
            }
        } else {
            /**
             * [CNetKAppState.STATE_DOWNLOAD_FAIL]
             */
            onDownloadFail(appTask, CNetKAppErrorCode.CODE_DOWNLOAD_SERVER_CANCELED.intAppErrorCode2appDownloadException())
        }
    }

    override fun onDownloadFail(appTask: AppTask, exception: AppDownloadException) {
//        AppTaskDaoManager.removeAppTaskForDatabase(appTask)

        applyAppTaskStateException(appTask, CNetKAppState.STATE_DOWNLOAD_FAIL, exception, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASK_FAIL]
             */
            onTaskFinish(appTask, ENetKAppFinishType.FAIL(exception))
        })
    }

    /////////////////////////////////////////////////////////////////

    override fun onVerifying(appTask: AppTask) {
        applyAppTaskState(appTask, CNetKAppState.STATE_VERIFYING, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASKING]
             */
            onTasking(appTask, CNetKAppState.STATE_VERIFYING)
        })
    }

    override fun onVerifySuccess(appTask: AppTask) {
        applyAppTaskState(appTask, CNetKAppState.STATE_VERIFY_SUCCESS, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASKING]
             */
            onTasking(appTask, CNetKAppState.STATE_VERIFY_SUCCESS)
        })
    }

    override fun onVerifyFail(appTask: AppTask, exception: AppDownloadException) {
        applyAppTaskStateException(appTask, CNetKAppState.STATE_VERIFY_FAIL, exception, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASK_FAIL]
             */
            onTaskFinish(appTask, ENetKAppFinishType.FAIL(exception))
        })
    }

    /////////////////////////////////////////////////////////////////

    override fun onUnziping(appTask: AppTask, progress: Int, currentIndex: Long, totalIndex: Long, offsetIndexPerSeconds: Long) {
        applyAppTaskState(appTask, CNetKAppState.STATE_UNZIPING, progress, currentIndex, totalIndex, offsetIndexPerSeconds, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASKING]
             */
            onTasking(appTask, CNetKAppState.STATE_UNZIPING)
        })
    }

    override fun onUnzipSuccess(appTask: AppTask) {
        applyAppTaskState(appTask, CNetKAppState.STATE_UNZIP_SUCCESS, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASKING]
             */
            onTasking(appTask, CNetKAppState.STATE_UNZIP_SUCCESS)

            if (NetKAppTaskManager.isAutoInstall && UtilKPermission.hasPackageInstalls()) {
                taskInstall(appTask)
            }
        })
    }

    override fun onUnzipFail(appTask: AppTask, exception: AppDownloadException) {
        //            AlertTools.showToast("解压失败，请检测存储空间是否足够！")
        applyAppTaskStateException(appTask, CNetKAppState.STATE_UNZIP_FAIL, exception, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASK_FAIL]
             */
            onTaskFinish(appTask, ENetKAppFinishType.FAIL(exception))
        })
    }

    /////////////////////////////////////////////////////////////////

    override fun onInstalling(appTask: AppTask) {
        applyAppTaskState(appTask, CNetKAppState.STATE_INSTALLING, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASKING]
             */
            onTasking(appTask, CNetKAppState.STATE_INSTALLING)
        })
    }

    override fun onInstallSuccess(appTask: AppTask) {
        applyAppTaskState(appTask.apply {
            this.apkIsInstalled = true
        }, CNetKAppState.STATE_INSTALL_SUCCESS, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASK_SUCCESS]
             */
            onTaskFinish(appTask, ENetKAppFinishType.SUCCESS)
        })
    }

    override fun onInstallFail(appTask: AppTask, exception: AppDownloadException) {
        applyAppTaskState(appTask, CNetKAppState.STATE_INSTALL_FAIL, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASK_FAIL]
             */
            onTaskFinish(appTask, ENetKAppFinishType.FAIL(exception))
        })
    }

    override fun onInstallCancel(appTask: AppTask) {
//        appTask.apply {
//            downloadProgress = 0
//            downloadFileSize = 0
//        }
        applyAppTaskState(appTask, CNetKAppState.STATE_INSTALL_CANCEL, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASK_FAIL]
             */
            onTaskFinish(appTask, ENetKAppFinishType.CANCEL)
        })
    }

    /////////////////////////////////////////////////////////////////

    override fun onUninstallSuccess(appTask: AppTask) {
        appTask.apply {
            apkIsInstalled = false
//            downloadProgress = 0
//            downloadFileSize = 0
        }
        applyAppTaskState(appTask, CNetKAppState.STATE_UNINSTALL_SUCCESS, onNext = {
            /**
             * [CNetKAppTaskState.STATE_TASK_CANCEL]
             */
            onTaskFinish(appTask, ENetKAppFinishType.CANCEL)
        })//设置为未安装
    }

    /////////////////////////////////////////////////////////////////

    private fun applyAppTaskState(
        appTask: AppTask, state: Int, progress: Int = 0, finishType: ENetKAppFinishType = ENetKAppFinishType.SUCCESS, onNext: I_Listener? = null
    ) {
        appTask.apply {
            this.taskState = state
            if (progress > 0) downloadProgress = progress
        }
        Log.d(TAG, "applyAppTaskState: id ${appTask.taskId} state ${state.intAppState2strAppState()} progress ${appTask.downloadProgress} appTask $appTask")
        AppTaskDaoManager.update(appTask)
        postAppTaskState(appTask, state, appTask.downloadProgress, finishType, onNext)
    }

    private fun applyAppTaskState(
        appTask: AppTask, state: Int, progress: Int, currentIndex: Long, totalIndex: Long, offsetIndexPerSeconds: Long, onNext: I_Listener? = null
    ) {
        appTask.apply {
            this.taskState = state
            if (progress > 0) downloadProgress = progress
        }
        Log.d(
            TAG,
            "applyAppTaskState: id ${appTask.taskId} state ${state.intAppState2strAppState()} progress ${appTask.downloadProgress} currentIndex $currentIndex totalIndex $totalIndex offsetIndexPerSeconds $offsetIndexPerSeconds appTask $appTask"
        )
        AppTaskDaoManager.update(appTask)
        postAppTaskState(appTask, state, appTask.downloadProgress, currentIndex, totalIndex, offsetIndexPerSeconds, onNext)
    }

    private fun applyAppTaskStateException(
        appTask: AppTask,
        state: Int,
        exception: AppDownloadException,
        progress: Int = 0,
        onNext: I_Listener? = null
    ) {
        appTask.apply {
            this.taskState = state
            if (progress > 0) downloadProgress = progress
        }
        Log.d(TAG, "applyAppTaskState: id ${appTask.taskId} state ${state.intAppState2strAppState()} exception $exception appTask $appTask")
        AppTaskDaoManager.update(appTask)
        postAppTaskState(appTask, state, exception, onNext)
    }

    private fun postAppTaskState(appTask: AppTask, state: Int, exception: AppDownloadException, nextMethod: I_Listener?) {
        for (listener in _appDownloadStateListeners) {
            when (state) {
                CNetKAppState.STATE_DOWNLOAD_FAIL -> listener.onDownloadFail(appTask, exception)
                CNetKAppState.STATE_VERIFY_FAIL -> listener.onVerifyFail(appTask, exception)
                CNetKAppState.STATE_UNZIP_FAIL -> listener.onUnzipFail(appTask, exception)
                CNetKAppState.STATE_INSTALL_FAIL -> listener.onInstallFail(appTask, exception)
            }
        }
        nextMethod?.invoke()
    }

    private fun postAppTaskState(appTask: AppTask, state: Int, progress: Int, finishType: ENetKAppFinishType, nextMethod: I_Listener?) {
        for (listener in _appDownloadStateListeners) {
            when (state) {
                CNetKAppTaskState.STATE_TASK_CREATE -> listener.onTaskCreate(appTask, false)
                CNetKAppTaskState.STATE_TASK_UPDATE -> listener.onTaskCreate(appTask, true)
//                CNetKAppTaskState.STATE_TASK_WAIT -> listener.onTaskWait(appTask)
                CNetKAppTaskState.STATE_TASKING -> listener.onTasking(appTask, state)
                CNetKAppTaskState.STATE_TASK_PAUSE -> listener.onTaskPause(appTask)
                CNetKAppTaskState.STATE_TASK_CANCEL, CNetKAppTaskState.STATE_TASK_SUCCESS, CNetKAppTaskState.STATE_TASK_FAIL -> listener.onTaskFinish(appTask, finishType)
                ///////////////////////////////////////////////////////////////////////////////
//                CNetKAppState.STATE_DOWNLOAD_WAIT -> listener.onDownloadWait(appTask)
                CNetKAppState.STATE_DOWNLOAD_PAUSE -> listener.onDownloadPause(appTask)
                CNetKAppState.STATE_DOWNLOAD_CANCEL -> listener.onDownloadCancel(appTask)
                CNetKAppState.STATE_DOWNLOAD_SUCCESS -> listener.onDownloadSuccess(appTask)
                ///////////////////////////////////////////////////////////////////////////////
                CNetKAppState.STATE_VERIFYING -> listener.onVerifying(appTask)
                CNetKAppState.STATE_VERIFY_SUCCESS -> listener.onVerifySuccess(appTask)
                ///////////////////////////////////////////////////////////////////////////////
                CNetKAppState.STATE_UNZIP_SUCCESS -> listener.onUnzipSuccess(appTask)
                ///////////////////////////////////////////////////////////////////////////////
                CNetKAppState.STATE_INSTALLING -> listener.onInstalling(appTask)
                CNetKAppState.STATE_INSTALL_SUCCESS -> listener.onInstallSuccess(appTask)
                CNetKAppState.STATE_INSTALL_CANCEL -> listener.onInstallCancel(appTask)
                ///////////////////////////////////////////////////////////////////////////////
                CNetKAppState.STATE_UNINSTALL_SUCCESS -> listener.onUninstallSuccess(appTask)
            }
        }
        nextMethod?.invoke()
    }

    private fun postAppTaskState(appTask: AppTask, state: Int, progress: Int, currentIndex: Long, totalIndex: Long, offsetIndexPerSeconds: Long, nextMethod: I_Listener?) {
        for (listener in _appDownloadStateListeners) {
            when (state) {
                CNetKAppState.STATE_DOWNLOADING -> listener.onDownloading(appTask, progress, currentIndex, totalIndex, offsetIndexPerSeconds)
                CNetKAppState.STATE_UNZIPING -> listener.onUnziping(appTask, progress, currentIndex, totalIndex, offsetIndexPerSeconds)
            }
        }
        nextMethod?.invoke()
    }
}