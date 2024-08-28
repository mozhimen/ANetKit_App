package com.mozhimen.taskk.provider.verify

import com.mozhimen.taskk.provider.basic.bases.providers.ATaskVerify
import com.mozhimen.taskk.provider.basic.bases.sets.ATaskSetVerify
import java.util.concurrent.ConcurrentHashMap

/**
 * @ClassName TaskProviderSetVerify
 * @Description TODO
 * @Author Mozhimen / Kolin Zhao
 * @Date 2024/8/21 21:36
 * @Version 1.0
 */
class TaskSetVerify(override val providerDefaults: List<ATaskVerify>) : ATaskSetVerify() {
    override val providers: ConcurrentHashMap<String, ATaskVerify> by lazy {
        ConcurrentHashMap(
            providerDefaults.mapNotNull { (it.getSupportFileTasks() as? Map<String, ATaskVerify>) }.fold(emptyMap()) { acc, nex -> acc + nex }
        )
    }
}