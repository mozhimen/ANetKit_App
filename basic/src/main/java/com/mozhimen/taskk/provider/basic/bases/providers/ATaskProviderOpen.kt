package com.mozhimen.taskk.provider.basic.bases.providers

import androidx.annotation.CallSuper
import com.mozhimen.taskk.provider.basic.annors.ATaskName
import com.mozhimen.taskk.provider.basic.bases.ATaskProvider
import com.mozhimen.taskk.provider.basic.cons.CTaskState
import com.mozhimen.taskk.provider.basic.cons.STaskFinishType
import com.mozhimen.taskk.provider.basic.db.AppTask
import com.mozhimen.taskk.provider.basic.interfaces.ITaskProviderLifecycle

/**
 * @ClassName ITaskProviderOpen
 * @Description TODO
 * @Author mozhimen
 * @Date 2024/8/20
 * @Version 1.0
 */
abstract class ATaskProviderOpen(iTaskProviderLifecycle: ITaskProviderLifecycle?): ATaskProvider(iTaskProviderLifecycle) {
    override fun getTaskName(): String {
        return ATaskName.TASK_OPEN
    }

    @CallSuper
    override fun taskStart(appTask: AppTask) {
        onTaskStarted(CTaskState.STATE_OPENING, appTask)
    }

    @CallSuper
    override fun taskResume(appTask: AppTask) {
        onTaskStarted(CTaskState.STATE_OPENING, appTask)
    }

    @CallSuper
    override fun taskPause(appTask: AppTask) {
        onTaskPaused(CTaskState.STATE_OPEN_PAUSE, appTask)
    }

    @CallSuper
    override fun taskCancel(appTask: AppTask) {
        onTaskFinished(CTaskState.STATE_OPEN_CANCEL, STaskFinishType.CANCEL, appTask)
    }
}