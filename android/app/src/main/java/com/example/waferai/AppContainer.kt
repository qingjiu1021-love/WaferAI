package com.example.waferai

import android.content.Context
import com.example.waferai.data.repository.ImportRepository
import com.example.waferai.data.ai.WaferKnowledge
import com.example.waferai.data.ai.KnowledgeTaskStore

class AppContainer(context: Context) {
    val importRepository = ImportRepository(context.applicationContext)
    internal val knowledgeTasks by lazy { KnowledgeTaskStore(context.applicationContext) }
    internal val knowledge by lazy { WaferKnowledge(context.applicationContext, importRepository.operationLog) }
}
