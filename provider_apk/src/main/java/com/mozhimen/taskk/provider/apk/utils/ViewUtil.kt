package com.mozhimen.taskk.provider.apk.utils

import android.content.Context
import android.view.View
import com.mozhimen.basick.elemk.commons.IAB_Listener
import com.mozhimen.basick.elemk.commons.I_AListener
import com.mozhimen.taskk.provider.basic.db.AppTask

/**
 * @ClassName NetKAppViewUtil
 * @Description TODO
 * @Author Mozhimen & Kolin Zhao
 * @Date 2023/12/6 9:58
 * @Version 1.0
 */
object ViewUtil {
    @JvmStatic
    fun generateViewLongClickOfAppTask(
        view: View,
        onGetAppTask: I_AListener<AppTask>,
        onTaskCancel: IAB_Listener<Context, AppTask>
    ) {
        view.setOnLongClickListener {
            val appTask = onGetAppTask.invoke()
            if (appTask.isTaskProcess() && appTask.atTaskDownload()) {
                onTaskCancel.invoke(it.context, appTask)
            }
            true
        }
    }

    @JvmStatic
    fun generateViewClickOfAppTask(
        view: View,
        onGetAppTask: I_AListener<AppTask>,
        onTaskStart: IAB_Listener<Context, AppTask>,
        onTaskOpen: IAB_Listener<Context, AppTask>,
        onTaskPause: IAB_Listener<Context, AppTask>,
        onTaskResume: IAB_Listener<Context, AppTask>,
        onTaskInstall: IAB_Listener<Context, AppTask>,
        onTaskCancel: IAB_Listener<Context, AppTask>
    ) {
        view.setOnClickListener {
            val appTask: AppTask = onGetAppTask.invoke()
            when {
                appTask.isTaskCreateOrUpdate() -> {
                    onTaskStart.invoke(it.context, appTask)
                }

                appTask.isTaskSuccess() -> {
                    onTaskOpen.invoke(it.context, appTask)
                }

                appTask.isAnyTasking() -> {
                    onTaskPause.invoke(it.context, appTask)
                }

                appTask.isAnyTaskPause() -> {
                    onTaskResume.invoke(it.context, appTask)
                }

                appTask.canTaskInstall()/*CTaskState.STATE_UNZIP_SUCCESS, CTaskState.STATE_INSTALLING*/ -> {
                    onTaskInstall.invoke(it.context, appTask)
                }

                else -> {
                }
            }
        }
        generateViewLongClickOfAppTask(view, onGetAppTask, onTaskCancel)
    }
}